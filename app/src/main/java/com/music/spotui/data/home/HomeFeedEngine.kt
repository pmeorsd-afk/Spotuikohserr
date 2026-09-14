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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeFeedEngine @Inject constructor(
    private val repository: AppRepository,
    private val listeningTracker: LocalListeningTracker
) {

    private var cachedFeed: HomeFeedModel? = null
    private var cachedAt: Long = 0L
    private val cacheTtlMs = 2 * 60 * 1000L // 2 minutes in-memory cache
    private val mutex = Mutex()

    fun invalidate() {
        cachedFeed = null
        cachedAt = 0L
    }

    private fun isCacheValid(): Boolean {
        val feed = cachedFeed ?: return false
        return (System.currentTimeMillis() - cachedAt) < cacheTtlMs && feed.sections.isNotEmpty()
    }

    suspend fun getHomeFeed(forceRefresh: Boolean = false): HomeFeedModel {
        if (!forceRefresh && isCacheValid()) {
            return cachedFeed!!
        }

        return mutex.withLock {
            if (!forceRefresh && isCacheValid()) {
                return@withLock cachedFeed!!
            }

            val feed = buildOrchestratedFeed()
            cachedFeed = feed
            cachedAt = System.currentTimeMillis()
            feed
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
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
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
}
