package app.pillion.core

/**
 * Optional signal implemented by screen sources backed by a live, ongoing capture session
 * (MediaProjection, ReplayKit) so callers can tell a *newly captured* frame from a merely *cached*
 * one. [ScreenSource.latestFrame] alone can't make that distinction — a source can keep returning
 * the same bitmap forever after capture actually stops. Consumers that must know whether the source
 * is still alive (e.g. deciding whether it's safe to resume showing it after the display was off)
 * use this instead of inferring health from a non-null frame.
 */
interface MirrorFreshness {
    /** Monotonically increasing; changes only when a new frame is captured. */
    fun frameGeneration(): Long

    /** True once the underlying capture session has been stopped by the platform and cannot resume. */
    fun isCaptureStopped(): Boolean
}
