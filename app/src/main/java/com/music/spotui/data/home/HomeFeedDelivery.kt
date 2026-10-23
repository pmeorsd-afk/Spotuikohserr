package com.music.spotui.data.home

import com.music.spotui.data.entity.HomeFeedModel

/** Delivery order is explicit: a slow/offline remote catalog cannot hold local Home behind a loader. */
internal object HomeFeedDelivery {
    suspend fun refresh(local: suspend () -> HomeFeedModel, remote: suspend () -> HomeFeedModel,
                        publish: (HomeFeedModel) -> Unit): HomeFeedModel {
        local().takeIf { it.sections.isNotEmpty() }?.let(publish)
        return remote().also { if (it.sections.isNotEmpty()) publish(it) }
    }
}
