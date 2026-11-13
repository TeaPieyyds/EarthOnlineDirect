package com.earthonline.direct

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

private fun colorOf(classId: Int): Color =
    Color(CocoLabels.color(classId) or 0xFF000000.toInt())

/**
 * 检测框叠加层。坐标是归一化值，按 PreviewView FIT_CENTER 的实际显示矩形换算。
 */
@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    frameWidth: Int,
    frameHeight: Int,
    showLabels: Boolean,
    showScore: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (detections.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return@Canvas

        val vw = size.width
        val vh = size.height
        val s = min(vw / frameWidth, vh / frameHeight)
        val dw = frameWidth * s
        val dh = frameHeight * s
        val ox = (vw - dw) * 0.5f
        val oy = (vh - dh) * 0.5f

        val stroke = max(2.5f, min(vw, vh) * 0.004f)
        val textPx = max(20f, min(vw, vh) * 0.030f)

        for (d in detections) {
            val l = ox + d.x1 * dw
            val t = oy + d.y1 * dh
            val r = ox + d.x2 * dw
            val b = oy + d.y2 * dh
            if (r - l < 2f || b - t < 2f) continue

            val c = colorOf(d.classId)

            drawRect(
                color = c.copy(alpha = 0.12f),
                topLeft = Offset(l, t),
                size = Size(r - l, b - t)
            )
            drawRect(
                color = c,
                topLeft = Offset(l, t),
                size = Size(r - l, b - t),
                style = Stroke(width = stroke)
            )

            val corner = min(r - l, b - t) * 0.2f
            val cw = stroke * 2.2f
            drawLine(c, Offset(l, t), Offset(l + corner, t), cw)
            drawLine(c, Offset(l, t), Offset(l, t + corner), cw)
            drawLine(c, Offset(r, t), Offset(r - corner, t), cw)
            drawLine(c, Offset(r, t), Offset(r, t + corner), cw)
            drawLine(c, Offset(l, b), Offset(l + corner, b), cw)
            drawLine(c, Offset(l, b), Offset(l, b - corner), cw)
            drawLine(c, Offset(r, b), Offset(r - corner, b), cw)
            drawLine(c, Offset(r, b), Offset(r, b - corner), cw)

            if (showLabels) {
                val label = if (showScore) {
                    "${CocoLabels.zh(d.classId)} ${(d.score * 100).toInt()}%"
                } else {
                    CocoLabels.zh(d.classId)
                }
                val paint = android.graphics.Paint().apply {
                    textSize = textPx
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.BOLD
                    )
                }
                val padH = textPx * 0.34f
                val boxH = textPx * 1.42f
                val textW = paint.measureText(label)
                val bw = textW + padH * 2f
                val bt = max(oy, t - boxH)
                val bl = min(max(l, ox), ox + dw - bw).coerceAtLeast(ox)

                val nc = drawContext.canvas.nativeCanvas

                paint.color = android.graphics.Color.argb(225, 8, 16, 26)
                nc.drawRoundRect(bl, bt, bl + bw, bt + boxH, boxH * 0.24f, boxH * 0.24f, paint)

                paint.color = android.graphics.Color.argb(255, 255, 255, 255)
                nc.drawText(label, bl + padH, bt + boxH * 0.75f, paint)
            }
        }
    }
}

/** 左上角统计面板。 */
@Composable
fun CountBadge(
    counts: List<Pair<Int, Int>>,
    total: Int,
    inferenceMs: Long,
    fps: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xCC081120), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column {
            Text(
                text = "共 $total 个目标",
                color = Color(0xFF7FE7F2),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${inferenceMs}ms · ${"%.1f".format(fps)}fps",
                color = Color(0xFF9BB2C4),
                fontSize = 11.sp
            )
            if (counts.isNotEmpty()) {
                Spacer(Modifier.padding(top = 3.dp))
                counts.take(6).forEach { (id, n) ->
                    Text(
                        text = "${CocoLabels.zh(id)} × $n",
                        color = colorOf(id),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}