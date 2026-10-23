package com.music.spotui.ui.viewmodel

import kotlinx.coroutines.flow.MutableSharedFlow

object HomeRefreshSignal {
    data class Request(val selectAll: Boolean)
    val events = MutableSharedFlow<Request>(extraBufferCapacity = 1)

    fun trigger(selectAll: Boolean = false) {
        events.tryEmit(Request(selectAll))
    }
}
