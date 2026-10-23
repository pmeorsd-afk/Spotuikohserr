package com.music.spotui.ui.library

import com.music.spotui.data.entity.SongsModel

/** No metadata-only reconstruction and no first-match selection on colliding numeric IDs. */
internal fun savedMediaTarget(queue: List<SongsModel>, id: Int, title: String, owned: SongsModel?): SongsModel? {
    fun matches(song: SongsModel) = song.id == id && song.title == title
    return if (owned != null) owned.takeIf(::matches) else queue.singleOrNull(::matches)
}
