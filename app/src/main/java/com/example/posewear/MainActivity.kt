package com.example.posewear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 单 Activity + 常驻 Fragment + hide/show 切换（参考 novel-studio-app）。
 * 折叠屏展开后（sw600dp+）变成左右双栏：左侧浏览素材/配置，右侧「生成」面板常驻。
 * 窄屏 6 个 tab；宽屏左栏 5 个 tab（生成页常驻右栏，不占 tab）。
 */
class MainActivity : AppCompatActivity() {

    private val wide: Boolean by lazy { resources.getBoolean(R.bool.wide_screen) }
    private lateinit var tabs: TabLayout
    private lateinit var statusBar: TextView
    private var rightFrame: FrameLayout? = null
    private val frags = mutableListOf<Fragment>()
    private var genFrag: GenerateFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabs = findViewById(R.id.tab_layout)
        statusBar = findViewById(R.id.status_bar)
        rightFrame = findViewById(R.id.right_frame)
        val contentFrame: FrameLayout = findViewById(R.id.content_frame)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.root_layout)) { v, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(safe.left, safe.top, safe.right, max(safe.bottom, ime))
            insets
        }

        val titles = if (wide)
            listOf(
                R.string.tab_person, R.string.tab_costume, R.string.tab_pose,
                R.string.tab_params, R.string.tab_settings
            )
        else
            listOf(
                R.string.tab_person, R.string.tab_costume, R.string.tab_pose,
                R.string.tab_generate, R.string.tab_params, R.string.tab_settings
            )
        val count = titles.size

        if (savedInstanceState == null) {
            val person = PersonFragment()
            val costume = LibraryFragment.newInstance(Catalog.COSTUME)
            val pose = LibraryFragment.newInstance(Catalog.POSE)
            val generate = GenerateFragment()
            val params = ParamsFragment()
            val settings = SettingsFragment()
            genFrag = generate
            frags.clear()
            if (wide) frags.addAll(listOf(person, costume, pose, params, settings))
            else frags.addAll(listOf(person, costume, pose, generate, params, settings))

            val tr = supportFragmentManager.beginTransaction()
            frags.forEachIndexed { i, f ->
                tr.add(contentFrame.id, f, "f${i}")
                if (i != 0) tr.hide(f)
            }
            if (wide) rightFrame?.let { tr.add(it.id, generate, "gen") }
            tr.commitAllowingStateLoss()
        } else {
            frags.clear()
            for (i in 0 until count) {
                supportFragmentManager.findFragmentByTag("f${i}")?.let { frags.add(it) }
            }
            genFrag = supportFragmentManager.findFragmentByTag("gen") as? GenerateFragment
        }

        // 立即落盘 add 事务，避免 addTab 首次触发 onTabSelected 时 show/hide 尚未 add 的 Fragment
        supportFragmentManager.executePendingTransactions()
        titles.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Store.status.collect { statusBar.text = it }
            }
        }

        maybeAskMediaPermission()
        maybeShowDisclaimer()
        autoLoad()
    }

    private fun showTab(idx: Int) {
        if (idx !in frags.indices) return
        val tr = supportFragmentManager.beginTransaction()
        frags.forEachIndexed { i, f -> if (i == idx) tr.show(f) else tr.hide(f) }
        tr.commitAllowingStateLoss()
    }

    /** 启动时自动装载设置，并扫描已配置的素材库目录。 */
    private fun autoLoad() {
        val cfg = Store.cfg
        if (cfg.costumeDir.isBlank() && cfg.poseDir.isBlank()) {
            Store.setStatus(getString(R.string.status_ready))
            return
        }
        Store.setStatus("正在扫描手机本地素材库…")
        lifecycleScope.launch {
            val (c, p) = withContext(Dispatchers.IO) { Catalog.scan(this@MainActivity, cfg) }
            val err = listOf(Catalog.costumeError, Catalog.poseError).filter { it.isNotBlank() }
            Store.setStatus(
                if (err.isEmpty()) "设置已自动装载 · 服装 ${c} 件 / 姿态 ${p} 张"
                else "设置已自动装载 · ${err.joinToString("；")}"
            )
        }
    }

    private fun maybeAskMediaPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 101)
        }
    }

    /** 首次启动的成年人肖像使用确认（与桌面版免责声明一致）。 */
    private fun maybeShowDisclaimer() {
        val sp = getSharedPreferences("posewear_disclaimer", MODE_PRIVATE)
        if (sp.getBoolean("shown", false)) return
        AlertDialog.Builder(this)
            .setTitle("使用前请确认")
            .setMessage(
                "本应用仅用于处理「成年人本人或已获明确授权」的肖像照片，禁止上传未成年人照片。\n\n" +
                "照片只发送给你自己在设置页填写的 ComfyUI 地址，生成结果只保存在手机本地。\n\n" +
                "继续使用表示你已成年并确认上述内容。"
            )
            .setPositiveButton("我已成年并同意") { _, _ ->
                sp.edit().putBoolean("shown", true).apply()
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
