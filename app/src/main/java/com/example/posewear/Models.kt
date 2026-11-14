package com.example.posewear

/**
 * 与桌面版 config.py 的 UNET_MODELS / CLIP_MODELS / VAE_MODELS / UNET_DEFAULTS 逐字对应。
 *
 * label = 界面显示的纯文件名，value = 传给 ComfyUI 的 unet_name / clip_name / vae_name。
 * 三个 text encoder 实际都在 models/text_encoders/qwen_image_2.1/ 子目录，
 * 电脑端是 Windows 版 ComfyUI，所以 value 用反斜杠（与 /object_info 列表逐字一致）。
 */
/** MiniMax-H3-Image 三件套（与桌面版 config.py 一致）。 */
val H3_UNET = "minimax_h3_image_pruned_int8_convrot.safetensors"
val H3_CLIP = "minimax_h3\\qwen3vl_32b_minimax_h3_int8_convrot.safetensors"
val H3_VAE = "minimax_h3_image_vae_fp16.safetensors"
val H3_STEPS = 6
val H3_CFG = 1.0

object Models {
    data class Choice(val label: String, val value: String)

    val UNET: List<Choice> = listOf(
        Choice("qwen-image-2.1-UC-int8_convrot.safetensors",
            "qwen-image-2.1-UC-int8_convrot.safetensors"),
        Choice("qwen-image-2.1-UC-Q4_K_M.gguf",
            "qwen-image-2.1-UC-Q4_K_M.gguf"),
        Choice("qwen-image-2.1-turbo-Q4_K_M.gguf",
            "qwen-image-2.1-turbo-Q4_K_M.gguf"),
        Choice("[H3] " + H3_UNET, H3_UNET)
    )

    val CLIP: List<Choice> = listOf(
        Choice("qwen3-vl-8b-heretic-1.3.0-int8convrot.safetensors",
            "qwen_image_2.1\\qwen3-vl-8b-heretic-1.3.0-int8convrot.safetensors"),
        Choice("qwen3vl_8b_int8_convrot.safetensors",
            "qwen_image_2.1\\qwen3vl_8b_int8_convrot.safetensors"),
        Choice("qwen3vl_8b_w4a8.safetensors",
            "qwen_image_2.1\\qwen3vl_8b_w4a8.safetensors"),
        Choice("[H3] qwen3vl_32b_minimax_h3_int8_convrot.safetensors", H3_CLIP)
    )

    val VAE: List<Choice> = listOf(
        Choice("qwen_image_2.1_vae_bf16.safetensors",
            "qwen_image_2.1_vae_bf16.safetensors"),
        Choice("[H3] minimax_h3_image_vae_fp16.safetensors", H3_VAE)
    )

    /** 切换基模时的推荐组合：text encoder 显示名 + 步数 + 分辨率档位名。 */
    data class Preset(val clipLabel: String, val steps: Int, val tier: String, val vaeLabel: String = "qwen_image_2.1_vae_bf16.safetensors", val cfgValue: Double = 1.0)

    val DEFAULTS: Map<String, Preset> = mapOf(
        "qwen-image-2.1-UC-int8_convrot.safetensors" to
            Preset("qwen3-vl-8b-heretic-1.3.0-int8convrot.safetensors", 25, "标准：2.4MP"),
        "qwen-image-2.1-UC-Q4_K_M.gguf" to
            Preset("qwen3vl_8b_int8_convrot.safetensors", 25, "低显存：1MP"),
        "qwen-image-2.1-turbo-Q4_K_M.gguf" to
            Preset("qwen3vl_8b_w4a8.safetensors", 8, "低显存：0.6MP"),
        H3_UNET to
            Preset("[H3] qwen3vl_32b_minimax_h3_int8_convrot.safetensors", H3_STEPS, "H3：16MP",
                  "[H3] minimax_h3_image_vae_fp16.safetensors", H3_CFG)
    )

    /** GGUF 量化基模必须走 ComfyUI-GGUF 插件的 UnetLoaderGGUF（无 weight_dtype 输入）。 */
    fun isGguf(unet: String): Boolean = unet.trim().lowercase().endsWith(".gguf")

    /** 把 ComfyUI 侧 value 反查成界面 label；清单外的自定义值原样返回。 */
    fun valueToLabel(list: List<Choice>, value: String): String =
        list.firstOrNull { it.value == value }?.label ?: value

    /** 把界面 label 还原成传给 ComfyUI 的 value；清单外原样返回。 */
    fun labelToValue(list: List<Choice>, label: String): String =
        list.firstOrNull { it.label == label }?.value ?: label

    /** Spinner 选项：清单内 label + 清单外自定义值（追加到末尾，不丢用户旧配置）。 */
    fun labels(list: List<Choice>, currentValue: String): List<String> {
        val out = list.map { it.label }.toMutableList()
        val cur = valueToLabel(list, currentValue)
        if (cur !in out) out.add(cur)
        return out
    }

    /** 选中项下标：清单内按 value 匹配，清单外指向末尾的自定义项。 */
    fun indexOfValue(list: List<Choice>, value: String): Int {
        val i = list.indexOfFirst { it.value == value }
        return if (i >= 0) i else list.size
    }

    /** 推荐组合里的 encoder 转成 ComfyUI 侧 value。 */
    fun clipValue(preset: Preset): String = labelToValue(CLIP, preset.clipLabel)

    /** 清单外的自定义基模不联动，保留用户手工填的值。 */
    fun presetFor(unetValue: String): Preset? = DEFAULTS[unetValue]
}
