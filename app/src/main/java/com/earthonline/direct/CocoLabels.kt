package com.earthonline.direct

/** COCO-80 类别表（YOLO11n 预训练权重顺序，索引 0 起）。 */
object CocoLabels {

    val EN: Array<String> = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
        "toothbrush"
    )

    val ZH: Array<String> = arrayOf(
        "人", "自行车", "汽车", "摩托车", "飞机", "公交车", "火车", "卡车", "船",
        "红绿灯", "消防栓", "停止标志", "停车计时器", "长椅", "鸟", "猫",
        "狗", "马", "羊", "牛", "大象", "熊", "斑马", "长颈鹿", "背包",
        "雨伞", "手提包", "领带", "行李箱", "飞盘", "滑雪板", "单板", "球",
        "风筝", "棒球棒", "棒球手套", "滑板", "冲浪板", "网球拍",
        "瓶子", "酒杯", "杯子", "叉子", "刀", "勺子", "碗", "香蕉", "苹果",
        "三明治", "橘子", "西兰花", "胡萝卜", "热狗", "披萨", "甜甜圈", "蛋糕", "椅子",
        "沙发", "盆栽", "床", "餐桌", "马桶", "电视", "笔记本", "鼠标",
        "遥控器", "键盘", "手机", "微波炉", "烤箱", "烤面包机", "水槽",
        "冰箱", "书", "时钟", "花瓶", "剪刀", "泰迪熊", "吹风机",
        "牙刷"
    )

    fun name(id: Int): String = EN.getOrElse(id) { "cls_$id" }
    fun zh(id: Int): String = ZH.getOrElse(id) { "类别$id" }

    /** 每类的显示颜色（ARGB），按索引取色，保证同一类颜色稳定。 */
    fun color(id: Int): Int {
        val palette = intArrayOf(
            0xFFFF3B30.toInt(), 0xFF34C759.toInt(), 0xFF0A84FF.toInt(), 0xFFFF9F0A.toInt(),
            0xFF5E5CE6.toInt(), 0xFFFFD60A.toInt(), 0xFFFF2D55.toInt(), 0xFF30D158.toInt(),
            0xFF64D2FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF6B6B.toInt(), 0xFF8E8E93.toInt()
        )
        return palette[id % palette.size]
    }

    /** 首页快捷筛选用的常见类别。 */
    val QUICK: List<Int> = listOf(0, 1, 2, 3, 5, 7, 8, 13, 15, 16, 56, 60, 63, 67)
}