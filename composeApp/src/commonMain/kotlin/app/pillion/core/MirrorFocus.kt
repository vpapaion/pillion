package app.pillion.core

/**
 * Selects which part of the captured screen remains visible when dashboard zoom crops the image.
 *
 * At 100% zoom every option produces the complete frame. At higher zoom levels the selected corner
 * or the centre is kept fixed while the opposite edges are cropped.
 */
enum class MirrorFocus(
    val label: String,
    val xPercent: Int,
    val yPercent: Int,
) {
    TOP_LEFT("Top left", 0, 0),
    TOP_RIGHT("Top right", 100, 0),
    CENTER("Center", 50, 50),
    BOTTOM_LEFT("Bottom left", 0, 100),
    BOTTOM_RIGHT("Bottom right", 100, 100),
    ;

    fun cropLeft(fullWidth: Int, cropWidth: Int): Int =
        cropStart(fullWidth, cropWidth, xPercent)

    fun cropTop(fullHeight: Int, cropHeight: Int): Int =
        cropStart(fullHeight, cropHeight, yPercent)

    private fun cropStart(fullSize: Int, cropSize: Int, percent: Int): Int {
        val remaining = (fullSize - cropSize).coerceAtLeast(0)
        return (remaining * percent.coerceIn(0, 100) / 100f).toInt().coerceIn(0, remaining)
    }

    companion object {
        val DEFAULT = CENTER

        fun fromName(value: String?): MirrorFocus = parse(value) ?: DEFAULT

        fun parse(value: String?): MirrorFocus? =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
