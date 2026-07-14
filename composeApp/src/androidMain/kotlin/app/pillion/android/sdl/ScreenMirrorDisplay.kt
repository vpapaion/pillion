package app.pillion.android.sdl

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import app.pillion.android.MirrorViewportSnapshot
import app.pillion.android.MirrorViewportState
import app.pillion.core.Logger
import com.smartdevicelink.streaming.video.SdlRemoteDisplay

/**
 * Casts the phone's live screen to the dash. The TextureView transform is updated from
 * [MirrorViewportState], allowing the motorcycle joystick to pan or zoom the crop in real time.
 */
class ScreenMirrorDisplay(context: Context, display: Display) : SdlRemoteDisplay(context, display) {

    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var texture: TextureView
    private lateinit var status: TextView
    private var removeViewportListener: (() -> Unit)? = null
    private val displaySize = Point()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        display.getSize(displaySize)

        val root = FrameLayout(context)
        texture = TextureView(context)
        root.addView(
            texture,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        status = TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            textSize = 13f
            setPadding(10, 5, 10, 5)
            visibility = View.GONE
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply { setMargins(8, 8, 8, 8) },
        )
        setContentView(root)

        removeViewportListener = MirrorViewportState.addListener { viewport ->
            texture.post { applyViewport(viewport) }
        }

        val dpi = context.resources.displayMetrics.densityDpi
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val mp = ProjectionHolder.projection
                if (mp == null) {
                    Logger.d("screen mirror: no MediaProjection — grant screen capture first")
                    return
                }
                st.setDefaultBufferSize(displaySize.x, displaySize.y)
                val surface = Surface(st)
                try {
                    virtualDisplay = mp.createVirtualDisplay(
                        "pillion-sdl-mirror", displaySize.x, displaySize.y, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, null,
                    )
                    applyViewport(MirrorViewportState.snapshot())
                    Logger.d("screen mirror started -> dash ${displaySize.x}x${displaySize.y}")
                } catch (t: Throwable) {
                    Logger.e("screen mirror createVirtualDisplay failed", t)
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                applyViewport(MirrorViewportState.snapshot())
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                virtualDisplay?.release()
                virtualDisplay = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun applyViewport(viewport: MirrorViewportSnapshot) {
        if (!::texture.isInitialized) return
        val width = texture.width.takeIf { it > 0 } ?: displaySize.x
        val height = texture.height.takeIf { it > 0 } ?: displaySize.y
        val scale = viewport.zoomPercent / 100f
        val extraX = width * (scale - 1f)
        val extraY = height * (scale - 1f)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                -extraX * viewport.xPercent / 100f,
                -extraY * viewport.yPercent / 100f,
            )
        }
        texture.setTransform(matrix)

        val message = viewport.message
        if (viewport.overlayVisible() && message != null) {
            status.text = message
            status.visibility = View.VISIBLE
            val remaining = (viewport.messageUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
            status.removeCallbacks(hideStatus)
            status.postDelayed(hideStatus, remaining)
        } else {
            status.visibility = View.GONE
        }
    }

    private val hideStatus = Runnable { if (::status.isInitialized) status.visibility = View.GONE }

    override fun onStop() {
        removeViewportListener?.invoke()
        removeViewportListener = null
        if (::status.isInitialized) status.removeCallbacks(hideStatus)
        virtualDisplay?.release()
        virtualDisplay = null
        super.onStop()
    }

    override fun onViewResized(width: Int, height: Int) {
        Logger.d("dash video surface: ${width}x$height")
        if (::texture.isInitialized) texture.post { applyViewport(MirrorViewportState.snapshot()) }
    }
}
