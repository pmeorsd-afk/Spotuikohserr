package com.music.spotui.data.home

import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.HomeSectionIds
import com.music.spotui.data.entity.HomeSectionType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.ArtistListenStat
import com.music.spotui.data.local.LocalListeningTracker
import com.music.spotui.data.local.TrackListenStat
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import com.music.spotui.data.preferences.getCachedPlaylists
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeFeedEngine @Inject constructor(
    private val repository: AppRepository,
    private val listeningTracker: LocalListeningTracker,
    private val publicCatalog: PublicSpotifyHomeSource,
    private val discovery: HomeDiscoveryRepository,
    @ApplicationContext private val context: Context
) {

    @Volatile private var cachedFeed: HomeFeedModel? = null
    @Volatile private var cachedAt: Long = 0L
    private var diskCachedAt: Long = 0L
    private val cacheTtlMs = 2 * 60 * 1000L // 2 minutes in-memory cache
    private val mutex = Mutex()
    private val singleFlightMutex = Mutex()
    private var inFlightRefresh: Deferred<HomeFeedModel>? = null

    fun invalidate() {
        cachedAt = 0L
    }

    private fun isCacheValid(): Boolean {
        val feed = cachedFeed ?: return false
        return (System.currentTimeMillis() - cachedAt) < cacheTtlMs && feed.isHealthy()
    }

    private fun HomeFeedModel.isHealthy(): Boolean {
        return sections.any { it.id == HomeSectionIds.RECENTLY_PLAYED && it.items.isNotEmpty() } &&
               sections.count { it.items.isNotEmpty() } >= 3
    }

    fun patchLikedSongsCount(feed: HomeFeedModel): HomeFeedModel {
        val count = com.music.spotui.data.preferences.getLikedSongsCount(context)
        val patched = HomeDiscoveryPresentation.withLikedCount(feed, count)
        // The visible feed can deliberately have an older discovery snapshot.
        // A count update must not write that older snapshot over prepared recommendations.
        cachedFeed = cachedFeed?.let { HomeDiscoveryPresentation.withLikedCount(it, count) }
        return patched
    }

    suspend fun getHomeFeed(forceRefresh: Boolean = false): HomeFeedModel {
        if (!forceRefresh && isCacheValid()) {
            return patchLikedSongsCount(cachedFeed!!)
        }

        if (!forceRefresh && cachedFeed == null) {
            val disk = loadFromDisk()
            if (disk != null && disk.isHealthy()) {
                val patched = patchLikedSongsCount(disk)
                cachedFeed = patched
                cachedAt = diskCachedAt
                if (isCacheValid()) return patched
            }
        }

        // Single-flight refresh: if a full refresh is already running, await it
        val job = singleFlightMutex.withLock {
            if (!forceRefresh && isCacheValid()) {
                return patchLikedSongsCount(cachedFeed!!)
            }
            val existing = inFlightRefresh
            if (existing != null && existing.isActive) {
                existing
            } else {
                val newJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
                    doOrchestratedRefresh()
                }
                inFlightRefresh = newJob
                newJob
            }
        }

        return job.await()
    }

    private suspend fun doOrchestratedRefresh(): HomeFeedModel {
        // Cached/local Home must stay available while independent network sources are pending.
        val newFeed = buildOrchestratedFeed()
        return mutex.withLock {
            val previous = cachedFeed ?: loadFromDisk()
            val recoveredFeed = HomeDiscoveryPresentation.retainKnownGeneral(newFeed, previous)
            val finalFeed = if (recoveredFeed.isHealthy()) {
                recoveredFeed
            } else {
                // A partial/offline refresh must not replace a healthy saved Home.
                val healthyBase = previous?.takeIf { it.isHealthy() }
                if (healthyBase != null) {
                    val recentTracks = listeningTracker.recentTracks(limit = 10)
                    val likedSongs = loadLikedSongs()
                    mergeLocalRecent(healthyBase, recentTracks, likedSongs)
                } else {
                    recoveredFeed
                }
            }
            val patchedFeed = patchLikedSongsCount(finalFeed)
            cachedFeed = patchedFeed
            cachedAt = System.currentTimeMillis()
            saveToDisk(patchedFeed)
            patchedFeed
        }
    }

    /**
     * Disk/local-only startup and incremental update. Network work never runs on this path.
     */
    suspend fun updateRecentListeningOnly(): HomeFeedModel = withContext(Dispatchers.IO) {
        mutex.withLock {
            val base = cachedFeed ?: loadFromDisk().also { if (it != null) cachedAt = diskCachedAt }
            val recentTracks = listeningTracker.recentTracks(limit = 10)
            val likedSongs = com.music.spotui.data.preferences.getLocallyLikedSongs(context)
            val local = mergeLocalRecent(base ?: HomeFeedModel(), recentTracks, likedSongs)
            val updated = HomeDiscoveryPresentation.apply(local, publicCatalog.cachedHome(),
                discovery.cached(listeningTracker.recentTracks(limit = 100)))
            val patched = patchLikedSongsCount(updated)
            cachedFeed = patched
            saveToDisk(patched)
            patched
        }
    }

    /** Expensive catalog work stays outside the feed mutex and never runs on the playback thread. */
    suspend fun refreshDiscoveryRecommendations(onReady: suspend (HomeFeedModel) -> Unit = {}) = withContext(Dispatchers.IO) {
        val history = listeningTracker.recentTracks(limit = 100)
        val general = publicCatalog.home()
        // Public shelves must not wait behind the longer personal playlist verification.
        applyDiscovery(general, discovery.cached(history), history)?.let { onReady(it) }
        val personal = discovery.refresh(history)
        applyDiscovery(general, personal, history)?.let { onReady(it) }
    }

    private suspend fun applyDiscovery(general: PublicHomeCatalog, personal: PersonalHomeCatalog,
                                       history: List<TrackListenStat>): HomeFeedModel? =
        mutex.withLock {
            val base = cachedFeed ?: loadFromDisk() ?: return@withLock null
            val currentHistory = listeningTracker.recentTracks(limit = 100)
            val currentPersonal = if (HomeRecommendationPolicy.fingerprint(history) ==
                HomeRecommendationPolicy.fingerprint(currentHistory)) personal else discovery.cached(currentHistory)
            val updated = HomeDiscoveryPresentation.apply(base, general, currentPersonal)
            cachedFeed = updated
            saveToDisk(updated)
            updated
        }

    /**
     * Non-destructive personalized patch: updates SIMILAR_ARTISTS, RECOMMENDED_TODAY,
     * based on the latest listening tracker stats. Discovery shelves have their own
     * bounded, exact-content refresh. Missing shelves publish during the current visit.
     */
    suspend fun updatePersonalizedRecommendations(): HomeFeedModel = withContext(Dispatchers.IO) {
        val recentTracks = listeningTracker.recentTracks(limit = 10)
        val topArtists = listeningTracker.topArtists(limit = 1)
        val topArtist = topArtists.firstOrNull()

        val seedIds = HomeRecommendationPolicy.musicHistory(recentTracks).take(5).map { it.trackId }

        // Parallel network work must not block the local Home snapshot mutex.
        val (recommendations, similarContent) = coroutineScope {
            val recDeferred = if (seedIds.isNotEmpty()) {
                async(Dispatchers.IO) { loadRecommendations(seedIds) }
            } else null

            val similarDeferred = if (topArtist != null && topArtist.artistName.isNotBlank()) {
                async(Dispatchers.IO) { loadSimilarContent(topArtist.artistName) }
            } else null

            Pair(
                recDeferred?.await().orEmpty(),
                similarDeferred?.await()
            )
        }

        mutex.withLock {
            val base = cachedFeed ?: loadFromDisk() ?: return@withLock HomeFeedModel()
            // Merge with the latest local history after the remote requests finish.
            val mergedWithRecent = mergeLocalRecent(base, listeningTracker.recentTracks(limit = 10), loadLikedSongs())
            val updatedSections = mergedWithRecent.sections.toMutableList()

            // 2. Non-destructive patch: Replace section if new content arrived, else preserve existing!
            // RECOMMENDED_TODAY
            if (recommendations.isNotEmpty()) {
                val recSection = HomeSection(
                    id = HomeSectionIds.RECOMMENDED_TODAY,
                    title = "מומלץ להיום",
                    type = HomeSectionType.HORIZONTAL,
                    items = recommendations.take(15).map { HomeItem.Track(it) }
                )
                val idx = updatedSections.indexOfFirst { it.id == HomeSectionIds.RECOMMENDED_TODAY }
                if (idx != -1) {
                    updatedSections[idx] = recSection
                } else {
                    updatedSections.add(recSection)
                }
            }

            // SIMILAR_ARTISTS
            if (topArtist != null && similarContent != null && similarContent.items.isNotEmpty()) {
                val similarSection = HomeSection(
                    id = HomeSectionIds.SIMILAR_ARTISTS,
                    title = topArtist.artistName,
                    subtitle = "אמנים נוספים כמו",
                    headerArtist = similarContent.topArtistModel,
                    type = HomeSectionType.ARTISTS,
                    items = similarContent.items
                )
                val idx = updatedSections.indexOfFirst { it.id == HomeSectionIds.SIMILAR_ARTISTS }
                if (idx != -1) {
                    updatedSections[idx] = similarSection
                } else {
                    updatedSections.add(similarSection)
                }
            }

            // 3. Sort sections according to canonical order
            val canonicalOrder = listOf(
                HomeSectionIds.TOP_MIXES,
                HomeSectionIds.POPULAR_RADIO,
                HomeSectionIds.RECENTLY_PLAYED,
                HomeSectionIds.RECOMMENDED_TODAY,
                HomeSectionIds.SIMILAR_ARTISTS,
                HomeSectionIds.HISTORY_BASED,
                HomeSectionIds.POPULAR_ALBUMS,
                HomeSectionIds.POPULAR_ARTISTS
            )
            updatedSections.sortBy { sec ->
                val ord = canonicalOrder.indexOf(sec.id)
                if (ord != -1) ord else 999
            }

            val finalFeed = mergedWithRecent.copy(sections = updatedSections)
            if (finalFeed.isHealthy()) {
                cachedFeed = finalFeed
                cachedAt = System.currentTimeMillis()
                saveToDisk(finalFeed)
            }
            finalFeed
        }
    }

    private suspend fun buildOrchestratedFeed(): HomeFeedModel = coroutineScope {
        // Phase 1: Parallel fetch of all independent data sources
        val recentDeferred = async(Dispatchers.IO) { listeningTracker.recentTracks(limit = 10) }
        val topArtistsDeferred = async(Dispatchers.IO) { listeningTracker.topArtists(limit = 1) }
        val spotifyHomeDeferred = async(Dispatchers.IO) { loadSpotifyHome() }
        val catalogDeferred = async(Dispatchers.IO) { publicCatalog.home() }
        val artistsDeferred = async(Dispatchers.IO) { loadArtists() }
        val likedSongsDeferred = async(Dispatchers.IO) { loadLikedSongs() }
        val mixesDeferred = async(Dispatchers.IO) { loadPlaylists("מיקס") }
        val fallbackSongsDeferred = async(Dispatchers.IO) { loadTopSongs() }
        val fallbackArtistsDeferred = async(Dispatchers.IO) { loadSearchArtists("ישראלי") }

        val recentTracks = recentDeferred.await()
        val topArtists = topArtistsDeferred.await()
        val spotifyHome = spotifyHomeDeferred.await()
        val catalog = catalogDeferred.await()
        val artists = artistsDeferred.await()
        val likedSongs = likedSongsDeferred.await()
        val fallbackMixes = mixesDeferred.await()
        val fallbackTopSongs = fallbackSongsDeferred.await()
        val fallbackArtists = fallbackArtistsDeferred.await()

        // Seeds for recommendations based on recent plays
        val seedIds = HomeRecommendationPolicy.musicHistory(recentTracks).take(5).map { it.trackId }
        val recommendations = if (seedIds.isNotEmpty()) {
            loadRecommendations(seedIds)
        } else {
            emptyList()
        }

        // Phase 2: Personalization dependent on Top Artist
        val topArtist = topArtists.firstOrNull()
        val similarDeferred = if (topArtist != null && topArtist.artistName.isNotBlank()) {
            async(Dispatchers.IO) { loadSimilarContent(topArtist.artistName) }
        } else null

        val similarContent = similarDeferred?.await()
        val personal = discovery.cached(listeningTracker.recentTracks(limit = 100))

        assembleFeed(
            recentTracks = recentTracks,
            topArtist = topArtist,
            spotifyHome = spotifyHome,
            catalog = catalog,
            personal = personal,
            artists = artists,
            fallbackArtists = fallbackArtists,
            likedSongs = likedSongs,
            recommendations = recommendations,
            fallbackTopSongs = fallbackTopSongs,
            fallbackMixes = fallbackMixes,
            similarContent = similarContent
        )
    }

    private fun assembleFeed(
        recentTracks: List<TrackListenStat>,
        topArtist: ArtistListenStat?,
        spotifyHome: HomeFeedModel?,
        catalog: PublicHomeCatalog,
        personal: PersonalHomeCatalog,
        artists: List<ArtistsModel>,
        fallbackArtists: List<ArtistsModel>,
        likedSongs: List<SongsModel>,
        recommendations: List<SongsModel>,
        fallbackTopSongs: List<SongsModel>,
        fallbackMixes: List<HomeItem.Playlist>,
        similarContent: SimilarArtistData?
    ): HomeFeedModel {
        val rawSections = spotifyHome?.sections.orEmpty()
        val sections = mutableListOf<HomeSection>()

        // ── Resolved Data Elements ──
        val albumItems = catalog.albums

        val generalMixes = rawSections.firstOrNull { s ->
            val t = s.title.lowercase()
            t.contains("מיקס") || t.contains("mix") || t.contains("daily")
        }?.items?.filterIsInstance<HomeItem.Playlist>()?.takeIf { it.isNotEmpty() }
            ?: fallbackMixes.ifEmpty { catalog.discovery }
        val mixItems = personal.mixes.ifEmpty { generalMixes }

        val radioItems = personal.radio.ifEmpty { catalog.radio }

        // ── 0. Top 2x2 Grid (Guaranteed EXACTLY 4 items) ──
        val topGrid = mutableListOf<HomeItem>()
        if (recentTracks.isNotEmpty()) {
            topGrid.addAll(recentTracks.take(4).map { HomeItem.Track(it.toSongModel()) })
        }
        if (topGrid.size < 4 && albumItems.isNotEmpty()) {
            val needed = 4 - topGrid.size
            topGrid.addAll(albumItems.take(needed))
        }
        if (topGrid.size < 4 && mixItems.isNotEmpty()) {
            val needed = 4 - topGrid.size
            topGrid.addAll(mixItems.take(needed))
        }

        // ── 1. המיקסים המובילים שלכם ──
        if (mixItems.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.TOP_MIXES,
                    title = if (personal.mixes.isNotEmpty()) "המיקסים המובילים שלכם" else "פלייליסטים לגלות",
                    type = HomeSectionType.MIXES,
                    items = mixItems
                )
            )
        }

        // ── 2. רדיו פופולרי ──
        if (radioItems.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.POPULAR_RADIO,
                    title = if (personal.radio.isNotEmpty()) "רדיו בשבילכם" else "רדיו פופולרי",
                    type = HomeSectionType.HORIZONTAL,
                    items = radioItems
                )
            )
        }

        // ── 3. לאחרונה (Liked Songs pinned first + recent tracks + user playlists) ──
        val recentItems = mutableListOf<HomeItem>()
        recentItems.add(HomeItem.LikedSongs(count = likedSongs.size))
        recentTracks.forEach { stat ->
            recentItems.add(HomeItem.Track(stat.toSongModel()))
        }
        val playlists = getCachedPlaylists(context)
        playlists.forEach { pl ->
            recentItems.add(
                HomeItem.Playlist(
                    name = pl.name,
                    imageUrl = pl.coverUri,
                    subtitle = pl.subtitle,
                    id = pl.spotifyId
                )
            )
        }
        sections.add(
            HomeSection(
                id = HomeSectionIds.RECENTLY_PLAYED,
                title = "לאחרונה",
                type = HomeSectionType.HORIZONTAL,
                items = recentItems
            )
        )

        // ── 4. מומלץ להיום (MUST BE TRACKS ONLY - NO ARTISTS!) ──
        val recSongs = if (recommendations.isNotEmpty()) {
            recommendations
        } else {
            fallbackTopSongs
        }
        if (recSongs.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.RECOMMENDED_TODAY,
                    title = "מומלץ להיום",
                    type = HomeSectionType.HORIZONTAL,
                    items = recSongs.take(15).map { HomeItem.Track(it) }
                )
            )
        }

        // ── 5. אמנים נוספים כמו [שם האמן המוביל] (ONLY ARTISTS!) ──
        if (topArtist != null && similarContent != null && similarContent.items.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.SIMILAR_ARTISTS,
                    title = topArtist.artistName,
                    subtitle = "אמנים נוספים כמו",
                    headerArtist = similarContent.topArtistModel,
                    type = HomeSectionType.ARTISTS,
                    items = similarContent.items // ONLY artists!
                )
            )
        }

        // ── 6. על סמך היסטוריית ההאזנה שלכם בזמן האחרון ──
        val historyItems = personal.history
        if (historyItems.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.HISTORY_BASED,
                    title = "על סמך היסטוריית ההאזנה שלכם בזמן האחרון",
                    type = HomeSectionType.HORIZONTAL,
                    items = historyItems
                )
            )
        }

        // ── 7. אלבומים וסינגלים פופולריים ──
        if (albumItems.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.POPULAR_ALBUMS,
                    title = "אלבומים וסינגלים פופולריים",
                    type = HomeSectionType.ALBUMS,
                    items = albumItems
                )
            )
        }

        // ── 8. אמנים פופולריים (Always Hebrew title + circular cards) ──
        val artistItems = if (artists.isNotEmpty()) {
            artists.map { HomeItem.Artist(name = it.name, imageUrl = it.coverUri, id = it.id) }
        } else {
            val fromSpotify = rawSections.firstOrNull { s ->
                val t = s.title.lowercase()
                t.contains("popular artists") || t.contains("אמנים") || t.contains("favorite artists")
            }?.items?.filterIsInstance<HomeItem.Artist>()
            fromSpotify?.takeIf { it.isNotEmpty() } ?: fallbackArtists.map {
                HomeItem.Artist(name = it.name, imageUrl = it.coverUri, id = it.id)
            }
        }
        if (artistItems.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.POPULAR_ARTISTS,
                    title = "אמנים פופולריים",
                    type = HomeSectionType.ARTISTS,
                    items = artistItems
                )
            )
        }

        return HomeFeedModel(
            greeting = spotifyHome?.greeting?.ifBlank { "שלום" } ?: "שלום",
            topGrid = topGrid.take(4),
            sections = sections
        )
    }

    private data class SimilarArtistData(
        val topArtistModel: ArtistsModel?,
        val items: List<HomeItem.Artist>
    )

    private suspend fun loadSimilarContent(artistName: String): SimilarArtistData {
        return try {
            withTimeoutOrNull(6000L) {
                val resp = repository.provideArtistOverview(artistName)
                    .filter { it !is Response.Loading }
                    .firstOrNull()
                val overview = (resp as? Response.Success)?.data ?: return@withTimeoutOrNull null

                val topArtistModel = ArtistsModel(
                    name = artistName,
                    coverUri = overview.avatarImage.ifBlank { overview.headerImage },
                    id = overview.id
                )

                // Add ONLY similar artists (no topTracks!)
                val items = overview.relatedArtists.take(12)
                    .filter { it.name.isNotBlank() }
                    .map { HomeItem.Artist(name = it.name, imageUrl = it.coverUri, id = it.id) }

                SimilarArtistData(topArtistModel, items)
            } ?: SimilarArtistData(null, emptyList())
        } catch (e: Exception) {
            SimilarArtistData(null, emptyList())
        }
    }

    private suspend fun loadPlaylists(query: String): List<HomeItem.Playlist> {
        return try {
            withTimeoutOrNull(5000L) {
                val resp = repository.provideCategoryPlaylists(query)
                    .filter { it !is Response.Loading }
                    .firstOrNull()
                val entries = (resp as? Response.Success)?.data.orEmpty()
                entries.map {
                    HomeItem.Playlist(name = it.name, imageUrl = it.coverUri, subtitle = it.subtitle, id = it.spotifyId)
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun loadTopSongs(): List<SongsModel> {
        return try {
            withTimeoutOrNull(5000L) {
                val resp = repository.provideSongs()
                    .filter { it !is Response.Loading }
                    .firstOrNull()
                (resp as? Response.Success)?.data.orEmpty()
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun loadSearchArtists(query: String): List<ArtistsModel> {
        return try {
            withTimeoutOrNull(5000L) {
                val resp = repository.searchEverything(query)
                    .filter { it !is Response.Loading }
                    .firstOrNull()
                (resp as? Response.Success)?.data?.artists.orEmpty()
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun loadSpotifyHome(): HomeFeedModel? {
        return try {
            withTimeoutOrNull(6000L) {
                val resp = repository.provideHomeFeed()
                    .filter { it !is Response.Loading }
                    .firstOrNull()
                (resp as? Response.Success)?.data
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadArtists(): List<ArtistsModel> {
        return try {
            withTimeoutOrNull(5000L) {
                val resp = repository.provideArtists()
                    .filter { it !is Response.Loading }
                    .firstOrNull()
                (resp as? Response.Success)?.data.orEmpty()
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun loadLikedSongs(): List<SongsModel> {
        return try {
            withTimeoutOrNull(5000L) {
                val resp = repository.provideLikedSongs()
                    .filter { it !is Response.Loading }
                    .firstOrNull()
                (resp as? Response.Success)?.data.orEmpty()
            } ?: com.music.spotui.data.preferences.getLocallyLikedSongs(context)
        } catch (e: Exception) {
            com.music.spotui.data.preferences.getLocallyLikedSongs(context)
        }
    }

    private suspend fun loadRecommendations(seedTrackIds: List<String>): List<SongsModel> {
        if (seedTrackIds.isEmpty()) return emptyList()
        return try {
            withTimeoutOrNull(5000L) {
                repository.provideRecommendations(seedTrackIds)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun mergeLocalRecent(
        baseFeed: HomeFeedModel,
        recentTracks: List<TrackListenStat>,
        likedSongs: List<SongsModel>
    ): HomeFeedModel {
        // 1. Rebuild Top Grid
        val topGrid = mutableListOf<HomeItem>()
        if (recentTracks.isNotEmpty()) {
            topGrid.addAll(recentTracks.take(4).map { HomeItem.Track(it.toSongModel()) })
        }
        val remainingNeeded = 4 - topGrid.size
        if (remainingNeeded > 0) {
            topGrid.addAll(baseFeed.topGrid.filterNot { it in topGrid }.take(remainingNeeded))
        }

        // 2. Rebuild RECENTLY_PLAYED section
        val recentItems = mutableListOf<HomeItem>()
        recentItems.add(HomeItem.LikedSongs(count = likedSongs.size))
        recentTracks.forEach { stat ->
            recentItems.add(HomeItem.Track(stat.toSongModel()))
        }
        val playlists = getCachedPlaylists(context)
        playlists.forEach { pl ->
            recentItems.add(
                HomeItem.Playlist(
                    name = pl.name,
                    imageUrl = pl.coverUri,
                    subtitle = pl.subtitle,
                    id = pl.spotifyId
                )
            )
        }
        val recentSection = HomeSection(
            id = HomeSectionIds.RECENTLY_PLAYED,
            title = "לאחרונה",
            type = HomeSectionType.HORIZONTAL,
            items = recentItems
        )

        // 3. Update sections list
        val updatedSections = baseFeed.sections.toMutableList()
        val index = updatedSections.indexOfFirst { it.id == HomeSectionIds.RECENTLY_PLAYED }
        if (index != -1) {
            updatedSections[index] = recentSection
        } else {
            updatedSections.add(recentSection)
        }

        return baseFeed.copy(
            topGrid = topGrid.take(4),
            sections = updatedSections
        )
    }

    private fun saveToDisk(feed: HomeFeedModel) {
        runCatching {
            val root = JSONObject().apply {
                put("version", 2)
                put("cachedAt", cachedAt)
                val feedObj = JSONObject().apply {
                    put("greeting", feed.greeting)
                    val gridArr = JSONArray()
                    feed.topGrid.forEach { item ->
                        HomeFeedItemCodec.encode(item)?.let { gridArr.put(it) }
                    }
                    put("topGrid", gridArr)

                    val secArr = JSONArray()
                    feed.sections.forEach { sec ->
                        val secObj = JSONObject().apply {
                            put("id", sec.id)
                            put("title", sec.title)
                            put("subtitle", sec.subtitle ?: "")
                            put("type", sec.type.name)
                            if (sec.headerArtist != null) {
                                put("headerArtist", JSONObject().apply {
                                    put("name", sec.headerArtist.name)
                                    put("coverUri", sec.headerArtist.coverUri)
                                    put("id", sec.headerArtist.id)
                                })
                            }
                            val itemsArr = JSONArray()
                            sec.items.forEach { item ->
                                HomeFeedItemCodec.encode(item)?.let { itemsArr.put(it) }
                            }
                            put("items", itemsArr)
                        }
                        secArr.put(secObj)
                    }
                    put("sections", secArr)
                }
                put("feed", feedObj)
            }
            val file = File(context.filesDir, "home_feed_cache.json")
            file.writeText(root.toString())
        }
    }

    private fun loadFromDisk(): HomeFeedModel? {
        return runCatching {
            val file = File(context.filesDir, "home_feed_cache.json")
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            diskCachedAt = if (root.optInt("version") >= 2) root.optLong("cachedAt") else 0L
            val feedObj = root.optJSONObject("feed") ?: return null
            val greeting = feedObj.optString("greeting", "שלום")

            val gridArr = feedObj.optJSONArray("topGrid")
            val topGrid = mutableListOf<HomeItem>()
            if (gridArr != null) {
                for (i in 0 until gridArr.length()) {
                    gridArr.optJSONObject(i)?.let { HomeFeedItemCodec.decode(it)?.let { item -> topGrid.add(item) } }
                }
            }

            val secArr = feedObj.optJSONArray("sections")
            val sections = mutableListOf<HomeSection>()
            if (secArr != null) {
                for (i in 0 until secArr.length()) {
                    val secObj = secArr.optJSONObject(i) ?: continue
                    val id = secObj.optString("id", "")
                    val title = secObj.optString("title", "")
                    val subtitle = secObj.optString("subtitle").takeIf { it.isNotBlank() }
                    val typeStr = secObj.optString("type", HomeSectionType.HORIZONTAL.name)
                    val type = runCatching { HomeSectionType.valueOf(typeStr) }.getOrDefault(HomeSectionType.HORIZONTAL)

                    val headerArtistObj = secObj.optJSONObject("headerArtist")
                    val headerArtist = headerArtistObj?.let {
                        ArtistsModel(name = it.optString("name"), coverUri = it.optString("coverUri"), id = it.optString("id"))
                    }

                    val itemsArr = secObj.optJSONArray("items")
                    val items = mutableListOf<HomeItem>()
                    if (itemsArr != null) {
                        for (j in 0 until itemsArr.length()) {
                            itemsArr.optJSONObject(j)?.let { HomeFeedItemCodec.decode(it)?.let { item -> items.add(item) } }
                        }
                    }

                    sections.add(HomeSection(id, title, subtitle, headerArtist, type, items))
                }
            }

            val feed = HomeFeedModel(greeting, topGrid, sections)
            if (root.optInt("version") < 2) HomeDiscoveryPresentation.migrateLegacy(feed) else feed
        }.getOrNull()
    }

}
