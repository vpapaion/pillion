package app.pillion.android

import android.content.Context
import android.graphics.Canvas
import android.view.View

/** Transparent overlay used by the SDL/USB remote display path. */
internal class GoogleMapsManeuverOverlayView(context: Context) : View(context) {
    private val renderer = GoogleMapsOverlayRenderer(context.applicationContext)
    private var removeListener: (() -> Unit)? = null

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeListener = GoogleMapsNavigationState.addListener { postInvalidate() }
    }

    override fun onDetachedFromWindow() {
        removeListener?.invoke()
        removeListener = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(canvas, width, height)
    }
}
