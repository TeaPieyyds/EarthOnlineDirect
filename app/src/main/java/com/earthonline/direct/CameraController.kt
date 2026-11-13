package com.earthonline.direct

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX 绑定封装：Preview + ImageAnalysis。
 *
 * 分析流有两种像素格式可选，由 [useRgba] 控制：
 *  - RGBA_8888：HAL 直接吐打包像素，分析器拿到就是 ARGB，省掉 YUV→RGB 转换（快 ~20ms/帧）
 *  - YUV_420_888：兼容性最好，但每帧要自己做色彩空间换算
 */
class CameraController(
    private val context: Context,
    private val previewView: PreviewView
) {

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    val isFront: Boolean get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    /**
     * @param lifecycleOwner 绑定的生命周期
     * @param analyzer 帧分析器
     * @param front true=前置
     * @param analysisSize 期望的分析分辨率（会就近取最接近的档位）
     * @param useRgba true=让 HAL 直出 RGBA，省掉 YUV 转换
     * @param onBound 绑定成功回调
     * @param onError 失败回调
     */
    fun start(
        lifecycleOwner: LifecycleOwner,
        analyzer: ImageAnalysis.Analyzer,
        front: Boolean,
        analysisSize: Size = Size(640, 480),
        useRgba: Boolean = true,
        onBound: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        lensFacing = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val ex = executor ?: Executors.newSingleThreadExecutor().also { executor = it }

                val resSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            analysisSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val builder = ImageAnalysis.Builder()
                    .setResolutionSelector(resSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

                // RGBA 直出：部分老机型 HAL 不支持，失败时由调用方回退到 YUV
                if (useRgba) {
                    builder.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                } else {
                    builder.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                }

                val ia = builder.build().also { it.setAnalyzer(ex, analyzer) }

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, selector, preview, ia)
                analysis = ia
                onBound()
            } catch (t: Throwable) {
                onError("${t.javaClass.simpleName}: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** 是否支持手电。 */
    fun hasFlash(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    fun setTorch(on: Boolean): Boolean {
        val c = camera ?: return false
        if (!c.cameraInfo.hasFlashUnit()) return false
        c.cameraControl.enableTorch(on)
        return true
    }

    /** 解绑分析流，释放相机。 */
    fun stop() {
        try { analysis?.clearAnalyzer() } catch (_: Throwable) {}
        try { provider?.unbindAll() } catch (_: Throwable) {}
        analysis = null
        camera = null
    }

    fun shutdown() {
        stop()
        try { executor?.shutdownNow() } catch (_: Throwable) {}
        executor = null
    }
}