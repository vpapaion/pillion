package app.pillion.core

/** Coarse manoeuvre categories used by the compact Tracer 7 overlay. */
enum class NavigationManeuver {
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    U_TURN,
    ROUNDABOUT,
    DESTINATION,
}

/** Parsed turn instruction from the ongoing Google Maps navigation notification. */
data class GoogleMapsDirection(
    val instruction: String,
    val distance: String?,
    val maneuver: NavigationManeuver,
)

/**
 * Tolerant, language-aware parser for Google Maps notification text.
 *
 * Google Maps does not publish a stable notification schema, so Android-specific code supplies every
 * useful text field and this parser chooses the likely instruction and distance without depending on
 * exact notification templates.
 */
object GoogleMapsDirectionParser {
    private val distanceRegex = Regex(
        """(?i)(\d+(?:[.,]\d+)?)\s*(km|mi|ft|yd|m|χλμ\.?|μέτρα|μετρα)""",
    )

    fun parse(rawLines: List<String>): GoogleMapsDirection? {
        val lines = rawLines
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .distinct()
        if (lines.isEmpty()) return null

        val distance = lines.asSequence()
            .mapNotNull { distanceRegex.find(it)?.value?.trimEnd('.') }
            .firstOrNull()

        val instruction = lines
            .filterNot(::isGenericLine)
            .sortedWith(
                compareByDescending<String> { navigationScore(it) }
                    .thenByDescending { it.length },
            )
            .firstOrNull()
            ?: return null

        if (navigationScore(instruction) < 2 && distance == null) return null
        return GoogleMapsDirection(
            instruction = instruction,
            distance = distance,
            maneuver = maneuverFor(instruction),
        )
    }

    fun maneuverFor(text: String): NavigationManeuver {
        val value = text.lowercase()
        return when {
            containsAny(value, "destination", "arrive", "προορισ", "φτάσατε", "φτασατε") ->
                NavigationManeuver.DESTINATION
            containsAny(value, "u-turn", "u turn", "uturn", "αναστροφ") ->
                NavigationManeuver.U_TURN
            containsAny(value, "roundabout", "traffic circle", "κυκλικ", "κόμβο", "κομβο") ->
                NavigationManeuver.ROUNDABOUT
            containsAny(value, "sharp left", "απότομα αριστερ", "αποτομα αριστερ") ->
                NavigationManeuver.SHARP_LEFT
            containsAny(value, "slight left", "keep left", "ελαφρώς αριστερ", "ελαφρως αριστερ") ->
                NavigationManeuver.SLIGHT_LEFT
            containsAny(value, "turn left", "bear left", "αριστερ") ->
                NavigationManeuver.LEFT
            containsAny(value, "sharp right", "απότομα δεξ", "αποτομα δεξ") ->
                NavigationManeuver.SHARP_RIGHT
            containsAny(value, "slight right", "keep right", "ελαφρώς δεξ", "ελαφρως δεξ") ->
                NavigationManeuver.SLIGHT_RIGHT
            containsAny(value, "turn right", "bear right", "δεξ") ->
                NavigationManeuver.RIGHT
            else -> NavigationManeuver.STRAIGHT
        }
    }

    private fun navigationScore(text: String): Int {
        val value = text.lowercase()
        var score = 0
        if (containsAny(
                value,
                "turn", "keep", "continue", "head ", "merge", "exit", "roundabout", "destination",
                "στρίψ", "στριψ", "συνεχ", "κατευθυν", "έξοδο", "εξοδο", "κόμβ", "κομβ",
                "αριστερ", "δεξ", "ευθεία", "ευθεια", "αναστροφ",
            )
        ) score += 4
        if (distanceRegex.containsMatchIn(value)) score += 2
        if (text.length in 8..120) score += 1
        return score
    }

    private fun isGenericLine(text: String): Boolean {
        val value = text.lowercase()
        return value == "google maps" ||
            value == "maps" ||
            value == "navigation" ||
            value == "πλοήγηση" ||
            value == "πλοηγηση" ||
            distanceRegex.matchEntire(value) != null
    }

    private fun containsAny(value: String, vararg needles: String): Boolean =
        needles.any(value::contains)
}
