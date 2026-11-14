package com.example.posewear

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * MiniMax-H3-Image 的 ComfyUI API 工作流（与桌面版 h3_workflow.py 一致）。
 * 6 步、CFG=1、无负面提示词（用 ConditioningZeroOut(positive) 接 KSampler.negative）。
 */
object H3Workflow {
    private fun link(node: String, port: Int) = JSONArray().put(node).put(port)

    fun build(refs: List<String>, width: Int, height: Int, prompt: String,
             steps: Int, cfgScale: Double, sampler: String, scheduler: String,
             unet: String, clip: String, vae: String, prefix: String,
             stripBackground: Boolean = false): Pair<JSONObject, String> {
        require(refs.size in 1..3) { "参考图数量必须是 1~3 张，当前 ${refs.size} 张" }
        val seed = Random.nextInt(1, Int.MAX_VALUE).toLong()
        val p = JSONObject()
        p.put("1", JSONObject()
            .put("class_type", "UNETLoader")
            .put("inputs", JSONObject().put("unet_name", unet).put("weight_dtype", "default")))
        p.put("3", JSONObject()
            .put("class_type", "CLIPLoader")
            .put("inputs", JSONObject()
                .put("clip_name", clip)
                .put("type", "minimax")
                .put("device", "default")))
        p.put("4", JSONObject()
            .put("class_type", "VAELoader")
            .put("inputs", JSONObject().put("vae_name", vae)))
        val cond = JSONObject()
            .put("clip", link("3", 0))
            .put("vae", link("4", 0))
            .put("prompt", prompt)
            .put("width", width)
            .put("height", height)
        refs.forEachIndexed { i, name ->
            val nodeId = (9 + i + 1).toString()
            p.put(nodeId, JSONObject()
                .put("class_type", "LoadImage")
                .put("inputs", JSONObject().put("image", name)))
            cond.put("images.image_${i + 1}", link(nodeId, 0))
        }
        p.put("20", JSONObject()
            .put("class_type", "MiniMaxH3ImageConditioning")
            .put("inputs", cond))
        p.put("33", JSONObject()
            .put("class_type", "ConditioningZeroOut")
            .put("inputs", JSONObject().put("conditioning", link("20", 0))))
        p.put("30", JSONObject()
            .put("class_type", "KSampler")
            .put("inputs", JSONObject()
                .put("model", link("1", 0))
                .put("seed", seed)
                .put("steps", steps)
                .put("cfg", cfgScale)
                .put("sampler_name", sampler)
                .put("scheduler", scheduler)
                .put("positive", link("20", 0))
                .put("negative", link("33", 0))
                .put("latent_image", link("20", 1))
                .put("denoise", 1.0)))
        p.put("31", JSONObject()
            .put("class_type", "VAEDecode")
            .put("inputs", JSONObject()
                .put("samples", link("30", 0))
                .put("vae", link("4", 0))))
        // 「背景」下拉选到透明背景：生成图先经 BiRefNet 抠成 RGBA，落盘即透明 PNG
        var outImage = link("31", 0)
        if (stripBackground) {
            // 统一先过 SplitImageWithAlpha：4 通道取 RGB，3 通道原样返回
            p.put("41", JSONObject()
                .put("class_type", "SplitImageWithAlpha")
                .put("inputs", JSONObject().put("image", outImage)))
            p.put("40", JSONObject()
                .put("class_type", "BiRefNetRMBG")
                .put("inputs", Rmbg.inputs(link("41", 0))))
            outImage = link("40", 0)
        }
        p.put("32", JSONObject()
            .put("class_type", "SaveImage")
            .put("inputs", JSONObject()
                .put("images", outImage)
                .put("filename_prefix", prefix)))
        return Pair(p, "32")
    }
}
