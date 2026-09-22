/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust

/**
 * P1 · 大屏幕模式的方向策略**纯判定**（可脱离 Android 单测）。
 *
 * 需求是「进入时请求横屏，进入横屏后立刻放宽成 SENSOR，绝不长期强制锁横屏」。
 * 字面照做会踩一个坑，所以这里多了一层物理朝向门控，两个判定都在本文件里：
 *
 *  - 「进入时横屏」用 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`：用户此刻还竖着拿手机
 *    （按钮就在竖屏播放器里），只有强制横屏能让窗口立刻转过去。
 *  - 「立刻放宽成 SENSOR」不能无条件做：`SCREEN_ORIENTATION_SENSOR` 是**跟随传感器**
 *    的（它忽略系统的自动旋转锁），设备还竖着/平放在桌上时系统会在下一帧把窗口转回竖屏，
 *    大屏模式当场自我退出。所以放宽的前提是「设备物理上确实已经横过来了」
 *    —— 见 [isPhysicallyLandscape] / [shouldRelaxToSensor]。
 *
 * 门控只影响「什么时候放宽」，不影响「一定会放宽」：用户一把手机转横，方向立刻交给
 * 传感器；此后转回竖屏就是配置回到竖屏 → 退出大屏模式（需求里的第二条退出路径）。
 */
object BigScreenOrientation {

    /** `OrientationEventListener.ORIENTATION_UNKNOWN`（读不到方向时回调 -1）。 */
    const val ORIENTATION_UNKNOWN = -1

    /**
     * 设备是否物理横向。
     *
     * `OrientationEventListener` 的语义：0° = 自然方向竖直（竖屏正持），
     * 90° / 270° = 左右横向，180° = 倒竖。判定取 |45°..135°| 与 |225°..315°|
     * 这两段（即"偏离竖持超过 45°"），与 Android 平台自身的旋转 hysteresis 同量级；
     * 平放时传感器给不出稳定值，平台会回 [-1]，一律当作"不是横向"。
     */
    fun isPhysicallyLandscape(degrees: Int): Boolean {
        if (degrees == ORIENTATION_UNKNOWN) return false
        val d = ((degrees % 360) + 360) % 360
        return d in 45..135 || d in 225..315
    }

    /**
     * 是否可以把方向从"强制横屏"放宽成 `SCREEN_ORIENTATION_SENSOR`。
     *
     * 幂等：已经放宽过就不再重复设置（每次设置 requestedOrientation 都会触发一次
     * 方向重算）。没进大屏模式时永远不放宽。
     */
    fun shouldRelaxToSensor(bigScreen: Boolean, alreadyRelaxed: Boolean, degrees: Int): Boolean =
        bigScreen && !alreadyRelaxed && isPhysicallyLandscape(degrees)

    /**
     * 配置回调里是否应该因为"回到竖屏"而退出大屏模式。
     *
     * 这是「旋转回竖屏 = 退出大屏」这条路径的唯一判据：大屏模式期间配置变成竖屏，
     * 只有两种可能 —— 用户把手机转回去了，或系统拒绝了横屏请求；两种都应该退。
     */
    fun shouldExitOnConfiguration(bigScreen: Boolean, orientationLandscape: Boolean): Boolean =
        bigScreen && !orientationLandscape
}
