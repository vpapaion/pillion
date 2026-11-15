package app.pillion.core

/**
 * User-tunable session settings. Lower values trade smoothness for battery, heat and bandwidth.
 *
 * @param quality JPEG quality of each frame (10–80).
 * @param maxFps upper bound on frames sent per second; the engine paces to this.
 * @param dashResolution off-screen display size for dedicated dash mode; output is scaled to 480x240.
 * @param zoomPercent crop/zoom applied before encoding (100 keeps the complete frame).
 * @param focus area kept visible when zoom crops the frame.
 */
data class MirrorSettings(
    val quality: Int = 40,
    val maxFps: Int = 15,
    val dashResolution: DashResolution = DashResolution.DEFAULT,
    val zoomPercent: Int = MirrorZoom.DEFAULT_PERCENT,
    val focus: MirrorFocus = MirrorFocus.DEFAULT,
)
