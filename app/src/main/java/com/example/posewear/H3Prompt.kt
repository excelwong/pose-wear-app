package com.example.posewear

import kotlin.random.Random

/**
 * MiniMax-H3-Image 的英文单段落提示词（与桌面版 h3_workflow.py 一致）。
 * 姿态部分直接用 _metadata.csv 的「姿态描述_英文」列（LibItem.descriptionEn）。
 * 参考图按上传顺序称 <Picture 1> <Picture 2> ……（人物 → 服装 → 姿态）。
 */
object H3Prompt {
    val POSE_FALLBACK_EN = "The body, arms, hands, legs and gaze follow the arrangement shown in the pose reference, with the same body orientation and camera direction."

    /** 「保持原姿势」时 <Subject N> 的完整保留句：原大小 / 原位置 / 原姿势 / 原表情。 */
    fun keepPoseRelation(subjectNo: Int, person: String): String =
        "<Subject $subjectNo> is fully preserved, together with the woman's original scale " +
        "and placement inside the frame: she is not enlarged, shrunk, shifted, " +
        "cropped or recomposed, and the distances from her head, hands and feet to " +
        "the frame edges stay exactly as in $person. Her body pose, limb placement, " +
        "finger positions, body orientation, head tilt and camera angle stay exactly " +
        "the same, and her facial expression, gaze direction, eyebrow and eye shape, " +
        "mouth shape and every subtle micro-expression remain untouched."

    /** 保持原姿势时的构图句：不再要求 full-length / half-body / 按新画幅重排。 */
    fun keepPoseFraming(person: String): String =
        "The original framing, subject scale and subject placement from $person are " +
        "preserved; the composition is not recomputed for a different output canvas."

    /** 「背景」下拉的三种英文描述（与桌面版 h3_workflow.py 逐字一致）。 */
    fun bgKeep(person: String): String =
        "The same background and soft illumination from $person sit behind " +
        "<Subject 1> at the same depth."
    val BG_WHITE_EN = "The original background, scenery, objects and shadows behind the subject " +
        "are replaced by a perfectly uniform, evenly lit pure white studio backdrop " +
        "with no texture, no objects, no gradient and no cast shadow. The subject " +
        "silhouette stays sharp and clearly separated from the pure white backdrop, " +
        "with clean edges around the hair, clothing and limbs. Soft natural light " +
        "falls evenly across the subject."
    val BG_TRANSPARENT_EN = "The original background, scenery, objects and shadows behind the subject " +
        "are replaced by a perfectly flat, uniform, evenly lit light studio backdrop " +
        "with no texture, no objects, no gradient and no cast shadow. The subject " +
        "silhouette stays sharp and clearly separated from the backdrop, with clean " +
        "edges around the hair, clothing and limbs, so the figure can be extracted " +
        "onto a transparent background. Soft natural light falls evenly across the subject."

    fun bgEn(bgMode: String = BgMode.KEEP, person: String = "<Picture 1>"): String = when (bgMode) {
        BgMode.WHITE -> BG_WHITE_EN
        BgMode.TRANSPARENT -> BG_TRANSPARENT_EN
        else -> bgKeep(person)
    }

    fun refTags(hasCostume: Boolean, hasPose: Boolean): Map<String, String> {
        val tags = mapOf("person" to "<Picture 1>")
        if (!hasCostume && !hasPose) return tags
        val m = LinkedHashMap<String, String>(tags)
        var n = 2
        if (hasCostume) { m["costume"] = "<Picture $n>"; n++ }
        if (hasPose) { m["pose"] = "<Picture $n>" }
        return m
    }

    /** costumeKeywords / poseName / poseAngle 仅为与 Qwen 侧签名对齐，H3 正文不写中文。 */
    @Suppress("UNUSED_PARAMETER")
    fun make(tags: Map<String, String>, costumeKeywords: String = "", poseName: String = "",
            poseAngle: String = "", poseDescriptionEn: String = "", bgMode: String = BgMode.KEEP,
            upperBody: Boolean = false, nude: Boolean = false, halfBody: Boolean = false): String {
        val person = tags["person"] ?: "<Picture 1>"
        val costume = tags["costume"]
        val pose = tags["pose"]
        val keepBackground = bgMode == BgMode.KEEP
        val parts = ArrayList<String>()
        parts.add("The woman in $person is <Subject 1>.")
        var subjectNo = 2
        var costumeSub: Int? = null
        var poseSub: Int? = null
        if (costume != null) {
            costumeSub = subjectNo
            parts.add("The complete outfit displayed in $costume is <Subject $costumeSub>.")
            subjectNo++
        }
        if (pose != null) {
            poseSub = subjectNo
            parts.add("The full pose and camera setup shown in $pose is <Subject $poseSub>.")
            subjectNo++
        }
        var keepCostumeSub: Int? = null
        var keepPoseSub: Int? = null
        var bgSub: Int? = null
        if (costume == null && !nude) {
            keepCostumeSub = subjectNo
            parts.add("The complete outfit worn by the woman in $person is <Subject $keepCostumeSub>.")
            subjectNo++
        }
        if (pose == null) {
            keepPoseSub = subjectNo
            parts.add("The original pose arrangement in $person is <Subject $keepPoseSub>.")
            subjectNo++
        }
        if (keepBackground) {
            bgSub = subjectNo
            parts.add("The original background and lighting setup in $person is <Subject $bgSub>.")
        }
        // 2. 目标图总览（保持原姿势时不能指定全身/半身，否则模型会重新摆位缩放人物）
        val keepPose = pose == null
        val framing = if (keepPose) "portrait"
            else if (upperBody) "upper-body portrait" else "full-length portrait"
        var overview = "The target image is a naturalistic $framing photograph of <Subject 1>"
        if (nude) overview += ", shown without clothing"
        overview += "."
        parts.add(overview)
        // 3. 关系
        val changed = ArrayList<String>()
        if (nude) changed.add("she is shown without clothing")
        else if (costume != null) changed.add("her outfit changes")
        if (pose != null) changed.add("her body pose changes")
        if (changed.isEmpty()) changed.add("only the presentation changes")
        parts.add("<Subject 1> is largely preserved; her facial features, facial expression, hairstyle, hair color, skin tone and body shape remain unchanged while " + changed.joinToString(" and ") + ".")
        if (keepCostumeSub != null) parts.add("<Subject $keepCostumeSub> is fully preserved.")
        if (keepPoseSub != null) parts.add(keepPoseRelation(keepPoseSub, person))
        if (bgSub != null) parts.add("<Subject $bgSub> is fully preserved.")
        if (costumeSub != null) {
            parts.add("<Subject $costumeSub> supplies the exact garments transferred to <Subject 1>. The transferred outfit keeps its cut, color, pattern, fabric texture and styling without importing anything else from $costume.")
        }
        if (poseSub != null) {
            parts.add("<Subject $poseSub> supplies the exact body pose, limb placement, face direction, body orientation and camera angle transferred to <Subject 1>.")
            val desc = poseDescriptionEn.trim()
            parts.add(if (desc.isEmpty()) POSE_FALLBACK_EN else desc)
        }
        // 4. 细节
        if (nude) {
            parts.add("The garments visible in $person are absent from the target image; no clothing appears on <Subject 1>.")
            if (halfBody) {
                parts.add("She has a slim, hourglass figure with a narrow waist and large breasts, rosy nipples, smooth bare skin.")
            } else {
                parts.add("She has a slim, hourglass figure with a narrow waist and large breasts, rosy nipples, smooth bare skin, and sparse pubic hair.")
            }
        }
        if (keepPose) {
            parts.add(keepPoseFraming(person))
        } else if (upperBody) {
            parts.add("The framing is a half-body portrait with the head and upper torso fully in frame, the head and shoulders uncropped, and the legs outside the image.")
        } else {
            parts.add("The entire figure stays inside the frame with the head and feet uncropped.")
        }
        parts.add(bgEn(bgMode, person))
        parts.add("Believable skin texture, fine fabric detail and high-resolution photographic clarity complete the portrait. No lettering, text or watermarks appear.")
        return parts.joinToString(" ")
    }

    fun nude(tags: Map<String, String>, poseDescriptionEn: String = "", bgMode: String = BgMode.KEEP,
            upperBody: Boolean = false, halfBody: Boolean = false): String =
        make(tags, "", "", "", poseDescriptionEn, bgMode, upperBody, true, halfBody)

    /** 「保持原服装 + 保持原姿势」（1 张参考图）：背景由 bgMode 决定。 */
    @Suppress("UNUSED_PARAMETER") // 与桌面版签名对齐；保持原姿势时不再按全身/半身重排构图
    fun plain(bgMode: String = BgMode.KEEP, upperBody: Boolean = false): String {
        val person = "<Picture 1>"
        val keep = bgMode == BgMode.KEEP
        val parts = mutableListOf(
            "The woman in $person is <Subject 1>.",
            "The complete outfit worn by the woman in $person is <Subject 2>.",
            "The original pose arrangement in $person is <Subject 3>."
        )
        if (keep) parts.add("The original background and lighting setup in $person is <Subject 4>.")
        parts += listOf(
            "The target image is a naturalistic portrait photograph of <Subject 1>.",
            "<Subject 1> is largely preserved; her facial features, facial expression, hairstyle, hair color, skin tone and body shape remain unchanged while " +
                    (if (keep) "only the presentation changes." else "only the background changes."),
            "<Subject 2> is fully preserved.",
            keepPoseRelation(3, person)
        )
        if (keep) {
            parts.add("<Subject 4> is fully preserved.")
            parts.add(bgKeep(person))
        } else {
            parts.add(if (bgMode == BgMode.WHITE) BG_WHITE_EN else BG_TRANSPARENT_EN)
        }
        parts += listOf(
            keepPoseFraming(person),
            "Believable skin texture, fine fabric detail and high-resolution photographic clarity complete the portrait. No lettering, text or watermarks appear."
        )
        return parts.joinToString(" ")
    }

    /** 兼容旧调用：等价于透明背景的双保持模式。 */
    fun stripBackground(upperBody: Boolean = false): String = plain(BgMode.TRANSPARENT, upperBody)

    fun validate(text: String, nImages: Int): String? {
        if (text.contains("<image")) return "当前 H3 档位需要英文 <Picture N> 提示词，但框里仍是 Qwen 的 <imageN> 标签。切换一次分辨率档位或重新点选素材即可自动重建。"
        val used = mutableSetOf<Int>()
        val pattern = "<Picture\\s+(\\d+)>".toRegex()
        for (m in pattern.findAll(text)) used.add(m.groupValues[1].toIntOrNull() ?: -1)
        if (used.isEmpty() || !used.contains(1)) return "提示词中没有 <Picture 1> 等参考图引用（H3-Image 用英文描述）。请重新点选素材。"
        val bad = used.filter { it < 1 || it > nImages }
        if (bad.isNotEmpty()) return "提示词引用了不存在的参考图 ${bad.joinToString(" ")}；当前只有 $nImages 张参考图。请重新点选素材。"
        return null
    }
}
