package com.example.posewear

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 内置文件浏览器：从「上次浏览 / 上次选图」的目录开始，列出子目录与图片。
 *
 * 不用系统 DocumentsUI，目录记忆完全由 App 自己掌控 —— 国产 ROM 普遍忽略
 * ACTION_OPEN_DOCUMENT 的 EXTRA_INITIAL_URI，靠系统选择器无法保证下次定位。
 * 列表项带缩略图，点图片先弹大图预览，确认后才返回绝对路径给 PersonFragment。
 */
class FileBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PICKED_PATH = "picked_path"
        fun intent(ctx: Context): Intent = Intent(ctx, FileBrowserActivity::class.java)
    }

    private data class Row(val file: File, val isDir: Boolean, val sub: String)

    private var current: File = File("/storage/emulated/0")
    private lateinit var pathView: TextView
    private lateinit var memoryView: TextView
    private lateinit var permView: TextView
    private lateinit var emptyView: TextView
    private lateinit var upBtn: MaterialButton
    private val rows = mutableListOf<Row>()
    private val adapter = FileAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        current = StoragePick.lastBrowseDir(this)
        load()
        refreshPermissionHint()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionHint()
    }

    private fun buildUi(): View {
        val ctx = this
        val pad = dp(ctx, 12)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.getColor(R.color.bg))
            setPadding(pad, dp(ctx, 16), pad, pad)
        }

        root.addView(TextView(ctx).apply {
            text = "选择人物照片 · 本机文件"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.primary))
        }, wide())

        pathView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_secondary))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }
        root.addView(pathView, wide())

        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        upBtn = smallButton(ctx, "↑ 上级") { goUp() }
        bar.addView(upBtn, weighted())
        bar.addView(smallButton(ctx, "内部存储根") { enter(StoragePick.storageRoot()) }, weighted())
        bar.addView(smallButton(ctx, "取消") { finish() }, weighted())
        root.addView(bar, wide())

        val memoryRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 4))
        }
        memoryView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.getColor(R.color.primary))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        memoryRow.addView(memoryView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        memoryRow.addView(smallButton(ctx, "清除记忆") {
            StoragePick.resetBrowseDir(this@FileBrowserActivity)
            toast("已清除目录记忆，下次从内部存储根目录开始")
            refreshMemory()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36)))
        root.addView(memoryRow, wide())

        permView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.getColor(R.color.warn_red))
            setBackgroundColor(ctx.getColor(R.color.warning_bg))
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            visibility = View.GONE
            setOnClickListener { StoragePick.openAllFilesSettings(this@FileBrowserActivity) }
        }
        root.addView(permView, wide())

        root.addView(TextView(ctx).apply {
            text = "列表显示缩略图 · 点图片可先看大图，确认后再使用"
            textSize = 11f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
        }, wide())

        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = this@FileBrowserActivity.adapter
            setBackgroundColor(ctx.getColor(R.color.panel_bg))
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        emptyView = TextView(ctx).apply {
            textSize = 13f
            setTextColor(ctx.getColor(R.color.text_secondary))
            visibility = View.GONE
            setPadding(dp(ctx, 12), dp(ctx, 24), dp(ctx, 12), dp(ctx, 12))
        }
        root.addView(emptyView, wide())
        return root
    }

    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weighted() = LinearLayout.LayoutParams(0, dp(this, 40), 1f).apply {
        marginEnd = dp(this@FileBrowserActivity, 4)
    }

    private fun smallButton(ctx: Context, label: String, onClick: () -> Unit) =
        MaterialButton(ctx).apply {
            text = label
            textSize = 11f
            minimumHeight = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
            setOnClickListener { onClick() }
        }

    private fun refreshPermissionHint() {
        val granted = StoragePick.hasAllFilesAccess()
        permView.visibility = if (granted) View.GONE else View.VISIBLE
        permView.text =
            "未开启「所有文件访问权限」：部分目录可能列不出来，缩略图也可能读不到。点此去系统设置开启。"
    }

    private fun refreshMemory() {
        memoryView.text = "下次默认打开：${StoragePick.browseLabel(current)}（浏览或选图后自动记住）"
    }

    /** 进入目录即写入记忆；即使这次取消不选，下次也从最后停留的目录开始。 */
    private fun load() {
        pathView.text = "当前目录：${current.absolutePath}"
        upBtn.isEnabled = current.parentFile != null
        StoragePick.rememberBrowseDir(this, current)
        refreshMemory()
        lifecycleScope.launch {
            val (items, err) = withContext(Dispatchers.IO) { scan(current) }
            rows.clear()
            rows.addAll(items)
            adapter.notifyDataSetChanged()
            emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            emptyView.text = err ?: "此目录没有子目录，也没有 JPG / PNG / WEBP / BMP 图片。"
        }
    }

    private fun scan(dir: File): Pair<List<Row>, String?> {
        val children = try { dir.listFiles() } catch (e: Exception) { null }
        if (children == null) {
            val msg = "无法读取此目录。请到「6.设置 → 开启所有文件访问权限」后重试。"
            return Pair(emptyList(), msg)
        }
        val dirs = children.filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        val imgs = children.filter { it.isFile && Images.isImage(it.name) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        val out = ArrayList<Row>(dirs.size + imgs.size)
        dirs.forEach { out.add(Row(it, true, "文件夹")) }
        imgs.forEach { out.add(Row(it, false, "图片 · ${formatSize(it.length())}")) }
        return out to null
    }

    private fun formatSize(n: Long): String = when {
        n >= 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, n / 1048576.0)
        n >= 1024 -> "%.0f KB".format(Locale.ROOT, n / 1024.0)
        else -> "$n B"
    }

    private fun goUp() {
        val parent = current.parentFile
        if (parent == null) { toast("已经是最上层目录"); return }
        enter(parent)
    }

    private fun enter(dir: File) {
        if (!dir.isDirectory) { toast("无法进入该目录"); return }
        current = dir
        load()
    }

    /** 点击图片 → 大图预览，确认后才真正选中。 */
    private fun preview(file: File) {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(getColor(R.color.thumb_bg))
        }
        val info = TextView(this).apply {
            text = "正在载入预览…"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(this@FileBrowserActivity, 10), dp(this@FileBrowserActivity, 6),
                dp(this@FileBrowserActivity, 10), 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(iv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this@FileBrowserActivity, 340)
            ))
            addView(info, wide())
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(box)
            .setNegativeButton("返回", null)
            .setPositiveButton("使用这张照片", null)
            .create()
        dlg.setOnShowListener {
            dlg.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dlg.dismiss()
                pick(file)
            }
        }
        dlg.show()
        lifecycleScope.launch {
            val (bmp, size) = withContext(Dispatchers.IO) { decodePreview(file) }
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                info.text = "${size.first} × ${size.second} · ${formatSize(file.length())}"
            } else {
                info.text = "这张图片无法解码（文件损坏或格式不支持）"
            }
        }
    }

    /** 直接从文件采样解码，避免把整张大图读进内存。 */
    private fun decodePreview(file: File): Pair<Bitmap?, Pair<Int, Int>> = try {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, probe)
        val longest = maxOf(probe.outWidth, probe.outHeight)
        var sample = 1
        while (longest / sample > 1600 && sample < 32) sample *= 2
        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opt)
        Pair(bmp, Pair(probe.outWidth, probe.outHeight))
    } catch (e: Exception) {
        Pair(null, Pair(0, 0))
    }

    private fun pick(file: File) {
        StoragePick.rememberBrowseDir(this, current)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PICKED_PATH, file.absolutePath))
        finish()
    }

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tag: TextView = v.findViewById(R.id.tv_file_tag)
            val thumb: ImageView = v.findViewById(R.id.iv_file_thumb)
            val name: TextView = v.findViewById(R.id.tv_file_name)
            val sub: TextView = v.findViewById(R.id.tv_file_sub)
            var job: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.name.text = r.file.name
            h.sub.text = r.sub
            h.job?.cancel()
            h.job = null
            if (r.isDir) {
                h.tag.visibility = View.VISIBLE
                h.thumb.visibility = View.GONE
                h.thumb.setImageBitmap(null)
                h.itemView.setOnClickListener { enter(r.file) }
            } else {
                h.tag.visibility = View.GONE
                h.thumb.visibility = View.VISIBLE
                h.thumb.setImageBitmap(null)
                val key = r.file.absolutePath
                h.thumb.tag = key
                h.job = lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = Images.fileThumb(this@FileBrowserActivity, key, 128)
                    withContext(Dispatchers.Main) {
                        if (h.thumb.tag == key && h.bindingAdapterPosition != RecyclerView.NO_POSITION)
                            h.thumb.setImageBitmap(bmp)
                    }
                }
                h.itemView.setOnClickListener { preview(r.file) }
            }
        }

        override fun onViewRecycled(h: VH) {
            h.job?.cancel()
            h.job = null
            h.thumb.setImageBitmap(null)
        }
    }
}
