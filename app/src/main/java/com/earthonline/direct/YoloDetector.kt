package com.earthonline.direct


import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/** 推理后端。NNAPI 走硬件加速器，XNNPACK 走 CPU SIMD，CPU 是纯参考实现。 */
enum class Backend(val label: String) {
    XNNPACK("XNNPACK"),
    NNAPI("NNAPI"),
    CPU("CPU")
}

/**
 * YOLO11n ONNX 推理封装（可换后端 + 可变输入尺寸）。
 *
 * 模型：assets/yolo11n.onnx
 *   输入  images  : float32 [1, 3, H, W]（动态 H/W）
 *   输出  output0 : float32 [1, 84, N]   84 = 4(box) + 80(class)，无 objectness
 */
class YoloDetector(
    private val appContext: Context,
    var inputSize: Int = 480,
    private val assetName: String = "yolo11n.onnx",
    var numThreads: Int = 4
) {

    companion object {
        private const val NUM_CLASSES = 80
        private const val MAX_DETECTIONS = 100
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var options: OrtSession.SessionOptions? = null
    private var inputName: String = "images"
    private var outputName: String = "output0"

    var backend: Backend = Backend.XNNPACK
        private set

    /** 输入通道缓冲，inputSize 变化时重建。 */
    private var inputBuffer: FloatBuffer = FloatBuffer.allocate(3 * inputSize * inputSize)
    private var bufferSize: Int = inputSize

    private var lastInferenceMs: Long = 0
    private var lastPreMs: Long = 0
    private var lastPostMs: Long = 0

    val lastError: String? get() = _lastError
    private var _lastError: String? = null

    val timings: Triple<Long, Long, Long> get() = Triple(lastPreMs, lastInferenceMs, lastPostMs)

    // —— 采样映射表，避免每帧重复算 ——
    private var mapSize: Int = -1
    private var mapSrcW: Int = -1
    private var mapSrcH: Int = -1
    private var mapDrawW: Int = 0
    private var mapDrawH: Int = 0
    private var mapX: IntArray = IntArray(0)
    private var mapY: IntArray = IntArray(0)

    /** 加载模型。backend 变更后需重新调用。 */
    fun load(target: Backend = backend): Boolean {
        return try {
            closeSession()
            backend = target

            val bytes = readAsset(appContext, assetName)
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(numThreads)
                setInterOpNumThreads(1)
                setMemoryPatternOptimization(true)
                setCPUArenaAllocator(true)

                when (target) {
                    Backend.XNNPACK -> try {
                        addXnnpack(mapOf("intra_op_num_threads" to numThreads.toString()))
                    } catch (_: Throwable) {
                        // 回退：不加 EP，走默认 CPU
                    }
                    Backend.NNAPI -> try {
                        addNnapi()
                    } catch (_: Throwable) {
                    }
                    Backend.CPU -> Unit
                }
            }
            val s = env.createSession(bytes, opts)
            session = s
            options = opts
            s.inputNames.firstOrNull()?.let { inputName = it }
            s.outputNames.firstOrNull()?.let { outputName = it }
            _lastError = null
            true
        } catch (t: Throwable) {
            _lastError = "${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }

    fun close() {
        closeSession()
    }

    /**
     * 基准测试：各跑 [rounds] 次，返回「中位 xx ms / 最小 xx ms」。
     * 用灰块当输入，只测推理本身的耗时，不含相机采集与预处理。
     */
    fun bench(rounds: Int = 15): String {
        if (session == null) return "未加载"
        val n = inputSize
        val dummy = IntArray(n * n) { 0xFF808080.toInt() }
        val frame = FrameBuffer(dummy, n, n)

        // 预热 3 次，让 ORT 把内存池和权重图都准备好
        repeat(3) { detect(frame, 0.35f, 0.45f, null) }

        val times = LongArray(rounds) { detect(frame, 0.35f, 0.45f, null).inferenceMs }
        times.sort()
        val med = times[rounds / 2]
        return "中位 ${med}ms / 最快 ${times.first()}ms / 最慢 ${times.last()}ms"
    }

    private fun closeSession() {
        try { session?.close() } catch (_: Throwable) {}
        try { options?.close() } catch (_: Throwable) {}
        session = null
        options = null
    }

    private fun readAsset(context: Context, name: String): ByteArray {
        val f = File(context.filesDir, name)
        if (f.exists() && f.length() > 1_000_000) {
            // 已缓存且与新 asset 尺寸一致则直接复用，避免每次启动解压
            val assetLen = runCatching { context.assets.openFd(name).use { it.length } }.getOrDefault(-1L)
            if (assetLen <= 0 || f.length() == assetLen) return f.readBytes()
        }
        val bytes = context.assets.open(name).use { it.readBytes() }
        runCatching { f.writeBytes(bytes) }
        return bytes
    }

    private fun ensureBuffer(size: Int) {
        if (bufferSize != size || inputBuffer.capacity() != 3 * size * size) {
            inputBuffer = FloatBuffer.allocate(3 * size * size)
            bufferSize = size
        }
    }

    /** 重建采样映射表。 */
    private fun buildMap(srcW: Int, srcH: Int): Pad {
        val ins = inputSize
        val scale = min(ins.toFloat() / srcW, ins.toFloat() / srcH)
        val drawW = (srcW * scale).toInt().coerceAtLeast(1)
        val drawH = (srcH * scale).toInt().coerceAtLeast(1)
        val dx = (ins - drawW) / 2
        val dy = (ins - drawH) / 2

        if (mapSize == ins && mapSrcW == srcW && mapSrcH == srcH &&
            mapDrawW == drawW && mapDrawH == drawH
        ) {
            return Pad(dx.toFloat(), dy.toFloat(), drawW.toFloat(), drawH.toFloat())
        }

        val xMap = IntArray(drawW)
        val invX = srcW.toFloat() / drawW
        for (x in 0 until drawW) {
            xMap[x] = ((x + 0.5f) * invX).toInt().coerceIn(0, srcW - 1)
        }
        val yMap = IntArray(drawH)
        val invY = srcH.toFloat() / drawH
        for (y in 0 until drawH) {
            yMap[y] = ((y + 0.5f) * invY).toInt().coerceIn(0, srcH - 1)
        }

        mapSize = ins
        mapSrcW = srcW
        mapSrcH = srcH
        mapDrawW = drawW
        mapDrawH = drawH
        mapX = xMap
        mapY = yMap
        return Pad(dx.toFloat(), dy.toFloat(), drawW.toFloat(), drawH.toFloat())
    }

    private data class Pad(val dx: Float, val dy: Float, val drawW: Float, val drawH: Float)

    /**
     * 最近邻采样进 CHW float 张量，灰边 114/255。
     * 相比双线性快 3 倍以上，对检测精度影响可忽略（本身就是降采样）。
     */
    private fun fillInput(frame: FrameBuffer, out: FloatBuffer): Pad {
        val ins = inputSize
        val pad = buildMap(frame.width, frame.height)
        val arr = out.array()
        val plane = ins * ins
        val padValue = 114f / 255f

        arr.fill(padValue)

        val pixels = frame.argb
        val srcW = frame.width
        val xMap = mapX
        val yMap = mapY
        val drawW = mapDrawW
        val drawH = mapDrawH
        val dx = (ins - drawW) / 2
        val dy = (ins - drawH) / 2
        val inv255 = 1f / 255f

        for (y in 0 until drawH) {
            val srcRow = yMap[y] * srcW
            val outRow = (y + dy) * ins + dx
            for (x in 0 until drawW) {
                val p = pixels[srcRow + xMap[x]]
                val idx = outRow + x
                arr[idx] = ((p shr 16) and 0xFF) * inv255
                arr[plane + idx] = ((p shr 8) and 0xFF) * inv255
                arr[2 * plane + idx] = (p and 0xFF) * inv255
            }
        }
        return pad
    }

    /**
     * 对一帧做检测。
     * @param classFilter 只保留这些类别；null / 空 = 全部
     */
    fun detect(
        frame: FrameBuffer,
        conf: Float,
        iou: Float,
        classFilter: Set<Int>?
    ): DetectorOutput {
        val s = session ?: return DetectorOutput(emptyList(), 0, 0, 0, frame.width, frame.height)

        val t0 = System.nanoTime()
        ensureBuffer(inputSize)
        inputBuffer.clear()
        val pad = fillInput(frame, inputBuffer)
        val t1 = System.nanoTime()
        lastPreMs = (t1 - t0) / 1_000_000

        var dets: List<Detection> = emptyList()
        try {
            val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            OnnxTensor.createTensor(env, inputBuffer, shape).use { tensor ->
                val tInfer = System.nanoTime()
                s.run(mapOf(inputName to tensor)).use { result ->
                    val tOut = System.nanoTime()
                    lastInferenceMs = (tOut - tInfer) / 1_000_000
                    val outTensor = result.get(0) as OnnxTensor
                    dets = postprocess(outTensor, conf, iou, classFilter, pad)
                    lastPostMs = (System.nanoTime() - tOut) / 1_000_000
                }
            }
        } catch (t: Throwable) {
            _lastError = "infer: ${t.javaClass.simpleName}: ${t.message}"
            return DetectorOutput(emptyList(), lastPreMs, 0, 0, frame.width, frame.height)
        }

        return DetectorOutput(
            detections = dets,
            preprocessMs = lastPreMs,
            inferenceMs = lastInferenceMs,
            postprocessMs = lastPostMs,
            frameWidth = frame.width,
            frameHeight = frame.height
        )
    }

    /** 解析 [1,84,N]，阈值过滤 + NMS，坐标归一化回原帧。 */
    private fun postprocess(
        out: OnnxTensor,
        conf: Float,
        iou: Float,
        classFilter: Set<Int>?,
        pad: Pad
    ): List<Detection> {
        val shape = out.info.shape
        if (shape.size != 3) return emptyList()

        val data = out.floatBuffer
        data.rewind()
        val flat = FloatArray(data.remaining())
        data.get(flat)

        val d0 = shape[0].toInt()
        val d1 = shape[1].toInt()
        val d2 = shape[2].toInt()

        val channels: Int
        val anchors: Int
        val transposed: Boolean
        when {
            d1 == NUM_CLASSES + 4 -> { channels = d1; anchors = d2; transposed = false }
            d2 == NUM_CLASSES + 4 -> { channels = d2; anchors = d1; transposed = true }
            else -> return emptyList()
        }

        val candidates = ArrayList<Detection>(64)
        val filter = if (classFilter.isNullOrEmpty()) null else classFilter
        val inv255 = 1f
        val base = 0

        for (a in 0 until anchors) {
            var bestScore = 0f
            var bestCls = -1
            if (transposed) {
                val off = a * channels + 4
                for (c in 0 until NUM_CLASSES) {
                    val v = flat[off + c]
                    if (v > bestScore) { bestScore = v; bestCls = c }
                }
            } else {
                var idx = (4) * anchors + a
                for (c in 0 until NUM_CLASSES) {
                    val v = flat[idx]
                    if (v > bestScore) { bestScore = v; bestCls = c }
                    idx += anchors
                }
            }
            if (bestScore < conf || bestCls < 0) continue
            if (filter != null && !filter.contains(bestCls)) continue

            val cx: Float; val cy: Float; val w: Float; val h: Float
            if (transposed) {
                val off = a * channels
                cx = flat[off]; cy = flat[off + 1]; w = flat[off + 2]; h = flat[off + 3]
            } else {
                cx = flat[base + a]
                cy = flat[anchors + a]
                w = flat[2 * anchors + a]
                h = flat[3 * anchors + a]
            }

            val nx1 = ((cx - w * 0.5f - pad.dx) / pad.drawW).coerceIn(0f, 1f)
            val ny1 = ((cy - h * 0.5f - pad.dy) / pad.drawH).coerceIn(0f, 1f)
            val nx2 = ((cx + w * 0.5f - pad.dx) / pad.drawW).coerceIn(0f, 1f)
            val ny2 = ((cy + h * 0.5f - pad.dy) / pad.drawH).coerceIn(0f, 1f)

            if (nx2 - nx1 < 0.004f || ny2 - ny1 < 0.004f) continue
            candidates.add(Detection(bestCls, bestScore, nx1, ny1, nx2, ny2))
        }

        candidates.sortByDescending { it.score }
        return nms(candidates, iou)
    }

    private fun nms(input: List<Detection>, iouThr: Float): List<Detection> {
        if (input.isEmpty()) return input
        val kept = ArrayList<Detection>(min(input.size, MAX_DETECTIONS))
        val suppressed = BooleanArray(input.size)
        for (i in input.indices) {
            if (suppressed[i]) continue
            val a = input[i]
            kept.add(a)
            if (kept.size >= MAX_DETECTIONS) break
            for (j in i + 1 until input.size) {
                if (suppressed[j]) continue
                val b = input[j]
                if (b.classId != a.classId) continue
                if (iou(a, b) > iouThr) suppressed[j] = true
            }
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val ua = a.w * a.h + b.w * b.h - inter
        return if (ua <= 0f) 0f else inter / ua
    }
}