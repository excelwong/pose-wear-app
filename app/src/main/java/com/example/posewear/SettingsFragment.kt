package com.example.posewear

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「6.设置」TAB：只管远程 ComfyUI 的 IP/端口与手机本地素材库目录。
 * 分辨率档位、采样参数、模型三件套已经移到「5.模型与参数」TAB（ParamsFragment）。
 * 所有字段保存在本机 SharedPreferences，启动时自动装载；切走本页时自动保存。
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etCostumeDir: EditText
    private lateinit var etPoseDir: EditText
    private lateinit var tvConn: TextView
    private lateinit var tvCatalog: TextView

    private val pickCostume = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onDirPicked(uri, true) }

    private val pickPose = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onDirPicked(uri, false) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val fService = view.findViewById<LinearLayout>(R.id.form_service)
        val fDirs = view.findViewById<LinearLayout>(R.id.form_dirs)
        tvConn = view.findViewById(R.id.tv_conn)
        tvCatalog = view.findViewById(R.id.tv_catalog)

        // 显示当前已安装 APK 的版本，便于核对每次交付是否递增
        @Suppress("DEPRECATION")
        val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        view.findViewById<TextView>(R.id.tv_version).text =
            "应用版本 ${pkgInfo.versionName} · versionCode ${pkgInfo.versionCode}"

        val c = Store.cfg

        etHost = Form.edit(ctx, fService, "host", "IP 地址", c.host, "如 192.168.1.100 或 http://…")
        etPort = Form.edit(ctx, fService, "port", "端口", c.port.toString(), "8188", numeric = true)

        etCostumeDir = Form.edit(ctx, fDirs, "costumeDir", "服装库目录", c.costumeDir,
            "绝对路径或点下方按钮选目录", multiLine = true)
        etPoseDir = Form.edit(ctx, fDirs, "poseDir", "姿态库目录", c.poseDir,
            "绝对路径或点下方按钮选目录", multiLine = true)

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { save(true) }

        view.findViewById<MaterialButton>(R.id.btn_reset).setOnClickListener {
            // 只恢复本页字段，不动「模型与参数」页的配置
            val d = Settings.defaults()
            val n = Store.cfg.copy(host = d.host, port = d.port,
                costumeDir = d.costumeDir, poseDir = d.poseDir)
            Store.cfg = n
            Settings.save(ctx, n)
            fill(n)
            Store.bumpConfig()
            Store.setStatus("设置页已恢复默认。")
            toast("已恢复默认")
        }

        view.findViewById<MaterialButton>(R.id.btn_test).setOnClickListener {
            val cfg = collect()
            tvConn.text = "正在连接 ${cfg.baseUrl()} …"
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val s = ComfyClient.systemStats(cfg.baseUrl(), cfg.requestTimeout)
                    val dev = s.optJSONArray("devices")?.optJSONObject(0)
                    val free = dev?.optDouble("vram_free", 0.0) ?: 0.0
                    val name = dev?.optString("name") ?: "GPU"
                    val text = "已连接 · ${name} · 可用显存 ${"%.1f".format(free / 1073741824.0)} GB"
                    withContext(Dispatchers.Main) { if (isAdded) tvConn.text = text }
                } catch (e: Exception) {
                    val msg = "连接失败：${e.message}"
                    withContext(Dispatchers.Main) { if (isAdded) tvConn.text = msg }
                }
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_pick_costume).setOnClickListener {
            pickCostume.launch(null)
        }
        view.findViewById<MaterialButton>(R.id.btn_pick_pose).setOnClickListener {
            pickPose.launch(null)
        }
        view.findViewById<MaterialButton>(R.id.btn_scan).setOnClickListener {
            val cfg = collect()
            Store.cfg = cfg
            Store.syncToCfg()
            Settings.save(ctx, Store.cfg)
            tvCatalog.text = "正在扫描…"
            lifecycleScope.launch {
                val (n1, n2) = withContext(Dispatchers.IO) { Catalog.scan(ctx, cfg) }
                updateCatalog(n1, n2)
                Store.setStatus("素材库扫描完成：服装 ${n1} 件 / 姿态 ${n2} 张")
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_permission).setOnClickListener { askAllFiles() }

        updateCatalog(Catalog.costumes.size, Catalog.poses.size)
    }

    private fun updateCatalog(n1: Int, n2: Int) {
        if (!isAdded) return
        val err = listOf(Catalog.costumeError, Catalog.poseError).filter { it.isNotBlank() }
        tvCatalog.text = if (err.isEmpty())
            "已装载：服装 ${n1} 件 / 姿态 ${n2} 张"
        else "服装 ${n1} 件 / 姿态 ${n2} 张\n${err.joinToString("；")}"
    }

    private fun onDirPicked(uri: Uri, isCostume: Boolean) {
        val ctx = requireContext()
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { /* 某些 provider 不支持持久化 */ }
        val s = uri.toString()
        if (isCostume) etCostumeDir.setText(s) else etPoseDir.setText(s)
        save(false)
        toast("目录已选择，请点「重新扫描素材库」或下次启动自动扫描")
    }

    private fun askAllFiles() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                val i = Intent(
                    AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
                startActivity(i)
            } catch (e: Exception) {
                try { startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (e2: Exception) { toastLong("无法打开权限设置：${e2.message}") }
            }
        } else {
            toast("已具备文件访问权限，可直接填写绝对路径；也可点「选目录」用系统选择器授权。")
        }
    }

    private fun fill(c: Cfg) {
        etHost.setText(c.host)
        etPort.setText(c.port.toString())
        etCostumeDir.setText(c.costumeDir)
        etPoseDir.setText(c.poseDir)
    }

    /** 只读取本页 4 个字段，其余配置从 Store.cfg 原样继承，避免覆盖另一页的修改。 */
    private fun collect(): Cfg {
        val c = Store.cfg.copy()
        c.host = etHost.text.toString().trim()
        c.port = strInt(etPort.text.toString(), 8188).coerceIn(1, 65535)
        c.costumeDir = etCostumeDir.text.toString().trim()
        c.poseDir = etPoseDir.text.toString().trim()
        return c
    }

    private fun save(showToast: Boolean) {
        if (!::etHost.isInitialized || !isAdded) return
        Store.cfg = collect()
        Store.syncToCfg()
        Settings.save(requireContext().applicationContext, Store.cfg)
        Store.bumpConfig()
        if (showToast) {
            Store.setStatus("设置已保存到本机，下次打开自动装载。")
            toast("设置已保存")
        }
    }

    /** 常驻 Fragment hide/show：切走本页时自动保存。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!::etHost.isInitialized || !isAdded) return
        if (hidden) save(showToast = false)
        else fill(Store.cfg)
    }
}
