package com.example.posewear

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

class ComfyException(msg: String) : Exception(msg)
class OomException(msg: String) : Exception(msg)

/**
 * 桌面版 comfy_client.py 的 Android 移植：7 个 HTTP 接口。
 * 强制 Proxy.NO_PROXY，等价于桌面版 requests.Session(trust_env=False)，避免系统代理拦截局域网请求。
 */
object ComfyClient {
    val clientId: String = UUID.randomUUID().toString()

    private fun open(url: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val c = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        return c
    }

    private fun readAll(c: HttpURLConnection): String = try {
        c.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        ""
    }

    private fun readError(c: HttpURLConnection): String = try {
        c.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
    } catch (e: Exception) {
        ""
    }

    /** GET /system_stats —— 设置页「测试连接」用。 */
    fun systemStats(base: String, timeoutSec: Int): JSONObject {
        val c = open("${base}/system_stats", timeoutSec * 1000, timeoutSec * 1000)
        c.requestMethod = "GET"
        try {
            val code = c.responseCode
            if (code !in 200..299) throw ComfyException("HTTP ${code} ${readError(c)}")
            return JSONObject(readAll(c))
        } finally { c.disconnect() }
    }

    /** POST /upload/image (multipart/form-data) —— 返回 ComfyUI input 目录里的文件名。 */
    fun upload(base: String, png: ByteArray, name: String, timeoutSec: Int): String {
        val boundary = "----PWS${System.currentTimeMillis()}"
        val bos = ByteArrayOutputStream()
        fun textPart(field: String, value: String) {
            bos.write("--${boundary}\r\n".toByteArray(StandardCharsets.UTF_8))
            bos.write("Content-Disposition: form-data; name=\"${field}\"\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            bos.write(value.toByteArray(StandardCharsets.UTF_8))
            bos.write("\r\n".toByteArray(StandardCharsets.UTF_8))
        }
        bos.write("--${boundary}\r\n".toByteArray(StandardCharsets.UTF_8))
        bos.write("Content-Disposition: form-data; name=\"image\"; filename=\"${name}\"\r\n".toByteArray(StandardCharsets.UTF_8))
        bos.write("Content-Type: image/png\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        bos.write(png)
        bos.write("\r\n".toByteArray(StandardCharsets.UTF_8))
        textPart("overwrite", "true")
        textPart("type", "input")
        bos.write("--${boundary}--\r\n".toByteArray(StandardCharsets.UTF_8))
        val body = bos.toByteArray()

        val c = open("${base}/upload/image", timeoutSec * 1000, timeoutSec * 1000)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=${boundary}")
        c.setFixedLengthStreamingMode(body.size)
        try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) throw ComfyException("上传失败 HTTP ${code} ${readError(c)}")
            val info = JSONObject(readAll(c))
            return info.optString("name", name)
        } finally { c.disconnect() }
    }

    /** POST /prompt —— 返回 prompt_id。 */
    fun queue(base: String, flow: JSONObject, timeoutSec: Int): String {
        val body = JSONObject()
            .put("prompt", flow)
            .put("client_id", clientId)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val c = open("${base}/prompt", timeoutSec * 1000, timeoutSec * 1000)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setFixedLengthStreamingMode(body.size)
        try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = if (code in 200..299) readAll(c) else readError(c)
            if (code !in 200..299) throw ComfyException("工作流提交失败 (HTTP ${code}): ${text.take(2000)}")
            val data = JSONObject(text)
            if (data.has("node_errors") && data.getJSONObject("node_errors").length() > 0)
                throw ComfyException("节点校验失败: ${data.getJSONObject("node_errors")}")
            return data.optString("prompt_id")
        } finally { c.disconnect() }
    }

    /** 轮询 GET /history/{prompt_id}。 */
    fun waitHistory(
        base: String, promptId: String, timeoutSec: Int,
        abort: AbortFlag, onTick: (Long) -> Unit
    ): JSONObject {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutSec * 1000L
        while (true) {
            if (abort.aborted) throw ComfyException("已取消")
            if (System.currentTimeMillis() > deadline) throw ComfyException("生成超时（${timeoutSec} 秒）")
            var text = ""
            try {
                val c = open("${base}/history/${URLEncoder.encode(promptId, "UTF-8")}", 8000, 8000)
                c.requestMethod = "GET"
                try {
                    if (c.responseCode in 200..299) text = readAll(c)
                } finally { c.disconnect() }
            } catch (e: ComfyException) { throw e }
              catch (e: Exception) { /* 网络抖动继续轮询 */ }
            if (text.isNotBlank()) {
                try {
                    val h = JSONObject(text)
                    if (h.has(promptId)) {
                        val entry = h.getJSONObject(promptId)
                        val status = entry.optJSONObject("status")
                        val completed = status?.optBoolean("completed") ?: false
                        val statusStr = status?.optString("status_str") ?: ""
                        val outputs = entry.optJSONObject("outputs")
                        if (completed || statusStr == "error" || (outputs != null && outputs.length() > 0)) {
                            checkError(entry)
                            if (outputs != null && outputs.length() > 0) return entry
                        }
                    }
                } catch (e: ComfyException) { throw e }
                  catch (e: OomException) { throw e }
                  catch (e: Exception) { /* JSON 尚未写完，继续等 */ }
            }
            onTick((System.currentTimeMillis() - start) / 1000)
            Thread.sleep(800)
        }
    }

    private fun checkError(entry: JSONObject) {
        val status = entry.optJSONObject("status") ?: return
        val msgs = status.optJSONArray("messages") ?: return
        val buf = StringBuilder()
        for (i in 0 until msgs.length()) {
            val item = msgs.optJSONArray(i) ?: continue
            buf.append(item.toString()).append('\n')
            if (item.length() >= 2 && item.optString(0) == "execution_error") {
                val detail = item.optJSONObject(1) ?: JSONObject()
                val ex = detail.optString("exception_message")
                val tb = detail.optString("traceback")
                val all = "${ex} ${tb} ${buf}"
                if (isOom(all)) throw OomException(ex)
                throw ComfyException("节点执行失败: ${ex}")
            }
        }
        if (status.optString("status_str") == "error") {
            if (isOom(buf.toString())) throw OomException("显存不足")
            throw ComfyException("生成失败: ${buf.toString().take(1500)}")
        }
    }

    private fun isOom(s: String): Boolean {
        val l = s.lowercase()
        return l.contains("out of memory") || l.contains("cuda oom") || l.contains("allocation on device")
    }

    fun images(entry: JSONObject, outputNodeId: String = "32"): JSONObject? {
        val outs = entry.optJSONObject("outputs") ?: return null
        val node = outs.optJSONObject(outputNodeId) ?: return null
        val arr: JSONArray = node.optJSONArray("images") ?: return null
        if (arr.length() == 0) return null
        return arr.optJSONObject(0)
    }

    /** GET /view —— 返回 PNG 字节。 */
    fun fetchImage(base: String, info: JSONObject, timeoutSec: Int): ByteArray {
        val q = listOf(
            "filename" to info.optString("filename"),
            "subfolder" to info.optString("subfolder"),
            "type" to (if (info.optString("type").isBlank()) "output" else info.optString("type"))
        ).joinToString("&") { (k, v) -> "${k}=${URLEncoder.encode(v, "UTF-8")}" }
        val c = open("${base}/view?${q}", timeoutSec * 1000, timeoutSec * 1000)
        c.requestMethod = "GET"
        try {
            val code = c.responseCode
            if (code !in 200..299) throw ComfyException("取图失败 HTTP ${code} ${readError(c)}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** POST /interrupt —— 立即中断当前任务。 */
    fun interrupt(base: String) {
        try {
            val c = open("${base}/interrupt", 5000, 5000)
            c.requestMethod = "POST"
            c.doOutput = true
            try { c.outputStream.use { it.write(ByteArray(0)) }; c.responseCode } finally { c.disconnect() }
        } catch (e: Exception) { /* 忽略 */ }
    }
}
