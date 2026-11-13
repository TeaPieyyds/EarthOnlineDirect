package com.earthonline.direct

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * CameraX 分析器。
 *
 * 两种输入格式都支持：
 *  - RGBA_8888（推荐）：plane[0] 就是打包像素，直接取，跳过 YUV 换算
 *  - YUV_420_888：老机型兜底，自己做 BT.601 换算
 *
 * CameraX 已经把 analyze() 放在单线程 executor 上串行跑，
 * 所以这里只需一个 busy 标志：上一帧没算完就丢新帧。
 */
class YoloAnalyzer(
    private val context: Context,
    private val detector: YoloDetector,
    private val onResult: (DetectorOutput) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile var conf: Float = 0.35f
    @Volatile var iou: Float = 0.45f
    @Volatile var classFilter: Set<Int>? = null
    @Volatile var enabled: Boolean = true
    @Volatile var mirrorX: Boolean = false

    /** 输出帧长边上限，越小越省算力。 */
    @Volatile var maxSide: Int = 480

    private val busy = AtomicBoolean(false)
    private var outScratch: IntArray = IntArray(0)

    /** 最近一帧的输入格式，用于界面展示。 */
    @Volatile var lastFormat: String = "-"
        private set

    override fun analyze(image: ImageProxy) {
        if (!enabled) {
            image.close()
            return
        }
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val isRgba = image.format == android.graphics.PixelFormat.RGBA_8888
            lastFormat = if (isRgba) "RGBA" else "YUV"
            val frame = if (isRgba) rgbaToFrame(image) else yuvToFrame(image)
            image.close()
            val out = detector.detect(frame, conf, iou, classFilter)
            onResult(out)
        } catch (_: Throwable) {
            try { image.close() } catch (_: Throwable) {}
        } finally {
            busy.set(false)
        }
    }

    /**
     * RGBA_8888 直通：取出打包像素，按 rotationDegrees 旋转 + 缩放。
     * 像素序是 R,G,B,A，转成 ARGB int 一次移位搞定。
     */
    private fun rgbaToFrame(image: ImageProxy): FrameBuffer {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buf = plane.buffer
        buf.rewind()

        val rot = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val swap = rot == 90 || rot == 270
        val rotW = if (swap) h else w
        val rotH = if (swap) w else h

        val longSide = max(rotW, rotH)
        val scale = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
        val dstW = (rotW * scale).toInt().coerceAtLeast(1)
        val dstH = (rotH * scale).toInt().coerceAtLeast(1)

        if (outScratch.size != dstW * dstH) outScratch = IntArray(dstW * dstH)
        val out = outScratch

        val rowStride = plane.rowStride            // 字节数
        val pixelStride = plane.pixelStride        // 一般 = 4
        val invX = rotW.toFloat() / dstW
        val invY = rotH.toFloat() / dstH

        for (dy in 0 until dstH) {
            val ry = min(rotH - 1, (dy * invY).toInt())
            for (dx in 0 until dstW) {
                val rx = min(rotW - 1, (dx * invX).toInt())

                val sx: Int
                val sy: Int
                when (rot) {
                    90 -> { sx = ry; sy = h - 1 - rx }
                    180 -> { sx = w - 1 - rx; sy = h - 1 - ry }
                    270 -> { sx = w - 1 - ry; sy = rx }
                    else -> { sx = rx; sy = ry }
                }

                // 直接在 ByteBuffer 上读 4 字节
                val idx = sy * rowStride + sx * pixelStride
                val r = buf.get(idx).toInt() and 0xFF
                val g = buf.get(idx + 1).toInt() and 0xFF
                val b = buf.get(idx + 2).toInt() and 0xFF
                out[dy * dstW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        if (mirrorX) mirrorInPlace(out, dstW, dstH)

        val copy = IntArray(dstW * dstH)
        System.arraycopy(out, 0, copy, 0, dstW * dstH)
        return FrameBuffer(copy, dstW, dstH)
    }

    /**
     * YUV_420_888 兜底路径：BT.601 全范围换算 + 旋转 + 缩放。
     */
    private fun yuvToFrame(image: ImageProxy): FrameBuffer {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        val rot = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val swap = rot == 90 || rot == 270
        val rotW = if (swap) h else w
        val rotH = if (swap) w else h

        val longSide = max(rotW, rotH)
        val scale = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
        val dstW = (rotW * scale).toInt().coerceAtLeast(1)
        val dstH = (rotH * scale).toInt().coerceAtLeast(1)

        if (outScratch.size != dstW * dstH) outScratch = IntArray(dstW * dstH)
        val out = outScratch

        val invX = rotW.toFloat() / dstW
        val invY = rotH.toFloat() / dstH

        for (dy in 0 until dstH) {
            val ry = min(rotH - 1, (dy * invY).toInt())
            for (dx in 0 until dstW) {
                val rx = min(rotW - 1, (dx * invX).toInt())

                val sx: Int
                val sy: Int
                when (rot) {
                    90 -> { sx = ry; sy = h - 1 - rx }
                    180 -> { sx = w - 1 - rx; sy = h - 1 - ry }
                    270 -> { sx = w - 1 - ry; sy = rx }
                    else -> { sx = rx; sy = ry }
                }

                val yv = yBuf.get(sy * yRowStride + sx * yPixStride).toInt() and 0xFF
                val uvX = sx / 2
                val uvY = sy / 2
                val u = (uBuf.get(uvY * uRowStride + uvX * uPixStride).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvY * vRowStride + uvX * vPixStride).toInt() and 0xFF) - 128

                val r = (yv + 1.402f * v + 0.5f).toInt().coerceIn(0, 255)
                val g = (yv - 0.344136f * u - 0.714136f * v + 0.5f).toInt().coerceIn(0, 255)
                val b = (yv + 1.772f * u + 0.5f).toInt().coerceIn(0, 255)

                out[dy * dstW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        if (mirrorX) mirrorInPlace(out, dstW, dstH)

        val copy = IntArray(dstW * dstH)
        System.arraycopy(out, 0, copy, 0, dstW * dstH)
        return FrameBuffer(copy, dstW, dstH)
    }

    private fun mirrorInPlace(arr: IntArray, w: Int, h: Int) {
        for (y in 0 until h) {
            val base = y * w
            var l = base
            var r = base + w - 1
            while (l < r) {
                val t = arr[l]; arr[l] = arr[r]; arr[r] = t
                l++; r--
            }
        }
    }
}