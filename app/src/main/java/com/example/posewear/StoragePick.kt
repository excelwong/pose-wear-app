package com.example.posewear

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import java.io.File

/**
 * 本机目录记忆：内置文件浏览器记住「上次浏览 / 选图」的绝对路径，
 * 设置页的素材库目录选择器另记一份 SAF tree URI。
 *
 * 1.0.18 起人物照片只走内置文件浏览器，不再使用系统 DocumentsUI 选图：
 * ACTION_OPEN_DOCUMENT 的 EXTRA_INITIAL_URI 在多数国产 ROM 上会被忽略，
 * 目录记忆必须由 App 自己掌控。
 */
object StoragePick {
    private const val AUTH = "com.android.externalstorage.documents"
    private const val PRIMARY = "primary"

    private const val PREF = "posewear_pick_history"
    private const val KEY_BROWSE_DIR = "last_browse_dir"   // 内置浏览器：绝对路径（主记录）
    private const val KEY_FILE_DIR = "last_file_dir"       // 旧版 SAF 记录，仅用于迁移
    private const val KEY_FILE_LABEL = "last_file_label"   // 展示用目录名
    private const val KEY_TREE_DIR = "last_tree_dir"       // 设置页：素材库目录 tree uri
    private const val KEY_TREE_LABEL = "last_tree_label"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun storageRoot(): File = try {
        Environment.getExternalStorageDirectory()
    } catch (e: Exception) {
        File("/storage/emulated/0")
    }

    /** 设置页 ACTION_OPEN_DOCUMENT_TREE 的兜底初始位置。 */
    fun treeRoot(): Uri = DocumentsContract.buildTreeDocumentUri(AUTH, PRIMARY)

    fun initialTreeDir(ctx: Context): Uri =
        prefs(ctx).getString(KEY_TREE_DIR, null)?.takeIf { it.isNotBlank() }
            ?.let { try { Uri.parse(it) } catch (e: Exception) { null } }
            ?: treeRoot()

    // ---------- 内置文件浏览器的目录记忆 ----------

    /** 可读的目录名：优先显示相对内部存储根的路径。 */
    fun browseLabel(dir: File): String {
        val root = storageRoot().absolutePath.trimEnd('/')
        val p = dir.absolutePath.trimEnd('/')
        return when {
            p == root -> "内部存储根目录"
            p.startsWith("$root/") -> p.removePrefix("$root/")
            else -> p
        }
    }

    /**
     * 内置浏览器下次打开的位置。绝对路径记录优先；
     * 没有则尝试把 1.0.17 及更早版本的 SAF 记录（primary:DCIM/Camera）还原成路径。
     */
    fun lastBrowseDir(ctx: Context): File {
        val saved = prefs(ctx).getString(KEY_BROWSE_DIR, null)
        if (!saved.isNullOrBlank()) {
            val f = File(saved)
            if (f.isDirectory && f.canRead()) return f
        }
        legacyPathFromSaf(ctx)?.let { f -> if (f.isDirectory && f.canRead()) return f }
        return storageRoot()
    }

    /** 浏览到哪个目录就记住哪个目录；即使中途取消，下次也从上一次停留的位置开始。 */
    fun rememberBrowseDir(ctx: Context, dir: File) {
        if (!dir.isDirectory) return
        prefs(ctx).edit()
            .putString(KEY_BROWSE_DIR, dir.absolutePath)
            .putString(KEY_FILE_LABEL, browseLabel(dir))
            .apply()
    }

    /** 选中图片后再确认一次：记住图片所在目录。 */
    fun rememberPickedPath(ctx: Context, path: String): String {
        val dir = File(path).parentFile
        if (dir != null && dir.isDirectory) {
            rememberBrowseDir(ctx, dir)
            return browseLabel(dir)
        }
        return lastDirLabel(ctx)
    }

    /** 人物页常驻文案：优先用内置浏览器的绝对路径记录。 */
    fun lastDirLabel(ctx: Context): String {
        val saved = prefs(ctx).getString(KEY_BROWSE_DIR, null)
        if (!saved.isNullOrBlank()) {
            val f = File(saved)
            if (f.isDirectory) return browseLabel(f)
        }
        return prefs(ctx).getString(KEY_FILE_LABEL, null)?.takeIf { it.isNotBlank() }
            ?: "内部存储根目录"
    }

    fun resetBrowseDir(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_BROWSE_DIR)
            .remove(KEY_FILE_DIR)
            .remove(KEY_FILE_LABEL)
            .apply()
    }

    // ---------- 设置页：素材库目录（SAF tree URI） ----------

    /** 目录选择器选完后记住该 tree uri，下次直接打开这个目录。 */
    fun rememberTreePick(ctx: Context, uri: Uri): String {
        val label = try {
            DocumentsContract.getTreeDocumentId(uri)
                .substringAfter(':', "")
                .ifBlank { "内部存储根目录" }
        } catch (e: Exception) { "上次选择的目录" }
        prefs(ctx).edit()
            .putString(KEY_TREE_DIR, uri.toString())
            .putString(KEY_TREE_LABEL, label)
            .apply()
        return label
    }

    fun lastTreeDirLabel(ctx: Context): String =
        prefs(ctx).getString(KEY_TREE_LABEL, null)?.takeIf { it.isNotBlank() }
            ?: "内部存储根目录"

    /** 兼容 1.0.17 及更早版本只写了 SAF document URI 的旧记录。 */
    private fun legacyPathFromSaf(ctx: Context): File? {
        val s = prefs(ctx).getString(KEY_FILE_DIR, null) ?: return null
        return try {
            val u = Uri.parse(s)
            if (u.authority != AUTH) return null
            val id = DocumentsContract.getDocumentId(u)
            if (!id.startsWith("$PRIMARY:")) return null
            File(storageRoot(), id.removePrefix("$PRIMARY:"))
        } catch (e: Exception) { null }
    }

    // ---------- 所有文件访问权限 ----------

    /** Android 10 及以下由 READ_EXTERNAL_STORAGE 覆盖；11+ 需要「所有文件访问权限」。 */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /** 跳转系统「所有文件访问权限」设置页（从 Application / Activity context 都能用）。 */
    fun openAllFilesSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            ctx.startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                ctx.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                toastLong("无法打开权限设置：${e2.message}")
            }
        }
    }
}
