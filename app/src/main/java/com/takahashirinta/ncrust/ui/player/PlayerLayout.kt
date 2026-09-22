/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

/**
 * P1 · 大屏幕模式（横屏桌面播放器布局）与宽屏两栏共用的**纯布局逻辑**。
 *
 * 抽出来的原因有两个：
 *  1. 「大屏模式」不能塞进全仓库那 7 处 `screenWidthDp >= 600` 的宽屏判定里 ——
 *     横屏时窗口宽度必然 >= 600dp（PCL110 实测 800dp），改那个谓词会把首页 / 详情页 /
 *     收藏页的布局一起改掉。所以大屏模式是**第三个谓词**（[isBigScreenActive]），只让播放器读。
 *  2. 这些判定是分栏命中区语义的唯一定义处，必须能脱离 Compose 单测（见 PlayerLayoutTest）。
 */
object PlayerLayout {

    /** 宽屏（平板/折叠展开/车机）分栏断点。与全仓库其余 6 处保持一致，不要改。 */
    const val WIDE_BREAKPOINT_DP = 600

    /** 宽屏两栏时**右栏**占比（原实现写死 0.56f，这里提出来当唯一定义）。 */
    const val WIDE_RIGHT_FRACTION = 0.56f

    /** 大屏模式左栏占比：与宽屏两栏同一比例，保持两套布局的视觉一致。 */
    const val BIG_SCREEN_LEFT_FRACTION = 1f - WIDE_RIGHT_FRACTION

    /**
     * 第三谓词：大屏幕模式是否生效。
     *
     * [requested] 是用户的意图（点了播放器里的「大屏幕」按钮），[orientationLandscape]
     * 是当前**窗口**方向。两者都与才切横屏桌面布局：
     *  - 用户点了按钮但系统还没转过去（旋转有 ~1 帧到数百毫秒的延迟）→ 仍是竖屏布局，
     *    避免竖屏窗口里塞一个横屏两栏布局；
     *  - 用户在大屏里把手机转回竖屏 → 立即回竖屏布局（MainActivity 同时会退出大屏模式）。
     */
    fun isBigScreenActive(requested: Boolean, orientationLandscape: Boolean): Boolean =
        requested && orientationLandscape

    /**
     * 宽屏左栏占整宽的比例：随 wideSplit 在 100%（单栏）与 44%（两栏）之间过渡。
     * 窄屏恒为 1（[isWidePlayer] = false 时不读 wideSplit）。
     */
    fun wideLeftFraction(isWidePlayer: Boolean, wideSplit: Float): Float =
        if (isWidePlayer) 1f - WIDE_RIGHT_FRACTION * wideSplit.coerceIn(0f, 1f) else 1f

    /**
     * 宽屏**分栏语义边界**（px）：左边是封面/信息区，右边是歌词·队列面板。
     *
     * 这个值是命中测试的分界线（面板内让路给列表滚动 / 面板外整卡拖拽），
     * 必须与真实分栏边界一致 —— 旧实现两处都用 `screenWidthPx / 2`，而真实边界是
     * 0.44×宽（两栏稳定态），于是 44%~50% 那条窄带被判成"封面区"：在带内上下拖歌词，
     * 事件被整卡拖拽抢走（P1 顺手修掉）。
     */
    fun splitBoundaryPx(screenWidthPx: Float, isWidePlayer: Boolean, wideSplit: Float): Float =
        screenWidthPx * wideLeftFraction(isWidePlayer, wideSplit)

    /** 大屏模式的分栏边界（px）：左栏占比固定，不随任何动画变化。 */
    fun bigScreenLeftBoundaryPx(screenWidthPx: Float): Float =
        screenWidthPx * BIG_SCREEN_LEFT_FRACTION

    /**
     * 封面正方形边长（px）：在实测到的可用宽高里取最小边。
     *
     * 大屏模式左栏拿到的是「左栏宽 × 左栏剩余高」，取小边 ⇒ 封面尽量占满**可用高度**，
     * 且永远是正方形；宽屏竖屏两栏同理（原实现是 `minOf(b.width, b.height)`，
     * 这里补一个 >= 1px 的下界，避免首帧 0 让 Coil 按 1px 解码成纯色）。
     */
    fun squareCoverSizePx(availableWidthPx: Float, availableHeightPx: Float): Float =
        minOf(availableWidthPx, availableHeightPx).coerceAtLeast(1f)

    /**
     * 唯一封面 overlay 在**实测之前**的兜底边长（px）。
     *
     * 实测（[squareCoverSizePx]）要等第一帧布局回调，此前若用 1px 会让 Coil 按 1px
     * 解码成纯色（重进播放器时尤其明显）。大屏模式左栏高度约等于可用高度，所以取
     * 「窗口宽 × 0.4」与「窗口高 × 0.5」的较小者 —— 横屏（PCL110：2800×1272px）
     * 得到 636px ≈ 182dp，与实测值同量级。
     */
    fun coverFallbackSizePx(screenWidthPx: Float, screenHeightPx: Float): Float =
        minOf(screenWidthPx * 0.4f, screenHeightPx * 0.5f)
}
