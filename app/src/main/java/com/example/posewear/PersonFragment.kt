package com.example.posewear

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PersonFragment : Fragment(R.layout.fragment_person) {

    private lateinit var preview: ImageView
    private lateinit var hint: TextView
    private lateinit var pathLabel: TextView
    private lateinit var photoInfo: TextView
    private lateinit var pickDirLabel: TextView

    /** 内置文件浏览器：从上次浏览 / 选图的目录开始，列表带缩略图与大图预览。 */
    private val pickFromBrowser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_PICKED_PATH)
        if (!path.isNullOrBlank()) savePhoto(path)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        preview = view.findViewById(R.id.person_preview)
        hint = view.findViewById(R.id.tv_photo_hint)
        pathLabel = view.findViewById(R.id.tv_photo_path)
        photoInfo = view.findViewById(R.id.tv_photo_info)
        pickDirLabel = view.findViewById(R.id.tv_pick_dir)
        val open: (View) -> Unit = {
            pickFromBrowser.launch(FileBrowserActivity.intent(requireContext()))
        }
        view.findViewById<FrameLayout>(R.id.preview_wrap).setOnClickListener(open)
        view.findViewById<MaterialButton>(R.id.btn_pick_photo).setOnClickListener(open)
        view.findViewById<MaterialButton>(R.id.btn_reset_dir).setOnClickListener {
            StoragePick.resetBrowseDir(requireContext())
            refreshPickDir()
            toast("已清除目录记忆，下次从内部存储根目录开始")
        }
        showSavedPhoto()
        refreshPickDir()
    }

    /** 转存成应用私有 PNG，成功后才写目录记忆。 */
    private fun savePhoto(path: String) {
        val ctx = requireContext()
        CoroutineScope(Dispatchers.IO).launch {
            val (srcW, srcH) = Images.size(ctx, path)   // 只读图片头，不解码整图
            val dst = File(ctx.filesDir, "person_photo.png")
            val ok = try {
                dst.writeBytes(Images.pngBytes(ctx, path))
                true
            } catch (e: Exception) {
                try {
                    Images.open(ctx, path)?.use { ins ->
                        dst.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                    dst.isFile && dst.length() > 0
                } catch (e2: Exception) { false }
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (ok) {
                    Store.personLocator = dst.absolutePath
                    Store.personW = srcW
                    Store.personH = srcH
                    Store.syncToCfg()
                    Settings.save(ctx, Store.cfg)
                    Store.bumpSelection()
                    val label = StoragePick.rememberPickedPath(ctx, path)
                    Store.setStatus("人物照片已选择 · 下次默认打开「${label}」")
                    showSavedPhoto()
                    refreshPickDir()
                } else {
                    toastLong("无法读取这张照片，请换一张 JPG/PNG 图片。")
                }
            }
        }
    }

    /** 常驻显示「下次默认打开的目录」，让用户确认记忆已生效。 */
    private fun refreshPickDir() {
        if (!::pickDirLabel.isInitialized || !isAdded) return
        pickDirLabel.text =
            "下次默认打开：${StoragePick.lastDirLabel(requireContext())}（浏览 / 选图后自动记住）"
    }

    private fun showSavedPhoto() {
        val loc = Store.personLocator
        if (loc.isBlank()) {
            preview.setImageBitmap(null)
            hint.visibility = View.VISIBLE
            pathLabel.text = "尚未选择照片"
            photoInfo.text = "选择照片后，这里会显示原图分辨率与最接近的画幅比例。"
            return
        }
        var w = Store.personW
        var h = Store.personH
        if (w <= 0 || h <= 0) {
            // 1.0.19 及更早版本没存尺寸：从已转存的 PNG 补读一次并写回，下次启动直接命中
            val fallback = Images.size(requireContext(), loc)
            w = fallback.first
            h = fallback.second
            if (w > 0 && h > 0) {
                Store.personW = w
                Store.personH = h
                Store.syncToCfg()
                Settings.save(requireContext(), Store.cfg)
            }
        }
        photoInfo.text = if (w > 0 && h > 0) Resolution.photoInfoText(w, h)
                         else "分辨率读取失败，无法显示画幅比例。"
        val bmp = try {
            Images.decode(requireContext(), loc, 1600)
                ?: BitmapFactory.decodeFile(loc)
        } catch (e: Exception) { null }
        if (bmp != null) {
            preview.setImageBitmap(bmp)
            hint.visibility = View.GONE
            pathLabel.text = "已选择：${loc.substringAfterLast('/')}"
        } else {
            preview.setImageBitmap(null)
            hint.visibility = View.VISIBLE
            pathLabel.text = "照片不可读，请重新选择：${loc}"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized) showSavedPhoto()
        refreshPickDir()
    }
}
