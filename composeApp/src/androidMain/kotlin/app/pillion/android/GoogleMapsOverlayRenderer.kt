package app.pillion.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.pillion.core.GoogleMapsDirection
import app.pillion.core.NavigationManeuver
import kotlin.math.min

/** Draws a Garmin-like compact turn panel over the final dashboard frame. */
internal class GoogleMapsOverlayRenderer(private val context: Context) {
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 12, 16, 20)
    }
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 1f
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
    }
    private val mapsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(95, 180, 105)
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
    }

    fun draw(canvas: Canvas, width: Int, height: Int): Boolean {
        val direction = GoogleMapsNavigationState.snapshot(context) ?: return false
        if (width <= 0 || height <= 0) return false

        val scale = height / 240f
        val panelWidth = min(width * 0.40f, 154f * scale)
        val radius = 12f * scale
        canvas.drawRoundRect(0f, 0f, panelWidth, height.toFloat(), radius, radius, panelPaint)
        canvas.drawLine(panelWidth - scale, 12f * scale, panelWidth - scale, height - 12f * scale, separatorPaint)

        mapsPaint.textSize = 10f * scale
        canvas.drawText("GOOGLE MAPS", 10f * scale, 16f * scale, mapsPaint)

        arrowPaint.strokeWidth = 8f * scale
        drawManeuver(
            canvas = canvas,
            maneuver = direction.maneuver,
            area = RectF(20f * scale, 22f * scale, panelWidth - 20f * scale, 112f * scale),
        )

        distancePaint.textSize = 22f * scale
        val distance = direction.distance ?: "NEXT"
        canvas.drawText(distance, panelWidth / 2f, 142f * scale, distancePaint)

        instructionPaint.textSize = 12f * scale
        val textLeft = 10f * scale
        val textWidth = panelWidth - 20f * scale
        val lines = wrap(direction.instruction, textWidth, instructionPaint, maxLines = 3)
        var baseline = 166f * scale
        val lineHeight = 17f * scale
        lines.forEach { line ->
            canvas.drawText(line, textLeft, baseline, instructionPaint)
            baseline += lineHeight
        }
        return true
    }

    private fun drawManeuver(canvas: Canvas, maneuver: NavigationManeuver, area: RectF) {
        val cx = area.centerX()
        val top = area.top + area.height() * 0.08f
        val bottom = area.bottom - area.height() * 0.05f
        val left = area.left + area.width() * 0.08f
        val right = area.right - area.width() * 0.08f
        val midY = area.centerY()
        val head = area.width() * 0.17f

        when (maneuver) {
            NavigationManeuver.STRAIGHT -> {
                canvas.drawLine(cx, bottom, cx, top, arrowPaint)
                arrowHead(canvas, cx, top, 0f, -1f, head)
            }
            NavigationManeuver.LEFT, NavigationManeuver.SHARP_LEFT -> {
                val bendY = if (maneuver == NavigationManeuver.SHARP_LEFT) midY + area.height() * 0.12f else midY
                val path = Path().apply {
                    moveTo(cx, bottom)
                    lineTo(cx, bendY)
                    lineTo(left, bendY)
                }
                canvas.drawPath(path, arrowPaint)
                arrowHead(canvas, left, bendY, -1f, 0f, head)
            }
            NavigationManeuver.RIGHT, NavigationManeuver.SHARP_RIGHT -> {
                val bendY = if (maneuver == NavigationManeuver.SHARP_RIGHT) midY + area.height() * 0.12f else midY
                val path = Path().apply {
                    moveTo(cx, bottom)
                    lineTo(cx, bendY)
                    lineTo(right, bendY)
                }
                canvas.drawPath(path, arrowPaint)
                arrowHead(canvas, right, bendY, 1f, 0f, head)
            }
            NavigationManeuver.SLIGHT_LEFT -> {
                canvas.drawLine(cx, bottom, cx, midY + head, arrowPaint)
                canvas.drawLine(cx, midY + head, left, top + head, arrowPaint)
                arrowHead(canvas, left, top + head, -0.7f, -0.7f, head)
            }
            NavigationManeuver.SLIGHT_RIGHT -> {
                canvas.drawLine(cx, bottom, cx, midY + head, arrowPaint)
                canvas.drawLine(cx, midY + head, right, top + head, arrowPaint)
                arrowHead(canvas, right, top + head, 0.7f, -0.7f, head)
            }
            NavigationManeuver.U_TURN -> {
                val oval = RectF(left, top + head * 0.2f, right, bottom - head)
                canvas.drawArc(oval, 10f, 250f, false, arrowPaint)
                canvas.drawLine(right, oval.centerY(), right, bottom, arrowPaint)
                arrowHead(canvas, left + head * 0.15f, oval.centerY() + head * 0.4f, -0.4f, 0.9f, head)
            }
            NavigationManeuver.ROUNDABOUT -> {
                val size = min(area.width(), area.height()) * 0.62f
                val oval = RectF(cx - size / 2f, midY - size / 2f, cx + size / 2f, midY + size / 2f)
                canvas.drawArc(oval, 35f, 300f, false, arrowPaint)
                val x = oval.right - head * 0.15f
                val y = oval.top + head * 0.45f
                arrowHead(canvas, x, y, 0.75f, -0.65f, head)
            }
            NavigationManeuver.DESTINATION -> {
                canvas.drawLine(cx - head * 0.7f, bottom, cx - head * 0.7f, top, arrowPaint)
                val flag = Path().apply {
                    moveTo(cx - head * 0.65f, top)
                    lineTo(right, top + head * 0.55f)
                    lineTo(cx - head * 0.65f, top + head * 1.1f)
                }
                canvas.drawPath(flag, arrowPaint)
            }
        }
    }

    private fun arrowHead(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, size: Float) {
        val px = -dy
        val py = dx
        val backX = x - dx * size
        val backY = y - dy * size
        canvas.drawLine(x, y, backX + px * size * 0.55f, backY + py * size * 0.55f, arrowPaint)
        canvas.drawLine(x, y, backX - px * size * 0.55f, backY - py * size * 0.55f, arrowPaint)
    }

    private fun wrap(text: String, maxWidth: Float, paint: Paint, maxLines: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = candidate
            } else {
                result += current
                current = word
                if (result.size == maxLines - 1) break
            }
        }
        if (result.size < maxLines && current.isNotEmpty()) result += current
        if (result.isNotEmpty() && words.joinToString(" ") != result.joinToString(" ")) {
            var last = result.last()
            while (last.isNotEmpty() && paint.measureText("$last…") > maxWidth) last = last.dropLast(1)
            result[result.lastIndex] = "$last…"
        }
        return result
    }
}
