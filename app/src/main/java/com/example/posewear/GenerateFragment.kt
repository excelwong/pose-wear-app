package com.example.posewear

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class GenerateFragment : Fragment(R.layout.fragment_generate) {

    private lateinit var cbKeepBg: CheckBox
    private lateinit var tvMode: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvPromptLabel: TextView
    private lateinit var ratioGrid: GridLayout
    private lateinit var tvResolution: TextView
    private lateinit var etPrompt: EditText
    private lateinit var ivResult: ImageView
    private lateinit var tvResultHint: TextView
    private lateinit var tvResultInfo: TextView
    private lateinit var btnGenerate: MaterialButton
    private lateinit var btnInterrupt: MaterialButton
    private lateinit var btnOpen: MaterialButton
    private lateinit var btnSaveAs: MaterialButton
    private lateinit var progress: ProgressBar

    private val ratioButtons = mutableListOf<MaterialButton>()
    private var settingText = false
    private var job: Job? = null
    private var abort: AbortFlag? = null

    /** 只处理 selection 版本号的真实变化，避免切 tab 重新订阅时把手工修改覆盖掉。 */
    private var lastSelectionVersion = Int.MIN_VALUE

    private val saveAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val src = Store.lastResult
        if (src.isBlank()) return@registerForActivityResult
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                File(src).inputStream().use { it.copyTo(out) }
            }
            Store.setStatus("已另存为：${uri.lastPathSegment}")
            toast("已保存")
        } catch (e: Exception) {
            toastLong("保存失败：${e.message}")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cbKeepBg = view.findViewById(R.id.cb_keep_bg)
        tvMode = view.findViewById(R.id.tv_mode)
        tvProgress = view.findViewById(R.id.tv_progress)
        tvPromptLabel = view.findViewById(R.id.tv_prompt_label)
        ratioGrid = view.findViewById(R.id.ratio_grid)
        tvResolution = view.findViewById(R.id.tv_resolution)
        etPrompt = view.findViewById(R.id.et_prompt)
        ivResult = view.findViewById(R.id.iv_result)
        tvResultHint = view.findViewById(R.id.tv_result_hint)
        tvResultInfo = view.findViewById(R.id.tv_result_info)
        btnGenerate = view.findViewById(R.id.btn_generate)
        btnInterrupt = view.findViewById(R.id.btn_interrupt)
        btnOpen = view.findViewById(R.id.btn_open)
        btnSaveAs = view.findViewById(R.id.btn_save_as)
        progress = view.findViewById(R.id.progress)

        buildRatioButtons()
        // 初始化文本框不能算「用户手工修改」，否则后续自动更新会被误判阻断
        settingText = true
        etPrompt.setText(Store.promptText.ifBlank { Prompt.PLACEHOLDER })
        settingText = false
        updatePromptLabel()
        etPrompt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (settingText) return
                Store.promptText = s?.toString() ?: ""
                Store.promptDirty = true
                updatePromptLabel()
            }
        })

        cbKeepBg.isChecked = Store.keepBackground
        cbKeepBg.setOnCheckedChangeListener { _, on ->
            Store.keepBackground = on
            Store.syncToCfg()
            Settings.save(requireContext(), Store.cfg)
            // 与桌面版 _on_keep_bg() 一致：勾选状态变化立即重建提示词
            rebuildPrompt()
            updateMode()
        }

        btnGenerate.setOnClickListener { startGenerate() }
        btnInterrupt.setOnClickListener {
            abort?.set()
            val base = Store.cfg.baseUrl()
            CoroutineScope(Dispatchers.IO).launch { ComfyClient.interrupt(base) }
            Store.setStatus("正在中断…")
        }
        btnOpen.setOnClickListener { openBigImage() }
        btnSaveAs.setOnClickListener {
            val src = Store.lastResult
            if (src.isBlank()) { toastLong("还没有生成结果。"); return@setOnClickListener }
            saveAs.launch(File(src).name)
        }
        ivResult.setOnClickListener { openBigImage() }

        updateResolution()
        updateMode()
        if (Store.lastResult.isNotBlank()) showResult(Store.lastResult)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Store.selection.collect { v -> if (isAdded) onSelectionVersion(v) } }
                launch { Store.busy.collect { b -> if (isAdded) updateButtons(b) } }
                launch { Store.resultFile.collect { p -> if (isAdded && p.isNotBlank()) showResult(p) } }
                launch { Store.status.collect { s -> if (isAdded) tvProgress.text = s } }
                // 「5.模型与参数」页保存/恢复默认/联动改档位后，立即刷新生成页的分辨率备注
                launch { Store.configVersion.collect { if (isAdded) updateResolution() } }
            }
        }
    }

    private fun onSelectionVersion(v: Int) {
        if (v == lastSelectionVersion) return
        val first = lastSelectionVersion == Int.MIN_VALUE
        lastSelectionVersion = v
        if (first) onFirstSubscribe() else onSelectionChanged()
    }

    /** 首次订阅：冷启动恢复过素材但提示词还是占位符时补一次生成；否则保留现有文本。 */
    private fun onFirstSubscribe() {
        val cur = etPrompt.text?.toString()?.trim().orEmpty()
        val hasChoice = Store.nude || Store.costume != null || Store.pose != null
        if (hasChoice && (cur.isBlank() || cur == Prompt.PLACEHOLDER.trim())) {
            rebuildPrompt()
            return
        }
        updatePromptLabel()
        updateMode()
        updateResolution()
    }

    private fun buildRatioButtons() {
        ratioGrid.removeAllViews()
        ratioButtons.clear()
        val cols = resources.getInteger(R.integer.grid_columns)
        ratioGrid.columnCount = cols
        for (key in Resolution.RATIOS.keys) {
            val b = MaterialButton(requireContext()).apply {
                text = key
                textSize = 12f
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(requireContext(), 2), 0, dp(requireContext(), 2), 0)
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(requireContext(), 2), dp(requireContext(), 2),
                    dp(requireContext(), 2), dp(requireContext(), 2))
            }
            ratioGrid.addView(b, lp)
            ratioButtons += b
            b.setOnClickListener {
                Store.ratio = key
                Store.syncToCfg()
                Settings.save(requireContext(), Store.cfg)
                updateRatioButtons()
                updateResolution()
            }
        }
        updateRatioButtons()
    }

    private fun updateRatioButtons() {
        ratioButtons.forEachIndexed { i, b ->
            val key = Resolution.RATIOS.keys.elementAt(i)
            b.text = if (key == Store.ratio) "✓ ${key}" else key
            b.alpha = if (key == Store.ratio) 1f else 0.82f
        }
    }

    private fun updateResolution() {
        tvResolution.text = Resolution.resolutionText(Store.ratio, Store.cfg.megapixels)
    }

    /** 选择真的变了：无条件重建提示词（与桌面版 _update_prompt() 一致）。 */
    private fun onSelectionChanged() {
        rebuildPrompt()
        updateMode()
        updateResolution()
    }

    /**
     * 「上半身单人」系列的姿态图是半身 / 胸像，不能要求「人物完整入画、手清晰可见、不裁切头脚」。
     * 优先看实际选中的姿态参考图；没有姿态参考图时看姿态页当前筛选的系列。
     */
    private fun upperBodyActive(): Boolean {
        val p = Store.pose
        if (p != null) return p.group == Prompt.UPPER_BODY_SYSTEM
        if (Store.keepPose || Store.nude) return false
        return Store.poseSystem == Prompt.UPPER_BODY_SYSTEM
    }

    private fun updateMode() {
        var m = when {
            Store.nude -> "全裸 · 无服装"
            Store.costume != null && Store.pose != null -> "换装 + 换姿势"
            Store.costume != null -> "只换装 · 保持原姿势"
            Store.pose != null -> "只换姿势 · 保持原服装"
            else -> "未选择素材"
        }
        if (Store.keepBackground) m += " · 保持原图背景"
        if (upperBodyActive()) m += " · 半身构图"
        tvMode.text = m
    }

    private fun updatePromptLabel() {
        tvPromptLabel.text = if (Store.promptDirty)
            "提示词（已手工修改 · 更改素材会自动重新生成）"
        else
            "提示词（随素材自动更新，可临时修改）"
    }

    /** 与桌面版 app.py._update_prompt() 完全一致的模式分支。 */
    private fun rebuildPrompt() {
        val hasC = Store.costume != null
        val hasP = Store.pose != null
        val upper = upperBodyActive()
        var text = when {
            Store.nude -> Prompt.nudePrompt(
                hasP, Store.pose?.name ?: "", Store.pose?.angle ?: "", upper
            )
            !hasC && !hasP -> Prompt.PLACEHOLDER
            else -> Prompt.makePrompt(
                Prompt.refTags(hasC, hasP),
                Store.costume?.keywords ?: "",
                Store.pose?.name ?: "",
                Store.pose?.angle ?: "",
                upperBody = upper
            )
        }
        if (Store.keepBackground) text = Prompt.applyKeepBackground(text, upper)
        settingText = true
        etPrompt.setText(text)
        settingText = false
        Store.promptText = text
        Store.promptDirty = false
        updatePromptLabel()
    }

    private fun buildRefs(): List<RefSpec> {
        val refs = mutableListOf<RefSpec>()
        refs += RefSpec("person", Store.personLocator, "pws_person")
        Store.costume?.let { refs += RefSpec("costume", it.locator, "pws_costume") }
        Store.pose?.let { refs += RefSpec("pose", it.locator, "pws_pose") }
        return refs
    }

    private fun negativeFor(refs: List<RefSpec>, hasC: Boolean, hasP: Boolean): String {
        if (Store.nude && !hasP) return Prompt.negativeNudeNoPose()
        return Prompt.makeNegative(Prompt.refTags(hasC, hasP))
    }

    private fun validate(refs: List<RefSpec>, prompt: String): String? {
        if (Store.personLocator.isBlank()) return "请先在「1.人物照片」选择人物照片。"
        if (!Store.hasAnyChoice()) return "请至少选择服装或姿态（或勾选「不换装」「不换姿势」「全裸」）。"
        if (refs.size == 1 && !Store.nude)
            return "服装和姿态至少要选择一项；当前只有人物原图，无法构建生成提示词。"
        if (prompt.isBlank()) return "提示词为空，请先选择素材让提示词自动生成。"
        Prompt.validatePrompt(prompt, refs.size)?.let { return it }
        if (Store.cfg.host.isBlank()) return "请先在「6.设置」填写 ComfyUI 的 IP 地址。"
        return null
    }

    private fun startGenerate() {
        if (Store.busy.value) return
        val ctx = requireContext().applicationContext
        val cfg = Store.cfg.copy()
        val prompt = etPrompt.text.toString().trim()
        val refs = buildRefs()
        validate(refs, prompt)?.let { toastLong(it); return }

        val hasC = Store.costume != null
        val hasP = Store.pose != null
        val spec = GenSpec(
            refs = refs,
            prompt = prompt,
            negative = negativeFor(refs, hasC, hasP),
            ratioKey = Store.ratio,
            megapixels = cfg.megapixels,
            seed = cfg.seed,
            steps = cfg.steps,
            cfgScale = cfg.cfgScale,
            cfg = cfg
        )
        Store.syncToCfg()
        Settings.save(ctx, cfg)

        val flag = AbortFlag()
        abort = flag
        Store.setBusy(true)
        Store.setStatus("准备中…")
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val info = Generator.run(ctx, spec, flag)
                Store.setResult(info.path)
                Store.setStatus("完成：${File(info.path).name} · ${info.width}×${info.height} · 用时 ${info.seconds} 秒")
                toast("生成完成")
            } catch (e: CancellationException) {
                Store.setStatus("已中止。")
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                Store.setStatus("生成失败：${msg}")
                toastLong("生成失败：${msg}")
            } finally {
                Store.setBusy(false)
                abort = null
            }
        }
    }

    private fun updateButtons(busy: Boolean) {
        btnGenerate.isEnabled = !busy
        btnInterrupt.isEnabled = busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) tvProgress.text = Store.status.value
    }

    private fun showResult(path: String) {
        val f = File(path)
        if (!f.isFile) return
        val bmp = try { Images.decode(requireContext(), path, 1600) } catch (e: Exception) { null }
        if (bmp != null) {
            ivResult.setImageBitmap(bmp)
            tvResultHint.visibility = View.GONE
        }
        tvResultInfo.text = "${f.name}\n${f.absolutePath}"
    }

    private fun openBigImage() {
        val path = Store.lastResult
        if (path.isBlank() || !File(path).isFile) { toastLong("还没有生成结果。"); return }
        try {
            val ctx = requireContext()
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(path))
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (e: Exception) {
            toastLong("打开失败：${e.message}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        abort?.set()
    }
}
