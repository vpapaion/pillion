package app.pillion.core

/**
 * Optional control surface implemented by screen sources that can react to dashboard input while a
 * mirroring session is active. NaviLite only exposes two map-zoom commands, so they are used to step
 * through the saved crop positions without restarting screen capture.
 */
interface DashboardCropControl {
    /** Move to the next or previous crop preset. Positive values move forward. */
    fun cycleCropPreset(delta: Int)
}
