package com.music.spotui.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Offset advances by server items consumed, including unavailable/filtered entries. */
internal class PlaylistContentLoader<H, T>(private val fetch: suspend (Int) -> Page<H, T>) {
    data class Page<H, T>(val header: H, val items: List<T>, val total: Int, val consumed: Int)
    data class Content<H, T>(val header: H, val items: List<T>)

    fun load(): Flow<Content<H, T>> = flow {
        var offset = 0
        val all = mutableListOf<T>()
        var header: H? = null
        do {
            val page = fetch(offset)
            if (offset == 0) header = page.header
            all += page.items
            emit(Content(requireNotNull(header), all.toList()))
            if (page.consumed <= 0) break
            offset += page.consumed
        } while (offset < page.total)
    }
}
