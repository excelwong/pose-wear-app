package com.example.posewear

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.util.Locale

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

    // 与桌面版 resolution.py 的 TIERS 逐字一致：全角冒号、数值与 MP 之间无空格。
    // 新增 H3：20/18/16MP 三档走 MiniMax-H3-Image；其余 7 档走 Qwen-Image 2.1。
    val TIERS: LinkedHashMap<String, Double> = linkedMapOf(
        "H3：20MP" to 20.0,
        "H3：18MP" to 18.0,
        "H3：16MP" to 16.0,
        "极限：9.4MP(4K)" to 9.4,
        "超清：7.2MP" to 7.2,
        "高清：5.3MP" to 5.3,
        "标准：2.4MP" to 2.4,
        "快速：1.5MP" to 1.5,
        "低显存：1MP" to 1.0,
        "低显存：0.6MP" to 0.6
    )

    // H3 档位的最小像素预算；≥ 此值的档位走 MiniMax-H3-Image，其余走 Qwen-Image 2.1。
    const val H3_MIN_MP = 16.0
    val H3_TIERS = TIERS.filter { it.value >= H3_MIN_MP }.keys
    val QWEN_TIERS = TIERS.filter { it.value < H3_MIN_MP }.keys

    /** 按像素预算判定引擎名：'h3' 或 'qwen'。 */
    fun engineFor(megapixels: Double): String =
        if (megapixels >= H3_MIN_MP) "h3" else "qwen"

    /** 按档位名判定引擎名；未知档位按像素兜底为 qwen。 */
    fun engineForTier(name: String): String {
        val mp = TIERS[name]
        return if (mp == null) engineFor(0.0) else engineFor(mp)
    }

    fun calc(ratioKey: String, megapixels: Double): Pair<Int, Int> {
        val ab = RATIOS[ratioKey] ?: RATIOS["2:3"]!!
        val a = ab.first.toDouble()
        val b = ab.second.toDouble()
        var u = sqrt(megapixels * 1_000_000.0 / (a * b * UNIT * UNIT)).roundToInt()
        if (u < 1) u = 1
        return Pair((a * UNIT * u).toInt(), (b * UNIT * u).toInt())
    }

    /**
     * 当前档位（含）逐档降到底，用于显存不足自动重试。
     * engine 不传时保持旧行为（所有不高于当前预算的档位）。
     * 传 'h3' 时只在 20/18/16 MP 三档内降，传 'qwen' 时排除 H3 档——
     * 避免 OOM 重试时把模型和提示词语种悄悄换掉。
     */
    fun tierSteps(megapixels: Double, engine: String? = null): List<Double> {
        var list = TIERS.values.filter { it <= megapixels + 1e-6 }
        if (engine == "h3") list = list.filter { it >= H3_MIN_MP }
        else if (engine == "qwen") list = list.filter { it < H3_MIN_MP }
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

    /** 档位切换时的提示文案（引擎名称 + 官方有效范围）。 */
    fun engineHint(megapixels: Double): String {
        val eng = engineFor(megapixels)
        return if (eng == "h3") "MiniMax-H3-Image（6 步 / CFG 1）" else "Qwen-Image 2.1"
    }

    /** 最接近的画幅比例：返回 (比例键, 相对偏差%)；宽高非法时返回 null。
     *  与桌面版 resolution.nearest_ratio 一致：距离取 |ln(r / t)|，横竖幅对称。 */
    fun nearestRatio(w: Int, h: Int): Pair<String, Double>? {
        if (w <= 0 || h <= 0) return null
        val r = w.toDouble() / h
        var bestKey: String? = null
        var bestDist = Double.POSITIVE_INFINITY
        for ((key, ab) in RATIOS) {
            val t = ab.first.toDouble() / ab.second
            val d = abs(ln(r / t))
            if (d < bestDist - 1e-12) {
                bestKey = key
                bestDist = d
            }
        }
        val k = bestKey ?: return null
        val ab = RATIOS[k]!!
        val dev = abs(r / (ab.first.toDouble() / ab.second) - 1.0) * 100.0
        return Pair(k, dev)
    }

    /** 人物照片下方的两行说明，与桌面版 resolution.photo_info_text 逐字一致。 */
    fun photoInfoText(w: Int, h: Int): String {
        val near = nearestRatio(w, h)
            ?: return "分辨率读取失败，无法显示画幅比例。"
        val (key, dev) = near
        val mp = w.toDouble() * h / 1_000_000.0
        val r = w.toDouble() / h
        return String.format(
            Locale.ROOT,
            "分辨率 %d × %d（%.1f MP）· 宽高比 %.3f\n最接近画幅比例 %s（偏差 %.1f%%）· 建议把输出画幅比例设为 %s",
            w, h, mp, r, key, dev, key
        )
    }
}
