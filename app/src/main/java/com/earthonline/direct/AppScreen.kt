package com.earthonline.direct

import android.util.Size
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Ink = Color(0xFF05080D)
private val Panel = Color(0xF70E1725)
private val PanelEdge = Color(0xFF22344A)
private val Cyan = Color(0xFF35D6E8)
private val Mint = Color(0xFF34C759)
private val Amber = Color(0xFFFFB020)
private val Dim = Color(0xFF8FA6B8)
private val Light = Color(0xFFE9F2F8)

/** 抽屉的两种停靠状态（保留给状态描述用）。 */
private enum class DrawerValue { Closed, Open }

@Composable
fun AppScreen(
    vm: MainViewModel,
    hasCameraPermission: Boolean,
    permissionDenied: Boolean,
    requestPermission: () -> Unit
) {
    val s by vm.settings.collectAsState()
    val f by vm.frame.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        if (!s.modelReady && s.loading) vm.loadModel()
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    val analyzer = remember {
        YoloAnalyzer(context, vm.detector) { out -> vm.onDetectorResult(out) }
    }

    LaunchedEffect(s.running, s.conf, s.iou, s.maxSide, s.selected, s.frontCamera) {
        analyzer.enabled = s.running
        analyzer.conf = s.conf
        analyzer.iou = s.iou
        analyzer.maxSide = s.maxSide
        analyzer.classFilter = s.selected.ifEmpty { null }
        analyzer.mirrorX = s.frontCamera
    }

    LaunchedEffect(Unit) {
        while (true) {
            vm.setFrameFormat(analyzer.lastFormat)
            kotlinx.coroutines.delay(1500)
        }
    }

    val cameraController = remember { CameraController(context, previewView) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(hasCameraPermission, s.frontCamera, s.modelReady, s.useRgba) {
        if (hasCameraPermission && s.modelReady) {
            cameraController.start(
                lifecycleOwner = lifecycleOwner,
                analyzer = analyzer,
                front = s.frontCamera,
                analysisSize = Size(640, 480),
                useRgba = s.useRgba,
                onBound = { cameraReady = true; cameraError = null },
                onError = { cameraReady = false; cameraError = it }
            )
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        onDispose { cameraController.shutdown() }
    }

    LaunchedEffect(s.torch, cameraReady) {
        if (cameraReady) cameraController.setTorch(s.torch)
    }

    Box(Modifier.fillMaxSize().background(Ink).clipToBounds()) {

        if (hasCameraPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        DetectionOverlay(
            detections = if (s.countMode) f.detections else emptyList(),
            frameWidth = f.frameW,
            frameHeight = f.frameH,
            showLabels = s.showLabels,
            showScore = s.showScore,
            modifier = Modifier.fillMaxSize()
        )

        TopHud(
            settings = s,
            frame = f,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 12.dp, top = 10.dp, end = 12.dp)
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
        ) {
            CircleButton(
                symbol = if (s.running) "Ⅱ" else "▶",
                label = if (s.running) "暂停" else "继续",
                accent = if (s.running) Mint else Amber
            ) { vm.toggleRunning() }
        }

        BottomDrawer(
            settings = s,
            frame = f,
            vm = vm,
            onOpenChanged = { vm.setDrawerOpen(it) }
        )

        if (!hasCameraPermission) {
            CenterNotice("需要相机权限", "授权后即可实时识别", "去授权", requestPermission)
        } else if (s.loading) {
            Box(Modifier.fillMaxSize().background(Color(0xD905080D)), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cyan)
                    Spacer(Modifier.height(14.dp))
                    Text("正在加载 yolo11n（${s.backend.label}）…", color = Light, fontSize = 13.sp)
                }
            }
        } else if (!s.modelReady) {
            CenterNotice("模型加载失败", s.modelError ?: "未知错误", "重试") { vm.loadModel() }
        } else if (cameraError != null) {
            CenterNotice("相机启动失败", cameraError!!, "重试") {
                cameraError = null
                vm.setFront(s.frontCamera)
            }
        }

        if (permissionDenied && !hasCameraPermission) {
            Text(
                "权限被拒绝，可到系统设置里手动开启",
                color = Amber,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
            )
        }
    }
}

/* ───────────────────────── 顶部 HUD ───────────────────────── */

@Composable
private fun TopHud(
    settings: SettingsState,
    frame: FrameState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .background(Color(0x99081120), RoundedCornerShape(50))
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).background(if (settings.running) Mint else Amber, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                if (settings.running) "识别中" else "已暂停",
                color = Light,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (settings.countMode) {
            Row(
                modifier = Modifier
                    .background(Color(0x99081120), RoundedCornerShape(50))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${frame.total}", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text("目标", color = Dim, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text("${frame.inferenceMs}ms", color = Dim, fontSize = 11.sp)
            }
        }

        if (settings.boost) {
            Row(
                modifier = Modifier
                    .background(Color(0xCC3A2A00), RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⚡", color = Amber, fontSize = 11.sp)
                Spacer(Modifier.width(3.dp))
                Text("加速", color = Amber, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/* ───────────────────────── 抽屉 ───────────────────────── */

/**
 * 抽屉实现要点（跟手不卡的三条关键）：
 *  1. 位移存在普通 Float state 里，draggable 的 onDelta 直接改，零协程零动画
 *  2. 松手用 velocity 判断方向，配一个短 tween 吸到锚点
 *  3. 只有顶部把手条接手势，内容区照常滚动，互不抢
 */
@Composable
private fun BottomDrawer(
    settings: SettingsState,
    frame: FrameState,
    vm: MainViewModel,
    onOpenChanged: (Boolean) -> Unit
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenH = config.screenHeightDp.dp

    val expandedH = screenH * 0.80f
    val peekH = 64.dp

    val peekPx = with(density) { peekH.toPx() }
    val expandedPx = with(density) { expandedH.toPx() }
    val closedOffset = (expandedPx - peekPx).coerceAtLeast(1f)

    // 0f = 完全展开，closedOffset = 收起
    val offsetY = remember(closedOffset) { mutableFloatStateOf(closedOffset) }
    var open by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun settleTo(targetOpen: Boolean, animate: Boolean = true) {
        open = targetOpen
        val target = if (targetOpen) 0f else closedOffset
        if (animate) {
            scope.launch {
                androidx.compose.animation.core.animate(
                    initialValue = offsetY.floatValue,
                    targetValue = target,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 180,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    )
                ) { v, _ -> offsetY.floatValue = v }
            }
        } else {
            offsetY.floatValue = target
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(expandedH)
                .offset { IntOffset(0, offsetY.floatValue.roundToInt()) }
                .background(Panel, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        ) {
            // 手势条：唯一能拖动抽屉的区域
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            // 直接改 Float state，Compose 下一帧就重绘，完全不排队等协程
                            offsetY.floatValue =
                                (offsetY.floatValue + delta).coerceIn(0f, closedOffset)
                        },
                        onDragStopped = { velocity ->
                            // 手指往上甩（velocity < 0）或已经拉过一半 -> 展开
                            val shouldOpen = when {
                                velocity < -600f -> true
                                velocity > 600f -> false
                                else -> offsetY.floatValue < closedOffset * 0.6f
                            }
                            settleTo(shouldOpen)
                        }
                    )
                    .clickable {
                        settleTo(!open || offsetY.floatValue > closedOffset * 0.5f)
                    }
                    .padding(top = 11.dp, bottom = 9.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 46.dp, height = 5.dp)
                        .background(Color(0x99AFC3D4), RoundedCornerShape(50))
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (frame.total > 0) {
                            frame.counts.take(3)
                                .joinToString("  ") { "${CocoLabels.zh(it.first)}×${it.second}" }
                        } else {
                            if (open) "下滑收起" else "上滑展开设置"
                        },
                        color = Light,
                        fontSize = 12.5.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${frame.preMs}+${frame.inferenceMs}+${frame.postMs}ms · " +
                            "${"%.1f".format(frame.fps)}fps",
                        color = Dim,
                        fontSize = 10.5.sp
                    )
                }
            }

            HorizontalDivider(color = Color(0x1AFFFFFF))

            DrawerContent(settings = settings, frame = frame, vm = vm)
        }
    }

    LaunchedEffect(open) { onOpenChanged(open) }
}

@Composable
private fun DrawerContent(
    settings: SettingsState,
    frame: FrameState,
    vm: MainViewModel
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 20.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(
                if (settings.running) "暂停识别" else "开始识别",
                if (settings.running) Mint else Amber
            ) { vm.toggleRunning() }
            ActionChip(
                if (settings.countMode) "计数 开" else "计数 关",
                if (settings.countMode) Cyan else Dim
            ) { vm.toggleCount() }
            ActionChip("翻转", Dim) { vm.setFront(!settings.frontCamera) }
            if (!settings.frontCamera) {
                ActionChip(
                    if (settings.torch) "灯 开" else "灯 关",
                    if (settings.torch) Color(0xFFFFD60A) else Dim
                ) { vm.setTorch(!settings.torch) }
            }
        }

        ExplainCard(
            title = "⚡ 加速组合拳",
            subtitle = "一键开满：RGBA 直出 + 416 输入 + NNAPI fp16 + 4 线程",
            detail = "打开后同时做四件事，实测能把单帧从 ~300ms 压到 ~120ms：\n\n" +
                "① 相机直出 RGBA —— 跳过每帧 YUV→RGB 换算，省约 20ms。\n" +
                "② 模型输入 416px —— 计算量是 480 的约 75%，大目标几乎不掉精度。\n" +
                "③ 预处理帧长边 416px —— 旋转缩放的像素量同步减少。\n" +
                "④ 推理后端切 NNAPI(fp16) —— 尝试让 GPU/DSP 参与计算。\n\n" +
                "⚠ 第 4 步依赖厂商 NNAPI 驱动。联发科某些机型会把算子退回 CPU，" +
                "反而更慢。所以如果打开后帧率下降，就在下面「推理性能」里用" +
                "「跑基准」对比一下，挑最快的那个后端手动锁定。\n\n" +
                "关掉开关 = 全部回到保守值：YUV 流 / 480 输入 / XNNPACK / 4 线程。",
            checked = settings.boost,
            accent = Amber
        ) { vm.setBoost(it) }

        SectionTitle("识别对象")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (settings.selected.isEmpty()) "全部 80 类" else "已选 ${settings.selected.size} 类",
                color = Cyan,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.selectQuick() }) { Text("常用", fontSize = 12.sp) }
            TextButton(onClick = { vm.clearClasses() }) { Text("全部", fontSize = 12.sp) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CocoLabels.QUICK) { id ->
                SmallChip(
                    text = CocoLabels.zh(id),
                    selected = settings.selected.contains(id),
                    accent = Color(CocoLabels.color(id) or 0xFF000000.toInt())
                ) { vm.toggleClass(id) }
            }
        }
        HintText("点类别 = 只识别它；不选任何类别 = 识别全部 80 种")

        Divider()

        SectionTitle("显示")
        ExplainToggle(
            title = "计数与统计",
            value = settings.countMode,
            detail = "画面左上角显示「共 N 个目标」以及每一类的数量，" +
                "关闭后只保留检测框，视野更干净。"
        ) { vm.toggleCount() }

        ExplainToggle(
            title = "类别标签",
            value = settings.showLabels,
            detail = "在每个框的上方画出类别名（如「汽车」「人」）。" +
                "关掉只剩彩色框，适合看密集场景。"
        ) { vm.toggleLabels() }

        ExplainToggle(
            title = "置信度数值",
            value = settings.showScore,
            detail = "标签后面附带百分比，表示模型有多确定。" +
                "70% 以上通常可信；30~50% 多是误检或遮挡严重的目标。"
        ) { vm.toggleScore() }

        Divider()

        SectionTitle("推理性能")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Backend.entries.forEach { b ->
                SmallChip(b.label, settings.backend == b, Cyan) { vm.setBackend(b) }
            }
        }
        HintText(
            "XNNPACK：纯 CPU，用 ARM NEON 指令集，最稳，任何机型都能跑。\n" +
                "NNAPI：走系统神经网络接口，可能调用 GPU/DSP/NPU，速度看厂商驱动。\n" +
                "CPU：最慢的参考实现，只有在其他两个都崩溃时才用。\n" +
                "切后端会重新加载模型，约 1~2 秒。"
        )

        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { vm.runBenchmark() }, enabled = !settings.benching) {
            Text(
                if (settings.benching) "测试中…" else "▶ 跑基准（三个后端各测 15 次）",
                fontSize = 12.sp,
                color = Cyan
            )
        }
        settings.benchText?.let {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A1420), RoundedCornerShape(10.dp))
                    .padding(11.dp)
            ) {
                Text(it, color = Light, fontSize = 11.sp)
            }
            HintText("中位数越小越快。挑最快的那个后端，在上面的按钮里手动锁定。")
        }

        Spacer(Modifier.height(6.dp))
        SliderRow("模型输入 ${settings.modelInput}px", settings.modelInput.toFloat(), 320f, 640f) {
            vm.setModelInput(it.roundToInt())
        }
        HintText(
            "送进神经网络的图片边长。\n" +
                "320px：最快，适合只看人和车这种大目标，远处小物体容易漏。\n" +
                "480px：均衡，日常推荐。\n" +
                "640px：最准，官方训练尺寸，但计算量是 320 的 4 倍。\n" +
                "注意：这个值只影响模型输入，不影响屏幕上的画面清晰度。"
        )

        SliderRow("预处理 ${settings.maxSide}px", settings.maxSide.toFloat(), 320f, 960f) {
            vm.setMaxSide((it / 16).roundToInt() * 16)
        }
        HintText(
            "相机帧在送进模型之前先缩到这么大，越小人越小越省算力。\n" +
                "一般设成跟上面的「模型输入」一样大就好，\n" +
                "设得比它大是浪费（还会被再缩一次），设得太小则画面细节先丢了。"
        )

        SliderRow("线程数 ${settings.threads}", settings.threads.toFloat(), 1f, 8f) {
            vm.setThreads(it.roundToInt())
        }
        HintText(
            "同时干活的计算核心数。\n" +
                "4 是甜点值；调到 8 有时反而慢，因为大核数量有限、线程争抢会掉频。\n" +
                "调到 1~2 省电但明显变卡。"
        )

        ExplainToggle(
            title = "相机直出 RGBA",
            value = settings.useRgba,
            detail = "开启后相机直接把像素以 RGBA 打包交给 App，跳过 YUV→RGB 转换，省约 20ms/帧。\n\n" +
                "绝大多数 2019 年后的机器都支持。若开启后预览黑屏或报「相机启动失败」，" +
                "关掉它会自动回退到 YUV 兼容模式。\n\n" +
                "当前实际流格式：${frame.frameFormat}"
        ) { vm.setUseRgba(it) }

        Text(
            "分段耗时：预处理 ${frame.preMs}ms · 推理 ${frame.inferenceMs}ms · 后处理 ${frame.postMs}ms",
            color = Dim,
            fontSize = 11.sp
        )
        HintText(
            "预处理 = 缩放旋转 + 打包成模型要的张量。\n" +
                "推理 = 神经网络本身的计算时间，这是大头。\n" +
                "后处理 = 阈值筛选 + NMS 去重叠框。\n" +
                "想整体变快，优先压「推理」那一项。"
        )

        Divider()

        SectionTitle("阈值")
        SliderRow("置信度 ${"%.2f".format(settings.conf)}", settings.conf, 0.05f, 0.9f) {
            vm.setConf(it)
        }
        HintText(
            "低于这个分数的检测框直接丢掉。\n" +
                "0.25：宽松，能抓到模糊目标，但误检变多。\n" +
                "0.35：默认，日常够用。\n" +
                "0.60 以上：只留非常确定的目标，容易漏检。"
        )

        SliderRow("NMS IoU ${"%.2f".format(settings.iou)}", settings.iou, 0.1f, 0.9f) {
            vm.setIou(it)
        }
        HintText(
            "两个同类别框重叠超过这个比例时，只保留分数高的那个。\n" +
                "调低（0.3）：去重更狠，密集人群里会误删相邻的人。\n" +
                "调高（0.7）：保留更多框，可能出现同一个物体套两个框。"
        )

        Divider()

        SectionTitle("关于")
        Text(
            "yolo11n · COCO-80 类 · ONNX Runtime 1.24.1\n" +
                "模型 10.9MB · 输入动态尺寸 · 输出 [1,84,N]",
            color = Dim,
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(14.dp))
    }
}

/* ───────────────────────── 带说明的控件 ───────────────────────── */

@Composable
private fun ExplainToggle(
    title: String,
    value: Boolean,
    detail: String,
    onChange: (Boolean) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (open) "▾" else "▸", color = Dim, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 12.5.sp, color = Light)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = value,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Cyan.copy(alpha = 0.55f))
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            ExplainBox(detail)
        }
    }
}

@Composable
private fun ExplainCard(
    title: String,
    subtitle: String,
    detail: String,
    checked: Boolean,
    accent: Color,
    onChange: (Boolean) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(
                if (checked) accent.copy(alpha = 0.10f) else Color(0xFF131E2E),
                RoundedCornerShape(14.dp)
            )
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { open = !open }) {
                Text(title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Dim, fontSize = 10.5.sp, lineHeight = 14.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = accent)
            )
        }
        TextButton(
            onClick = { open = !open },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.height(22.dp)
        ) {
            Text(if (open) "收起说明 ▴" else "这是什么？▾", color = accent, fontSize = 11.sp)
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            ExplainBox(detail)
        }
    }
}

@Composable
private fun ExplainBox(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .background(Color(0xFF0A1420), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(text, color = Color(0xFFB9CBD9), fontSize = 11.5.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        color = Color(0xFF6E8598),
        fontSize = 10.5.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
    )
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, color = Light, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(6.dp))
    HorizontalDivider(color = Color(0x14FFFFFF))
    Spacer(Modifier.height(4.dp))
}

/* ───────────────────────── 小组件 ───────────────────────── */

@Composable
private fun CircleButton(symbol: String, label: String, accent: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(48.dp).background(Color(0x8C081120), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            TextButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
                Text(symbol, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(label, color = Color(0x99E9F2F8), fontSize = 9.5.sp)
    }
}

@Composable
private fun ActionChip(text: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Color(0xFF16233A), RoundedCornerShape(11.dp))
            .padding(horizontal = 13.dp, vertical = 8.dp)
            .clickable(onClick = onClick)
    ) {
        Text(text, color = accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SmallChip(text: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, fontSize = 11.5.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = accent.copy(alpha = 0.22f),
            selectedLabelColor = accent,
            labelColor = Dim
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = PanelEdge,
            selectedBorderColor = accent.copy(alpha = 0.6f)
        )
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 11.5.sp, color = Color(0xFFC4D4E0))
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

@Composable
private fun CenterNotice(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color(0xE605080D)), Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111C2C)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.padding(28.dp)
        ) {
            Column(
                Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, color = Light, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(body, color = Dim, fontSize = 12.5.sp)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan)
                ) {
                    Text(action, color = Ink, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}