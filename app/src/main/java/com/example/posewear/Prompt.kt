package com.example.posewear

/**
 * 与桌面版 workflow.py / app.py 逐字一致的提示词逻辑。
 * App 内另有「禁止未成年人照片」的提示与确认（2026-11-11 起提示词正文不再写「成年女性」）。
 *
 * 姿态参考句：统一前缀 + 冒号 + _metadata.csv「姿态描述_中文」整段。该列已包含
 * 画幅、躯体姿势、四肢手指、脸部朝向与镜头方向，不再按「角度」列额外拼短句。
 */
object Prompt {
    /** 姿态库 CSV「系统」列的这个取值是半身 / 胸像素材，不能套用全身构图描述。 */
    const val UPPER_BODY_SYSTEM = "上半身单人"

    /** 全身构图（与桌面版 workflow.py 逐字一致）。 */
    const val FRAME_FULL = "人物完整入画、不裁切头脚"
    const val FRAME_UPPER = "半身取景，头部与上半身完整入画、不裁切头部与肩膀，腿部不必入画"

    const val SCENE_TAIL_KEEP_BG = "保持<image1> 的背景、光线不变。"

    /** 「背景」下拉的另两种背景描述（与桌面版 workflow.py 逐字一致）。 */
    val BG_PARA_WHITE = "把她身后原有的背景全部替换为纯净、均匀的纯白色背景，整片背景为一致的纯白色，" +
            "不出现任何物体、纹理、渐变、阴影或投影；人物轮廓与纯白背景对比明确，" +
            "发丝、衣物和四肢边缘完整清晰。"
    val BG_PARA_TRANSPARENT = "把她身后原有的背景全部替换为纯净、均匀的单一浅色平涂背景，" +
            "不出现任何物体、纹理、渐变、阴影或投影；人物轮廓与背景对比明确，" +
            "发丝、衣物和四肢边缘完整清晰，便于精确抠出透明背景。"

    const val SCENE_TAIL = "根据输出画幅合理构图，" + FRAME_FULL + "，光线自然柔和，背景简洁干净，" +
            "真实皮肤质感，高细节、高清晰度，专业人像摄影，无文字无水印。"

    /** 上半身系列：只替换构图那一小句，光线 / 质感 / 画质描述保持不变。 */
    val SCENE_TAIL_UPPER = SCENE_TAIL.replace(FRAME_FULL, FRAME_UPPER)

    /**
     * 「保持原姿势」时的构图句。默认 SCENE_TAIL 里的「根据输出画幅合理构图，
     * 人物完整入画、不裁切头脚」会主动要求模型重新摆位缩放，与保持原大小 /
     * 原位置冲突；半身或近景原图还会被强行拉成全身。
     */
    const val SCENE_TAIL_KEEP_POSE = "保持<image1> 的原始取景与构图，人物在画面中的大小、位置与透视关系按原图等比延续，" +
            "不因输出画幅重新构图，光线自然柔和，背景简洁干净，" +
            "真实皮肤质感，高细节、高清晰度，专业人像摄影，无文字无水印。"

    /** 背景已单独描述时使用的构图 / 画质整句（不再写「背景简洁干净」）。 */
    val STRIP_BG_TAIL_FULL = "根据输出画幅合理构图，$FRAME_FULL，光线自然柔和，" +
            "真实皮肤质感，高细节、高清晰度，专业人像摄影，无文字无水印。"
    val STRIP_BG_TAIL_UPPER = "根据输出画幅合理构图，$FRAME_UPPER，光线自然柔和，" +
            "真实皮肤质感，高细节、高清晰度，专业人像摄影，无文字无水印。"
    /** 双保持「原样保留」同样要保持原姿势，构图句也不能要求重新摆位。 */
    val STRIP_BG_TAIL_KEEP_POSE = "保持<image1> 的原始取景与构图，人物在画面中的大小与位置按原图等比延续，" +
            "不因输出画幅重新构图，光线自然柔和，" +
            "真实皮肤质感，高细节、高清晰度，专业人像摄影，无文字无水印。"

    const val NEGATIVE_KEEP_POSE = "姿势被改变，四肢位置变化，动作不符，人物变大或变小，人物位置偏移，" +
            "重新构图，裁切头脚，面部表情改变，眼神或嘴型变化"

    const val NEGATIVE_BASE = "多个人物，多余肢体，畸形的手指，面部变形，身份不一致，" +
            "低分辨率，模糊，过曝，卡通，插画，3D 渲染，文字，水印，logo，边框"

    const val NUDE_BODY = "纤细沙漏型身材，窄腰，丰满的胸部，粉嫩乳头，光滑裸露的肌肤，阴部有稀疏的阴毛"
    const val NUDE_BODY_HALF = "纤细沙漏型身材，窄腰，丰满的胸部，粉嫩乳头，光滑裸露的肌肤"

    /** 姿态参考句统一前缀，与桌面版 workflow.py 的 POSE_REF_PREFIX 生成的句子逐字一致。 */
    const val POSE_REF_PREFIX = "让她的身体姿势、四肢位置、脸部朝向、身体朝向与镜头角度完全参照"

    /**
     * 生成姿态参考句：统一前缀 + 冒号 + _metadata.csv「姿态描述_中文」整段。
     * description 自带句末句号；旧素材库缺列时降级为「姿态名 + 角度视角。」，
     * 但不追加任何按角度推断的脸部朝向短句。与桌面版 pose_reference_line() 行为一致。
     */
    fun poseReferenceLine(
        poseTag: String,
        description: String = "",
        poseName: String = "",
        poseAngle: String = ""
    ): String {
        var desc = description.trim()
        if (desc.isBlank()) {
            val bits = listOfNotNull(
                poseName.trim().takeIf { it.isNotEmpty() },
                poseAngle.trim().takeIf { it.isNotEmpty() }?.let { "${it}视角" }
            )
            desc = if (bits.isEmpty()) "" else bits.joinToString("，") + "。"
        } else if (listOf("。", "！", "？", ".", "!", "?").none { desc.endsWith(it) }) {
            desc += "。"
        }
        return "$POSE_REF_PREFIX $poseTag" + if (desc.isEmpty()) "。" else "：$desc"
    }

    fun sceneTail(upperBody: Boolean, keepPose: Boolean = false): String = when {
        keepPose -> SCENE_TAIL_KEEP_POSE
        upperBody -> SCENE_TAIL_UPPER
        else -> SCENE_TAIL
    }

    /** 按「背景」下拉返回提示词末尾整句（与桌面版 bg_scene_tail() 一致）。 */
    fun bgSceneTail(bgMode: String = BgMode.KEEP, upperBody: Boolean = false,
                    keepPose: Boolean = false): String {
        if (bgMode == BgMode.KEEP)
            return SCENE_TAIL_KEEP_BG + (if (upperBody) FRAME_UPPER else "")
        val para = if (bgMode == BgMode.WHITE) BG_PARA_WHITE else BG_PARA_TRANSPARENT
        val framing = when {
            keepPose -> STRIP_BG_TAIL_KEEP_POSE
            upperBody -> STRIP_BG_TAIL_UPPER
            else -> STRIP_BG_TAIL_FULL
        }
        return para + "\n" + framing
    }

    /** 「保持原姿势」的约束句：原大小 + 原位置 + 原姿势 + 原表情（与桌面版逐字一致）。 */
    fun keepPoseLine(person: String = "<image1>"): String =
        "严格保持 $person 中她在画面里的原始大小与原始位置不变：人物不放大、不缩小、不平移、" +
        "不重新构图、不裁切，头部、手脚及身体各部位到画幅边缘的距离都与原图一致；" +
        "身体姿势、四肢位置、手指状态、身体朝向、头部角度与镜头角度完全不变；" +
        "面部表情、眼神与视线方向、眉眼状态、嘴型以及所有微表情也完全不变，" +
        "不要改变动作、站姿或坐姿。"

    /** 参考图顺序固定：人物 → 服装（可选）→ 姿态（可选）。 */
    fun refTags(hasCostume: Boolean, hasPose: Boolean): Map<String, String> {
        if (!hasCostume && !hasPose) throw IllegalArgumentException("服装和姿态至少要选择一项")
        val t = LinkedHashMap<String, String>()
        t["person"] = "<image1>"
        var n = 2
        if (hasCostume) { t["costume"] = "<image${n}>"; n++ }
        if (hasPose) { t["pose"] = "<image${n}>" }
        return t
    }

    fun makePrompt(
        tags: Map<String, String>,
        costumeKeywords: String = "",
        poseName: String = "",
        poseAngle: String = "",
        bgMode: String = BgMode.KEEP,
        upperBody: Boolean = false,
        poseDescription: String = ""
    ): String {
        if (!tags.containsKey("costume") && !tags.containsKey("pose"))
            throw IllegalArgumentException("服装和姿态至少要选择一项")
        val person = tags["person"] ?: "<image1>"
        val costume = tags["costume"]
        val pose = tags["pose"]
        val lines = mutableListOf<String>()
        lines += "以 ${person} 中的女性为主角，严格保持她的面部特征、五官、发型发色、肤色与体型不变，生成一张自然写实的人像摄影照片。"
        if (costume != null) {
            val cloth = if (costumeKeywords.isBlank()) "" else "（${costumeKeywords}）"
            lines += "让她换上 ${costume} 中展示的整套服装，精确还原款式、颜色、图案、面料质感与搭配${cloth}。"
        } else {
            lines += "严格保持 ${person} 中她原本的服装不变：款式、颜色、图案、面料质感与搭配都与原照片完全一致，不要更换、添加或改动任何衣物。"
        }
        val keepPose = pose == null
        if (pose != null) {
            lines += poseReferenceLine(pose, poseDescription, poseName, poseAngle)
        } else {
            lines += keepPoseLine(person)
        }
        lines += bgSceneTail(bgMode, upperBody, keepPose)
        return lines.joinToString("\n")
    }

    /** 桌面版 app.py._nude_prompt() 的两种分支。 */
    fun nudePrompt(
        hasPose: Boolean,
        poseName: String = "",
        poseAngle: String = "",
        upperBody: Boolean = false,
        poseDescription: String = "",
        bgMode: String = BgMode.KEEP,
        halfBody: Boolean = false
    ): String {
        val lines = mutableListOf<String>()
        lines += "以 <image1> 中的女性为主角，严格保持她的面部特征、五官、发型发色、肤色与体型不变。"
        lines += "让她完全脱光所有衣物，呈现全裸状态。"
        if (hasPose) {
            lines += poseReferenceLine("<image2>", poseDescription, poseName, poseAngle)
        } else {
            lines += keepPoseLine("<image1>")
        }
        lines += bgSceneTail(bgMode, upperBody, keepPose = !hasPose)
        lines += if (halfBody) NUDE_BODY_HALF else NUDE_BODY
        return lines.joinToString("\n")
    }

    fun makeNegative(tags: Map<String, String>): String {
        val extra = mutableListOf<String>()
        if (tags.containsKey("costume")) extra += "服装错乱" else extra += "服装被更换，衣物款式或颜色改变，换装"
        if (tags.containsKey("pose")) extra += "姿势不符" else extra += NEGATIVE_KEEP_POSE
        return extra.joinToString("，") + "，" + NEGATIVE_BASE
    }

    fun negativeNudeNoPose(): String =
        "多个人物，多余肢体，畸形的手指，面部变形，身份不一致，服装，衣物，内衣，$NEGATIVE_KEEP_POSE"

    /** 「保持原服装 + 保持原姿势」（1 张参考图）：背景由 bgMode 决定。 */
    @Suppress("UNUSED_PARAMETER") // 与桌面版签名对齐；保持原姿势时不再按全身/半身重排构图
    fun plainPrompt(bgMode: String = BgMode.KEEP, upperBody: Boolean = false): String {
        val person = "<image1>"
        return listOf(
            "以 $person 中的女性为主角，严格保持她的面部特征、五官、发型发色、肤色与体型不变，生成一张自然写实的人像摄影照片。",
            "严格保持 $person 中她原本的服装不变：款式、颜色、图案、面料质感与搭配都与原照片完全一致，不要更换、添加或改动任何衣物。",
            keepPoseLine(person),
            bgSceneTail(bgMode, upperBody, keepPose = true)
        ).joinToString("\n")
    }

    /** 兼容旧调用：等价于透明背景的双保持模式。 */
    fun stripBackgroundPrompt(upperBody: Boolean = false): String =
        plainPrompt(BgMode.TRANSPARENT, upperBody)

    /** 1 张参考图时 refTags(false,false) 不可用，用这份专用负面词。 */
    val NEGATIVE_PLAIN = "服装被更换，衣物款式或颜色改变，换装，$NEGATIVE_KEEP_POSE，$NEGATIVE_BASE"

    /** 纯白 / 透明背景下额外压制的背景元素。 */
    val NEGATIVE_BG_CLUTTER = "背景杂物，复杂背景，纹理背景，渐变背景，阴影，投影"

    fun withBgNegative(negative: String, bgMode: String = BgMode.KEEP): String =
        negative + (if (bgMode != BgMode.KEEP) "，$NEGATIVE_BG_CLUTTER" else "")

    fun negativeStripBg(): String = withBgNegative(NEGATIVE_PLAIN, BgMode.TRANSPARENT)

    /** 幂等：把默认构图/背景整句替换为「保持原图背景」文案（两种构图版本都支持）。 */
    fun applyKeepBackground(text: String, upperBody: Boolean = false): String {
        val replacement = if (upperBody) SCENE_TAIL_KEEP_BG + FRAME_UPPER else SCENE_TAIL_KEEP_BG
        // 同时兼容默认版与「保持原姿势」版的构图整句；大小/位置/表情约束由 keepPoseLine 独立承担
        return text.replace(sceneTail(upperBody), replacement)
            .replace(SCENE_TAIL_KEEP_POSE, replacement)
    }

    fun validatePrompt(text: String, nImages: Int): String? {
        val used = Regex("<image(\\d+)>").findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct().sorted().toList()
        if (used.isEmpty()) return "提示词中没有 <image1> 等参考图引用，请重新点选素材。"
        val bad = used.filter { it < 1 || it > nImages }
        if (bad.isNotEmpty())
            return "提示词引用了不存在的参考图 ${bad.joinToString { "<image${it}>" }}；当前只有 ${nImages} 张参考图，请重新点选素材。"
        return null
    }

    val PLACEHOLDER = """
        # 请先在「② 服装库」或「③ 姿态库」中至少选择一项（也可两项都选）。
        # 只选服装：保持原姿势、只换装；只选姿态：保持原服装、只换姿势。
        # 两项都选：同时换装与换姿势。选择后本提示词会自动生成，也可自行修改。
        # 两个「保持原样」同时勾选：什么都不换，只按生成页「背景」下拉处理背景。
        # 背景下拉：保持原图背景（默认）/ 纯白背景 / 透明背景（抠成 RGBA 透明 PNG）。
        # 勾选「保持原姿势」时，人物在原图中的大小、位置、姿势与表情都会严格保持，并按原取景输出。
        # 背景选择会自动保存，下次打开应用时沿用。
    """.trimIndent()
}
