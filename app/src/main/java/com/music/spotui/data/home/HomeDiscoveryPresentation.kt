package com.music.spotui.data.home

import com.music.spotui.data.entity.*

internal object HomeDiscoveryPresentation {
    fun withLikedCount(feed: HomeFeedModel, count: Int): HomeFeedModel = feed.copy(sections = feed.sections.map { section ->
        if (section.id != HomeSectionIds.RECENTLY_PLAYED) section else section.copy(items = section.items.map {
            if (it is HomeItem.LikedSongs) it.copy(count = count) else it
        })
    })

    private val order = listOf(HomeSectionIds.TOP_MIXES, HomeSectionIds.POPULAR_RADIO,
        HomeSectionIds.RECENTLY_PLAYED, HomeSectionIds.RECOMMENDED_TODAY, HomeSectionIds.SIMILAR_ARTISTS,
        HomeSectionIds.HISTORY_BASED, HomeSectionIds.POPULAR_ALBUMS, HomeSectionIds.POPULAR_ARTISTS)

    fun apply(feed: HomeFeedModel, general: PublicHomeCatalog, personal: PersonalHomeCatalog): HomeFeedModel {
        val sections = feed.sections.toMutableList()
        fun replace(id: String, title: String, type: HomeSectionType, items: List<HomeItem>) {
            sections.removeAll { it.id == id }
            if (items.isNotEmpty()) sections.add(HomeSection(id = id, title = title, type = type, items = items))
        }
        val genericMixes = general.discovery.ifEmpty {
            feed.sections.find { it.id == HomeSectionIds.TOP_MIXES }?.items?.filterIsInstance<HomeItem.Playlist>().orEmpty()
        }
        replace(HomeSectionIds.TOP_MIXES, if (personal.mixes.isEmpty()) "פלייליסטים לגלות" else "המיקסים המובילים שלכם",
            HomeSectionType.MIXES, personal.mixes.ifEmpty { genericMixes })
        val radio = personal.radio.ifEmpty { general.radio }
        if (radio.isNotEmpty() || sections.any { it.id == HomeSectionIds.POPULAR_RADIO && it.title == "רדיו בשבילכם" }) {
            replace(HomeSectionIds.POPULAR_RADIO, if (personal.radio.isEmpty()) "רדיו פופולרי" else "רדיו בשבילכם",
                HomeSectionType.HORIZONTAL, radio)
        }
        replace(HomeSectionIds.HISTORY_BASED, "על סמך היסטוריית ההאזנה שלכם בזמן האחרון",
            HomeSectionType.HORIZONTAL, personal.history)
        // Failure retains only previously verified v2 popular albums; legacy albums are removed on migration.
        if (general.albums.isNotEmpty()) replace(HomeSectionIds.POPULAR_ALBUMS, "אלבומים וסינגלים פופולריים",
            HomeSectionType.ALBUMS, general.albums)
        return feed.copy(sections = sections.sortedBy { order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE })
    }

    /** A partial refresh is not evidence that a previously verified public shelf was deleted. */
    fun retainKnownGeneral(next: HomeFeedModel, previous: HomeFeedModel?): HomeFeedModel {
        val missing = previous?.sections.orEmpty().filter { old -> old.items.isNotEmpty() &&
            (old.id == HomeSectionIds.POPULAR_ALBUMS ||
                old.id == HomeSectionIds.POPULAR_RADIO && old.title == "רדיו פופולרי") &&
            next.sections.none { it.id == old.id && it.items.isNotEmpty() } }
        return next.copy(sections = (next.sections.filterNot { section -> missing.any { it.id == section.id } } + missing)
            .sortedBy { order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE })
    }

    fun migrateLegacy(feed: HomeFeedModel): HomeFeedModel = feed.copy(sections = feed.sections.mapNotNull {
        when (it.id) {
            HomeSectionIds.POPULAR_ALBUMS, HomeSectionIds.HISTORY_BASED -> null
            HomeSectionIds.TOP_MIXES -> it.copy(title = "פלייליסטים לגלות")
            HomeSectionIds.POPULAR_RADIO -> it.copy(title = "תחנות רדיו")
            else -> it
        }
    })
}
