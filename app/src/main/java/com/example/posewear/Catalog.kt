package com.example.posewear

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** 素材库条目。locator 是绝对路径或 content:// document uri。
 *  description 是 _metadata.csv「姿态描述_中文」列，提示词里姿态参考句冒号后的内容全部由它提供。 */
data class LibItem(
    val id: String,
    val name: String,
    val keywords: String,
    val category: String,
    val group: String = "",
    val angle: String = "",
    val locator: String = "",
    val description: String = ""
)

private data class Collected(
    val csv: String?,
    val files: LinkedHashMap<String, String>,   // 文件名 → locator
    val error: String = ""
)

object Catalog {
    const val COSTUME = "costume"
    const val POSE = "pose"

    @Volatile var costumes: List<LibItem> = emptyList()
    @Volatile var poses: List<LibItem> = emptyList()
    @Volatile var costumeError: String = ""
    @Volatile var poseError: String = ""

    fun list(kind: String): List<LibItem> = if (kind == COSTUME) costumes else poses

    fun categories(kind: String): List<String> =
        list(kind).map { it.category }.filter { it.isNotBlank() }.distinct().sorted()

    fun groups(kind: String): List<String> =
        list(kind).map { it.group }.filter { it.isNotBlank() }.distinct().sorted()

    fun angles(kind: String): List<String> =
        list(kind).map { it.angle }.filter { it.isNotBlank() }.distinct().sorted()

    fun find(kind: String, id: String): LibItem? =
        if (id.isBlank()) null else list(kind).firstOrNull { it.id == id }

    /** 扫描两个素材库。可在设置页手动触发，也可在启动时自动触发。 */
    fun scan(ctx: Context, cfg: Cfg): Pair<Int, Int> {
        val c = collect(ctx, cfg.costumeDir)
        val p = collect(ctx, cfg.poseDir)
        costumes = buildItems(COSTUME, c)
        poses = buildItems(POSE, p)
        costumeError = c.error
        poseError = p.error
        // 手机上仍可能是旧版 CSV（没有「姿态描述_中文」列）：明确提示用户重新拷贝
        if (poses.isNotEmpty() && poses.all { it.description.isBlank() }) {
            val hint = "姿态库 _metadata.csv 缺少「姿态描述_中文」列，请把电脑端更新后的 CSV 重新拷到手机；当前提示词会降级为「姿态名 + 角度视角」。"
            poseError = listOf(poseError, hint).filter { it.isNotBlank() }.joinToString("\n")
        }
        // 恢复选择：优先保留用户当前已选，其次才是上次保存的 id
        val keepC = Store.costume?.id ?: cfg.costumeId
        val keepP = Store.pose?.id ?: cfg.poseId
        Store.costume = find(COSTUME, keepC)
        Store.pose = find(POSE, keepP)
        // 从设置恢复的旧 id 不能覆盖当前生效的「全裸 / 保持原服装 / 保持原姿势」
        Store.normalizeModes()
        Store.bumpCatalog()
        Store.bumpSelection()
        return Pair(costumes.size, poses.size)
    }

    // ---------- 目录枚举 ----------

    private fun collect(ctx: Context, root: String): Collected {
        if (root.isBlank()) return Collected(null, LinkedHashMap(), "未设置目录，请到「6.设置」指定。")
        return try {
            if (root.startsWith("content://", true)) collectSaf(ctx, root) else collectFile(root)
        } catch (e: Exception) {
            Collected(null, LinkedHashMap(), "读取目录失败：${e.message}")
        }
    }

    private fun collectFile(root: String): Collected {
        val base = File(root)
        if (!base.isDirectory) return Collected(null, LinkedHashMap(), "目录不存在或不是文件夹：${root}")
        val files = LinkedHashMap<String, String>()
        walkFile(base, files, 0)
        val csv = File(base, "_metadata.csv").takeIf { it.isFile }?.readText(Charsets.UTF_8)
        var err = ""
        if (files.isEmpty()) err = "目录里没有找到图片：${root}"
        return Collected(csv, files, err)
    }

    private fun walkFile(dir: File, out: LinkedHashMap<String, String>, depth: Int) {
        if (depth > 3) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (f.isDirectory) walkFile(f, out, depth + 1)
            else if (Images.isImage(f.name)) out.putIfAbsent(f.name, f.absolutePath)
        }
    }

    private fun collectSaf(ctx: Context, root: String): Collected {
        val tree = Uri.parse(root)
        val files = LinkedHashMap<String, String>()
        var csvText: String? = null
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        walkSaf(ctx, tree, rootId, files, 0) { name, uri ->
            if (name.equals("_metadata.csv", true) && csvText == null) {
                csvText = try {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) { null }
            }
        }
        var err = ""
        if (files.isEmpty()) err = "所选目录里没有找到图片（如果是外置 SD 卡，请确认已授权）。"
        return Collected(csvText, files, err)
    }

    private fun walkSaf(
        ctx: Context, tree: Uri, docId: String,
        out: LinkedHashMap<String, String>, depth: Int,
        onCsv: (String, Uri) -> Unit
    ) {
        if (depth > 3) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val cursor = try {
            ctx.contentResolver.query(children, proj, null, null, null)
        } catch (e: Exception) { null } ?: return
        cursor.use { c ->
            val iId = c.getColumnIndex(proj[0])
            val iName = c.getColumnIndex(proj[1])
            val iMime = c.getColumnIndex(proj[2])
            while (c.moveToNext()) {
                val id = if (iId >= 0) c.getString(iId) ?: continue else continue
                val name = if (iName >= 0) c.getString(iName) ?: "" else ""
                val mime = if (iMime >= 0) c.getString(iMime) ?: "" else ""
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkSaf(ctx, tree, id, out, depth + 1, onCsv)
                } else if (name.equals("_metadata.csv", true)) {
                    onCsv(name, uri)
                } else if (Images.isImage(name)) {
                    out.putIfAbsent(name, uri.toString())
                }
            }
        }
    }

    // ---------- CSV ----------

    private fun parseCsv(text: String): List<Map<String, String>> {
        val lines = text.trimStart('\uFEFF').split(Regex("\\r?\\n")).filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val head = csvLine(lines[0])
        val out = ArrayList<Map<String, String>>()
        for (i in 1 until lines.size) {
            val cells = csvLine(lines[i])
            val m = LinkedHashMap<String, String>()
            head.forEachIndexed { idx, h -> m[h.trim()] = cells.getOrElse(idx) { "" }.trim() }
            out += m
        }
        return out
    }

    private fun csvLine(line: String): List<String> {
        val r = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (quoted && i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ }
                    else quoted = !quoted
                }
                ch == ',' && !quoted -> { r += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        r += sb.toString()
        return r
    }

    private fun buildItems(kind: String, c: Collected): List<LibItem> {
        val rows = c.csv?.let { parseCsv(it) } ?: emptyList()
        val out = ArrayList<LibItem>()
        if (rows.isNotEmpty()) {
            for (m in rows) {
                val fn = m["原图"] ?: continue
                val loc = c.files[fn] ?: continue
                if (kind == COSTUME) {
                    out += LibItem(
                        id = m["id"] ?: fn,
                        name = m["id"] ?: fn,
                        keywords = m["关键词(提示词)"] ?: "",
                        category = m["分类"] ?: "",
                        locator = loc
                    )
                } else {
                    out += LibItem(
                        id = m["id"] ?: fn,
                        name = m["姿态名"] ?: (m["id"] ?: fn),
                        keywords = m["提示词"] ?: "",
                        category = m["分类"] ?: "",
                        group = m["系统"] ?: "",
                        angle = m["角度"] ?: "",
                        locator = loc,
                        description = m["姿态描述_中文"] ?: ""
                    )
                }
            }
        }
        if (out.isEmpty() && c.files.isNotEmpty()) {
            // 没有 CSV 也能用：按文件名/父目录名生成条目
            for ((fn, loc) in c.files) {
                val parent = if (loc.startsWith("content://")) "" else File(loc).parentFile?.name ?: ""
                out += LibItem(
                    id = fn.substringBeforeLast('.'),
                    name = fn.substringBeforeLast('.'),
                    keywords = "",
                    category = parent,
                    group = "",
                    angle = "",
                    locator = loc
                )
            }
        }
        return out.sortedBy { it.id }
    }
}
