/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import android.content.SharedPreferences

/**
 * 歌词逐字动画模式（v1.5.1 · A，用户可选）。
 *
 * v1.5.0 只有「开 / 关」两种状态，且开的时候是「逐词硬切」——S6 (Android 7.0) 上真实生效，
 * 但用户反馈两点：① 硬切不够顺滑；② PCL110 (Android 16) 上完全没有逐字效果
 * （根因见 [LyricsDisplayPrefs] 与 TASK.md：v1.5.0 走的是
 * `TextLayoutResult.getPathForRange` + `clipPath`，在 API 36 上拿不到可用的裁剪区域）。
 *
 * 改成三模式后：
 *  - [GRADIENT_SWEEP]：软边横扫（默认）。词内按时间线性推进，边缘 0.5~1 个字宽渐变；
 *  - [HARD_CUT]：v1.5.0 的逐词硬切，向后兼容保留；
 *  - [OFF]：不做逐字，当前行整行高亮（等价于 v1.4.x 的渲染路径）。
 */
object LyricsWordAnimationMode {
    const val GRADIENT_SWEEP = 0
    const val HARD_CUT = 1
    const val OFF = 2

    /** 越界值一律回落默认模式，绝不因为 prefs 被写坏而崩或不显示。 */
    fun normalize(raw: Int): Int = if (raw in GRADIENT_SWEEP..OFF) raw else GRADIENT_SWEEP
}

/**
 * 歌词显示相关的持久化设置（v1.5.1 · A/E）。
 *
 * 全部落在 `ncrust_settings`，与其余设置项一致（无 DI、无 Room，读时现取）。
 */
object LyricsDisplayPrefs {

    const val PREFS_NAME = "ncrust_settings"

    /** 逐字动画模式（Int，取值见 [LyricsWordAnimationMode]）。 */
    const val KEY_WORD_ANIMATION = "lyrics_word_animation"

    /** v1.5.0 的布尔开关，仅用于一次性迁移，不再写入。 */
    const val KEY_WORD_BY_WORD_LEGACY = "lyrics_word_by_word"

    /** 歌词字号倍率（Float，0.7~1.5）。 */
    const val KEY_FONT_SCALE = "lyrics_font_scale"

    const val FONT_SCALE_MIN = 0.7f
    const val FONT_SCALE_MAX = 1.5f
    const val FONT_SCALE_DEFAULT = 1.0f

    /** 设置页档位（TASK 里要求 5~7 档，这里取 5 档；A-/A+ 按键也走同一张表）。 */
    val FONT_SCALE_STEPS = listOf(0.7f, 0.85f, 1.0f, 1.2f, 1.5f)

    /**
     * 读逐字动画模式，并完成 v1.5.0 布尔开关的一次性迁移：
     * 老用户关掉过逐字 → [LyricsWordAnimationMode.OFF]（等价于他原来的选择）；
     * 老用户开着（默认）→ 新的默认 [LyricsWordAnimationMode.GRADIENT_SWEEP]。
     *
     * 迁移结果立刻写回，所以「用户关掉过逐字」这个意图只被消费一次。
     */
    fun readWordAnimation(prefs: SharedPreferences): Int {
        if (prefs.contains(KEY_WORD_ANIMATION)) {
            return LyricsWordAnimationMode.normalize(prefs.getInt(KEY_WORD_ANIMATION, LyricsWordAnimationMode.GRADIENT_SWEEP))
        }
        val migrated = if (prefs.getBoolean(KEY_WORD_BY_WORD_LEGACY, true)) {
            LyricsWordAnimationMode.GRADIENT_SWEEP
        } else {
            LyricsWordAnimationMode.OFF
        }
        prefs.edit().putInt(KEY_WORD_ANIMATION, migrated).apply()
        return migrated
    }

    fun writeWordAnimation(prefs: SharedPreferences, mode: Int) {
        prefs.edit().putInt(KEY_WORD_ANIMATION, LyricsWordAnimationMode.normalize(mode)).apply()
    }

    fun readFontScale(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_FONT_SCALE, FONT_SCALE_DEFAULT).coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)

    fun writeFontScale(prefs: SharedPreferences, scale: Float) {
        prefs.edit().putFloat(KEY_FONT_SCALE, scale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)).apply()
    }

    /** 找当前档位在 [FONT_SCALE_STEPS] 里的下标；不在表里时取最接近的一档。 */
    fun fontScaleStepIndex(scale: Float): Int {
        var best = 0
        var bestDiff = Float.MAX_VALUE
        FONT_SCALE_STEPS.forEachIndexed { i, v ->
            val d = kotlin.math.abs(v - scale)
            if (d < bestDiff) { bestDiff = d; best = i }
        }
        return best
    }

    /** A- / A+ ：在档位表上移动一格；已经在端点则返回原值（配合调用方的防抖）。 */
    fun steppedFontScale(scale: Float, delta: Int): Float {
        val i = (fontScaleStepIndex(scale) + delta).coerceIn(0, FONT_SCALE_STEPS.size - 1)
        return FONT_SCALE_STEPS[i]
    }
}
