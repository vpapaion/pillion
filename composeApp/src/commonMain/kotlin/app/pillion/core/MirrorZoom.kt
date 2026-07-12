package app.pillion.core

/**
 * Dashboard crop/zoom applied before a frame is encoded for the head unit.
 *
 * 100% preserves the complete captured image. Higher values crop equally from every edge and scale
 * the remaining centre region back to the dash's 480x240 frame, making map labels and controls larger
 * at the cost of hiding some content near the outer edges.
 */
object MirrorZoom {
    const val MIN_PERCENT = 100
    const val MAX_PERCENT = 160
    const val DEFAULT_PERCENT = 120
    const val STEP_PERCENT = 5

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** Size of the centred source region that remains visible after zooming. */
    fun cropSize(fullSize: Int, percent: Int): Int =
        (fullSize * 100 / clamp(percent)).coerceIn(1, fullSize)

    /** Start coordinate for a centred crop of [cropSize] inside [fullSize]. */
    fun cropStart(fullSize: Int, cropSize: Int): Int =
        ((fullSize - cropSize) / 2).coerceAtLeast(0)
}
