package com.example.posewear

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 动态构造 ComfyUI「API 格式」工作流（与桌面版 workflow.py.build() 完全一致）。
 * 除节点 1 外全部是 ComfyUI 内置节点，不需要导入任何工作流文件。
 * 选 *.gguf 基模时节点 1 换成 ComfyUI-GGUF 插件的 UnetLoaderGGUF（只收 unet_name）。
 */
object Workflow {
    private fun link(node: String, port: Int) = JSONArray().put(node).put(port)

    fun build(
        refs: List<String>,                 // ComfyUI input 目录里的文件名，按 <imageN> 顺序
        width: Int,
        height: Int,
        prompt: String,
        negative: String,
        seed: Long,
        steps: Int,
        cfgScale: Double,
        cfg: Cfg,
        prefix: String
    ): Pair<JSONObject, String> {
        require(refs.size in 1..3) { "参考图数量必须是 1~3 张，当前 ${refs.size} 张" }
        val realSeed = if (seed == 0L) Random.nextInt(1, Int.MAX_VALUE).toLong() else seed
        val p = JSONObject()

        // 与桌面版 workflow.build() 一样按 .gguf 后缀切 loader；其余 11 个节点完全一致
        val gguf = Models.isGguf(cfg.unet)
        val loaderInputs = JSONObject().put("unet_name", cfg.unet)
        if (!gguf) loaderInputs.put("weight_dtype", "default")
        p.put("1", JSONObject()
            .put("class_type", if (gguf) "UnetLoaderGGUF" else "UNETLoader")
            .put("inputs", loaderInputs))

        p.put("2", JSONObject()
            .put("class_type", "QwenImage21Cache")
            .put("inputs", JSONObject()
                .put("model", link("1", 0))
                .put("device", "auto")
                .put("dtype", "default")))

        p.put("3", JSONObject()
            .put("class_type", "CLIPLoader")
            .put("inputs", JSONObject()
                .put("clip_name", cfg.clip)
                .put("type", "qwen_image")
                .put("device", "default")))

        p.put("4", JSONObject()
            .put("class_type", "VAELoader")
            .put("inputs", JSONObject().put("vae_name", cfg.vae)))

        // COMFY_AUTOGROW_V3 在 API 格式下用 <组名>.<子项名> 作为键
        val enc = JSONObject()
            .put("clip", link("3", 0))
            .put("vae", link("4", 0))
            .put("prompt", prompt)
            .put("negative_prompt", negative)
            .put("resolution", cfg.refResolution)

        refs.forEachIndexed { i, name ->
            val nodeId = (9 + i + 1).toString()   // 10 / 11 / 12
            p.put(nodeId, JSONObject()
                .put("class_type", "LoadImage")
                .put("inputs", JSONObject().put("image", name)))
            enc.put("images.image_${i + 1}", link(nodeId, 0))
        }

        p.put("20", JSONObject()
            .put("class_type", "TextEncodeQwenImage21")
            .put("inputs", enc))

        // 显式指定目标画幅，取代 TextEncode 自带 latent 输出，保证严格比例
        p.put("21", JSONObject()
            .put("class_type", "EmptyLatentImage")
            .put("inputs", JSONObject()
                .put("width", width)
                .put("height", height)
                .put("batch_size", 1)))

        p.put("30", JSONObject()
            .put("class_type", "KSampler")
            .put("inputs", JSONObject()
                .put("model", link("2", 0))
                .put("seed", realSeed)
                .put("steps", steps)
                .put("cfg", cfgScale)
                .put("sampler_name", "euler")
                .put("scheduler", "simple")
                .put("positive", link("20", 0))
                .put("negative", link("20", 1))
                .put("latent_image", link("21", 0))
                .put("denoise", 1.0)))

        p.put("31", JSONObject()
            .put("class_type", "VAEDecode")
            .put("inputs", JSONObject()
                .put("samples", link("30", 0))
                .put("vae", link("4", 0))))

        p.put("32", JSONObject()
            .put("class_type", "SaveImage")
            .put("inputs", JSONObject()
                .put("images", link("31", 0))
                .put("filename_prefix", prefix)))

        return Pair(p, "32")
    }
}
