package com.example.posewear

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

/**
 * 「5.模型与参数」TAB：桌面版「分辨率与参数」页的安卓对应实现。
 * 只管不常改的配置；切换基模会联动 text encoder / 步数 / 分辨率档位。
 * 隐藏本页时自动保存，显示本页时从 Store.cfg 重新填充，避免与设置页互相覆盖。
 */
class ParamsFragment : Fragment(R.layout.fragment_params) {

    private lateinit var spTier: Spinner
    private lateinit var spUnet: Spinner
    private lateinit var spClip: Spinner
    private lateinit var spVae: Spinner
    private lateinit var etSteps: EditText
    private lateinit var etCfg: EditText
    private lateinit var etSeed: EditText
    private lateinit var etRefRes: EditText
    private lateinit var etGenTimeout: EditText
    private lateinit var etReqTimeout: EditText
    private lateinit var tvTierDetail: TextView
    private lateinit var tvPresets: TextView

    /** 程序化 setSelection 不应触发联动，也不能误判为「用户切换了基模」。 */
    private var suppressLinkage = false
    private var lastUnetLabel = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val fTier = view.findViewById<LinearLayout>(R.id.form_tier)
        val fParams = view.findViewById<LinearLayout>(R.id.form_params)
        val fModels = view.findViewById<LinearLayout>(R.id.form_models)
        tvTierDetail = view.findViewById(R.id.tv_tier_detail)
        tvPresets = view.findViewById(R.id.tv_presets)

        spTier = Form.spinner(ctx, fTier, "档位", Resolution.TIERS.keys.toList(), 0)
        etSteps = Form.edit(ctx, fParams, "steps", "采样步数", "", "1–60（H3 推荐 6）", numeric = true)
        etCfg = Form.edit(ctx, fParams, "cfg", "CFG", "", "官方 1.0", numeric = true)
        etSeed = Form.edit(ctx, fParams, "seed", "种子(0=随机)", "", "0 = 随机", numeric = true)
        etRefRes = Form.edit(ctx, fParams, "ref", "参考图边长", "", "1024", numeric = true)
        etGenTimeout = Form.edit(ctx, fParams, "gentimeout", "生成超时(秒)", "", "1200", numeric = true)
        etReqTimeout = Form.edit(ctx, fParams, "reqtimeout", "请求超时(秒)", "", "20", numeric = true)

        spUnet = Form.spinner(ctx, fModels, "基模", Models.UNET.map { it.label }, 0)
        spClip = Form.spinner(ctx, fModels, "Text Encoder", Models.CLIP.map { it.label }, 0)
        spVae = Form.spinner(ctx, fModels, "VAE", Models.VAE.map { it.label }, 0)

        // 先填值、再装监听器，避免初始化误触发联动覆盖用户保存的设置
        fill(Store.cfg)
        installLinkage()
        tvPresets.text = presetText()

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { save(showToast = true) }
        view.findViewById<MaterialButton>(R.id.btn_reset).setOnClickListener { resetParams() }
    }

    /**
     * 长文件名的下拉项：两行显示 + 中间省略，避免只看到开头几个字符。
     * Form.spinner 建好行之后，这里只替换 adapter，布局参数保持不变。
     */
    private fun setOptions(sp: Spinner, items: List<String>) {
        val adapter = object : ArrayAdapter<String>(
            requireContext(), android.R.layout.simple_spinner_item, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.apply {
                    setSingleLine(false)
                    maxLines = 2
                    textSize = 11f
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter
    }

    private fun fill(c: Cfg) {
        suppressLinkage = true
        setOptions(spTier, Resolution.TIERS.keys.toList())
        val unetOpts = Models.labels(Models.UNET, c.unet)
        setOptions(spUnet, unetOpts)
        setOptions(spClip, Models.labels(Models.CLIP, c.clip))
        setOptions(spVae, Models.labels(Models.VAE, c.vae))

        spTier.setSelection(Resolution.tierIndex(c.megapixels))
        val unetIdx = Models.indexOfValue(Models.UNET, c.unet)
        spUnet.setSelection(unetIdx)
        spClip.setSelection(Models.indexOfValue(Models.CLIP, c.clip))
        spVae.setSelection(Models.indexOfValue(Models.VAE, c.vae))
        lastUnetLabel = unetOpts[unetIdx]

        etSteps.setText(c.steps.toString())
        etCfg.setText(c.cfgScale.toString())
        etSeed.setText(c.seed.toString())
        etRefRes.setText(c.refResolution.toString())
        etGenTimeout.setText(c.generationTimeout.toString())
        etReqTimeout.setText(c.requestTimeout.toString())
        suppressLinkage = false
        refreshTierDetail()
    }

    private fun installLinkage() {
        spUnet.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressLinkage) return
                val label = spUnet.selectedItem?.toString() ?: return
                if (label == lastUnetLabel) return
                lastUnetLabel = label
                applyPreset(Models.labelToValue(Models.UNET, label))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spTier.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressLinkage) return
                val tierName = spTier.selectedItem?.toString() ?: return
                val mp = Resolution.tierValue(tierName) ?: return
                val newEngine = Resolution.engineFor(mp)
                // 同引擎内换档位不动用户手工改过的模型参数（与桌面版 _set_tier 一致）。
                val curUnet = Models.labelToValue(Models.UNET, spUnet.selectedItem?.toString() ?: "")
                val curEngine = if (curUnet == H3_UNET) "h3" else "qwen"
                if (curEngine == newEngine) { refreshTierDetail(); return }
                // 引擎变了：只套用模型三件套 + 步数 + CFG，绝不反向改用户刚选的档位
                // （否则 18/20MP 会被推荐档位拉回 16MP，并可能递归触发本回调）。
                val targetUnet = if (newEngine == "h3") H3_UNET
                    else "qwen-image-2.1-UC-int8_convrot.safetensors"
                applyPreset(targetUnet, syncTier = false)
                refreshTierDetail()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * 只在用户切换基模那一刻套用一次；清单外的自定义基模不联动。
     * H3 基模会同步 VAE 与 CFG；Qwen 基模恢复 Qwen VAE 与 1.0 CFG。
     */
    private fun applyPreset(unetValue: String, syncTier: Boolean = true) {
        val preset = Models.presetFor(unetValue) ?: return
        suppressLinkage = true
        // 基模下拉必须一起切，否则档位已是 H3、cfg.unet 却仍是 Qwen 模型
        setOptions(spUnet, Models.labels(Models.UNET, unetValue))
        spUnet.setSelection(Models.indexOfValue(Models.UNET, unetValue))
        lastUnetLabel = Models.valueToLabel(Models.UNET, unetValue)
        val clipValue = Models.clipValue(preset)
        setOptions(spClip, Models.labels(Models.CLIP, clipValue))
        spClip.setSelection(Models.indexOfValue(Models.CLIP, clipValue))
        etSteps.setText(preset.steps.toString())
        // 由档位切换引起的联动不动档位；由用户切换基模引起的联动套用推荐档位
        if (syncTier) {
            val tierIdx = Resolution.TIERS.keys.indexOf(preset.tier)
            if (tierIdx >= 0) spTier.setSelection(tierIdx)
        }
        val vaeValue = Models.labelToValue(Models.VAE, preset.vaeLabel)
        setOptions(spVae, Models.labels(Models.VAE, vaeValue))
        spVae.setSelection(Models.indexOfValue(Models.VAE, vaeValue))
        etCfg.setText(preset.cfgValue.toString())
        suppressLinkage = false
        refreshTierDetail()
        Store.setStatus("已套用推荐组合：${preset.clipLabel} · ${preset.steps} 步 · ${preset.tier}")
    }

    private fun refreshTierDetail() {
        if (!::tvTierDetail.isInitialized) return
        val mp = Resolution.tierValue(spTier.selectedItem?.toString() ?: "")
            ?: Store.cfg.megapixels
        val eng = Resolution.engineFor(mp)
        val engHint = if (eng == "h3") " · MiniMax-H3-Image" else ""
        tvTierDetail.text = "当前：${Resolution.tierDetail(Store.ratio, mp)}${engHint}"
    }

    private fun presetText(): String = buildString {
        append("切换基模的推荐组合：\n")
        Models.UNET.forEach { c ->
            val p = Models.presetFor(c.value)
            append("· ${c.label}\n")
            if (p != null) append("    ${p.clipLabel} · ${p.steps} 步 · ${p.tier}\n")
            else append("    自定义基模，不联动\n")
        }
        append("只在切换基模那一刻套用一次；之后手工改的值保持有效。")
    }

    private fun collect(): Cfg {
        val c = Store.cfg.copy()
        c.megapixels = Resolution.tierValue(spTier.selectedItem?.toString() ?: "") ?: c.megapixels
        // H3 官方预设 6 步 CFG 1；不能用 Qwen 的 8 步下限把它抬成 8 步
        val isH3 = Resolution.engineFor(c.megapixels) == "h3"
        c.steps = strInt(etSteps.text.toString(), c.steps).coerceIn(if (isH3) 1 else 8, 60)
        c.cfgScale = strDouble(etCfg.text.toString(), c.cfgScale).coerceIn(0.5, 8.0)
        c.seed = strLong(etSeed.text.toString(), c.seed).coerceIn(0L, 2147483647L)
        c.refResolution = strInt(etRefRes.text.toString(), c.refResolution).coerceIn(0, 4096)
        c.generationTimeout = strInt(etGenTimeout.text.toString(), c.generationTimeout)
            .coerceIn(60, 7200)
        c.requestTimeout = strInt(etReqTimeout.text.toString(), c.requestTimeout)
            .coerceIn(5, 300)
        c.unet = Models.labelToValue(Models.UNET, spUnet.selectedItem?.toString() ?: c.unet)
        c.clip = Models.labelToValue(Models.CLIP, spClip.selectedItem?.toString() ?: c.clip)
        c.vae = Models.labelToValue(Models.VAE, spVae.selectedItem?.toString() ?: c.vae)
        return c
    }

    private fun save(showToast: Boolean) {
        if (!::spTier.isInitialized || !isAdded) return
        Store.cfg = collect()
        Store.syncToCfg()
        Settings.save(requireContext().applicationContext, Store.cfg)
        Store.bumpConfig()
        if (showToast) {
            toast("模型与参数已保存")
            Store.setStatus("模型与参数已保存到本机，下次打开自动装载。")
        }
    }

    /** 只恢复本页字段；ComfyUI 地址、端口、素材库目录保持不动。 */
    private fun resetParams() {
        val d = Settings.defaults()
        val c = Store.cfg.copy(
            megapixels = d.megapixels, steps = d.steps, cfgScale = d.cfgScale,
            seed = d.seed, refResolution = d.refResolution,
            generationTimeout = d.generationTimeout, requestTimeout = d.requestTimeout,
            unet = d.unet, clip = d.clip, vae = d.vae
        )
        Store.cfg = c
        Store.syncToCfg()
        Settings.save(requireContext().applicationContext, c)
        fill(c)
        Store.bumpConfig()
        toast("模型与参数已恢复默认")
        Store.setStatus("模型与参数已恢复默认。")
    }

    /** 常驻 Fragment hide/show：切走自动保存，切回来同步全局配置。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!::spTier.isInitialized || !isAdded) return
        if (hidden) save(showToast = false)
        else {
            fill(Store.cfg)
            tvPresets.text = presetText()
        }
    }
}
