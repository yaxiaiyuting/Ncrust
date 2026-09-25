/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

/**
 * v2.0.1 —— 歌词面板滚动定位的**纯计算**（无 Compose 依赖，JVM 可单测）。
 *
 * 抽出来的理由与 [PlayerLayout] 一样：本版修的 bug 就是「这几个数字算错时，第一句会被推出短视口」，
 * 它必须能在没有真机、没有 Compose 的情况下被断言（见 `LyricsPanelScrollTest`）。
 *
 * ## 面板的滚动结构
 *
 * ```
 * item(0)      = "top_spacer"   顶部留白
 * item(1..n)   = 歌词行（第 i 句 = item i+1）
 * item(n+1)    = "bottom_spacer" 底部留白
 * ```
 *
 * 定位语义（v1.5.0 起未变）：**当前行落在视口 [LEAD_FRACTION] 高处** ——
 * `animateScrollToItem(i + 1, leadOffsetPx(vh))`，负 offset = 目标行出现在视口上方 36% 处。
 *
 * ## 本版修的两个数字
 *
 * **① 顶部留白原本是固定 200dp**（v1.5.0 从 Kanesumi 面板整体搬入时，按竖屏全屏面板
 * 「约 700dp 高」定稿的）。大屏模式右栏的歌词面板只有约 280dp 高（PCL110 实测
 * 800×363dp 窗口 − 控制条），200dp 留白占掉 72% ⇒ **「列表在最顶端」直接等于
 * 「第一句被推到面板底边之外」**（会被面板裁掉，还整条落在底部渐隐带里）。
 * 竖屏 700dp 面板下同样的 200dp 只占 29%，第一句完整可见 —— 这就是
 * 「只有大屏模式出现」的原因。
 *
 * 改成 `min(200dp, LEAD_FRACTION × 视口)` 之后有两条不变量：
 *
 *  1. `topSpacer <= LEAD_FRACTION × 视口`（[topSpacerHeightPx]）⇒ **「列表在最顶端」与
 *     「第一句落在 36% 处」是同一个位置**（两者相差 `max(0, spacer − 36%×视口) = 0`），
 *     所以"回顶"与"定位"不再互相打架，第一句开唱时也不会再跳一下；
 *  2. 第一句恒定落在**顶部渐隐带下方** —— 渐隐高 = `min(100dp, 30% × 视口)`
 *     （见 `LyricsView`），而 `0.30 < 0.36` 对任何视口都成立；
 *  3. 竖屏（视口 ≥ 200dp / 0.36 ≈ 556dp）下结果仍是 200dp ⇒ 与 v2.0.0 逐像素一致。
 *
 * **② 视口高度未知时一律回落基准值**：首帧 `maxHeight` 拿不到 / 父级约束无界时，
 * 用 200dp 而不是 0 —— 0 会让第一句贴到面板顶边，比 200dp 更难看，而且竖屏下
 * 200dp 本来就是正确值。
 */
internal object LyricsPanelScroll {

    /**
     * 当前行在视口里的目标高度比例：0.36。
     *
     * 与 v1.5.0 起的定位语义完全一致（原实现写死 `0.36f`），只是提出来当唯一定义 ——
     * 顶部留白的上限与定位 offset 必须用同一个数，否则不变量 1 不成立。
     *
     * **竖屏用这个值**（黄金分割上方：当前行偏上，下方留出接下来的几句）。
     */
    const val LEAD_FRACTION = 0.36f

    /**
     * v2.3.0 · E：**横屏 / 大屏右栏**用的目标比例 —— 视口正中。
     *
     * ## 为什么横屏要换成 0.5 而不是继续用 0.36（真机实测几何）
     *
     * v2.3.0 的探针在大屏模式（S6 / SM-G9209，2560×1440px，density 640）上量到：
     *
     * | | 面板尺寸 | 0.36 对应的当前行位置 | 面板下方剩余 |
     * |---|---|---|---|
     * | 横屏 | 1274 × **928px = 232.0dp** | `0.36 × 232 = 83.5dp` = 334px | ≈ 148dp（约 1.4 行） |
     * | 竖屏 | 1280 × 816px = 204.0dp | `0.36 × 204 = 73.4dp` | ≈ 131dp |
     *
     * 横屏一屏只放得下 **3 行**（探针 dump：`frac_itemtop` 依次为 −0.04 / 0.12 / **0.36** / 0.74）。
     * 当前行落在 36% 时，它上方只露出一行的一半、下方只有一行半 —— 视觉重心明显偏上，
     * 这就是用户说的「不正」。0.5 让上下余量对称，是 3 行视口下唯一不偏的目标。
     *
     * ## 这是**有意的行为改变**，只在横屏生效
     *
     * 探针同时证明 0.36 的**自动定位本身是精确的**（6 次独立基线全部 `frac_itemtop = 0.3599`），
     * 所以这不是「修一个算错的数」，而是「横屏改用另一个目标」。竖屏一个像素都不动
     * （[LEAD_FRACTION] 仍是 0.36，探针竖屏实测 0.3591）。
     *
     * 证据：`docs/verification/v2.3.0/probe-lyric-landscape.md`、
     * `probe-raw/lyric-panel-measurements.txt`、`probe-raw/landscape-device-notes.md`。
     */
    const val CENTER_FRACTION = 0.5f

    /**
     * 顶部／底部留白的基准值（dp）。
     *
     * 200dp 是竖屏全屏面板下的定稿值：**不要**为了修大屏去改它 ——
     * 改了会移动竖屏第一句的位置，而竖屏从来没有出过问题（见 [topSpacerHeightPx] 的夹取）。
     */
    const val BASE_SPACER_DP = 200f

    /**
     * 顶部留白高度（px）：`min(基准, leadFraction × 视口)`。
     *
     * [viewportHeightPx] <= 0（首帧 / 无界约束）⇒ 返回基准值。
     *
     * v2.3.0 · E 起 [leadFraction] 可传入（横屏传 [CENTER_FRACTION]）。
     * **默认值就是原来的 0.36**，所以既有调用点与既有单测（`LyricsPanelScrollTest`）
     * 的行为逐字节不变。
     */
    fun topSpacerHeightPx(
        viewportHeightPx: Int,
        baseSpacerPx: Float,
        leadFraction: Float = LEAD_FRACTION,
    ): Float {
        if (viewportHeightPx <= 0) return baseSpacerPx
        return minOf(baseSpacerPx, viewportHeightPx * leadFraction)
    }

    /**
     * 定位 offset（px）：负值 = 目标行落在视口 [leadFraction] 高处。
     *
     * 视口未知（<= 0）时返回 0，与 v1.5.0 起的兜底行为一致（此时列表还没排版，
     * 这个 offset 也不会被真正用上）。
     */
    fun leadOffsetPx(viewportHeightPx: Int, leadFraction: Float = LEAD_FRACTION): Int =
        if (viewportHeightPx > 0) -(viewportHeightPx * leadFraction).toInt() else 0

    /**
     * 歌词行下标 → LazyColumn item 下标（第 0 项是顶部留白，所以恒 +1）。
     *
     * v2.0.1：**-1（还没到第一句）按第 0 句处理** —— 前奏 / 拖回开头时面板要摆的是"第一句"，
     * 不是"列表顶端"（在短面板里这两者曾经相差 124dp，正是本 bug 的表象）。
     * [lineCount] <= 0 时返回 0（调用方本来就有 `lines.isEmpty()` 早退，这里只是不让它抛
     * `coerceIn(1, 0)`）。
     */
    fun targetItemIndex(currentIndex: Int, lineCount: Int): Int {
        if (lineCount <= 0) return 0
        return (currentIndex.coerceAtLeast(0) + 1).coerceIn(1, lineCount)
    }
}
