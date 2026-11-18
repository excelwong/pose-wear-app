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
import androidx.fragment.app.FragmentTransaction
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
 * 折叠屏展开后（sw600dp+）变成左右双栏：
 *   左栏 3 个 tab（1.人物 / 2.服装 / 3.姿态），右栏 3 个 tab（4.生成 / 5.参数 / 6.设置）。
 *   两组 tab 完全同构，默认都选中第 0 个标签，其余标签为未选中状态。
 * 窄屏仍是单栏 6 个 tab，逻辑不变。
 */
class MainActivity : AppCompatActivity() {

    private val wide: Boolean by lazy { resources.getBoolean(R.bool.wide_screen) }
    private lateinit var tabs: TabLayout
    private var rightTabs: TabLayout? = null
    private lateinit var statusBar: TextView
    private var rightFrame: FrameLayout? = null
    private val frags = mutableListOf<Fragment>()        // 左栏（窄屏时为全部 6 个）
    private val rightFrags = mutableListOf<Fragment>()  // 宽屏右栏：生成 / 参数 / 设置

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabs = findViewById(R.id.tab_layout)
        statusBar = findViewById(R.id.status_bar)
        rightFrame = findViewById(R.id.right_frame)
        // 窄屏布局没有这两个 id，findViewById 返回 null，setupRightPanel() 会直接跳过
        rightTabs = findViewById(R.id.right_tab_layout)
        val contentFrame: FrameLayout = findViewById(R.id.content_frame)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.root_layout)) { v, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(safe.left, safe.top, safe.right, max(safe.bottom, ime))
            insets
        }

        val leftTitles = if (wide)
            listOf(R.string.tab_person, R.string.tab_costume, R.string.tab_pose)
        else
            listOf(
                R.string.tab_person, R.string.tab_costume, R.string.tab_pose,
                R.string.tab_generate, R.string.tab_params, R.string.tab_settings
            )

        buildFragments(contentFrame)

        // 立即落盘事务，避免 addTab 首次触发 onTabSelected 时 show/hide 尚未 add 的 Fragment
        supportFragmentManager.executePendingTransactions()
        leftTitles.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        setupRightPanel()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Store.status.collect { statusBar.text = it }
            }
        }

        maybeAskMediaPermission()
        maybeShowDisclaimer { maybeAskAllFilesAccess() }
        autoLoad()
    }

    /**
     * 按「当前布局」创建全部 Fragment。
     *
     * 不复用 FragmentManager 自动恢复出来的旧实例：折叠屏展开/收起会换布局，
     * 旧 Fragment 仍挂在以前的容器里，直接复用会让右栏空白或左栏重叠。
     * 页面状态本来就存在 Store / SharedPreferences 里，重建 Fragment 不会丢数据。
     */
    private fun buildFragments(contentFrame: FrameLayout) {
        val person = PersonFragment()
        val costume = LibraryFragment.newInstance(Catalog.COSTUME)
        val pose = LibraryFragment.newInstance(Catalog.POSE)
        val generate = GenerateFragment()
        val params = ParamsFragment()
        val settings = SettingsFragment()

        val tr = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments.forEach { tr.remove(it) }
        frags.clear()
        rightFrags.clear()
        if (wide) {
            frags.addAll(listOf(person, costume, pose))
            addGroup(tr, contentFrame.id, frags, "L")
            rightFrame?.let { frame ->
                // R0 = 生成（默认选中显示），R1 = 参数，R2 = 设置
                rightFrags.addAll(listOf(generate, params, settings))
                addGroup(tr, frame.id, rightFrags, "R")
            }
        } else {
            frags.addAll(listOf(person, costume, pose, generate, params, settings))
            addGroup(tr, contentFrame.id, frags, "L")
        }
        tr.commitAllowingStateLoss()
    }

    /** 把一组 Fragment add 进同一容器，默认只显示第 0 个，tag 用前缀 + 下标。 */
    private fun addGroup(tr: FragmentTransaction, frameId: Int,
                         list: List<Fragment>, prefix: String) {
        list.forEachIndexed { i, f ->
            tr.add(frameId, f, "$prefix$i")
            if (i != 0) tr.hide(f)
        }
    }

    /**
     * 右栏 tab 组与左栏同构：3 个标签（4.生成 / 5.参数 / 6.设置），
     * 默认选中第 0 个，其余两个保持未选中；点标签切换右栏内容。
     */
    private fun setupRightPanel() {
        val rt = rightTabs ?: return
        listOf(R.string.tab_generate, R.string.tab_params, R.string.tab_settings).forEach {
            rt.addTab(rt.newTab().setText(it))
        }
        rt.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { showRightPage(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        // 与左栏一致：默认选中第 0 个标签（4.生成），另外两个为未选中态。
        // 右栏 TabLayout 设了 saveEnabled=false，配置变更后也会回到这个默认态，
        // 与按当前布局重建后的 Fragment 显示保持一致。
        rt.selectTab(rt.getTabAt(0))
        showRightPage(0)
    }

    private fun showTab(idx: Int) = switchTo(frags, idx)
    private fun showRightPage(idx: Int) = switchTo(rightFrags, idx)

    private fun switchTo(list: List<Fragment>, idx: Int) {
        if (idx !in list.indices) return
        val tr = supportFragmentManager.beginTransaction()
        list.forEachIndexed { i, f -> if (i == idx) tr.show(f) else tr.hide(f) }
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

    /**
     * Android 11+ 未授予「所有文件访问权限」时，内置文件浏览器往往列不出
     * 部分目录、也读不到缩略图。首次启动引导一次，让浏览器能访问全部目录。
     */
    private fun maybeAskAllFilesAccess() {
        if (StoragePick.hasAllFilesAccess()) return
        val sp = getSharedPreferences("posewear_allfiles", MODE_PRIVATE)
        if (sp.getBoolean("asked", false)) return
        sp.edit().putBoolean("asked", true).apply()
        AlertDialog.Builder(this)
            .setTitle("开启「所有文件访问权限」")
            .setMessage(
                "为了能从本机任意目录选择照片和素材库，" +
                "Android 11 及以上需要开启本应用的「所有文件访问权限」。\n\n" +
                "请在接下来的系统设置里打开开关；也可以稍后到「6.设置」里补开。"
            )
            .setPositiveButton("去开启") { _, _ -> StoragePick.openAllFilesSettings(this) }
            .setNegativeButton("稍后") { _, _ -> }
            .show()
    }

    private fun maybeAskMediaPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 101)
        }
    }

    /** 首次启动的成年人肖像使用确认（与桌面版免责声明一致）。 */
    private fun maybeShowDisclaimer(onDone: () -> Unit) {
        val sp = getSharedPreferences("posewear_disclaimer", MODE_PRIVATE)
        if (sp.getBoolean("shown", false)) { onDone(); return }
        AlertDialog.Builder(this)
            .setTitle("使用前请确认")
            .setMessage(
                "本应用仅用于处理「成年人本人或已获明确授权」的肖像照片，禁止上传未成年人照片。\n\n" +
                "照片只发送给你自己在设置页填写的 ComfyUI 地址，生成结果只保存在手机本地。\n\n" +
                "继续使用表示你已成年并确认上述内容。"
            )
            .setPositiveButton("我已成年并同意") { _, _ ->
                sp.edit().putBoolean("shown", true).apply()
                onDone()
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
