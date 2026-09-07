package com.metrolist.innertube.models.response

import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.SectionListRenderer
import com.metrolist.innertube.models.Tabs
import kotlinx.serialization.Serializable

@Serializable
data class BrowseResponse(
    val contents: Contents? = null,
    val header: Header? = null,
) {
    @Serializable
    data class Contents(
        val singleColumnBrowseResultsRenderer: Tabs? = null,
        val sectionListRenderer: SectionListRenderer? = null,
        val twoColumnBrowseResultsRenderer: TwoColumnBrowseResultsRenderer? = null,
    )

    @Serializable
    data class TwoColumnBrowseResultsRenderer(
        val secondaryContents: SecondaryContents? = null,
    ) {
        @Serializable
        data class SecondaryContents(
            val sectionListRenderer: SectionListRenderer? = null,
        )
    }

    @Serializable
    data class Header(
        val musicDetailHeaderRenderer: MusicDetailHeaderRenderer? = null,
        val musicResponsiveHeaderRenderer: MusicResponsiveHeaderRenderer? = null,
    )

    @Serializable
    data class MusicDetailHeaderRenderer(
        val title: Runs? = null,
        val subtitle: Runs? = null,
        val description: Runs? = null,
        val thumbnail: com.metrolist.innertube.models.ThumbnailRenderer? = null,
    )

    @Serializable
    data class MusicResponsiveHeaderRenderer(
        val title: Runs? = null,
        val subtitle: Runs? = null,
        val description: Runs? = null,
        val thumbnail: com.metrolist.innertube.models.ThumbnailRenderer? = null,
    )
}
