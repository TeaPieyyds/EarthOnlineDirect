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

/** 界面状态。 */
data class UiState(
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
    /** 基准测试结果文本，null 表示还没测 */
    val benchText: String? = null,
    val benching: Boolean = false,
    /** 当前相机流格式，RGBA / YUV */
    val frameFormat: String = "-",

    val frontCamera: Boolean = false,
    val torch: Boolean = false,

    val selected: Set<Int> = emptySet(),  // 空 = 全部类别
    val detections: List<Detection> = emptyList(),
    val counts: List<Pair<Int, Int>> = emptyList(),
    val total: Int = 0,
    val preMs: Long = 0,
    val inferenceMs: Long = 0,
    val postMs: Long = 0,
    val fps: Float = 0f,
    val frameW: Int = 0,
    val frameH: Int = 0,

    /** 抽屉展开状态，Compose 里也能读，方便做「拖动时暂停刷新」之类的联动。 */
    val drawerOpen: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val detector = YoloDetector(app.applicationContext, 480)

    private var lastFrameTime = 0L
    private var smoothedFps = 0f

    fun loadModel() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            val b = _ui.value.backend
            val ok = withContext(Dispatchers.IO) { detector.load(b) }
            _ui.value = _ui.value.copy(
                loading = false,
                modelReady = ok,
                modelError = if (ok) null else detector.lastError
            )
        }
    }

    fun setBackend(b: Backend) {
        if (_ui.value.backend == b) return
        _ui.value = _ui.value.copy(backend = b, loading = true, modelReady = false)
        viewModelScope.launch {
            detector.numThreads = _ui.value.threads
            val ok = withContext(Dispatchers.IO) { detector.load(b) }
            _ui.value = _ui.value.copy(
                loading = false,
                modelReady = ok,
                modelError = if (ok) null else detector.lastError
            )
        }
    }

    fun setThreads(n: Int) {
        val v = n.coerceIn(1, 8)
        detector.numThreads = v
        _ui.value = _ui.value.copy(threads = v)
    }

    /**
     * 加速组合拳总开关。
     *
     * 打开时一次性做四件事：
     *  1. 相机 RGBA 直出 —— 省掉每帧 YUV→RGB 换算
     *  2. 模型输入降到 416 —— 计算量约为 480 的 75%
     *  3. 预处理帧长边降到 416 —— 旋转缩放更省
     *  4. 线程数拉到 4 并切 NNAPI(fp16) —— 尝试走 GPU/DSP
     *
     * 关掉时全部回到保守值：YUV / 640 / XNNPACK / 4 线程。
     */
    fun setBoost(on: Boolean) {
        if (on) {
            _ui.value = _ui.value.copy(
                boost = true,
                useRgba = true,
                modelInput = 416,
                maxSide = 416,
                threads = 4
            )
            detector.inputSize = 416
            detector.numThreads = 4
            applyBackend(Backend.NNAPI)
        } else {
            _ui.value = _ui.value.copy(
                boost = false,
                useRgba = false,
                modelInput = 480,
                maxSide = 480,
                threads = 4
            )
            detector.inputSize = 480
            detector.numThreads = 4
            applyBackend(Backend.XNNPACK)
        }
    }

    /** 单独切 RGBA 直出，不影响其他项。 */
    fun setUseRgba(on: Boolean) {
        _ui.value = _ui.value.copy(useRgba = on)
    }

    private fun applyBackend(b: Backend) {
        _ui.value = _ui.value.copy(backend = b, loading = true, modelReady = false)
        viewModelScope.launch {
            detector.numThreads = _ui.value.threads
            val ok = withContext(Dispatchers.IO) { detector.load(b) }
            _ui.value = _ui.value.copy(
                loading = false,
                modelReady = ok,
                modelError = if (ok) null else detector.lastError
            )
        }
    }

    /** 各后端各跑 N 轮取中位数，写入 benchText。 */
    fun runBenchmark(rounds: Int = 15) {
        if (_ui.value.benching) return
        _ui.value = _ui.value.copy(benching = true, benchText = "测试中…")
        viewModelScope.launch {
            val sb = StringBuilder()
            val original = _ui.value.backend
            for (b in Backend.entries) {
                val ok = withContext(Dispatchers.IO) { detector.load(b) }
                if (!ok) {
                    sb.append("${b.label}: 加载失败\n")
                    continue
                }
                val line = withContext(Dispatchers.Default) {
                    detector.bench(rounds)
                }
                sb.append(b.label).append(": ").append(line).append('\n')
            }
            withContext(Dispatchers.IO) { detector.load(original) }
            _ui.value = _ui.value.copy(
                benching = false,
                benchText = sb.toString().trim(),
                backend = original
            )
        }
    }

    fun setFrameFormat(f: String) {
        if (_ui.value.frameFormat != f) _ui.value = _ui.value.copy(frameFormat = f)
    }

    fun toggleRunning() {
        val next = !_ui.value.running
        _ui.value = _ui.value.copy(
            running = next,
            detections = if (next) _ui.value.detections else emptyList(),
            counts = if (next) _ui.value.counts else emptyList(),
            total = if (next) _ui.value.total else 0
        )
    }

    fun toggleCount() { _ui.value = _ui.value.copy(countMode = !_ui.value.countMode) }
    fun toggleLabels() { _ui.value = _ui.value.copy(showLabels = !_ui.value.showLabels) }
    fun toggleScore() { _ui.value = _ui.value.copy(showScore = !_ui.value.showScore) }

    fun setConf(v: Float) { _ui.value = _ui.value.copy(conf = v.coerceIn(0.05f, 0.95f)) }
    fun setIou(v: Float) { _ui.value = _ui.value.copy(iou = v.coerceIn(0.1f, 0.95f)) }

    fun setMaxSide(v: Int) {
        _ui.value = _ui.value.copy(maxSide = v.coerceIn(256, 1280))
    }

    /** 模型输入边长，32 的倍数，320..640。 */
    fun setModelInput(v: Int) {
        val snapped = (((v.coerceIn(320, 640) + 16) / 32) * 32)
        if (snapped == _ui.value.modelInput) return
        detector.inputSize = snapped
        _ui.value = _ui.value.copy(modelInput = snapped)
    }

    fun toggleClass(id: Int) {
        val cur = _ui.value.selected.toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        _ui.value = _ui.value.copy(selected = cur)
    }

    fun clearClasses() { _ui.value = _ui.value.copy(selected = emptySet()) }
    fun selectQuick() { _ui.value = _ui.value.copy(selected = CocoLabels.QUICK.toSet()) }

    fun setFront(front: Boolean) { _ui.value = _ui.value.copy(frontCamera = front, torch = false) }
    fun setTorch(on: Boolean) { _ui.value = _ui.value.copy(torch = on) }

    fun setDrawerOpen(open: Boolean) {
        if (_ui.value.drawerOpen != open) _ui.value = _ui.value.copy(drawerOpen = open)
    }

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

        val counts = out.detections
            .groupingBy { it.classId }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }

        _ui.value = _ui.value.copy(
            detections = out.detections,
            counts = counts,
            total = out.detections.size,
            preMs = out.preprocessMs,
            inferenceMs = out.inferenceMs,
            postMs = out.postprocessMs,
            fps = smoothedFps,
            frameW = out.frameWidth,
            frameH = out.frameHeight
        )
    }

    override fun onCleared() {
        detector.close()
        super.onCleared()
    }
}