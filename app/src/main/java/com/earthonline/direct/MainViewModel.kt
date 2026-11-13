package com.earthonline.direct

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置类状态：只由用户操作改变，推理结果不写进这里。
 * 抽屉只订阅这个 StateFlow，所以识别跑多快都不会引发抽屉重组。
 */
data class SettingsState(
    val modelReady: Boolean = false,
    val modelError: String? = null,
    val loading: Boolean = true,

    val running: Boolean = true,          // 识别总开关
    val countMode: Boolean = true,        // 框与计数显示
    val showLabels: Boolean = true,
    val showScore: Boolean = true,

    val conf: Float = 0.35f,
    val iou: Float = 0.45f,
    val maxSide: Int = 480,
    val modelInput: Int = 480,
    val backend: Backend = Backend.XNNPACK,
    val threads: Int = 4,

    /** 加速组合拳：一键把 RGBA 直出 / 低分辨率 / NNAPI-fp16 / 多线程全开 */
    val boost: Boolean = true,
    /** 相机是否直出 RGBA（省掉 YUV 换算 ~20ms） */
    val useRgba: Boolean = true,

    val frontCamera: Boolean = false,
    val torch: Boolean = false,

    val selected: Set<Int> = emptySet(),  // 空 = 全部类别

    /** 基准测试结果文本，null 表示还没测 */
    val benchText: String? = null,
    val benching: Boolean = false,

    /** 抽屉是否展开（决定相机是否降频） */
    val drawerOpen: Boolean = false
)

/** 检测结果状态：每帧更新，只有画框和 HUD 订阅。 */
data class FrameState(
    val detections: List<Detection> = emptyList(),
    val counts: List<Pair<Int, Int>> = emptyList(),
    val total: Int = 0,
    val preMs: Long = 0,
    val inferenceMs: Long = 0,
    val postMs: Long = 0,
    val fps: Float = 0f,
    val frameW: Int = 0,
    val frameH: Int = 0,
    val frameFormat: String = "-"
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private val _frame = MutableStateFlow(FrameState())
    val frame: StateFlow<FrameState> = _frame.asStateFlow()

    val detector = YoloDetector(app.applicationContext, 480)

    private var lastFrameTime = 0L
    private var smoothedFps = 0f
    private var lastFramePush = 0L

    fun loadModel() {
        viewModelScope.launch {
            _settings.value = _settings.value.copy(loading = true)
            val b = _settings.value.backend
            val ok = withContext(Dispatchers.IO) { detector.load(b) }
            _settings.value = _settings.value.copy(
                loading = false,
                modelReady = ok,
                modelError = if (ok) null else detector.lastError
            )
        }
    }

    fun setBackend(b: Backend) {
        if (_settings.value.backend == b) return
        applyBackend(b)
    }

    private fun applyBackend(b: Backend) {
        _settings.value = _settings.value.copy(backend = b, loading = true, modelReady = false)
        viewModelScope.launch {
            detector.numThreads = _settings.value.threads
            val ok = withContext(Dispatchers.IO) { detector.load(b) }
            _settings.value = _settings.value.copy(
                loading = false,
                modelReady = ok,
                modelError = if (ok) null else detector.lastError
            )
        }
    }

    fun setThreads(n: Int) {
        val v = n.coerceIn(1, 8)
        detector.numThreads = v
        _settings.value = _settings.value.copy(threads = v)
    }

    /**
     * 加速组合拳总开关：打开时一次做四件事。
     *  1. 相机 RGBA 直出 —— 省掉每帧 YUV→RGB 换算
     *  2. 模型输入降到 416 —— 计算量约为 480 的 75%
     *  3. 预处理帧长边降到 416 —— 旋转缩放更省
     *  4. 线程 4 + 切 NNAPI(fp16) —— 尝试走 GPU/DSP
     * 关掉时全部回保守值：YUV / 480 / XNNPACK / 4 线程。
     */
    fun setBoost(on: Boolean) {
        if (on) {
            _settings.value = _settings.value.copy(
                boost = true, useRgba = true,
                modelInput = 416, maxSide = 416, threads = 4
            )
            detector.inputSize = 416
            detector.numThreads = 4
            applyBackend(Backend.NNAPI)
        } else {
            _settings.value = _settings.value.copy(
                boost = false, useRgba = false,
                modelInput = 480, maxSide = 480, threads = 4
            )
            detector.inputSize = 480
            detector.numThreads = 4
            applyBackend(Backend.XNNPACK)
        }
    }

    /** 单独切 RGBA 直出，不影响其他项。 */
    fun setUseRgba(on: Boolean) {
        _settings.value = _settings.value.copy(useRgba = on)
    }

    /** 各后端各跑 N 轮取中位数，写入 benchText。 */
    fun runBenchmark(rounds: Int = 15) {
        if (_settings.value.benching) return
        _settings.value = _settings.value.copy(benching = true, benchText = "测试中…")
        viewModelScope.launch {
            val sb = StringBuilder()
            val original = _settings.value.backend
            for (b in Backend.entries) {
                val ok = withContext(Dispatchers.IO) { detector.load(b) }
                if (!ok) {
                    sb.append(b.label).append(": 加载失败\n")
                    continue
                }
                val line = withContext(Dispatchers.Default) { detector.bench(rounds) }
                sb.append(b.label).append(": ").append(line).append('\n')
            }
            withContext(Dispatchers.IO) { detector.load(original) }
            _settings.value = _settings.value.copy(
                benching = false,
                benchText = sb.toString().trim(),
                backend = original
            )
        }
    }

    fun toggleRunning() {
        val next = !_settings.value.running
        _settings.value = _settings.value.copy(running = next)
        if (!next) {
            _frame.value = _frame.value.copy(
                detections = emptyList(), counts = emptyList(), total = 0
            )
        }
    }

    fun toggleCount() = _settings.value.let {
        _settings.value = it.copy(countMode = !it.countMode)
    }

    fun toggleLabels() = _settings.value.let {
        _settings.value = it.copy(showLabels = !it.showLabels)
    }

    fun toggleScore() = _settings.value.let {
        _settings.value = it.copy(showScore = !it.showScore)
    }

    fun setConf(v: Float) {
        _settings.value = _settings.value.copy(conf = v.coerceIn(0.05f, 0.95f))
    }

    fun setIou(v: Float) {
        _settings.value = _settings.value.copy(iou = v.coerceIn(0.1f, 0.95f))
    }

    fun setMaxSide(v: Int) {
        _settings.value = _settings.value.copy(maxSide = v.coerceIn(256, 1280))
    }

    /** 模型输入边长，32 的倍数，320..640。 */
    fun setModelInput(v: Int) {
        val snapped = (((v.coerceIn(320, 640) + 16) / 32) * 32)
        if (snapped == _settings.value.modelInput) return
        detector.inputSize = snapped
        _settings.value = _settings.value.copy(modelInput = snapped)
    }

    fun toggleClass(id: Int) {
        val cur = _settings.value.selected.toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        _settings.value = _settings.value.copy(selected = cur)
    }

    fun clearClasses() = _settings.value.let {
        _settings.value = it.copy(selected = emptySet())
    }

    fun selectQuick() = _settings.value.let {
        _settings.value = it.copy(selected = CocoLabels.QUICK.toSet())
    }

    fun setFront(front: Boolean) = _settings.value.let {
        _settings.value = it.copy(frontCamera = front, torch = false)
    }

    fun setTorch(on: Boolean) = _settings.value.let {
        _settings.value = it.copy(torch = on)
    }

    fun setDrawerOpen(open: Boolean) {
        if (_settings.value.drawerOpen != open) {
            _settings.value = _settings.value.copy(drawerOpen = open)
        }
    }

    /**
     * 推理结果回写。节流到 ~12Hz，避免每帧都触发 Compose 重组，
     * 抽屉拖动时被高频重组打断。
     */
    fun onDetectorResult(out: DetectorOutput) {
        val now = System.nanoTime()
        if (lastFrameTime != 0L) {
            val dt = (now - lastFrameTime) / 1e9f
            if (dt > 0f) {
                val inst = 1f / dt
                smoothedFps = if (smoothedFps == 0f) inst else smoothedFps * 0.75f + inst * 0.25f
            }
        }
        lastFrameTime = now

        // 首帧立即推，之后最少间隔 80ms
        if (lastFramePush != 0L && now - lastFramePush < 80_000_000L) return
        lastFramePush = now

        val counts = out.detections
            .groupingBy { it.classId }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }

        _frame.value = FrameState(
            detections = out.detections,
            counts = counts,
            total = out.detections.size,
            preMs = out.preprocessMs,
            inferenceMs = out.inferenceMs,
            postMs = out.postprocessMs,
            fps = smoothedFps,
            frameW = out.frameWidth,
            frameH = out.frameHeight,
            frameFormat = _frame.value.frameFormat
        )
    }

    fun setFrameFormat(f: String) {
        if (_frame.value.frameFormat != f) {
            _frame.value = _frame.value.copy(frameFormat = f)
        }
    }

    override fun onCleared() {
        detector.close()
        super.onCleared()
    }
}