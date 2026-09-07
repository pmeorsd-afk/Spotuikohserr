package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicCarouselShelfRenderer(
    val header: Header? = null,
    val contents: List<Content> = emptyList(),
    val itemSize: String? = null,
    val numItemsPerColumn: Int? = null,
) {
    @Serializable
    data class Header(
        val musicCarouselShelfBasicHeaderRenderer: MusicCarouselShelfBasicHeaderRenderer? = null,
    ) {
        @Serializable
        data class MusicCarouselShelfBasicHeaderRenderer(
            val strapline: Runs? = null,
            val title: Runs? = null,
            val thumbnail: ThumbnailRenderer? = null,
            val moreContentButton: Button? = null,
        )
    }

    @Serializable
    data class Content(
        val musicTwoRowItemRenderer: MusicTwoRowItemRenderer? = null,
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
        val musicNavigationButtonRenderer: MusicNavigationButtonRenderer? = null,
    )
}
