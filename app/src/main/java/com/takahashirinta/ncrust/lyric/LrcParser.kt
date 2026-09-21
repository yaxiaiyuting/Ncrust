/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import androidx.compose.runtime.Immutable

/**
 * 逐字歌词里的一个词（v1.5.0 · B）。
 *
 * [startMs]/[durationMs] 是「相对整首歌开头」的绝对毫秒（yrc 的原生语义）；
 * [charStart]/[charEndExclusive] 是该词在本行 [LrcLine.text] 里的**字符区间**，已经被
 * 对齐到 LRC 的文本上（yrc 的行文本会比 LRC 少一些空格，见 [YrcParser]），渲染时直接
 * 用它去 TextLayoutResult 取路径，不需要再猜。
 */
@Immutable
data class LrcWord(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
    val charStart: Int,
    val charEndExclusive: Int
)

/**
 * 一行歌词。
 *
 * [words] 为空 = 这首歌没有逐字数据（或用户关掉了逐字开关），按普通 LRC 渲染；
 * 非空时每个词的起始时间由 yrc 提供，供逐字高亮使用。
 * [endMs] 是该行的结束时刻（yrc 的行 duration 换算而来）；null 表示未知。
 */
@Immutable
data class LrcLine(
    val timeMs: Long,
    val text: String,
    val words: List<LrcWord> = emptyList(),
    val endMs: Long? = null
)

object LrcParser {
    fun parse(lrcText: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")

        for (line in lrcText.lines()) {
            val match = regex.find(line.trim()) ?: continue
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            var ms = match.groupValues[3].toLong()
            if (match.groupValues[3].length == 2) ms *= 10
            val timeMs = min * 60000 + sec * 1000 + ms
            val text = match.groupValues[4].trim()
            if (text.isNotEmpty()) {
                lines.add(LrcLine(timeMs, text))
            }
        }

        return lines.sortedBy { it.timeMs }
    }
}