package com.example.posewear

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 与桌面版 resolution.py 完全一致：w = a*32*u, h = b*32*u；7 档「名称：数值MP」。 */
object Resolution {
    val RATIOS: LinkedHashMap<String, Pair<Int, Int>> = linkedMapOf(
        "1:1" to (1 to 1),
        "2:3" to (2 to 3),
        "3:4" to (3 to 4),
        "4:5" to (4 to 5),
        "9:16" to (9 to 16),
        "4:3" to (4 to 3),
        "3:2" to (3 to 2),
        "16:9" to (16 to 9),
        "21:9" to (21 to 9)
    )

    const val UNIT = 32

    // 与桌面版 TIERS 的键逐字一致：全角冒号、数值与 MP 之间无空格。
    // 末尾两档面向 6 GB 显存的电脑（2:3 下约 832x1248 / 640x960）。
    val TIERS: LinkedHashMap<String, Double> = linkedMapOf(
        "极限：9.4MP(4K)" to 9.4,
        "超清：7.2MP" to 7.2,
        "高清：5.3MP" to 5.3,
        "标准：2.4MP" to 2.4,
        "快速：1.5MP" to 1.5,
        "低显存：1MP" to 1.0,
        "低显存：0.6MP" to 0.6
    )

    fun calc(ratioKey: String, megapixels: Double): Pair<Int, Int> {
        val ab = RATIOS[ratioKey] ?: RATIOS["2:3"]!!
        val a = ab.first.toDouble()
        val b = ab.second.toDouble()
        var u = sqrt(megapixels * 1_000_000.0 / (a * b * UNIT * UNIT)).roundToInt()
        if (u < 1) u = 1
        return Pair((a * UNIT * u).toInt(), (b * UNIT * u).toInt())
    }

    /** 当前档位（含）逐档降到底，用于显存不足自动重试。 */
    fun tierSteps(megapixels: Double): List<Double> {
        val list = TIERS.values.filter { it <= megapixels + 1e-6 }
        return list.ifEmpty { listOf(megapixels) }
    }

    fun tierIndex(megapixels: Double): Int {
        var idx = 0
        TIERS.values.forEachIndexed { i, v ->
            if (abs(v - megapixels) < 1e-6) idx = i
        }
        return idx
    }

    fun tierName(megapixels: Double): String =
        TIERS.entries.firstOrNull { abs(it.value - megapixels) < 1e-6 }?.key
            ?: TIERS.keys.first()

    fun tierValue(name: String): Double? = TIERS[name]

    /** 与桌面版 _update_res_label() 一致：备注只显示最终宽高，如 1280:1920。 */
    fun resolutionText(ratioKey: String, megapixels: Double): String {
        val (w, h) = calc(ratioKey, megapixels)
        return "$w:$h"
    }

    /** 参数页用：把档位名与当前比例下的尺寸一起说清楚。 */
    fun tierDetail(ratioKey: String, megapixels: Double): String =
        "${tierName(megapixels)} · 当前 $ratioKey → ${resolutionText(ratioKey, megapixels)}"
}
