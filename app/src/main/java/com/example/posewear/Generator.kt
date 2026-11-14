package com.example.posewear

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RefSpec(val kind: String, val locator: String, val uploadName: String)

data class GenSpec(
    val refs: List<RefSpec>,          // 顺序：人物 → 服装（可选）→ 姿态（可选）
    val prompt: String,
    val negative: String,
    val ratioKey: String,
    val megapixels: Double,
    val seed: Long,
    val steps: Int,
    val cfgScale: Double,
    val cfg: Cfg,
    val engine: String = "qwen",      // 'qwen' 或 'h3'
    val stripBackground: Boolean = false  // 「背景」下拉选到透明背景：生成后抠成透明 PNG
)

data class GenInfo(
    val path: String,
    val width: Int,
    val height: Int,
    val seconds: Long,
    val downgraded: Int,
    val promptId: String,
    val mode: String
)

/** 桌面版 GenerateWorker.run() 的 Kotlin 移植：上传 → 排队 → 轮询 → 取图 → 落盘 → OOM 降档。 */
object Generator {
    private fun stage(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { Store.setStatus(msg) }
    }

    private fun tick(msg: String) = stage(msg)

    fun outputDir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir
        return File(base, "PoseWear").apply { if (!exists()) mkdirs() }
    }

    suspend fun run(ctx: Context, spec: GenSpec, abort: AbortFlag): GenInfo =
        withContext(Dispatchers.IO) {
            require(spec.refs.isNotEmpty()) { "缺少人物照片，请先在「1.人物照片」中选择图片。" }
            val base = spec.cfg.baseUrl()
            val timeout = spec.cfg.requestTimeout
            val tag = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val mode = if (spec.refs.size > 1)
                spec.refs.drop(1).joinToString(" + ") { it.kind } else "仅人物原图"
            val engineName = if (spec.engine == "h3") "MiniMax-H3-Image" else "Qwen-Image 2.1"
            stage("生成模式：${mode}（${spec.refs.size} 张参考图 · $engineName）")

            // 上传只做一次，降档重试时复用
            stage("上传参考图…")
            val uploaded = ArrayList<String>()
            for (r in spec.refs) {
                if (abort.aborted) throw ComfyException("已取消")
                val png = Images.pngBytes(ctx, r.locator)
                uploaded += ComfyClient.upload(base, png, "${r.uploadName}_${tag}.png", timeout)
            }

            val tiers = Resolution.tierSteps(spec.megapixels, spec.engine)
            var lastErr: String? = null
            for ((idx, mp) in tiers.withIndex()) {
                if (abort.aborted) throw ComfyException("已取消")
                val (w, h) = Resolution.calc(spec.ratioKey, mp)
                stage(if (idx > 0) "显存不足，自动降至 ${w}×${h} 重试（${idx + 1}/${tiers.size}）"
                      else "目标 ${w}×${h} · ${"%.2f".format(w * h / 1_000_000.0)} MP")

                try {
                    val (flow, outId) = if (spec.engine == "h3") {
                        H3Workflow.build(
                            refs = uploaded,
                            width = w, height = h,
                            prompt = spec.prompt,
                            steps = spec.steps, cfgScale = spec.cfgScale,
                            sampler = "euler", scheduler = "simple",
                            unet = spec.cfg.unet, clip = spec.cfg.clip, vae = spec.cfg.vae,
                            prefix = "PoseWearStudio/pw_${tag}",
                            stripBackground = spec.stripBackground)
                    } else {
                        Workflow.build(
                            refs = uploaded,
                            width = w, height = h,
                            prompt = spec.prompt, negative = spec.negative,
                            seed = spec.seed, steps = spec.steps, cfgScale = spec.cfgScale,
                            cfg = spec.cfg,
                            prefix = "PoseWearStudio/pw_${tag}",
                            stripBackground = spec.stripBackground)
                    }
                    stage("已提交，正在生成…")
                    val t0 = System.currentTimeMillis()
                    val pid = ComfyClient.queue(base, flow, timeout)
                    val entry = ComfyClient.waitHistory(
                        base, pid, spec.cfg.generationTimeout, abort
                    ) { sec -> tick("生成中 · 已用时 ${sec} 秒") }
                    val img = ComfyClient.images(entry, outId)
                        ?: throw ComfyException("ComfyUI 未返回图片")
                    val bytes = ComfyClient.fetchImage(base, img, timeout)
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val file = File(outputDir(ctx), "pw_${stamp}_${w}x${h}.png")
                    file.writeBytes(bytes)
                    return@withContext GenInfo(
                        path = file.absolutePath, width = w, height = h,
                        seconds = (System.currentTimeMillis() - t0) / 1000,
                        downgraded = idx, promptId = pid, mode = mode
                    )
                } catch (e: ComfyException) {
                    if (abort.aborted) throw e
                    if (isOomText(e.message ?: "")) {
                        lastErr = e.message; tick("显存不足，尝试降低分辨率…"); continue
                    }
                    throw e
                } catch (e: OomException) {
                    if (abort.aborted) throw ComfyException("已取消")
                    lastErr = e.message; tick("显存不足，尝试降低分辨率…"); continue
                } catch (e: Exception) {
                    if (abort.aborted) throw ComfyException("已取消")
                    if (isOomText(e.message ?: "")) {
                        lastErr = e.message; tick("显存不足，尝试降低分辨率…"); continue
                    }
                    throw e
                }
            }
            throw ComfyException("显存不足，已尝试全部档位仍失败。${lastErr ?: ""}")
        }

    private fun isOomText(s: String): Boolean {
        val l = s.lowercase()
        return l.contains("out of memory") || l.contains("cuda oom") || l.contains("allocation on device")
    }
}
