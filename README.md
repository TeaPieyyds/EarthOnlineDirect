# 地球online直装

> 拿手机当眼睛，实时识别地球上的常见东西。

基于 **YOLO11n** + **CameraX** + **ONNX Runtime** 的安卓实时物体识别器。模型用 COCO-80 预训练权重，开箱即识别人、车、猫狗、杯子等 80 类常见物体，不需要自己训练。

---

## 效果

- 相机预览上直接画检测框，带类别中文名和置信度百分比
- 顶部 HUD 显示目标总数、每类数量、推理耗时、实时帧率
- 底部可下滑/上滑的抽屉控制台，不遮挡画面
- 每个选项点开都有中文说明，写着这个开关到底在干什么

---

## 功能

### 识别

| 项 | 说明 |
|---|---|
| 模型 | yolo11n（COCO-80），动态输入尺寸 320～640 |
| 类别筛选 | 80 类全部可勾选，另有「常用」一键选人/车/动物等 14 类 |
| 置信度阈值 | 0.05～0.90 可调 |
| NMS IoU | 0.10～0.90 可调，控制同类别框去重力度 |
| 前后摄像头 | 支持切换，前置自动水平镜像 |
| 手电筒 | 后置摄像头时可用 |

### 加速

| 手段 | 效果 |
|---|---|
| **加速组合拳**（总开关） | 一次开满：RGBA 直出 + 416 输入 + NNAPI fp16 + 4 线程 |
| 相机 RGBA 直出 | 跳过每帧 YUV→RGB 换算，省约 20ms |
| 模型输入尺寸 | 320/352/…/640，32 的倍数，越小越快 |
| 预处理分辨率 | 相机帧送模型前先缩放，独立可调 |
| 推理后端 | XNNPACK（CPU，稳）/ NNAPI（可能走 GPU/DSP/NPU）/ CPU（参考实现） |
| 线程数 | 1～8，4 是甜点值 |
| 基准测试 | 三个后端各跑 15 次取中位数，用数据挑最快的，不靠猜 |

### 显示

- 检测框：四角加粗描边 + 半透明填充，按类别自动配色
- 类别标签、置信度百分比可分别关闭
- 计数与统计面板可整体关闭，只看画面

---

## 构建

### GitHub Actions（推荐）

推到 `main` 分支自动触发，或手动在 Actions 页面点 **Run workflow**。

产物在 Actions 运行详情页底部的 **Artifacts → EarthOnlineDirect-debug**。

### 本地

```bash
# 需要 JDK 17 + Android SDK (compileSdk 36)
./gradlew :app:assembleDebug
```

APK 位置：`app/build/outputs/apk/debug/app-debug.apk`

> ARM64 的 proot / Termux 环境需要额外加 `-PforceArm64Aapt2=true` 让 Gradle 换用 aarch64 版 aapt2。

---

## 项目结构

```
app/src/main/
├── java/com/earthonline/direct/
│   ├── MainActivity.kt        入口，相机权限申请
│   ├── AppScreen.kt           全部 UI：HUD、抽屉、带说明的开关
│   ├── MainViewModel.kt       状态管理、加速开关、基准测试调度
│   ├── CameraController.kt    CameraX 绑定，RGBA / YUV 双模式
│   ├── YoloAnalyzer.kt        ImageProxy → ARGB → 旋转缩放 → 推理
│   ├── YoloDetector.kt        ONNX Runtime 封装，前后处理 + NMS
│   ├── DetectionOverlay.kt    Canvas 画框与标签
│   ├── CocoLabels.kt          80 类中英文表与配色
│   ├── FrameBuffer.kt         帧与检测结果的数据类
│   └── Theme.kt               Material3 深色主题
├── assets/
│   └── yolo11n.onnx           10.9 MB，opset 21
└── res/values/
    ├── strings.xml            应用名「地球online直装」
    └── themes.xml             无 ActionBar 主题
```

---

## 技术细节

### 模型

```
输入   images  : float32 [1, 3, H, W]   动态 H/W，默认填 480×480
输出   output0 : float32 [1, 84, N]     84 = 4(box) + 80(class)，无 objectness
```

- 来源：Ultralytics 官方 `yolo11n.onnx`（Release v8.3.0）
- 原模型 opset 22，ONNX Runtime 官方支持到 21，已原地把 `opset_import.version` 改成 21（两者都是单字节 varint，不改文件长度）
- 坐标是输入张量像素空间，后处理按 letterbox 参数逆变换回归一化 0～1

### 前处理

- 最近邻采样（不是双线性），比双线性快 3 倍以上，对降采样场景精度无差别
- letterbox 保持长宽比，灰边填充 114/255
- 采样映射表 `xMap[]`/`yMap[]` 按输入尺寸缓存，尺寸不变不重算
- CHW 布局，RGB 顺序，归一化到 0～1

### 后处理

- 逐 anchor 取 80 类最大分数，低于阈值直接丢
- 类别筛选在取最大值之后做，避免无谓计算
- 按类别分别做 NMS，IoU 超阈值抑制
- 最多保留 100 个框

### 相机流

- `PreviewView` 用 `IMPLEMENTATION_MODE_PERFORMANCE`（SurfaceView），省 EGL 纹理内存
- `ImageAnalysis` 用 `STRATEGY_KEEP_ONLY_LATEST` + 单线程 executor，上一帧没算完就丢新帧
- RGBA 模式下 `plane[0]` 是打包像素，直接按 `rowStride`/`pixelStride` 读；YUV 模式自己算 BT.601
- 旋转和缩放合并在一次采样循环里做，不产生中间整幅图

### 内存

实测 230MB PSS（识别运行中），大头是 ONNX Runtime 的 Native Heap（约 83MB），Compose 本体只占 15～20MB。

---

## 许可

PolyForm Noncommercial License 1.0.0

模型权重来自 [Ultralytics](https://github.com/ultralytics/ultralytics)，遵循 AGPL-3.0。