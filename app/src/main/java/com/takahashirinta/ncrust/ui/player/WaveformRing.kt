/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import kotlin.math.abs

/**
 * v1.8.0 · T3：音频可视化的**无锁单写者环形缓冲 + 柱状滚动窗口**（纯逻辑，可 JVM 单测）。
 *
 * 线程模型是本文件存在的全部理由：
 *
 *  - **写**只有一条线程 —— ExoPlayer 的 playback 线程（`ExoPlayer:Playback`，
 *    THREAD_PRIORITY_AUDIO）。[push] 里只做一次数组写 + 一次 volatile 自增，
 *    **零分配、零锁、零 IO**：音频线程上任何一次 GC 停顿都是爆音。
 *  - **读**是 UI 线程按帧率调用的 [pump]：把"上次之后新到的柱"搬进滚动窗口。
 *  - 两侧唯一的同步是 `writeIndex` 的 volatile 语义（单写者 ⇒ 读到的一定是某个一致前缀）。
 *    溢出时**丢最旧的**而不是等 —— 可视化允许丢帧，绝不允许回压到音频线程。
 *
 * [bars] 是 UI 侧直接拿去 draw 的滚动窗口（下标 0 = 最旧，最后一项 = 最新）。
 * 不做任何插值：数据本身 20 柱/秒，插值只会让 S6 多画几十次没信息量的帧。
 */
class WaveformRing(
    /** 环形缓冲容量（柱数）。UI 掉帧时最多积压这么多，再多就丢最旧的。 */
    private val capacity: Int,
    /** 滚动窗口长度 = 画面上的柱子数。 */
    val barCount: Int,
) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(barCount > 0) { "barCount must be > 0" }
    }

    private val ring = FloatArray(capacity)

    /**
     * 写入游标 = 累计写入的柱数（不是下标）。
     *
     * volatile：音频线程写、UI 线程读。**单写者**，所以 UI 侧先快照它、再读
     * `[0, snapshot)` 区间，读到的一定是连续且已经写完整的值。
     */
    @Volatile
    private var writeIndex: Int = 0

    /** UI 侧已消费到的位置。只有 UI 线程读写。 */
    private var readIndex: Int = 0

    /** 画面上的柱子高度（0..1）。UI 线程原地更新，绝不重新分配 —— 见 AGENTS.md 的零重组原则。 */
    private val bars = FloatArray(barCount)

    /** 音频线程：推入一根柱（RMS 幅度，0..1）。 */
    fun push(value: Float) {
        // NaN / Inf 防御：环形缓冲的边界读在和写者赛跑时理论上可能读到半个值
        // （见 pump 的注释）。宁可画成静音，也不要让 NaN 传染给整块画布。
        ring[writeIndex % capacity] = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        writeIndex += 1
    }

    /** UI 线程：清空（换歌 / 停止时用）。 */
    fun clear() {
        readIndex = writeIndex
        bars.fill(0f)
    }

    /**
     * UI 线程按帧率调用。
     *
     * @param active 播放中（且不在缓冲）时为 true：消费新柱；false 时把窗口**指数衰减**到 0
     *   —— 暂停/缓冲瞬间清零会"闪一下"，衰减看起来像余震自然消失。
     * @param decay 每帧的衰减系数（0..1）。0.7 在 20fps 下约 0.3s 归零。
     * @return 画面是否需要重绘（没有新数据且已经完全归零时返回 false ⇒ 零帧调度）。
     */
    fun pump(active: Boolean, decay: Float = DEFAULT_DECAY): Boolean {
        if (!active) return decayToZero(decay)
        val snapshot = writeIndex
        var pending = snapshot - readIndex
        if (pending <= 0) return false
        if (pending > capacity) {
            // 溢出：丢掉最旧的，绝不阻塞写者。
            readIndex = snapshot - capacity
            pending = capacity
        }
        repeat(pending) {
            // 注意：读到的是"某一时刻的值"，写者可能刚好覆盖同一格（只在溢出时发生）。
            // 单根柱失真在视觉上不可见，也不影响后续帧 —— 因此这里不用锁。
            shiftIn(ring[readIndex % capacity])
            readIndex += 1
        }
        return true
    }

    private fun shiftIn(value: Float) {
        for (i in 0 until barCount - 1) bars[i] = bars[i + 1]
        bars[barCount - 1] = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    }

    private fun decayToZero(decay: Float): Boolean {
        var changed = false
        val d = decay.coerceIn(0f, 1f)
        for (i in bars.indices) {
            val v = bars[i]
            if (v == 0f) continue
            val next = v * d
            bars[i] = if (abs(next) < SILENCE_EPSILON) 0f else next
            changed = true
        }
        return changed
    }

    /** 把滚动窗口拷进调用方**复用**的数组（避免每帧分配）。 */
    fun copyInto(destination: FloatArray) {
        val n = minOf(destination.size, barCount)
        for (i in 0 until n) destination[i] = bars[i]
    }

    /** 单测用：读第 index 根柱。 */
    fun barAt(index: Int): Float = bars[index]

    /** 单测用：还没被 UI 消费的柱数。 */
    val pendingCount: Int get() = writeIndex - readIndex

    companion object {
        /** 每帧衰减系数。20fps 下 0.7^k 大约 0.3 秒归零。 */
        const val DEFAULT_DECAY = 0.7f

        /** 小于它就当 0 —— 避免指数衰减永远逼近而不等于 0，导致 UI 无限重绘。 */
        const val SILENCE_EPSILON = 0.002f
    }
}
