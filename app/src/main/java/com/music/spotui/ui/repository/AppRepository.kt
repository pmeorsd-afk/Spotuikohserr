package com.music.spotui.ui.repository

import android.content.Context
import com.music.spotui.data.api.Api
import com.music.spotui.data.preferences.FollowedPodcastShow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AppRepository @Inject constructor(
    private val api: Api,
    @ApplicationContext private val context: Context,
) {

    suspend fun provideAlbums() = api.getAlbums()

    suspend fun provideHomeFeed() = api.getHomeFeed()

    suspend fun provideArtists() = api.getArtists()

    suspend fun provideSongs() = api.getSongs()

    suspend fun searchSongs(query: String) = api.searchTracks(query)

    suspend fun searchEverything(query: String) = api.searchEverything(query)

    suspend fun searchYouTube(
        query: String,
        filter: com.metrolist.innertube.YouTube.SearchFilter = com.metrolist.innertube.YouTube.SearchFilter.FILTER_ALL,
    ) = api.searchYouTube(query, filter)

    suspend fun provideAlbumSongs(albumName: String, artist: String = "", albumId: String = "") = api.getAlbumSongs(albumName, artist, albumId)

    suspend fun provideArtistSongs(artistName: String) = api.getArtistSongs(artistName)

    suspend fun provideArtistOverview(artistName: String, artistId: String = "") = api.getArtistOverview(artistName, artistId)

    suspend fun providePlaylistSongs(playlistId: String) = api.getPlaylistSongs(playlistId)

    suspend fun providePlaylist(playlistId: String) = api.getPlaylist(playlistId)

    suspend fun provideShowEpisodes(showId: String, showName: String = "") = api.getShowEpisodes(showId, showName)

    suspend fun providePodcastEpisodes(showId: String, showName: String = "") = api.getPodcastEpisodes(showId, showName)

    suspend fun provideShow(showId: String, showName: String = "") = api.getShow(showId, showName)

    suspend fun provideLibrary() = api.getLibrary()

    suspend fun provideFollowedArtists() = api.getFollowedArtists()

    suspend fun provideCategoryPlaylists(genre: String) = api.getCategoryPlaylists(genre)

    fun peekCachedPodcastHubShows() = api.peekCachedPodcastHubShows()

    suspend fun providePodcastHubShows(forceRefresh: Boolean = false) = api.getPodcastHubShows(forceRefresh)

    suspend fun providePodcastCategoryShows(
        categoryIdentifier: String,
        offset: Int = 0,
        forceRefresh: Boolean = false,
    ) = api.getPodcastCategoryShows(categoryIdentifier, offset, forceRefresh)

    suspend fun provideRecommendations(seedTrackIds: List<String>) = api.getRecommendations(seedTrackIds)

    suspend fun provideLikedSongs() = api.getLikedSongs()

    suspend fun provideAccount() = api.getAccount()

    suspend fun provideCanvasUrl(trackId: String) = api.getCanvasUrl(trackId)

    fun isPodcastFollowed(showId: String): Boolean =
        com.music.spotui.data.preferences.isPodcastFollowed(context, showId)

    fun followPodcast(show: FollowedPodcastShow) {
        com.music.spotui.data.preferences.followPodcast(context, show)
    }

    fun followPodcast(showId: String, name: String, imageUrl: String = "") {
        com.music.spotui.data.preferences.followPodcast(context, showId, name, imageUrl)
    }

    fun unfollowPodcast(showId: String): Boolean =
        com.music.spotui.data.preferences.unfollowPodcast(context, showId)

    fun getFollowedPodcasts(): List<FollowedPodcastShow> =
        com.music.spotui.data.preferences.getFollowedShows(context)
}