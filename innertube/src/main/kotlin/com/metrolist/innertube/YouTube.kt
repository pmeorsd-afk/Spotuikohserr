package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.models.getContinuation
import com.metrolist.innertube.models.getItems
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertube.models.response.SearchResponse
import com.metrolist.innertube.pages.SearchPage
import com.metrolist.innertube.pages.SearchResult
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.Proxy

/**
 * Parse useful data with [InnerTube] sending requests.
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic).
 *
 * Trimmed for spotui: only the pieces the app uses survive — track search
 * (to match a Spotify track to a YouTube video), the player endpoint (to
 * resolve the audio stream) and the NewPipe fallback deobfuscation.
 */
object YouTube {
    private val innerTube = InnerTube()

    var locale: YouTubeLocale
        get() = innerTube.locale
        set(value) {
            innerTube.locale = value
        }
    var visitorData: String?
        get() = innerTube.visitorData
        set(value) {
            innerTube.visitorData = value
        }
    var dataSyncId: String?
        get() = innerTube.dataSyncId
        set(value) {
            innerTube.dataSyncId = value
        }
    var cookie: String?
        get() = innerTube.cookie
        set(value) {
            innerTube.cookie = value
        }
    var proxy: Proxy?
        get() = innerTube.proxy
        set(value) {
            innerTube.proxy = value
        }
    var proxyAuth: String?
        get() = innerTube.proxyAuth
        set(value) {
            innerTube.proxyAuth = value
        }
    var useLoginForBrowse: Boolean
        get() = innerTube.useLoginForBrowse
        set(value) {
            innerTube.useLoginForBrowse = value
        }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.FILTER_ALL): Result<SearchResult> = runCatching {
        val params = filter.value.takeIf { it.isNotBlank() }
        val response = innerTube.search(WEB_REMIX, query, params).body<SearchResponse>()
        val tabContent = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer
        val sectionContents = tabContent?.contents.orEmpty()
        val shelves = sectionContents.mapNotNull { it.musicShelfRenderer }
        val cardShelves = sectionContents.mapNotNull { it.musicCardShelfRenderer }
        val carouselShelves = sectionContents.mapNotNull { it.musicCarouselShelfRenderer }

        val itemsFromShelves = shelves.flatMap { shelf ->
            shelf.contents?.getItems()?.mapNotNull { SearchPage.toYTItem(it) } ?: emptyList()
        }
        val itemsFromCards = cardShelves.flatMap { card ->
            card.contents?.mapNotNull { it.musicResponsiveListItemRenderer?.let { r -> SearchPage.toYTItem(r) } } ?: emptyList()
        }
        val itemsFromCarousels = carouselShelves.flatMap { carousel ->
            carousel.contents.mapNotNull { content ->
                content.musicResponsiveListItemRenderer?.let { r -> SearchPage.toYTItem(r) }
                    ?: content.musicTwoRowItemRenderer?.let { r -> SearchPage.toYTItem(r) }
            }
        }
        val allItems = (itemsFromCards + itemsFromShelves + itemsFromCarousels).distinctBy { it.id }
        SearchResult(
            items = allItems,
            continuation = shelves.firstOrNull { it.continuations != null }
                ?.continuations?.getContinuation()
        )
    }

    suspend fun searchContinuation(continuation: String): Result<SearchResult> = runCatching {
        val response = innerTube.search(WEB_REMIX, continuation = continuation).body<SearchResponse>()
        val items = response.continuationContents?.musicShelfContinuation?.contents
            ?.mapNotNull {
                SearchPage.toYTItem(it.musicResponsiveListItemRenderer)
            } ?: emptyList()
        SearchResult(
            items = items,
            continuation = if (items.isEmpty()) null else response.continuationContents?.musicShelfContinuation?.continuations?.getContinuation()
        )
    }

    suspend fun player(videoId: String, playlistId: String? = null, client: YouTubeClient, signatureTimestamp: Int? = null, poToken: String? = null, authenticated: Boolean = false): Result<PlayerResponse> = runCatching {
        innerTube.player(client, videoId, playlistId, signatureTimestamp, poToken, authenticated).body<PlayerResponse>()
    }

    suspend fun visitorData(): Result<String> = runCatching {
        Json.parseToJsonElement(innerTube.getSwJsData().bodyAsText().substring(5))
            .jsonArray[0]
            .jsonArray[2]
            .jsonArray.first {
                (it as? JsonPrimitive)?.contentOrNull?.let { candidate ->
                    VISITOR_DATA_REGEX.containsMatchIn(candidate)
                } ?: false
            }
            .jsonPrimitive.content
    }

    /**
     * Validates that the current cookie/session tuple resolves an active YouTube account.
     * A 200 response alone is not enough: the endpoint can return a signed-out menu.
     */
    suspend fun validateLogin(): Result<Boolean> = runCatching {
        val body = innerTube.accountMenu(WEB_REMIX).bodyAsText()
        body.contains("activeAccountHeaderRenderer") ||
            (body.contains("accountName") && !body.contains("Sign in"))
    }

    data class YouTubePlaylistDetails(
        val id: String,
        val title: String,
        val author: String,
        val description: String,
        val thumbnail: String,
        val songCount: Int,
        val songs: List<com.metrolist.innertube.models.SongItem>,
    )

    suspend fun playlistDetails(playlistId: String): Result<YouTubePlaylistDetails> = runCatching {
        val cleanId = playlistId.removePrefix("youtube:").removePrefix("yt:").trim()
        val browseId = if (cleanId.startsWith("VL") || cleanId.startsWith("MPREb_") || cleanId.startsWith("FEmusic_")) cleanId else "VL$cleanId"
        val response = innerTube.browse(WEB_REMIX, browseId = browseId).body<com.metrolist.innertube.models.response.BrowseResponse>()
        val sectionContents = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents
            ?: response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents
            ?: response.contents?.sectionListRenderer?.contents
            ?: emptyList()
        val songs = sectionContents.flatMap { section ->
            val playlistShelf = section.musicPlaylistShelfRenderer
            val shelf = section.musicShelfRenderer
            val shelfContents = playlistShelf?.contents ?: shelf?.contents ?: emptyList()
            shelfContents.mapNotNull { content ->
                val renderer = content.musicResponsiveListItemRenderer
                if (renderer != null) {
                    val item = SearchPage.toYTItem(renderer)
                    when (item) {
                        is com.metrolist.innertube.models.SongItem -> item
                        is com.metrolist.innertube.models.EpisodeItem -> item.asSongItem()
                        else -> null
                    }
                } else null
            }
        }
        val headerDetail = response.header?.musicDetailHeaderRenderer
        val headerResp = response.header?.musicResponsiveHeaderRenderer
        val title = headerDetail?.title?.runs?.firstOrNull()?.text
            ?: headerResp?.title?.runs?.firstOrNull()?.text
            ?: ""
        val author = headerDetail?.subtitle?.runs?.firstOrNull()?.text
            ?: headerResp?.subtitle?.runs?.firstOrNull()?.text
            ?: ""
        val description = headerDetail?.description?.runs?.firstOrNull()?.text
            ?: headerResp?.description?.runs?.firstOrNull()?.text
            ?: ""
        val thumb = headerDetail?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
            ?: headerDetail?.thumbnail?.croppedSquareThumbnailRenderer?.getThumbnailUrl()
            ?: headerResp?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
            ?: headerResp?.thumbnail?.croppedSquareThumbnailRenderer?.getThumbnailUrl()
            ?: songs.firstOrNull()?.thumbnail
            ?: ""

        YouTubePlaylistDetails(
            id = cleanId,
            title = title,
            author = author,
            description = description,
            thumbnail = thumb,
            songCount = songs.size,
            songs = songs,
        )
    }

    suspend fun playlist(playlistId: String): Result<List<com.metrolist.innertube.models.SongItem>> =
        playlistDetails(playlistId).map { it.songs }

    @JvmInline
    value class SearchFilter(val value: String) {
        companion object {
            val FILTER_ALL = SearchFilter("")
            val FILTER_SONG = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_PLAYLIST = SearchFilter("EgWKAQIoAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALBUM = SearchFilter("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ARTIST = SearchFilter("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D")
        }
    }

    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")

    fun getNewPipeStreamUrls(videoId: String): List<Pair<Int, String>> {
        return NewPipeExtractor.newPipePlayer(videoId)
    }

    suspend fun newPipePlayer(
        videoId: String,
        tempRes: PlayerResponse,
    ): PlayerResponse? {
        if (tempRes.playabilityStatus.status != "OK") {
            return null
        }

        val streamsList = getNewPipeStreamUrls(videoId)
        if (streamsList.isEmpty()) return null

        val decodedSigResponse = tempRes.copy(
            streamingData = tempRes.streamingData?.copy(
                formats = tempRes.streamingData.formats?.map { format ->
                    format.copy(
                        url = streamsList.find { it.first == format.itag }?.second ?: format.url,
                    )
                },
                adaptiveFormats = tempRes.streamingData.adaptiveFormats.map { adaptiveFormat ->
                    adaptiveFormat.copy(
                        url = streamsList.find { it.first == adaptiveFormat.itag }?.second ?: adaptiveFormat.url,
                    )
                },
            ),
        )

        val urlList = (
            decodedSigResponse.streamingData?.adaptiveFormats?.mapNotNull { it.url }?.toMutableList() ?: mutableListOf()
        ).apply {
            decodedSigResponse.streamingData?.formats?.mapNotNull { it.url }?.let { addAll(it) }
        }

        return if (urlList.isNotEmpty()) {
            decodedSigResponse
        } else {
            null
        }
    }
}
