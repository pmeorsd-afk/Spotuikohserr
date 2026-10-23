package com.music.spotui.playback

/** Home is a single scrollable feed; large library collections keep their bounded folders. */
internal object AutoBrowseWindow {
    data class Window<T>(val items: List<T>, val nextOffset: Int?)

    fun <T> select(parent: String, items: List<T>, offset: Int, pageSize: Int): Window<T> {
        require(offset >= 0 && pageSize > 0 && offset % pageSize == 0)
        if (parent == "home" || parent == "music") return Window(items.drop(offset), null)
        val page = AutoMediaCatalog.page(items, offset / pageSize, pageSize)
        val next = (offset.toLong() + pageSize).takeIf { it < items.size }?.toInt()
        return Window(page, next)
    }
}
