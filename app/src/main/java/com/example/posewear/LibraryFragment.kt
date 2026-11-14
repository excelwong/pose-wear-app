package com.example.posewear

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/** 服装库 / 姿态库共用的图片浏览器。姿态页的系统 → 分类 → 角度三级筛选为级联联动。 */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    companion object {
        private const val KEY_KIND = "kind"
        private const val PAGE_SIZE = 60
        private const val ALL_SYSTEM = "全部系列"
        private const val ALL_CATEGORY = "全部分类"
        private const val ALL_ANGLE = "全部角度"
        fun newInstance(kind: String) = LibraryFragment().apply {
            arguments = Bundle().apply { putString(KEY_KIND, kind) }
        }
    }

    private val kind: String get() = arguments?.getString(KEY_KIND) ?: Catalog.COSTUME
    private val isCostume get() = kind == Catalog.COSTUME

    private lateinit var btnKeep: MaterialButton
    private lateinit var btnNude: MaterialButton
    private lateinit var btnHalfBody: MaterialButton
    private lateinit var sp1: Spinner
    private lateinit var sp2: Spinner
    private lateinit var sp3: Spinner
    private lateinit var search: EditText
    private lateinit var info: TextView
    private lateinit var pageLabel: TextView
    private lateinit var rv: RecyclerView
    private lateinit var adapter: ThumbAdapter

    private lateinit var l1: AdapterView.OnItemSelectedListener
    private lateinit var l2: AdapterView.OnItemSelectedListener
    private lateinit var l3: AdapterView.OnItemSelectedListener

    private var all = listOf<LibItem>()
    private var filtered = listOf<LibItem>()
    private var page = 0
    private var spinnerReady = false
    private var cascadeGuard = false
    private var query = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        btnKeep = view.findViewById(R.id.btn_keep)
        btnNude = view.findViewById(R.id.btn_nude)
        btnHalfBody = view.findViewById(R.id.btn_half_body)
        sp1 = view.findViewById(R.id.sp_filter1)
        sp2 = view.findViewById(R.id.sp_filter2)
        sp3 = view.findViewById(R.id.sp_filter3)
        search = view.findViewById(R.id.et_search)
        info = view.findViewById(R.id.tv_info)
        pageLabel = view.findViewById(R.id.tv_page)
        rv = view.findViewById(R.id.rv_grid)

        // 姿态页三个选项按钮：保持原姿势 / 半身照，服装页两个：保持原服装 / 全裸
        btnKeep.text = if (isCostume) "保持原服装" else "保持原姿势"
        btnNude.visibility = if (isCostume) View.VISIBLE else View.GONE
        btnHalfBody.visibility = if (isCostume) View.GONE else View.VISIBLE

        adapter = ThumbAdapter { item -> onItemClicked(item) }
        rv.layoutManager = GridLayoutManager(
            requireContext(), resources.getInteger(R.integer.grid_columns)
        )
        rv.adapter = adapter
        rv.setHasFixedSize(true)

        btnKeep.setOnClickListener { toggleKeep() }
        btnNude.setOnClickListener { toggleNude() }
        btnHalfBody.setOnClickListener { toggleHalfBody() }
        view.findViewById<MaterialButton>(R.id.btn_prev).setOnClickListener {
            if (page > 0) { page--; render() }
        }
        view.findViewById<MaterialButton>(R.id.btn_next).setOnClickListener {
            if ((page + 1) * PAGE_SIZE < filtered.size) { page++; render() }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                applyFilter()
            }
        })

        refresh()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Store.catalogVersion.collect { if (isAdded) refresh() } }
                launch { Store.selection.collect { if (isAdded) syncUi() } }
            }
        }
    }

    // ---------- 三级联动 ----------

    private fun setupSpinners() {
        spinnerReady = false
        cascadeGuard = true
        try {
            // 服装页只有一级「分类」：选中后必须直接重新过滤。
            // 以前这里统一绑 onPrimaryChanged()，而它对服装页 isCostume 直接 return，
            // 导致下拉能选、列表却永远不刷新（BUG）。姿态页一级是「系统」，才走三级级联。
            l1 = listen { if (isCostume) applyFilter() else onPrimaryChanged() }
            l2 = listen { onSecondaryChanged() }
            l3 = listen { applyFilter() }

            // 保留用户当前的筛选（目录重新扫描后不会被悄悄重置）
            val prev1 = g1()
            val prev2 = g2()
            val prev3 = g3()

            if (isCostume) {
                fill(sp1, listOf(ALL_CATEGORY) + valuesOf(all) { it.category }, prev1, l1)
                sp2.visibility = View.GONE
                sp3.visibility = View.GONE
            } else {
                sp2.visibility = View.VISIBLE
                sp3.visibility = View.VISIBLE
                // 一级：系统；二级：该系统下的分类；三级：该系统 + 分类下的角度
                fill(sp1, listOf(ALL_SYSTEM) + valuesOf(all) { it.group }, prev1, l1)
                fill(sp2, listOf(ALL_CATEGORY) + valuesOf(bySystem()) { it.category }, prev2, l2)
                fill(sp3, listOf(ALL_ANGLE) + valuesOf(byCategory()) { it.angle }, prev3, l3)
            }
        } finally {
            cascadeGuard = false
            spinnerReady = true
        }
    }

    private fun onPrimaryChanged() {
        // 只处理姿态页的「系统」级联；服装页的一级是分类，由 setupSpinners 直接绑 applyFilter()
        if (!spinnerReady || cascadeGuard || isCostume) return
        cascadeGuard = true
        try {
            // 系统变了：分类列表收窄；旧分类若不属于新系统则回到「全部分类」
            val prev2 = g2()
            val prev3 = g3()
            fill(sp2, listOf(ALL_CATEGORY) + valuesOf(bySystem()) { it.category }, prev2, l2)
            fill(sp3, listOf(ALL_ANGLE) + valuesOf(byCategory()) { it.angle }, prev3, l3)
            applyFilter()
            // 系列变了：通知生成页按新系列重建提示词（上半身单人 → 半身构图）
            Store.bumpSelection()
        } finally {
            cascadeGuard = false
        }
    }

    private fun onSecondaryChanged() {
        if (!spinnerReady || cascadeGuard || isCostume) return
        cascadeGuard = true
        try {
            // 分类变了：角度列表收窄；旧角度若不在新结果里则回到「全部角度」
            val prev3 = g3()
            fill(sp3, listOf(ALL_ANGLE) + valuesOf(byCategory()) { it.angle }, prev3, l3)
            applyFilter()
        } finally {
            cascadeGuard = false
        }
    }

    private fun listen(onPick: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            // 级联重建期间与初始化期间屏蔽回调，避免 Spinner 自身回调递归
            if (spinnerReady && !cascadeGuard) onPick()
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun fill(
        sp: Spinner, values: List<String>, current: String,
        listener: AdapterView.OnItemSelectedListener
    ) {
        sp.onItemSelectedListener = null
        val ad = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, values)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        val idx = values.indexOf(current)
        sp.setSelection(if (idx >= 0) idx else 0)
        sp.onItemSelectedListener = listener
    }

    private fun valuesOf(src: List<LibItem>, field: (LibItem) -> String): List<String> =
        src.map(field).filter { it.isNotBlank() }.distinct().sorted()

    private fun g1() = sp1.selectedItem?.toString()
        ?: if (isCostume) ALL_CATEGORY else ALL_SYSTEM
    private fun g2() = sp2.selectedItem?.toString() ?: ALL_CATEGORY
    private fun g3() = sp3.selectedItem?.toString() ?: ALL_ANGLE
    private fun isAll(s: String) = s.startsWith("全部")

    /** 姿态页记录当前筛选系列，供生成页判断「上半身单人」半身构图。 */
    private fun syncPoseSystemToStore() {
        if (isCostume) return
        Store.poseSystem = if (isAll(g1())) "" else g1()
    }

    private fun bySystem(): List<LibItem> =
        all.filter { isAll(g1()) || it.group == g1() }

    private fun byCategory(): List<LibItem> =
        bySystem().filter { isCostume || isAll(g2()) || it.category == g2() }

    private fun refresh() {
        all = Catalog.list(kind)
        setupSpinners()
        applyFilter()
        syncUi()
        val err = if (isCostume) Catalog.costumeError else Catalog.poseError
        if (err.isNotBlank()) info.text = err
    }

    private fun applyFilter() {
        syncPoseSystemToStore()
        val s = g1()
        val c = g2()
        val a = g3()
        filtered = all.filter { it ->
            val ok1 = isAll(s) || if (isCostume) it.category == s else it.group == s
            val ok2 = isCostume || isAll(c) || it.category == c
            val ok3 = isCostume || isAll(a) || it.angle == a
            val okQ = query.isBlank() || listOf(it.id, it.name, it.category, it.group, it.angle, it.keywords)
                .any { t -> t.contains(query, ignoreCase = true) }
            ok1 && ok2 && ok3 && okQ
        }
        page = 0
        render()
    }

    private fun render() {
        val from = page * PAGE_SIZE
        val to = minOf(from + PAGE_SIZE, filtered.size)
        val slice = if (from < to) filtered.subList(from, to) else emptyList()
        adapter.set(slice, currentSelectedId())
        val pages = if (filtered.isEmpty()) 0 else (filtered.size + PAGE_SIZE - 1) / PAGE_SIZE
        pageLabel.text = "第 ${if (pages == 0) 0 else page + 1}/${pages} 页 · 共 ${filtered.size} 项"
    }

    private fun currentSelectedId(): String =
        if (isCostume) Store.costume?.id ?: "" else Store.pose?.id ?: ""

    private fun currentKeep(): Boolean = if (isCostume) Store.keepCostume else Store.keepPose
    private fun setCurrentKeep(v: Boolean) { if (isCostume) Store.keepCostume = v else Store.keepPose = v }
    private fun setCurrentItem(v: LibItem?) { if (isCostume) Store.costume = v else Store.pose = v }

    private fun syncUi() {
        val keep = currentKeep()
        val nude = isCostume && Store.nude
        val halfBody = !isCostume && Store.halfBody
        btnKeep.text = (if (keep) "✓ " else "") +
                (if (isCostume) "保持原服装" else "保持原姿势")
        btnKeep.alpha = if (keep) 1f else 0.82f
        btnNude.text = (if (nude) "✓ " else "") + "全裸 · 无服装"
        btnNude.alpha = if (nude) 1f else 0.82f
        btnHalfBody.text = (if (halfBody) "✓ " else "") + "半身照"
        btnHalfBody.alpha = if (halfBody) 1f else 0.82f
        adapter.setSelected(currentSelectedId())
        info.text = when {
            nude -> "已设为「全裸 · 无服装」，不上传服装参考图。"
            Store.plainMode() -> "两个「保持原样」已同时勾选：原样保留人物，背景由生成页「背景」下拉决定。"
            keep -> if (isCostume) "已设为「不换装 · 保持原服装」，只按姿态/原姿势生成。" else "已设为「不换姿势 · 保持原姿势」，不上传姿态参考图。"
            currentSelectedId().isNotEmpty() -> "已选择：${if (isCostume) Store.costume?.name else Store.pose?.name}"
            else -> "未选择 · 点选一张图片，或选择上方选项。"
        }
    }

    /**
     * 互斥关系只发生在「本页内部」：服装页的 图片 / 保持原服装 / 全裸 三者互斥，
     * 姿态页的 图片 / 保持原姿势 两者互斥。跨页互不影响 —— 在姿态页选图或点
     * 「保持原姿势」绝不能清掉服装页的「全裸」，否则切回服装页选中态就消失了。
     * 与桌面版 Browser._on_click / _keep_original 的语义一致。
     */
    private fun onItemClicked(item: LibItem) {
        setCurrentItem(item)
        setCurrentKeep(false)
        if (isCostume) Store.nude = false
        commit()
    }

    private fun toggleKeep() {
        val on = !currentKeep()
        setCurrentKeep(on)
        if (on) {
            if (isCostume) Store.nude = false
            setCurrentItem(null)
        }
        commit()
    }

    private fun toggleNude() {
        if (!isCostume) return   // 姿态页没有这个按钮，防御性守卫
        val on = !Store.nude
        Store.nude = on
        if (on) {
            Store.keepCostume = false
            Store.costume = null
        }
        commit()
    }

    private fun toggleHalfBody() {
        if (isCostume) return    // 服装页没有这个按钮，防御性守卫
        Store.halfBody = !Store.halfBody
        commit()
    }

    private fun commit() {
        Store.syncToCfg()
        Settings.save(requireContext(), Store.cfg)
        Store.bumpSelection()
        render()
        syncUi()
    }
}
