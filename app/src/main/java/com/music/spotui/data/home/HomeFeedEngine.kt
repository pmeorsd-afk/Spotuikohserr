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

        val recentTracks = recentDeferred.await()
        val topArtists = topArtistsDeferred.await()
        val spotifyHome = spotifyHomeDeferred.await()
        val albums = albumsDeferred.await()
        val artists = artistsDeferred.await()
        val likedSongs = likedSongsDeferred.await()

        // Seeds for recommendations based on recent plays
        val seedIds = recentTracks.mapNotNull { it.trackId.takeIf { id -> id.isNotBlank() } }.take(5)
        val recommendations = if (seedIds.isNotEmpty()) {
            loadRecommendations(seedIds)
        } else {
            emptyList()
        }

        // Phase 2: Personalization dependent on Top Artist
        val topArtist = topArtists.firstOrNull()
        val similarContent = if (topArtist != null && topArtist.artistName.isNotBlank()) {
            loadSimilarContent(topArtist.artistName)
        } else null

        assembleFeed(
            recentTracks = recentTracks,
            topArtist = topArtist,
            spotifyHome = spotifyHome,
            albums = albums,
            artists = artists,
            likedSongs = likedSongs,
            recommendations = recommendations,
            similarContent = similarContent
        )
    }

    private fun assembleFeed(
        recentTracks: List<TrackListenStat>,
        topArtist: ArtistListenStat?,
        spotifyHome: HomeFeedModel?,
        albums: List<AlbumsModel>,
        artists: List<ArtistsModel>,
        likedSongs: List<SongsModel>,
        recommendations: List<SongsModel>,
        similarContent: SimilarArtistData?
    ): HomeFeedModel {
        val sections = mutableListOf<HomeSection>()

        // 0. Top 2x2 Grid (4 items)
        val topGrid = mutableListOf<HomeItem>()
        if (recentTracks.isNotEmpty()) {
            topGrid.addAll(recentTracks.take(4).map { HomeItem.Track(it.toSongModel()) })
        }
        if (topGrid.size < 4 && albums.isNotEmpty()) {
            val needed = 4 - topGrid.size
            albums.take(needed).forEach { alb ->
                topGrid.add(HomeItem.Album(name = alb.name, imageUrl = alb.coverUri, subtitle = alb.artists, artists = alb.artists))
            }
        }

        // Helper to locate sections from Spotify GQL home
        val rawSections = spotifyHome?.sections.orEmpty()

        // 1. המיקסים המובילים שלכם (Top Mixes)
        val mixesSection = rawSections.firstOrNull { s ->
            val titleLower = s.title.lowercase()
            titleLower.contains("מיקס") || titleLower.contains("mix") || titleLower.contains("daily")
        }
        if (mixesSection != null && mixesSection.items.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.TOP_MIXES,
                    title = "המיקסים המובילים שלכם",
                    type = HomeSectionType.MIXES,
                    items = mixesSection.items
                )
            )
        }

        // 2. רדיו פופולרי (Popular Radio)
        val radioSection = rawSections.firstOrNull { s ->
            val titleLower = s.title.lowercase()
            titleLower.contains("רדיו") || titleLower.contains("radio") || titleLower.contains("station")
        }
        if (radioSection != null && radioSection.items.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.POPULAR_RADIO,
                    title = "רדיו פופולרי",
                    type = HomeSectionType.HORIZONTAL,
                    items = radioSection.items
                )
            )
        }

        // 3. לאחרונה (Recently Played) with pinned Liked Songs card
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

        // 4. מומלץ להיום (Recommended Today)
        val recItems = if (recommendations.isNotEmpty()) {
            recommendations.take(15).map { HomeItem.Track(it) }
        } else {
            rawSections.firstOrNull { it.title.isNotBlank() && it.id != HomeSectionIds.TOP_MIXES && it.id != HomeSectionIds.POPULAR_RADIO }
                ?.items?.take(10) ?: emptyList()
        }
        if (recItems.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.RECOMMENDED_TODAY,
                    title = "מומלץ להיום",
                    type = HomeSectionType.HORIZONTAL,
                    items = recItems
                )
            )
        }

        // 5. אמנים נוספים כמו [שם האמן המוביל] (Similar Artists)
        if (topArtist != null && similarContent != null && similarContent.items.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.SIMILAR_ARTISTS,
                    title = topArtist.artistName,
                    subtitle = "אמנים נוספים כמו",
                    headerArtist = similarContent.topArtistModel,
                    type = HomeSectionType.HORIZONTAL,
                    items = similarContent.items
                )
            )
        }

        // 6. על סמך היסטוריית ההאזנה שלכם בזמן האחרון (Based on recent history)
        val historySection = rawSections.firstOrNull { s ->
            val titleLower = s.title.lowercase()
            (titleLower.contains("היסטוריית") || titleLower.contains("האזנה") || titleLower.contains("recent") || titleLower.contains("jump back") || titleLower.contains("חזרה")) &&
            s.id != HomeSectionIds.TOP_MIXES && s.id != HomeSectionIds.POPULAR_RADIO
        }
        if (historySection != null && historySection.items.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.HISTORY_BASED,
                    title = "על סמך היסטוריית ההאזנה שלכם בזמן האחרון",
                    type = HomeSectionType.HORIZONTAL,
                    items = historySection.items
                )
            )
        } else {
            val otherSpotifySection = rawSections.firstOrNull { s ->
                s.title.isNotBlank() &&
                s != mixesSection &&
                s != radioSection &&
                s.items.isNotEmpty()
            }
            if (otherSpotifySection != null) {
                sections.add(
                    HomeSection(
                        id = HomeSectionIds.HISTORY_BASED,
                        title = otherSpotifySection.title,
                        type = HomeSectionType.HORIZONTAL,
                        items = otherSpotifySection.items
                    )
                )
            }
        }

        // 7. אלבומים וסינגלים פופולריים (Popular Albums & Singles)
        if (albums.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.POPULAR_ALBUMS,
                    title = "אלבומים וסינגלים פופולריים",
                    type = HomeSectionType.ALBUMS,
                    items = albums.map {
                        HomeItem.Album(name = it.name, imageUrl = it.coverUri, subtitle = it.artists, artists = it.artists)
                    }
                )
            )
        }

        // 8. אמנים פופולריים (Popular Artists - circular cards)
        if (artists.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = HomeSectionIds.POPULAR_ARTISTS,
                    title = "אמנים פופולריים",
                    type = HomeSectionType.ARTISTS,
                    items = artists.map {
                        HomeItem.Artist(name = it.name, imageUrl = it.coverUri, id = it.id)
                    }
                )
            )
        }

        return HomeFeedModel(
            greeting = spotifyHome?.greeting?.ifBlank { "שלום" } ?: "שלום",
            topGrid = topGrid,
            sections = sections
        )
    }

    private data class SimilarArtistData(
        val topArtistModel: ArtistsModel?,
        val items: List<HomeItem>
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

                val items = mutableListOf<HomeItem>()
                overview.relatedArtists.take(4).forEach { rel ->
                    if (rel.name.isNotBlank()) {
                        items.add(HomeItem.Artist(name = rel.name, imageUrl = rel.coverUri, id = rel.id))
                    }
                }
                overview.topTracks.take(8).forEach { trackUi ->
                    items.add(HomeItem.Track(trackUi.song))
                }

                SimilarArtistData(topArtistModel, items)
            } ?: SimilarArtistData(null, emptyList())
        } catch (e: Exception) {
            SimilarArtistData(null, emptyList())
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
