package com.music.spotui.data.home

import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
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
    @ApplicationContext private val context: Context
) {

    private var cachedFeed: HomeFeedModel? = null
    private var cachedAt: Long = 0L
    private val cacheTtlMs = 2 * 60 * 1000L // 2 minutes in-memory cache
    private val mutex = Mutex()
    private val singleFlightMutex = Mutex()
    private var inFlightRefresh: Deferred<HomeFeedModel>? = null

    fun invalidate() {
        cachedFeed = null
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

    suspend fun getHomeFeed(forceRefresh: Boolean = false): HomeFeedModel {
        if (!forceRefresh && isCacheValid()) {
            return cachedFeed!!
        }

        if (!forceRefresh && cachedFeed == null) {
            val disk = loadFromDisk()
            if (disk != null && disk.isHealthy()) {
                cachedFeed = disk
                cachedAt = System.currentTimeMillis()
                return disk
            }
        }

        // Single-flight refresh: if a full refresh is already running, await it
        val job = singleFlightMutex.withLock {
            if (!forceRefresh && isCacheValid()) {
                return cachedFeed!!
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

    private suspend fun doOrchestratedRefresh(): HomeFeedModel = mutex.withLock {
        val newFeed = buildOrchestratedFeed()
        val finalFeed = if (newFeed.isHealthy()) {
            cachedFeed = newFeed
            cachedAt = System.currentTimeMillis()
            saveToDisk(newFeed)
            newFeed
        } else {
            // Anti-degradation: Never replace a healthy feed with a degraded feed!
            val healthyBase = when {
                cachedFeed != null && cachedFeed!!.isHealthy() -> cachedFeed
                else -> loadFromDisk()?.takeIf { it.isHealthy() }
            }

            if (healthyBase != null) {
                val recentTracks = listeningTracker.recentTracks(limit = 10)
                val likedSongs = loadLikedSongs()
                val merged = mergeLocalRecent(healthyBase, recentTracks, likedSongs)
                cachedFeed = merged
                cachedAt = System.currentTimeMillis()
                saveToDisk(merged)
                merged
            } else {
                cachedFeed = newFeed
                cachedAt = System.currentTimeMillis()
                newFeed
            }
        }
        finalFeed
    }

    /**
     * Incremental update for local events (song played / liked) in <5ms without re-hitting network.
     */
    suspend fun updateRecentListeningOnly(): HomeFeedModel = withContext(Dispatchers.IO) {
        mutex.withLock {
            val base = cachedFeed ?: loadFromDisk()
            val recentTracks = listeningTracker.recentTracks(limit = 10)
            val likedSongs = loadLikedSongs()
            val updated = if (base != null) {
                mergeLocalRecent(base, recentTracks, likedSongs)
            } else {
                buildOrchestratedFeed()
            }
            cachedFeed = updated
            cachedAt = System.currentTimeMillis()
            saveToDisk(updated)
            updated
        }
    }

    /**
     * Non-destructive personalized patch: updates SIMILAR_ARTISTS, RECOMMENDED_TODAY,
     * and HISTORY_BASED based on the latest listening tracker stats without destroying
     * or rebuilding healthy base sections (albums, mixes, radio, popular artists).
     */
    suspend fun updatePersonalizedRecommendations(): HomeFeedModel = withContext(Dispatchers.IO) {
        mutex.withLock {
            val base = cachedFeed ?: loadFromDisk() ?: return@withLock updateRecentListeningOnly()
            val recentTracks = listeningTracker.recentTracks(limit = 10)
            val likedSongs = loadLikedSongs()
            val topArtists = listeningTracker.topArtists(limit = 1)
            val topArtist = topArtists.firstOrNull()

            val seedIds = recentTracks.mapNotNull { it.trackId.takeIf { id -> id.isNotBlank() } }.take(5)

            // Parallel fetch of personalized recommendations
            val (recommendations, similarContent, tastePlaylists) = coroutineScope {
                val recDeferred = if (seedIds.isNotEmpty()) {
                    async(Dispatchers.IO) { loadRecommendations(seedIds) }
                } else null

                val similarDeferred = if (topArtist != null && topArtist.artistName.isNotBlank()) {
                    async(Dispatchers.IO) { loadSimilarContent(topArtist.artistName) }
                } else null

                val tastePlaylistsDeferred = if (topArtist != null && topArtist.artistName.isNotBlank()) {
                    async(Dispatchers.IO) { loadPlaylists("${topArtist.artistName} פלייליסט") }
                } else null

                Triple(
                    recDeferred?.await().orEmpty(),
                    similarDeferred?.await(),
                    tastePlaylistsDeferred?.await().orEmpty()
                )
            }

            // 1. Merge recent tracks and top grid first
            val mergedWithRecent = mergeLocalRecent(base, recentTracks, likedSongs)
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

            // HISTORY_BASED
            if (tastePlaylists.isNotEmpty()) {
                val histSection = HomeSection(
                    id = HomeSectionIds.HISTORY_BASED,
                    title = "על סמך היסטוריית ההאזנה שלכם בזמן האחרון",
                    type = HomeSectionType.HORIZONTAL,
                    items = tastePlaylists
                )
                val idx = updatedSections.indexOfFirst { it.id == HomeSectionIds.HISTORY_BASED }
                if (idx != -1) {
                    updatedSections[idx] = histSection
                } else {
                    updatedSections.add(histSection)
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
        val albumsDeferred = async(Dispatchers.IO) { loadAlbums() }
        val artistsDeferred = async(Dispatchers.IO) { loadArtists() }
        val likedSongsDeferred = async(Dispatchers.IO) { loadLikedSongs() }
        val mixesDeferred = async(Dispatchers.IO) { loadPlaylists("מיקס") }
        val radioDeferred = async(Dispatchers.IO) { loadPlaylists("רדיו") }
        val fallbackSongsDeferred = async(Dispatchers.IO) { loadTopSongs() }
        val fallbackAlbumsDeferred = async(Dispatchers.IO) { loadSearchAlbums("2024") }
        val fallbackPlaylistsDeferred = async(Dispatchers.IO) { loadPlaylists("להיטים") }
        val fallbackArtistsDeferred = async(Dispatchers.IO) { loadSearchArtists("ישראלי") }

        val recentTracks = recentDeferred.await()
        val topArtists = topArtistsDeferred.await()
        val spotifyHome = spotifyHomeDeferred.await()
        val albums = albumsDeferred.await()
        val artists = artistsDeferred.await()
        val likedSongs = likedSongsDeferred.await()
        val fallbackMixes = mixesDeferred.await()
        val fallbackRadio = radioDeferred.await()
        val fallbackTopSongs = fallbackSongsDeferred.await()
        val fallbackAlbums = fallbackAlbumsDeferred.await()
        val fallbackPlaylists = fallbackPlaylistsDeferred.await()
        val fallbackArtists = fallbackArtistsDeferred.await()

        // Seeds for recommendations based on recent plays
        val seedIds = recentTracks.mapNotNull { it.trackId.takeIf { id -> id.isNotBlank() } }.take(5)
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

        val tastePlaylistsDeferred = if (topArtist != null && topArtist.artistName.isNotBlank()) {
            async(Dispatchers.IO) { loadPlaylists("${topArtist.artistName} פלייליסט") }
        } else null

        val similarContent = similarDeferred?.await()
        val tastePlaylists = tastePlaylistsDeferred?.await().orEmpty()

        assembleFeed(
            recentTracks = recentTracks,
            topArtist = topArtist,
            spotifyHome = spotifyHome,
            albums = albums,
            fallbackAlbums = fallbackAlbums,
            artists = artists,
            fallbackArtists = fallbackArtists,
            likedSongs = likedSongs,
            recommendations = recommendations,
            fallbackTopSongs = fallbackTopSongs,
            fallbackMixes = fallbackMixes,
            fallbackRadio = fallbackRadio,
            fallbackPlaylists = fallbackPlaylists,
            tastePlaylists = tastePlaylists,
            similarContent = similarContent
        )
    }

    private fun assembleFeed(
        recentTracks: List<TrackListenStat>,
        topArtist: ArtistListenStat?,
        spotifyHome: HomeFeedModel?,
        albums: List<AlbumsModel>,
        fallbackAlbums: List<HomeItem.Album>,
        artists: List<ArtistsModel>,
        fallbackArtists: List<ArtistsModel>,
        likedSongs: List<SongsModel>,
        recommendations: List<SongsModel>,
        fallbackTopSongs: List<SongsModel>,
        fallbackMixes: List<HomeItem.Playlist>,
        fallbackRadio: List<HomeItem.Playlist>,
        fallbackPlaylists: List<HomeItem.Playlist>,
        tastePlaylists: List<HomeItem.Playlist>,
        similarContent: SimilarArtistData?
    ): HomeFeedModel {
        val rawSections = spotifyHome?.sections.orEmpty()
        val sections = mutableListOf<HomeSection>()

        // ── Resolved Data Elements ──
        val albumItems = if (albums.isNotEmpty()) {
            albums.map { HomeItem.Album(name = it.name, imageUrl = it.coverUri, subtitle = it.artists, artists = it.artists) }
        } else {
            fallbackAlbums
        }

        val mixItems = rawSections.firstOrNull { s ->
            val t = s.title.lowercase()
            t.contains("מיקס") || t.contains("mix") || t.contains("daily")
        }?.items?.takeIf { it.isNotEmpty() } ?: fallbackMixes

        val radioItems = rawSections.firstOrNull { s ->
            val t = s.title.lowercase()
            t.contains("רדיו") || t.contains("radio") || t.contains("station")
        }?.items?.takeIf { it.isNotEmpty() } ?: fallbackRadio

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
                    title = "המיקסים המובילים שלכם",
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
                    title = "רדיו פופולרי",
                    type = HomeSectionType.HORIZONTAL,
                    items = radioItems
                )
            )
        }

        // ── 3. לאחרונה (Liked Songs pinned first + recent tracks) ──
        val recentItems = mutableListOf<HomeItem>()
        recentItems.add(HomeItem.LikedSongs(count = likedSongs.size))
        recentTracks.forEach { stat ->
            recentItems.add(HomeItem.Track(stat.toSongModel()))
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
        val historyItems = if (tastePlaylists.isNotEmpty()) {
            tastePlaylists
        } else {
            val fromSpotify = rawSections.firstOrNull { s ->
                val t = s.title.lowercase()
                (t.contains("היסטוריית") || t.contains("האזנה") || t.contains("recent") || t.contains("jump back") || t.contains("חזרה")) &&
                s.id != HomeSectionIds.TOP_MIXES && s.id != HomeSectionIds.POPULAR_RADIO
            }?.items
            fromSpotify?.takeIf { it.isNotEmpty() } ?: fallbackPlaylists
        }
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

    private suspend fun loadSearchAlbums(query: String): List<HomeItem.Album> {
        return try {
            withTimeoutOrNull(5000L) {
                val resp = repository.searchEverything(query)
                    .filter { it !is Response.Loading }
                    .firstOrNull()
                val albums = (resp as? Response.Success)?.data?.albums.orEmpty()
                albums.map {
                    HomeItem.Album(name = it.name, imageUrl = it.coverUri, subtitle = it.artists, artists = it.artists)
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

    private suspend fun loadAlbums(): List<AlbumsModel> {
        return try {
            withTimeoutOrNull(5000L) {
                val resp = repository.provideAlbums()
                    .filter { it !is Response.Loading }
                    .firstOrNull()
                (resp as? Response.Success)?.data.orEmpty()
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
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
                put("version", 1)
                put("cachedAt", System.currentTimeMillis())
                val feedObj = JSONObject().apply {
                    put("greeting", feed.greeting)
                    val gridArr = JSONArray()
                    feed.topGrid.forEach { item ->
                        itemToJson(item)?.let { gridArr.put(it) }
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
                                itemToJson(item)?.let { itemsArr.put(it) }
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
            val feedObj = root.optJSONObject("feed") ?: return null
            val greeting = feedObj.optString("greeting", "שלום")

            val gridArr = feedObj.optJSONArray("topGrid")
            val topGrid = mutableListOf<HomeItem>()
            if (gridArr != null) {
                for (i in 0 until gridArr.length()) {
                    gridArr.optJSONObject(i)?.let { jsonToItem(it)?.let { item -> topGrid.add(item) } }
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
                            itemsArr.optJSONObject(j)?.let { jsonToItem(it)?.let { item -> items.add(item) } }
                        }
                    }

                    sections.add(HomeSection(id, title, subtitle, headerArtist, type, items))
                }
            }

            HomeFeedModel(greeting, topGrid, sections)
        }.getOrNull()
    }

    private fun itemToJson(item: HomeItem): JSONObject? {
        val o = JSONObject()
        when (item) {
            is HomeItem.Album -> {
                o.put("itemType", "album")
                o.put("name", item.name)
                o.put("imageUrl", item.imageUrl)
                o.put("subtitle", item.subtitle)
                o.put("artists", item.artists)
            }
            is HomeItem.Artist -> {
                o.put("itemType", "artist")
                o.put("name", item.name)
                o.put("imageUrl", item.imageUrl)
                o.put("id", item.id)
            }
            is HomeItem.Playlist -> {
                o.put("itemType", "playlist")
                o.put("name", item.name)
                o.put("imageUrl", item.imageUrl)
                o.put("subtitle", item.subtitle)
                o.put("id", item.id)
            }
            is HomeItem.Track -> {
                o.put("itemType", "track")
                val s = item.song
                val songObj = JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("singer", s.singer)
                    put("album", s.album)
                    put("coverUri", s.coverUri)
                    put("url", s.url)
                    put("spotifyTrackId", s.spotifyTrackId)
                    put("explicit", s.explicit)
                    put("durationMs", s.durationMs)
                }
                o.put("song", songObj)
            }
            is HomeItem.LikedSongs -> {
                o.put("itemType", "liked_songs")
                o.put("count", item.count)
                o.put("name", item.name)
                o.put("imageUrl", item.imageUrl)
            }
        }
        return o
    }

    private fun jsonToItem(o: JSONObject): HomeItem? {
        return when (o.optString("itemType")) {
            "album" -> HomeItem.Album(
                name = o.optString("name"),
                imageUrl = o.optString("imageUrl"),
                subtitle = o.optString("subtitle"),
                artists = o.optString("artists")
            )
            "artist" -> HomeItem.Artist(
                name = o.optString("name"),
                imageUrl = o.optString("imageUrl"),
                id = o.optString("id")
            )
            "playlist" -> HomeItem.Playlist(
                name = o.optString("name"),
                imageUrl = o.optString("imageUrl"),
                subtitle = o.optString("subtitle"),
                id = o.optString("id")
            )
            "track" -> {
                val s = o.optJSONObject("song") ?: return null
                HomeItem.Track(
                    SongsModel(
                        id = s.optInt("id"),
                        title = s.optString("title"),
                        singer = s.optString("singer"),
                        album = s.optString("album"),
                        coverUri = s.optString("coverUri"),
                        url = s.optString("url"),
                        spotifyTrackId = s.optString("spotifyTrackId"),
                        explicit = s.optBoolean("explicit"),
                        durationMs = s.optInt("durationMs")
                    )
                )
            }
            "liked_songs" -> HomeItem.LikedSongs(
                count = o.optInt("count"),
                name = o.optString("name", "שירים שאהבתם"),
                imageUrl = o.optString("imageUrl")
            )
            else -> null
        }
    }
}
