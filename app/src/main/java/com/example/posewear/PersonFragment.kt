package com.example.posewear

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
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

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPhotoPicked(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        preview = view.findViewById(R.id.person_preview)
        hint = view.findViewById(R.id.tv_photo_hint)
        pathLabel = view.findViewById(R.id.tv_photo_path)
        val open: (View) -> Unit = { pickPhoto.launch(arrayOf("image/*")) }
        view.findViewById<FrameLayout>(R.id.preview_wrap).setOnClickListener(open)
        view.findViewById<MaterialButton>(R.id.btn_pick_photo).setOnClickListener(open)
        showSavedPhoto()
    }

    private fun onPhotoPicked(uri: Uri) {
        val ctx = requireContext()
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) { /* 部分文件选择器不支持持久化，忽略 */ }
        CoroutineScope(Dispatchers.IO).launch {
            // 统一转存为应用私有目录里的 PNG，下次启动无需再申请 uri 权限
            val dst = File(ctx.filesDir, "person_photo.png")
            val ok = try {
                dst.writeBytes(Images.pngBytes(ctx, uri.toString()))
                true
            } catch (e: Exception) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        dst.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                    dst.isFile && dst.length() > 0
                } catch (e2: Exception) { false }
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (ok) {
                    Store.personLocator = dst.absolutePath
                    Store.syncToCfg()
                    Settings.save(ctx, Store.cfg)
                    Store.bumpSelection()
                    Store.setStatus("人物照片已选择：${dst.name}")
                    showSavedPhoto()
                } else {
                    toastLong("无法读取这张照片，请换一张 JPG/PNG 图片。")
                }
            }
        }
    }

    private fun showSavedPhoto() {
        val loc = Store.personLocator
        if (loc.isBlank()) {
            preview.setImageBitmap(null)
            hint.visibility = View.VISIBLE
            pathLabel.text = "尚未选择照片"
            return
        }
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
    }
}
