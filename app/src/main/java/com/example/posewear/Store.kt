package com.example.posewear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 进程内全局状态（参考 novel-studio-app 的 object Store 单例 + StateFlow）。 */
object Store {
    lateinit var cfg: Cfg

    // 人物照片：绝对路径或 content:// uri
    var personLocator: String = ""

    // 当前选中的素材
    var costume: LibItem? = null
    var pose: LibItem? = null

    // 姿态页当前筛选的「系统 / 系列」（如「上半身单人」），用于半身构图提示词；
    // 只在进程内保存，切换系列时由 LibraryFragment 同步
    var poseSystem: String = ""

    // 三种「不提供参考图」的选项，与桌面版一致；各页内部互斥，跨页互不影响
    var keepCostume = false     // 不换装 · 保持原服装
    var nude = false            // 全裸 · 无服装（仅服装页）
    var keepPose = false        // 不换姿势 · 保持原姿势

    var keepBackground = false
    var ratio = "3:4"

    var promptText = ""         // 切 tab 时不丢用户改过的提示词
    // 仅用于生成页标题状态提示；不再作为「阻止自动重建」的开关（与桌面版一致）
    var promptDirty = false

    var lastResult: String = "" // 最近一次生成结果的本地文件路径

    private val _status = MutableStateFlow("就绪。请先在「6.设置」填写 ComfyUI 地址与素材库目录。")
    val status: StateFlow<String> get() = _status

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> get() = _busy

    // 素材选择/扫描变化版本号，GenerateFragment 订阅它重建提示词
    private val _selection = MutableStateFlow(0)
    val selection: StateFlow<Int> get() = _selection

    private val _catalog = MutableStateFlow(0)
    val catalogVersion: StateFlow<Int> get() = _catalog

    // 配置（分辨率档位 / 采样参数 / 模型）保存或恢复默认后自增；
    // GenerateFragment 订阅它刷新分辨率备注，ParamsFragment 用它同步显示。
    private val _config = MutableStateFlow(0)
    val configVersion: StateFlow<Int> get() = _config

    private val _resultFile = MutableStateFlow("")
    val resultFile: StateFlow<String> get() = _resultFile

    fun setStatus(s: String) { _status.value = s }
    fun setBusy(b: Boolean) { _busy.value = b }
    fun bumpSelection() { _selection.value = _selection.value + 1 }
    fun bumpCatalog() { _catalog.value = _catalog.value + 1 }
    fun bumpConfig() { _config.value = _config.value + 1 }
    fun setResult(path: String) {
        lastResult = path
        _resultFile.value = path
    }

    /** 是否至少选了服装或姿态（含全裸/保持原姿势的特殊模式）。 */
    fun hasCostumeRef() = costume != null
    fun hasPoseRef() = pose != null
    fun hasAnyChoice() = hasCostumeRef() || hasPoseRef() || keepCostume || nude || keepPose

    /**
     * 恢复设置 / 重新扫描素材库后调用：只整理每一页内部的互斥关系，
     * 绝不改动另一页的选项（例如「全裸」不会清掉「保持原姿势」）。
     */
    fun normalizeModes() {
        if (nude) keepCostume = false
        if (nude || keepCostume) costume = null
        if (keepPose) pose = null
    }

    /** 刷新所有选择状态后调用：同步写回 cfg，便于下次启动恢复。 */
    fun syncToCfg() {
        if (!::cfg.isInitialized) return
        cfg.personPath = personLocator
        cfg.costumeId = costume?.id ?: ""
        cfg.poseId = pose?.id ?: ""
        cfg.keepCostume = keepCostume
        cfg.nude = nude
        cfg.keepPose = keepPose
        cfg.keepBackground = keepBackground
        cfg.ratio = ratio
        cfg.lastResult = lastResult
    }
}
