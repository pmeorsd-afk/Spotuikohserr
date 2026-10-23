package com.music.spotui.ui.library

/** Only explicitly touched destinations are committed. Late loads never overwrite a user's draft. */
internal class SaveDestinationDraft {
    private val original = mutableMapOf<String, Boolean>()
    private val draft = mutableMapOf<String, Boolean>()
    fun load(id: String, saved: Boolean) { if (id !in original) { original[id] = saved; draft[id] = saved } }
    fun selected(id: String): Boolean? = draft[id]
    fun toggle(id: String) { draft[id]?.let { draft[id] = !it } }
    fun changes(): Map<String, Boolean> = draft.filter { (id, selected) -> original[id] != selected }
    fun accept(id: String, saved: Boolean) { original[id] = saved; draft[id] = saved }
}

/** Physical geometry in px, independent of language direction and device model. */
internal data class SaveSheetAnchors(val height: Float, val peek: Float) {
    val collapsed = (height - peek.coerceAtMost(height)).coerceAtLeast(0f)
    fun clamp(offset: Float) = offset.coerceIn(0f, height)
    fun settle(offset: Float, start: Float, velocity: Float, threshold: Float, fling: Float): Float = when {
        velocity < -fling || offset < start - threshold -> 0f
        velocity > fling || offset > start + threshold -> if (start < collapsed / 2) collapsed else height
        // A touch may interrupt an opening/snap animation. Never settle at its intermediate offset.
        else -> if (start < collapsed / 2) 0f else collapsed
    }
}
