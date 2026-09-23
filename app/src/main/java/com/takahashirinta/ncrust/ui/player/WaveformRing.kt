/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import kotlin.math.abs
import kotlin.math.exp

/**
 * v1.8.0 · T3：音频可视化的**无锁单写者环形缓冲 + 柱状滚动窗口 + 时间常数平滑**（纯逻辑，可 JVM 单测）。
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
 * ## v1.8.1：为什么加了平滑
 *
 * 音频数据只有 30 柱/秒，直接把这 30 个值画成柱高，画面就是"每 33ms 硬跳一次"，
 * 观感上明显是一跳一跳的（用户反馈"帧率太低"。**根因不是帧率而是阶跃**）。
 * 现在拆成两层：
 *
 *  - [targets] = 最新数据（阶跃）；
 *  - [bars] = 画面值，用**时间常数**指数逼近 targets：起音快（[ATTACK_TAU_MS]，跟得上鼓点）、
 *    回落慢（[RELEASE_TAU_MS]，像 VU 表一样自然衰减）。
 *
 * 时间常数形式（`k = 1 - exp(-dt/tau)`）而不是固定系数，是为了**与刷新率无关**：
 * 60fps 与 30fps 下同一条曲线，低端机降帧率不会让观感变形。
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

    /** 最新数据（阶跃）。UI 线程原地更新，绝不重新分配。 */
    private val targets = FloatArray(barCount)

    /** 画面上的柱子高度（0..1）= 平滑后的值。UI 线程原地更新。 */
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
        targets.fill(0f)
    }

    /**
     * UI 线程按帧率调用。
     *
     * @param active 播放中（且不在缓冲）时为 true：消费新柱并把画面值逼近数据值；
     *   false 时把**数据值**归零，画面值按 [RELEASE_TAU_MS] 衰减到 0
     *   —— 暂停/缓冲瞬间清零会"闪一下"，衰减看起来像余震自然消失。
     * @param dtMs 距上一帧的毫秒数。平滑系数由它算出来，所以 30fps 与 60fps 观感一致。
     * @return 画面是否需要重绘。**完全静止（没有新数据且已收敛）时返回 false** ——
     *   这是"暂停后不再白烧 GPU"的保证。
     */
    fun pump(active: Boolean, dtMs: Float): Boolean {
        var changed = false
        if (active) {
            changed = consumePending()
        } else if (targets.any { it != 0f }) {
            targets.fill(0f)
            changed = true
        }
        return approach(dtMs) || changed
    }

    /** 把环形缓冲里 UI 还没消费的柱搬进 [targets]。 */
    private fun consumePending(): Boolean {
        val snapshot = writeIndex
        var pending = snapshot - readIndex
        if (pending <= 0) return false
        if (pending > capacity) {
            // 溢出：丢掉最旧的，绝不阻塞写者。
            readIndex = snapshot - capacity
            pending = capacity
        }
        var changed = false
        repeat(pending) {
            // 注意：读到的是"某一时刻的值"，写者可能刚好覆盖同一格（只在溢出时发生）。
            // 单根柱失真在视觉上不可见，也不影响后续帧 —— 因此这里不用锁。
            if (shiftIn(ring[readIndex % capacity])) changed = true
            readIndex += 1
        }
        return changed
    }

    /** @return 这一根新柱是否真的改变了窗口内容（全等值时不必重绘）。 */
    private fun shiftIn(value: Float): Boolean {
        var changed = false
        for (i in 0 until barCount - 1) {
            if (targets[i] != targets[i + 1]) {
                targets[i] = targets[i + 1]
                changed = true
            }
        }
        val last = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        if (targets[barCount - 1] != last) {
            targets[barCount - 1] = last
            changed = true
        }
        return changed
    }

    /**
     * 让画面值朝数据值走一步（时间常数形式，与刷新率无关）。
     *
     * **重绘判据刻意不用"位移是否大于某个 epsilon"**：回落尾段每帧位移会越来越小，
     * 用位移阈值会让 [pump] 在柱子还停在 ~1.5% 的时候就报"不用重绘" —— 柱子冻在
     * 非零值上（v1.8.1 实现时被单测抓到的真实缺陷）。现在的判据是"这一根还没到位"
     * （`current != target`），配合下面的收敛截断：只要没到位就继续重绘，一旦吸附到
     * 目标值就精确相等、下一帧自然停。
     *
     * @return 有柱子还没到位（需要重绘）时为 true。
     */
    private fun approach(dtMs: Float): Boolean {
        val dt = dtMs.coerceIn(0f, 200f)
        val kAttack = 1f - exp(-dt / ATTACK_TAU_MS)
        val kRelease = 1f - exp(-dt / RELEASE_TAU_MS)
        var changed = false
        for (i in bars.indices) {
            val target = targets[i]
            val current = bars[i]
            if (current == target) continue
            val k = if (target > current) kAttack else kRelease
            val next = current + (target - current) * k
            // 收敛截断：无限逼近永远不等于目标，不截断就会永远"需要重绘"。
            bars[i] = if (abs(next - target) < SETTLE_EPSILON) target else next
            changed = true
        }
        return changed
    }

    /** 把滚动窗口拷进调用方**复用**的数组（避免每帧分配）。 */
    fun copyInto(destination: FloatArray) {
        val n = minOf(destination.size, barCount)
        for (i in 0 until n) destination[i] = bars[i]
    }

    /** 单测用：画面值（平滑后）。 */
    fun barAt(index: Int): Float = bars[index]

    /** 单测用：数据值（未平滑）。 */
    fun targetAt(index: Int): Float = targets[index]

    /** 单测用：还没被 UI 消费的柱数。 */
    val pendingCount: Int get() = writeIndex - readIndex

    companion object {
        /** 起音时间常数（毫秒）：跟得上鼓点，又不至于把 30Hz 的阶跃原样画出来。 */
        const val ATTACK_TAU_MS = 22f

        /** 回落时间常数（毫秒）：VU 表式的自然衰减。 */
        const val RELEASE_TAU_MS = 130f

        /** 收敛判据：低于它就吸附到目标值，避免"永远差一点点"导致无限重绘。 */
        const val SETTLE_EPSILON = 0.004f
    }
}
