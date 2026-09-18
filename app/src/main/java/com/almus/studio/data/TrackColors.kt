package com.almus.studio.data

/** Cycled through as tracks are created, so each track is visually distinct in the timeline. */
object TrackColors {
    private val palette = listOf(
        "#5CE1E6", "#FF6B6B", "#FFD166", "#06D6A0",
        "#A78BFA", "#F472B6", "#60A5FA", "#F97316"
    )

    fun forIndex(index: Int): String = palette[index % palette.size]
}
