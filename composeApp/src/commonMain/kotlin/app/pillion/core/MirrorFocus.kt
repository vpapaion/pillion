package app.pillion.core

/**
 * Selects which part of the captured screen remains visible when dashboard zoom crops the image.
 *
 * At 100% zoom every option produces the complete frame. At higher zoom levels the selected corner
 * or the centre is kept fixed while the opposite edges are cropped.
 */
enum class MirrorFocus(
    val label: String,
    private val horizontal: CropAlignment,
    private val vertical: CropAlignment,
) {
    TOP_LEFT("Top left", CropAlignment.START, CropAlignment.START),
    TOP_RIGHT("Top right", CropAlignment.END, CropAlignment.START),
    CENTER("Center", CropAlignment.CENTER, CropAlignment.CENTER),
    BOTTOM_LEFT("Bottom left", CropAlignment.START, CropAlignment.END),
    BOTTOM_RIGHT("Bottom right", CropAlignment.END, CropAlignment.END),
    ;

    fun cropLeft(fullWidth: Int, cropWidth: Int): Int =
        cropStart(fullWidth, cropWidth, horizontal)

    fun cropTop(fullHeight: Int, cropHeight: Int): Int =
        cropStart(fullHeight, cropHeight, vertical)

    private fun cropStart(fullSize: Int, cropSize: Int, alignment: CropAlignment): Int {
        val remaining = (fullSize - cropSize).coerceAtLeast(0)
        return when (alignment) {
            CropAlignment.START -> 0
            CropAlignment.CENTER -> remaining / 2
            CropAlignment.END -> remaining
        }
    }

    companion object {
        val DEFAULT = CENTER

        fun fromName(value: String?): MirrorFocus = parse(value) ?: DEFAULT

        fun parse(value: String?): MirrorFocus? =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

private enum class CropAlignment { START, CENTER, END }
