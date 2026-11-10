package com.example.posewear

import android.content.Context

/**
 * 全部需要持久化的设置。与桌面版 config.py / config.json 一一对应。
 * 保存到 SharedPreferences（本机），App.onCreate 里自动装载。
 */
data class Cfg(
    // 远程 ComfyUI
    var host: String = "192.168.1.100",
    var port: Int = 8188,
    // 手机本地素材库目录：绝对路径或 SAF content:// tree uri
    var costumeDir: String = "",
    var poseDir: String = "",
    // 生成参数
    var ratio: String = "3:4",
    var megapixels: Double = 2.4,
    var steps: Int = 25,
    var cfgScale: Double = 1.0,
    var seed: Long = 0L,
    var refResolution: Int = 1024,
    var generationTimeout: Int = 1200,
    var requestTimeout: Int = 20,
    // ComfyUI 侧模型文件名
    var unet: String = "qwen-image-2.1-UC-int8_convrot.safetensors",
    var clip: String = "qwen_image_2.1\\qwen3-vl-8b-heretic-1.3.0-int8convrot.safetensors",
    var vae: String = "qwen_image_2.1_vae_bf16.safetensors",
    // 界面选择状态（下次启动一并恢复）
    var personPath: String = "",
    var costumeId: String = "",
    var poseId: String = "",
    var keepCostume: Boolean = false,
    var nude: Boolean = false,
    var keepPose: Boolean = false,
    var keepBackground: Boolean = false,
    var lastResult: String = ""
) {
    /** host 允许直接填完整 URL，也允许只填 IP / 域名。 */
    fun baseUrl(): String {
        val h = host.trim()
        return when {
            h.startsWith("http://", true) || h.startsWith("https://", true) -> h.trimEnd('/')
            h.contains(":") && !h.startsWith("[") -> "http://${h}"
            else -> "http://${h}:${port}"
        }
    }
}

object Settings {
    private const val PREF = "posewear_settings"

    fun defaults() = Cfg()

    fun load(ctx: Context): Cfg {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val d = Cfg()
        return Cfg(
            host = p.getString("host", d.host) ?: d.host,
            port = p.getInt("port", d.port),
            costumeDir = p.getString("costumeDir", d.costumeDir) ?: d.costumeDir,
            poseDir = p.getString("poseDir", d.poseDir) ?: d.poseDir,
            ratio = p.getString("ratio", d.ratio) ?: d.ratio,
            megapixels = p.getFloat("megapixels", d.megapixels.toFloat()).toDouble(),
            steps = p.getInt("steps", d.steps),
            cfgScale = p.getFloat("cfgScale", d.cfgScale.toFloat()).toDouble(),
            seed = p.getLong("seed", d.seed),
            refResolution = p.getInt("refResolution", d.refResolution),
            generationTimeout = p.getInt("generationTimeout", d.generationTimeout),
            requestTimeout = p.getInt("requestTimeout", d.requestTimeout),
            unet = p.getString("unet", d.unet) ?: d.unet,
            clip = p.getString("clip", d.clip) ?: d.clip,
            vae = p.getString("vae", d.vae) ?: d.vae,
            personPath = p.getString("personPath", d.personPath) ?: d.personPath,
            costumeId = p.getString("costumeId", d.costumeId) ?: d.costumeId,
            poseId = p.getString("poseId", d.poseId) ?: d.poseId,
            keepCostume = p.getBoolean("keepCostume", d.keepCostume),
            nude = p.getBoolean("nude", d.nude),
            keepPose = p.getBoolean("keepPose", d.keepPose),
            keepBackground = p.getBoolean("keepBackground", d.keepBackground),
            lastResult = p.getString("lastResult", d.lastResult) ?: d.lastResult
        )
    }

    fun save(ctx: Context, c: Cfg) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("host", c.host)
            .putInt("port", c.port)
            .putString("costumeDir", c.costumeDir)
            .putString("poseDir", c.poseDir)
            .putString("ratio", c.ratio)
            .putFloat("megapixels", c.megapixels.toFloat())
            .putInt("steps", c.steps)
            .putFloat("cfgScale", c.cfgScale.toFloat())
            .putLong("seed", c.seed)
            .putInt("refResolution", c.refResolution)
            .putInt("generationTimeout", c.generationTimeout)
            .putInt("requestTimeout", c.requestTimeout)
            .putString("unet", c.unet)
            .putString("clip", c.clip)
            .putString("vae", c.vae)
            .putString("personPath", c.personPath)
            .putString("costumeId", c.costumeId)
            .putString("poseId", c.poseId)
            .putBoolean("keepCostume", c.keepCostume)
            .putBoolean("nude", c.nude)
            .putBoolean("keepPose", c.keepPose)
            .putBoolean("keepBackground", c.keepBackground)
            .putString("lastResult", c.lastResult)
            .apply()
    }
}
