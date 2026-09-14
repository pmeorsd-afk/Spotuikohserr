package com.music.spotui.ui.viewmodel

import kotlinx.coroutines.flow.MutableSharedFlow

object HomeRefreshSignal {
    val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun trigger() {
        events.tryEmit(Unit)
    }
}
