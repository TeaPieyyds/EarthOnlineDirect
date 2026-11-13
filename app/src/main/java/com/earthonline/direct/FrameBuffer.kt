package com.earthonline.direct

/**
 * 一帧待推理的图像：ARGB 像素 + 尺寸。
 * 已是「按显示方向旋转后」的像素，检测框直接按 0..1 归一化坐标返回。
 */
class FrameBuffer(
    val argb: IntArray,
    val width: Int,
    val height: Int
)

/** 单个检测框，坐标归一化到 [0,1]（相对 frame 宽高）。 */
data class Detection(
    val classId: Int,
    val score: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
) {
    val cx: Float get() = (x1 + x2) * 0.5f
    val cy: Float get() = (y1 + y2) * 0.5f
    val w: Float get() = x2 - x1
    val h: Float get() = y2 - y1
}

/** 一次推理结果统计。 */
data class DetectorOutput(
    val detections: List<Detection>,
    val preprocessMs: Long,
    val inferenceMs: Long,
    val postprocessMs: Long,
    val frameWidth: Int,
    val frameHeight: Int
)