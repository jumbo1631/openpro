package com.openpro.link

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the camera's data goes, drawn as the phone (OpenPro, and Camera Link if [mode] is
 * [Mode.With]) on the left, and your server and the GoPro on the right. [Mode.Without] shows why
 * Camera Link is needed: the VPN covering OpenPro blocks its way to the camera. [Mode.With] shows how
 * it works; [linkCovered] (a VPN covers Camera Link too) blocks its way to the camera as well.
 */
class RouteDiagram(context: Context, private val mode: Mode) : View(context) {
    enum class Mode { Without, With }

    var linkCovered = false
        set(value) {
            field = value
            invalidate()
        }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(200f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val nodeW = w * 0.3f
        val nodeH = dp(44f)
        val leftX = dp(14f)
        val rightX = w - nodeW
        val top = h * 0.32f
        val bottom = h * 0.8f

        // The phone, around the apps on it.
        stroke.color = GREY
        stroke.strokeWidth = dp(1.5f)
        stroke.pathEffect = null
        c.drawRoundRect(RectF(dp(1f), dp(1f), leftX * 2 + nodeW, h - dp(1f)), dp(16f), dp(16f), stroke)
        label(c, "Your phone", leftX + nodeW / 2, dp(18f), GREY, 12f)

        val server = Node("Your server", rightX, top)
        val gopro = Node("GoPro", rightX, bottom)
        when (mode) {
            Mode.Without -> {
                val openPro = Node("OpenPro", leftX, (top + bottom) / 2)
                link(c, openPro, server, BLUE, "VPN", blocked = false, nodeW, nodeH)
                link(c, openPro, gopro, RED, "Blocked by the VPN", blocked = true, nodeW, nodeH)
                draw(c, openPro, nodeW, nodeH, highlight = false)
            }
            Mode.With -> {
                val openPro = Node("OpenPro", leftX, top)
                val cameraLink = Node("Camera Link", leftX, bottom)
                link(c, openPro, server, BLUE, "VPN", blocked = false, nodeW, nodeH)
                // OpenPro and Camera Link talk inside the phone, never through the VPN.
                stroke.color = GREY
                stroke.strokeWidth = dp(2f)
                stroke.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
                val x = leftX + nodeW / 2
                c.drawLine(x, top + nodeH / 2, x, bottom - nodeH / 2, stroke)
                stroke.pathEffect = null
                if (linkCovered) {
                    link(c, cameraLink, gopro, RED, "Blocked by the VPN", blocked = true, nodeW, nodeH)
                } else {
                    link(c, cameraLink, gopro, GREEN, "Camera Wi-Fi", blocked = false, nodeW, nodeH)
                }
                draw(c, openPro, nodeW, nodeH, highlight = false)
                draw(c, cameraLink, nodeW, nodeH, highlight = true)
            }
        }
        draw(c, server, nodeW, nodeH, highlight = false)
        draw(c, gopro, nodeW, nodeH, highlight = false)
    }

    private class Node(val name: String, val x: Float, val cy: Float)

    private fun draw(c: Canvas, n: Node, nodeW: Float, nodeH: Float, highlight: Boolean) {
        val r = RectF(n.x, n.cy - nodeH / 2, n.x + nodeW, n.cy + nodeH / 2)
        fill.color = if (highlight) BLUE else NODE
        c.drawRoundRect(r, dp(10f), dp(10f), fill)
        stroke.color = if (highlight) BLUE else EDGE
        stroke.strokeWidth = dp(1.5f)
        c.drawRoundRect(r, dp(10f), dp(10f), stroke)
        label(c, n.name, r.centerX(), n.cy + sp(14f) / 3, Color.WHITE, 14f)
    }

    /** An arrow from [a]'s right edge to [b]'s left edge, its [caption] above the middle; a cross on it if [blocked]. */
    private fun link(c: Canvas, a: Node, b: Node, color: Int, caption: String, blocked: Boolean, nodeW: Float, nodeH: Float) {
        val x1 = a.x + nodeW
        val y1 = a.cy
        val x2 = b.x
        val y2 = b.cy
        stroke.color = color
        stroke.strokeWidth = dp(3f)
        c.drawLine(x1, y1, x2 - dp(2f), y2, stroke)
        // Arrowhead at the end.
        val angle = atan2(y2 - y1, x2 - x1)
        val len = dp(10f)
        val head = Path().apply {
            moveTo(x2, y2)
            lineTo(x2 - len * cos(angle - 0.45f), y2 - len * sin(angle - 0.45f))
            lineTo(x2 - len * cos(angle + 0.45f), y2 - len * sin(angle + 0.45f))
            close()
        }
        fill.color = color
        c.drawPath(head, fill)

        val mx = (x1 + x2) / 2
        val my = (y1 + y2) / 2
        if (blocked) {
            // A cross in a circle, the caption under it.
            fill.color = BACKGROUND
            c.drawCircle(mx, my, dp(11f), fill)
            stroke.strokeWidth = dp(2.5f)
            c.drawCircle(mx, my, dp(11f), stroke)
            val s = dp(5f)
            c.drawLine(mx - s, my - s, mx + s, my + s, stroke)
            c.drawLine(mx - s, my + s, mx + s, my - s, stroke)
            label(c, caption, mx, my + dp(28f), color, 12f)
        } else {
            label(c, caption, mx, my - dp(10f), color, 12f)
        }
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, color: Int, sizeSp: Float) {
        text.color = color
        text.textSize = sp(sizeSp)
        text.isFakeBoldText = sizeSp >= 14f
        c.drawText(s, x, y, text)
    }

    companion object {
        const val BLUE = 0xFF00A3E0.toInt()
        const val GREEN = 0xFF43A047.toInt()
        const val RED = 0xFFE53935.toInt()
        const val GREY = 0xFF8A8A8A.toInt()
        const val NODE = 0xFF1E1E1E.toInt()
        const val EDGE = 0xFF555555.toInt()
        const val BACKGROUND = Color.BLACK
    }
}
