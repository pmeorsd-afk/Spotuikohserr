package com.music.spotui

import com.metrolist.innertube.YouTube
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun testNewPipeExtractorStreams() {
        val videoId = "qLwLOI4di7I" // מתן חסן - כמה עברנו
        val streams = YouTube.getNewPipeStreamUrls(videoId)
        assertTrue("Expected non-empty streams from NewPipeExtractor", streams.isNotEmpty())

        val highOpusUrl = streams.find { it.first == 251 }?.second ?: streams.first().second
        val client = OkHttpClient()
        val req = Request.Builder()
            .url(highOpusUrl)
            .addHeader("Range", "bytes=1048576-2097151")
            .build()
        client.newCall(req).execute().use { resp ->
            assertEquals("Expected HTTP 206 Partial Content past 1 MiB boundary", 206, resp.code)
        }
    }

    @Test
    fun testLiveSearch() {
        kotlinx.coroutines.runBlocking {
            val query = "השיבנו - לייב מופע הינדיקים 360 חנן בן ארי"
            val songSearch = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            println("=== FILTER_SONG RESULTS ===")
            songSearch?.items?.forEach {
                println("ITEM: title=${it.title}, artists=${(it as? com.metrolist.innertube.models.SongItem)?.artists?.map { a -> a.name }}, duration=${(it as? com.metrolist.innertube.models.SongItem)?.duration}, id=${it.id}")
            }

            val allSearch = YouTube.search(query).getOrNull()
            println("=== UNFILTERED RESULTS ===")
            allSearch?.items?.forEach {
                println("ITEM: title=${it.title}, artists=${(it as? com.metrolist.innertube.models.SongItem)?.artists?.map { a -> a.name }}, duration=${(it as? com.metrolist.innertube.models.SongItem)?.duration}, id=${it.id}")
            }
        }
    }
}





