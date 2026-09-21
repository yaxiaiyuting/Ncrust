/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v1.2.0 · A3：音质状态判定。旧逻辑拿「服务端返回的 level 字符串」比档位序号，
 *     会把两种正常情况误报成「已降级」：
 *       ① level 标签写 lossless、实际文件是 Hi-Res（实测 br=1,685,762，type=flac）；
 *       ② 请求杜比全景声时服务端给了 jyeffect —— 沉浸声是不同容器/格式，不是降级。
 *     现在改为按**实际文件参数**（br/type）推算真实档位，再结合该曲的档位上限
 *     （privilege.maxBrLevel）区分「无权限」与「该曲无此档位」。
 */

package com.takahashirinta.ncrust.player

/** 音质标签后缀状态。NORMAL 时不加任何后缀。 */
enum class QualityStatus {
    /** 实际文件质量不低于请求档位（含"标签写低了、文件其实是高解析"）。 */
    NORMAL,

    /** 实际文件低于请求档位，但该曲本身有这个档位 → 账号/版权没给到。 */
    NO_ENTITLEMENT,

    /** 该曲本身就没有请求的档位（privilege.maxBrLevel 低于请求档位）。 */
    SONG_LACKS_TIER,

    /** 其余真实降级（服务端临时策略、解码器受限等）。 */
    DOWNGRADED,
}

data class QualityVerdict(val displayIndex: Int, val status: QualityStatus)

object QualityAssessment {
    /** 沉浸声档位：与立体声档位不是同一坐标系，不能按 br 直接比较。 */
    private val IMMERSIVE_TIERS = setOf("dolby", "jyeffect")

    // 实测锚点（2026-09，登录态）：母带 4.7–5.8 Mbps、jyeffect 2.8–3.1 Mbps、
    // Hi-Res 1.69 Mbps、无损 0.87–0.92 Mbps，均为 flac；320k 为 mp3。
    private const val MASTER_BR = 4_000_000L
    private const val HIRES_BR = 1_400_000L
    private const val LOSSLESS_BR = 700_000L

    /**
     * 由实际文件参数推算真实档位；拿不到（br=0 / 未知容器）返回 null，调用方据此不下结论。
     */
    fun measuredLevel(br: Long, type: String): String? = when (type.lowercase()) {
        "mp4" -> "dolby"
        "flac" -> when {
            br >= MASTER_BR -> "jymaster"
            br >= HIRES_BR -> "hires"
            br >= LOSSLESS_BR -> "lossless"
            else -> null
        }
        "mp3" -> when {
            br >= 300_000 -> "exhigh"
            br >= 190_000 -> "higher"
            br > 0 -> "standard"
            else -> null
        }
        else -> null
    }

    /**
     * 实际文件是否**可能**低于请求档位 —— 只有这种情况才值得再花一次请求去问该曲的
     * 档位上限（取链是开播关键路径，平时不要多加往返）。
     */
    fun needsSongCapability(requested: String, br: Long, type: String): Boolean {
        val levels = QualityLadder.LEVELS
        val measuredIdx = measuredLevel(br, type)?.let { levels.indexOf(it) } ?: return false
        val requestedIdx = levels.indexOf(requested).takeIf { it >= 0 } ?: return false
        if (measuredIdx >= requestedIdx) return false
        // 沉浸声换格式本来就不算降级（见 assess 的同款豁免），这里一并跳过，
        // 省掉一次纯属浪费的往返 —— 取链在开播关键路径上。
        if (requested in IMMERSIVE_TIERS && measuredIdx >= levels.indexOf("lossless")) return false
        return true
    }

    /**
     * @param requested 本次实际请求的 level（降档重试时是重试档位，不是用户偏好）
     * @param granted 服务端返回的 level 字符串（只是标签，可能与实际文件不符）
     * @param br / [type] 实际文件参数
     * @param songMaxLevel 该曲 privilege.maxBrLevel；拿不到传 null
     */
    fun assess(
        requested: String,
        granted: String,
        br: Long,
        type: String,
        songMaxLevel: String?,
    ): QualityVerdict {
        val levels = QualityLadder.LEVELS
        val requestedIdx = levels.indexOf(requested).takeIf { it >= 0 } ?: 0
        val grantedIdx = levels.indexOf(granted)
        val measuredIdx = measuredLevel(br, type)?.let { levels.indexOf(it) } ?: -1
        val capIdx = songMaxLevel?.let { levels.indexOf(it) } ?: -1

        // 展示档位取「标签」与「实际文件」中更高者：标签写低了不该跟着写低。
        // 未知标签（如未来的 sky）走 requestedIdx，绝不退化成索引 0（旧逻辑会显示成"压缩"）。
        val displayIdx = maxOf(
            if (grantedIdx >= 0) grantedIdx else requestedIdx,
            measuredIdx,
        ).coerceIn(0, levels.lastIndex)

        val status = when {
            // 拿不到实际文件参数：不妄下结论，保持安静
            measuredIdx < 0 -> QualityStatus.NORMAL
            // 实际文件不低于请求档位（含"标签写低了"与"尊享母带"两种）
            measuredIdx >= requestedIdx -> QualityStatus.NORMAL
            // 沉浸声换格式：请求杜比/环绕，实际拿到 >= 无损的沉浸声文件，不算降级
            requested in IMMERSIVE_TIERS && measuredIdx >= levels.indexOf("lossless") -> QualityStatus.NORMAL
            // 该曲自己就没有这个档位
            capIdx in 0 until requestedIdx -> QualityStatus.SONG_LACKS_TIER
            // 该曲有，但服务端没给
            capIdx >= requestedIdx -> QualityStatus.NO_ENTITLEMENT
            else -> QualityStatus.DOWNGRADED
        }
        return QualityVerdict(displayIdx, status)
    }
}
