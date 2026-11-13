package com.earthonline.direct

import android.util.Size
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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

@Composable
fun AppScreen(
    vm: MainViewModel,
    hasCameraPermission: Boolean,
    permissionDenied: Boolean,
    requestPermission: () -> Unit
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        if (!ui.modelReady && ui.loading) vm.loadModel()
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

    LaunchedEffect(ui.running, ui.conf, ui.iou, ui.maxSide, ui.selected, ui.frontCamera) {
        analyzer.enabled = ui.running
        analyzer.conf = ui.conf
        analyzer.iou = ui.iou
        analyzer.maxSide = ui.maxSide
        analyzer.classFilter = ui.selected.ifEmpty { null }
        analyzer.mirrorX = ui.frontCamera
    }

    // 相机格式回显到 UI
    LaunchedEffect(Unit) {
        while (true) {
            vm.setFrameFormat(analyzer.lastFormat)
            kotlinx.coroutines.delay(1200)
        }
    }

    val cameraController = remember { CameraController(context, previewView) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(hasCameraPermission, ui.frontCamera, ui.modelReady, ui.useRgba) {
        if (hasCameraPermission && ui.modelReady) {
            cameraController.start(
                lifecycleOwner = lifecycleOwner,
                analyzer = analyzer,
                front = ui.frontCamera,
                analysisSize = Size(640, 480),
                useRgba = ui.useRgba,
                onBound = { cameraReady = true; cameraError = null },
                onError = { cameraReady = false; cameraError = it }
            )
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        onDispose { cameraController.shutdown() }
    }

    LaunchedEffect(ui.torch, cameraReady) {
        if (cameraReady) cameraController.setTorch(ui.torch)
    }

    Box(Modifier.fillMaxSize().background(Ink).clipToBounds()) {

        if (hasCameraPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        DetectionOverlay(
            detections = if (ui.countMode) ui.detections else emptyList(),
            frameWidth = ui.frameW,
            frameHeight = ui.frameH,
            showLabels = ui.showLabels,
            showScore = ui.showScore,
            modifier = Modifier.fillMaxSize()
        )

        TopHud(
            ui = ui,
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
                symbol = if (ui.running) "Ⅱ" else "▶",
                label = if (ui.running) "暂停" else "继续",
                accent = if (ui.running) Mint else Amber
            ) { vm.toggleRunning() }
        }

        BottomDrawer(
            ui = ui,
            vm = vm,
            onOpenChanged = { vm.setDrawerOpen(it) }
        )

        if (!hasCameraPermission) {
            CenterNotice("需要相机权限", "授权后即可实时识别", "去授权", requestPermission)
        } else if (ui.loading) {
            Box(Modifier.fillMaxSize().background(Color(0xD905080D)), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cyan)
                    Spacer(Modifier.height(14.dp))
                    Text("正在加载 yolo11n（${ui.backend.label}）…", color = Light, fontSize = 13.sp)
                }
            }
        } else if (!ui.modelReady) {
            CenterNotice("模型加载失败", ui.modelError ?: "未知错误", "重试") { vm.loadModel() }
        } else if (cameraError != null) {
            CenterNotice("相机启动失败", cameraError!!, "重试") {
                cameraError = null
                vm.setFront(ui.frontCamera)
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
private fun TopHud(ui: UiState, modifier: Modifier = Modifier) {
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
            Box(Modifier.size(7.dp).background(if (ui.running) Mint else Amber, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                if (ui.running) "识别中" else "已暂停",
                color = Light,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (ui.countMode) {
            Row(
                modifier = Modifier
                    .background(Color(0x99081120), RoundedCornerShape(50))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${ui.total}", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text("目标", color = Dim, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text("${ui.inferenceMs}ms", color = Dim, fontSize = 11.sp)
            }
        }

        if (ui.boost) {
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

@Composable
private fun BottomDrawer(
    ui: UiState,
    vm: MainViewModel,
    onOpenChanged: (Boolean) -> Unit
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenH = config.screenHeightDp.dp
    val expandedH = screenH * 0.80f
    val peekH = 62.dp

    val peekPx = with(density) { peekH.toPx() }
    val expandedPx = with(density) { expandedH.toPx() }
    val travel = (expandedPx - peekPx).coerceAtLeast(1f)

    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val isOpen = progress.value > 0.5f
    LaunchedEffect(isOpen) { onOpenChanged(isOpen) }

    fun settle(target: Float) {
        scope.launch {
            progress.animateTo(
                target,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            )
        }
    }

    val offsetY = ((1f - progress.value) * travel).roundToInt()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(expandedH)
                .offset { IntOffset(0, offsetY) }
                .background(Panel, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        ) {
            // —— 手势区（整条都能拖，也能点一下开合）——
            val dragMod = Modifier
                .fillMaxWidth()
                .pointerInput(travel, progress.value) {
                    detectVerticalDragGestures(
                        onDragEnd = { settle(if (progress.value > 0.4f) 1f else 0f) },
                        onDragCancel = { settle(if (progress.value > 0.4f) 1f else 0f) }
                    ) { _, delta ->
                        val next = (progress.value - delta / travel).coerceIn(0f, 1f)
                        scope.launch { progress.snapTo(next) }
                    }
                }
                .clickable { settle(if (progress.value > 0.5f) 0f else 1f) }
                .padding(top = 9.dp, bottom = 8.dp)

            Column(modifier = dragMod) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 44.dp, height = 4.dp)
                        .background(Color(0x66AFC3D4), RoundedCornerShape(50))
                )
                Spacer(Modifier.height(9.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (ui.total > 0) {
                            ui.counts.take(3).joinToString("  ") { "${CocoLabels.zh(it.first)}×${it.second}" }
                        } else {
                            if (isOpen) "下滑收起" else "上滑展开设置"
                        },
                        color = Light,
                        fontSize = 12.5.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${ui.preMs}+${ui.inferenceMs}+${ui.postMs}ms · ${"%.1f".format(ui.fps)}fps",
                        color = Dim,
                        fontSize = 10.5.sp
                    )
                }
            }

            HorizontalDivider(color = Color(0x1AFFFFFF))

            // —— 面板主体 ——
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Spacer(Modifier.height(10.dp))

                // 快捷动作
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip(
                        if (ui.running) "暂停识别" else "开始识别",
                        if (ui.running) Mint else Amber
                    ) { vm.toggleRunning() }
                    ActionChip(if (ui.countMode) "计数 开" else "计数 关", if (ui.countMode) Cyan else Dim) {
                        vm.toggleCount()
                    }
                    ActionChip("翻转", Dim) { vm.setFront(!ui.frontCamera) }
                    if (!ui.frontCamera) {
                        ActionChip(
                            if (ui.torch) "灯 开" else "灯 关",
                            if (ui.torch) Color(0xFFFFD60A) else Dim
                        ) { vm.setTorch(!ui.torch) }
                    }
                }

                /* ── 加速组合拳 ── */
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
                    checked = ui.boost,
                    accent = Amber
                ) { vm.setBoost(it) }

                /* ── 识别对象 ── */
                SectionTitle("识别对象")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ui.selected.isEmpty()) "全部 80 类" else "已选 ${ui.selected.size} 类",
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
                            selected = ui.selected.contains(id),
                            accent = Color(CocoLabels.color(id) or 0xFF000000.toInt())
                        ) { vm.toggleClass(id) }
                    }
                }
                HintText("点类别 = 只识别它；不选任何类别 = 识别全部 80 种")

                Divider()

                /* ── 显示 ── */
                SectionTitle("显示")
                ExplainToggle(
                    title = "计数与统计",
                    value = ui.countMode,
                    detail = "画面左上角显示「共 N 个目标」以及每一类的数量，" +
                        "关闭后只保留检测框，视野更干净。"
                ) { vm.toggleCount() }

                ExplainToggle(
                    title = "类别标签",
                    value = ui.showLabels,
                    detail = "在每个框的上方画出类别名（如「汽车」「人」）。" +
                        "关掉只剩彩色框，适合看密集场景。"
                ) { vm.toggleLabels() }

                ExplainToggle(
                    title = "置信度数值",
                    value = ui.showScore,
                    detail = "标签后面附带百分比，表示模型有多确定。" +
                        "70% 以上通常可信；30~50% 多是误检或遮挡严重的目标。"
                ) { vm.toggleScore() }

                Divider()

                /* ── 推理性能 ── */
                SectionTitle("推理性能")

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Backend.entries.forEach { b ->
                        SmallChip(b.label, ui.backend == b, Cyan) { vm.setBackend(b) }
                    }
                }
                HintText(
                    "XNNPACK：纯 CPU，用 ARM NEON 指令集，最稳，任何机型都能跑。\n" +
                        "NNAPI：走系统神经网络接口，可能调用 GPU/DSP/NPU，速度看厂商驱动。\n" +
                        "CPU：最慢的参考实现，只有在其他两个都崩溃时才用。\n" +
                        "切后端会重新加载模型，约 1~2 秒。"
                )

                Spacer(Modifier.height(10.dp))
                TextButton(
                    onClick = { vm.runBenchmark() },
                    enabled = !ui.benching
                ) {
                    Text(
                        if (ui.benching) "测试中…" else "▶ 跑基准（三个后端各测 15 次）",
                        fontSize = 12.sp,
                        color = Cyan
                    )
                }
                if (ui.benchText != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A1420), RoundedCornerShape(10.dp))
                            .padding(11.dp)
                    ) {
                        Text(
                            ui.benchText,
                            color = Light,
                            fontSize = 11.sp
                        )
                    }
                    HintText("中位数越小越快。挑最快的那个后端，在上面的按钮里手动锁定。")
                }

                Spacer(Modifier.height(6.dp))
                SliderRow("模型输入 ${ui.modelInput}px", ui.modelInput.toFloat(), 320f, 640f) {
                    vm.setModelInput(it.roundToInt())
                }
                HintText(
                    "送进神经网络的图片边长。\n" +
                        "320px：最快，适合只看人和车这种大目标，远处小物体容易漏。\n" +
                        "480px：均衡，日常推荐。\n" +
                        "640px：最准，官方训练尺寸，但计算量是 320 的 4 倍。\n" +
                        "注意：这个值只影响模型输入，不影响屏幕上的画面清晰度。"
                )

                SliderRow("预处理 ${ui.maxSide}px", ui.maxSide.toFloat(), 320f, 960f) {
                    vm.setMaxSide((it / 16).roundToInt() * 16)
                }
                HintText(
                    "相机帧在送进模型之前先缩到这么大，越小人越小越省算力。\n" +
                        "一般设成跟上面的「模型输入」一样大就好，\n" +
                        "设得比它大是浪费（还会被再缩一次），设得太小则画面细节先丢了。"
                )

                SliderRow("线程数 ${ui.threads}", ui.threads.toFloat(), 1f, 8f) {
                    vm.setThreads(it.roundToInt())
                }
                HintText(
                    "同时干活的计算核心数。\n" +
                        "4 是甜点值；调到 8 有时反而慢，因为大核数量有限、线程争抢会掉频。\n" +
                        "调到 1~2 省电但明显变卡。"
                )

                ExplainToggle(
                    title = "相机直出 RGBA",
                    value = ui.useRgba,
                    detail = "开启后相机直接把像素以 RGBA 打包交给 App，跳过 YUV→RGB 转换，省约 20ms/帧。\n\n" +
                        "绝大多数 2019 年后的机器都支持。若开启后预览黑屏或报「相机启动失败」，" +
                        "关掉它会自动回退到 YUV 兼容模式。\n\n" +
                        "当前实际流格式：${ui.frameFormat}"
                ) { vm.setUseRgba(it) }

                Text(
                    "分段耗时：预处理 ${ui.preMs}ms · 推理 ${ui.inferenceMs}ms · 后处理 ${ui.postMs}ms",
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

                /* ── 阈值 ── */
                SectionTitle("阈值")
                SliderRow("置信度 ${"%.2f".format(ui.conf)}", ui.conf, 0.05f, 0.9f) { vm.setConf(it) }
                HintText(
                    "低于这个分数的检测框直接丢掉。\n" +
                        "0.25：宽松，能抓到模糊目标，但误检变多。\n" +
                        "0.35：默认，日常够用。\n" +
                        "0.60 以上：只留非常确定的目标，容易漏检。"
                )

                SliderRow("NMS IoU ${"%.2f".format(ui.iou)}", ui.iou, 0.1f, 0.9f) { vm.setIou(it) }
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
    }
}

/* ───────────────────────── 带说明的控件 ───────────────────────── */

/** 点击整行展开/收起详细说明。 */
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
            Text(
                if (open) "▾" else "▸",
                color = Dim,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 12.5.sp, color = Light)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = value,
                onCheckedChange = { onChange(it) },
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

/** 大卡片形式的开关（给加速组合拳用）。 */
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
                onCheckedChange = { onChange(it) },
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

/** 固定在控件下方的短说明，不用点也能看到。 */
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