package com.music.spotui.ui.screens

import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.HomeSectionIds

/** Presentation-only: retain the empty Liked card in the source so a reactive count can reveal it. */
internal fun visibleHomeSections(sections: List<HomeSection>): List<HomeSection> = sections.filter { section ->
    section.id != HomeSectionIds.RECENTLY_PLAYED || section.items.any { it !is HomeItem.LikedSongs || it.count > 0 }
}
