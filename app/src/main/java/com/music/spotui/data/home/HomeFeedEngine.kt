package com.music.spotui.data.home

import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.ArtistListenStat
import com.music.spotui.data.local.LocalListeningTracker
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeFeedEngine @Inject constructor(
    private val repository: AppRepository,
    private val listeningTracker: LocalListeningTracker
) {

    private data class SimilarContent(
        val artists: List<ArtistsModel>,
        val songs: List<SongsModel>
    )

    /**
     * Builds a personalized home feed based on the user's local listening history.
     * Returns null if there is no listening history yet (fallback to default Spotify home).
     */
    suspend fun buildPersonalizedFeed(): HomeFeedModel? {
        val sections = mutableListOf<HomeSection>()

        // 1. Recently Played Section
        val recentTracks = listeningTracker.recentTracks(limit = 10)
        val recentSongs = recentTracks.map { it.toSongModel() }

        if (recentSongs.isNotEmpty()) {
            sections += HomeSection(
                title = "האזנת לאחרונה",
                items = recentSongs.map { HomeItem.Track(it) }
            )
        }

        // 2. Strongest Artist Section
        val topArtist = listeningTracker.topArtists(limit = 1).firstOrNull()

        if (topArtist != null && topArtist.artistName.isNotBlank()) {
            val artistSongs = loadArtistSongs(topArtist.artistName)
            val recentKeys = recentSongs.map { it.spotifyTrackId.ifBlank { it.url } }.toSet()

            // Filter out songs already in "Recently Played" so the user discovers more
            val freshSongs = artistSongs
                .filter { (it.spotifyTrackId.ifBlank { it.url }) !in recentKeys }
                .distinctBy { it.spotifyTrackId.ifBlank { it.url } }
                .take(10)

            val displayArtistSongs = if (freshSongs.isNotEmpty()) freshSongs else artistSongs.take(10)
            if (displayArtistSongs.isNotEmpty()) {
                sections += HomeSection(
                    title = "עוד מ${topArtist.artistName}",
                    items = displayArtistSongs.map { HomeItem.Track(it) }
                )
            }

            // 3. Similar Artists & Songs Section
            val similar = loadSimilarContent(topArtist.artistName)
            val similarItems = mutableListOf<HomeItem>()

            similar.artists
                .filter { it.name.isNotBlank() }
                .distinctBy { it.id.ifBlank { it.name } }
                .take(6)
                .forEach {
                    similarItems.add(HomeItem.Artist(name = it.name, imageUrl = it.coverUri, id = it.id))
                }

            similar.songs
                .filter { it.title.isNotBlank() }
                .distinctBy { it.spotifyTrackId.ifBlank { it.url } }
                .take(10)
                .forEach {
                    similarItems.add(HomeItem.Track(it))
                }

            if (similarItems.isNotEmpty()) {
                sections += HomeSection(
                    title = "זמרים ושירים דומים",
                    items = similarItems
                )
            }
        }

        if (sections.isEmpty()) return null
        return HomeFeedModel(greeting = "שלום", sections = sections)
    }

    private suspend fun loadArtistSongs(artistName: String): List<SongsModel> {
        return try {
            withTimeoutOrNull(5000L) {
                val resp = repository.provideArtistSongs(artistName)
                    .filter { it !is Response.Loading }
                    .firstOrNull()
                (resp as? Response.Success)?.data.orEmpty()
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun loadSimilarContent(artistName: String): SimilarContent {
        return try {
            withTimeoutOrNull(6000L) {
                val resp = repository.provideArtistOverview(artistName)
                    .filter { it !is Response.Loading }
                    .firstOrNull()
                val overview = (resp as? Response.Success)?.data
                SimilarContent(
                    artists = overview?.relatedArtists.orEmpty(),
                    songs = overview?.topTracks?.map { it.song }.orEmpty()
                )
            } ?: SimilarContent(emptyList(), emptyList())
        } catch (e: Exception) {
            SimilarContent(emptyList(), emptyList())
        }
    }
}
