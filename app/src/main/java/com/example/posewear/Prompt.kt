package com.example.posewear

/**
 * 与桌面版 workflow.py / app.py 逐字一致的提示词逻辑。
 * 所有文案都明确限定「成年女性」，App 内另有「禁止未成年人照片」的提示与确认。
 */
object Prompt {
    /** 姿态库 CSV「系统」列的这个取值是半身 / 胸像素材，不能套用全身构图描述。 */
    const val UPPER_BODY_SYSTEM = "上半身单人"

    /** 全身构图强调手清晰可见，避免袖口空荡、缺手（与桌面版 workflow.py 逐字一致）。 */
    const val FRAME_FULL = "人物完整入画、手清晰可见、不裁切头脚"
    const val FRAME_UPPER = "半身取景，头部与上半身完整入画、不裁切头部与肩膀，腿部不必入画"

    const val SCENE_TAIL_KEEP_BG = "保持<image1> 的背景、光线不变。"

    const val SCENE_TAIL = "根据输出画幅合理构图，" + FRAME_FULL + "，光线自然柔和，背景简洁干净，" +
            "真实皮肤质感，高细节、高清晰度，专业人像摄影，无文字无水印。"

    /** 上半身系列：只替换构图那一小句，光线 / 质感 / 画质描述保持不变。 */
    val SCENE_TAIL_UPPER = SCENE_TAIL.replace(FRAME_FULL, FRAME_UPPER)

    const val NEGATIVE_BASE = "多个人物，多余肢体，畸形的手指，面部变形，身份不一致，" +
            "低分辨率，模糊，过曝，卡通，插画，3D 渲染，文字，水印，logo，边框"

    const val NUDE_TAGS = "completed_nude,slimthick body type with large prominent chest,with a slim waist and a full bust,huge_breasts,large_breasts,small_nipples,sparse_pubic_hair"

    fun sceneTail(upperBody: Boolean): String =
        if (upperBody) SCENE_TAIL_UPPER else SCENE_TAIL

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
        keepBackground: Boolean = false,
        upperBody: Boolean = false
    ): String {
        if (!tags.containsKey("costume") && !tags.containsKey("pose"))
            throw IllegalArgumentException("服装和姿态至少要选择一项")
        val person = tags["person"] ?: "<image1>"
        val costume = tags["costume"]
        val pose = tags["pose"]
        val lines = mutableListOf<String>()
        lines += "以 ${person} 中的成年女性为主角，严格保持她的面部特征、五官、发型发色、肤色与体型不变，生成一张自然写实的人像摄影照片。"
        if (costume != null) {
            val cloth = if (costumeKeywords.isBlank()) "" else "（${costumeKeywords}）"
            lines += "让她换上 ${costume} 中展示的整套服装，精确还原款式、颜色、图案、面料质感与搭配${cloth}。"
        } else {
            lines += "严格保持 ${person} 中她原本的服装不变：款式、颜色、图案、面料质感与搭配都与原照片完全一致，不要更换、添加或改动任何衣物。"
        }
        if (pose != null) {
            val desc = if (poseName.isBlank()) "" else "：${poseName}"
            val angle = if (poseAngle.isBlank()) "" else "，${poseAngle}视角"
            lines += "让她的身体姿势、四肢位置、脸部和身体朝向与镜头角度完全参照 ${pose}${desc}${angle}。"
        } else {
            lines += "严格保持 ${person} 中她原本的身体姿势、四肢位置、身体朝向与镜头角度不变，不要改变动作、站姿或坐姿。"
        }
        lines += when {
            // 保持原图背景时整句替换，但半身构图约束要保留，不能跟着一起丢掉
            keepBackground && upperBody -> SCENE_TAIL_KEEP_BG + FRAME_UPPER
            keepBackground -> SCENE_TAIL_KEEP_BG
            else -> sceneTail(upperBody)
        }
        return lines.joinToString("\n")
    }

    /** 桌面版 app.py._nude_prompt() 的两种分支。 */
    fun nudePrompt(
        hasPose: Boolean,
        poseName: String = "",
        poseAngle: String = "",
        upperBody: Boolean = false
    ): String {
        val lines = mutableListOf<String>()
        lines += "以 <image1> 中的成年女性为主角，严格保持她的面部特征、五官、发型发色、肤色与体型不变。"
        lines += "让她完全脱光所有衣物，呈现全裸状态。"
        if (hasPose) {
            val desc = if (poseName.isBlank()) "" else "：${poseName}"
            val angle = if (poseAngle.isBlank()) "" else "，${poseAngle}视角"
            lines += "让她的身体姿势、四肢位置、脸部和身体朝向与镜头角度完全参照 <image2>${desc}${angle}。"
        } else {
            lines += "保持她原本的身体姿势不变。"
        }
        lines += sceneTail(upperBody)
        lines += NUDE_TAGS
        return lines.joinToString("\n")
    }

    fun makeNegative(tags: Map<String, String>): String {
        val extra = mutableListOf<String>()
        if (tags.containsKey("costume")) extra += "服装错乱" else extra += "服装被更换，衣物款式或颜色改变，换装"
        if (tags.containsKey("pose")) extra += "姿势不符" else extra += "姿势被改变，四肢位置变化，动作不符"
        return extra.joinToString("，") + "，" + NEGATIVE_BASE
    }

    fun negativeNudeNoPose(): String =
        "多个人物，多余肢体，畸形的手指，面部变形，身份不一致，服装，衣物，内衣"

    /** 幂等：把默认构图/背景整句替换为「保持原图背景」文案（两种构图版本都支持）。 */
    fun applyKeepBackground(text: String, upperBody: Boolean = false): String {
        val replacement = if (upperBody) SCENE_TAIL_KEEP_BG + FRAME_UPPER else SCENE_TAIL_KEEP_BG
        return text.replace(sceneTail(upperBody), replacement)
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
        # 勾选「保持原图背景」后，构图/背景描述改为：保持<image1> 的背景、光线不变。
    """.trimIndent()
}
