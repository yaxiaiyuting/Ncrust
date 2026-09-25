/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v2.5.0 · A：全局动效规范（本仓库**唯一**允许新增动画参数的地方）。
 */

package com.takahashirinta.ncrust.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v2.5.0 · A：全局动效规格（**唯一落点**）。
 *
 * ## 现状（探针实测，不是假设）
 *
 * `docs/verification/v2.5.0/probe-motion.md`：
 *  - 本仓库有 **41 处**手写 `tween(...)`、**0 处** `spring(...)`；
 *  - 没有统一的动效规格对象 —— 但参数其实**已经收敛成一套词汇**
 *    （260 / 220 / 200 / 150 / 120 五档时长 + `FastOutSlowInEasing` /
 *    `MetroDefault` / `CubicBezier(0.2,0,0,1)` 三条曲线）。
 *
 * 所以本对象的取值策略是**归纳既有取值 + 补上缺的物理类**，不是发明一套新数值。
 *
 * ## 取值出处（逐条可核，见 `probe-splayer-ref.md` §5）
 *
 * ⚠️ **任务书 §3.3 里那组"四档 spatial/effects"在这份骨架里是不成立的**，探针已证伪：
 * Material 3 Expressive 的官方表述是「**三种速度**（fast / default / slow）×
 * **两种类型**（spatial / effects）= **六个弹簧**」，不是四档。
 * 官方也没有给出任何「弹簧 → tween」的对应值（`MotionScheme` 的 12 个 spec
 * **全部**是 `spring(...)`，代码里没有 tween 分支）。
 * 所以下面凡是标「官方」的都逐值取自 `ExpressiveMotionTokens.kt`，
 * 凡是标「本项目既有」的都取自本仓库既有的 41 处 `tween`。
 *
 * ## 本版**不做**的事（有意为之）
 *
 * **不把 41 处存量 `tween` 批量改走 [AppMotion]。** 理由：
 *  1. 逐处判断「这是空间类还是效果类」需要读 41 处上下文，改错会让手感变化且**无法归因**；
 *  2. 其中 `PlayerCard.kt` / `NcrustLyricsPanel.kt` 被 `AGENTS.md` 明确标注为
 *     load-bearing、**禁止批量重构**；
 *  3. 把「圆角/动效升级」与「播放路径动画行为变化」混进同一次提交，一旦真机掉帧就无法定位
 *     —— 与铁律 15 的取证要求直接冲突。
 *
 * 存量的 41 处作为**已知缺口**写入未验证清单；本版只保证「**新增**动效不散落」。
 *
 * ## 为什么 Float 与 Dp 两套都有
 *
 * 任务书 §3.3 给的骨架是 `spring<Dp>(...)`，而本应用真正被动画驱动的主角是
 * `progress: Animatable<Float>`（播放器卡片，GPU 零重组的核心）。
 * 两者不能互相赋值（`SpringSpec<Float>` ≠ `SpringSpec<Dp>`），所以两套都提供：
 *  - `spatial*`   → `SpringSpec<Float>`，给 `Animatable<Float>` / `animateFloatAsState`；
 *  - `spatial*Dp` → `SpringSpec<Dp>`，给 `Animatable<Dp>` / `animateDpAsState`。
 *
 * 不加第三套：本仓库没有任何需要位置弹簧的非 Float/Dp 属性。
 *
 * ## 与项目自身铁律的关系
 *
 * 这些 spec **只描述时间**，不规定怎么消费。消费侧仍必须遵守
 * 「GPU 零重组」：单一 `progress` + 在 `graphicsLayer { }` / draw 阶段读取，
 * **不得**为了用这些 spec 而新引入逐帧重组的 `animateFloatAsState`。
 */
object AppMotion {

    // ─────────────────────────────────────────────────────────────────────
    // 空间类（位置 / 尺寸 / 卡片入场）——**官方 M3 Expressive spatial 三档**
    //
    // 逐值取自 androidx `ExpressiveMotionTokens.kt`（文件头 VERSION v0_14_0）：
    //   spatial fast    = damping 0.6f / stiffness  800.0f
    //   spatial default = damping 0.8f / stiffness  380.0f
    //   spatial slow    = damping 0.8f / stiffness  200.0f
    //
    // 注意 dampingRatio < 1 ⇒ **会过冲**。这正是 Expressive 的手感来源，
    // 但也意味着它们**不能**用来驱动带阈值判定的 `progress`（见 [playerExpand]）。
    // ─────────────────────────────────────────────────────────────────────

    /** 官方 spatial fast（0.6 / 800）。小元件位移、图标切换。 */
    val spatialFast: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** 官方 spatial default（0.8 / 380）。卡片入场、面板切换的默认值。 */
    val spatialDefault: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** 官方 spatial slow（0.8 / 200）。大面板入场（宽屏侧栏、整页内容替换）。 */
    val spatialSlow: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 200f)

    /** [spatialFast] 的 Dp 版本。 */
    val spatialFastDp: SpringSpec<Dp> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** [spatialDefault] 的 Dp 版本。 */
    val spatialDefaultDp: SpringSpec<Dp> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /**
     * **不过冲**的空间弹簧（临界阻尼，`dampingRatio = 1.0`）。
     *
     * 用途只有一个但很关键：**驱动带阈值判定的 `progress`**。
     * 官方的三档 spatial 都会过冲，而本应用的播放器 `progress` 同时是命中测试的开关
     * （见 [playerExpand]），过冲会让阈值反复穿越。这**不是**官方 token，
     * 是本项目在自己的约束下必须补的一档，名字上刻意与官方三档区分开。
     */
    val spatialNoOvershoot: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 800f)

    /**
     * **播放器卡片展开**：临界阻尼弹簧（不是官方 spatial token，理由见下）。
     *
     * ⚠️ 这一条是本版**受约束**的落地，不是随手换的缓动。原因（详见 `probe-motion.md` §2.2）：
     * `progress` 同时是**命中测试的开关** ——
     *
     *  - `progress < 0.01f` ⇒ 整棵展开态子树**不挂载**；
     *  - `progress > 0.99f` ⇒ 卡片根节点**吞掉所有事件**；
     *  - 折叠态靠 `padding(top = hitGateInsetDp)` + 等量 `offset` 收窄命中区。
     *
     * 若用会过冲的弹簧（官方 spatial 三档的 dampingRatio 都 < 1），`progress` 会越过 1.0
     * 再回弹，**反复跨越 0.99 / 0.01 两个阈值** ⇒ 子树挂载抖动 + 命中区闪烁。
     * 因此这里固定 `dampingRatio = 1.0f`，并由 `AppMotionSpecTest` 断言
     * 「播放器转场不得过冲」—— 把这个约束钉死，防止下一个人「顺手调成 0.6 更活泼」。
     *
     * `stiffness = 260f` 是照既有 `tween(400ms)` 的**主观速度**配的：
     * 临界阻尼下 ≈4/ω₀ 收敛，ω₀ = √260 ≈ 16.1 rad/s ⇒ ≈250ms 到 98%，
     * 与 400ms 的 `CubicBezier(0.2,0,0,1)`（慢起快收，前段很慢）观感接近。
     */
    val playerExpand: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 260f)

    /**
     * **播放器卡片收起**：临界阻尼，比展开更快（`stiffness` 更大）。
     *
     * 照既有 `tween(260ms FastOutSlowInEasing)` 的主观速度配：收起应当比展开**果断**
     * —— 用户已经决定不看它了，慢吞吞地滑下去是拖沓。同样不过冲（同 [playerExpand] 的理由）。
     */
    val playerCollapse: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 420f)

    /**
     * **按压回弹**：`dampingRatio = 0.45f` 有意欠阻尼，松开后回弹一下。
     *
     * 只用于**缩放**（不参与任何阈值判定），所以过冲是安全的、也是想要的效果。
     */
    val pressScale: SpringSpec<Float> = spring(dampingRatio = 0.45f, stiffness = 900f)

    /** 按压放大倍数（任务书 §3.3「按钮点击：scale 1.05x 回弹」）。 */
    const val PRESS_SCALE: Float = 1.05f

    // ─────────────────────────────────────────────────────────────────────
    // 效果类弹簧 ——**官方 M3 Expressive effects 三档**
    //
    // 逐值取自 `ExpressiveMotionTokens.kt`：
    //   effects fast    = damping 1.0f / stiffness 3800.0f
    //   effects default = damping 1.0f / stiffness 1600.0f
    //   effects slow    = damping 1.0f / stiffness  800.0f
    //
    // Standard 与 Expressive 在 effects 上**完全相同**，只有 spatial 三档不同。
    // 三档都是临界阻尼（不过冲）—— 透明度这类无质量属性本来就不该弹。
    // ─────────────────────────────────────────────────────────────────────

    /** 官方 effects fast（1.0 / 3800）。 */
    val effectsSpringFast: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 3800f)

    /** 官方 effects default（1.0 / 1600）。 */
    val effectsSpringDefault: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 1600f)

    /** 官方 effects slow（1.0 / 800）。 */
    val effectsSpringSlow: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 800f)

    // ─────────────────────────────────────────────────────────────────────
    // 效果类 tween
    //
    // ⚠️ 任务书 §3.3 把 `effects` / `effectsFast` 给成 tween。官方**没有**这样的
    //    对应值（`MotionScheme` 全用弹簧），所以这两条**不是**官方 token，而是
    //    本项目自己的一组「短促、非物理」过渡 —— 它们服务的是
    //    「透明度/颜色这类属性用弹簧会显得忽快忽慢」这个具体问题。
    // ─────────────────────────────────────────────────────────────────────

    /** 通用效果过渡：200ms + `FastOutSlowInEasing`。 */
    val effects: TweenSpec<Float> = tween(durationMillis = 200, easing = FastOutSlowInEasing)

    /** 短促效果过渡：100ms 线性。 */
    val effectsFast: TweenSpec<Float> = tween(durationMillis = 100, easing = LinearEasing)

    // ─────────────────────────────────────────────────────────────────────
    // 本项目既有曲线（**归纳，不是发明**）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 面板 / 抽屉**登场**：300ms + `CubicBezier(0.2, 0, 0, 1)`（慢起快收）。
     *
     * 逐字沿用 Kanesumi `SokuouTweens.SheetAppear` 的取值 —— 这条曲线在本仓库
     * 出现 6 次（`probe-motion.md` §1.2），是已沉淀的手感，不换。
     *
     * 附注（可核，见 `probe-splayer-ref.md` §8-冲突3）：`CubicBezier(0.2, 0, 0, 1)`
     * 与 M3 官方的 `EasingEmphasizedCubicBezier` **逐值相同** ——
     * 也就是说这条既有曲线本来就跟 M3 一致，不需要为了"对齐 M3"去改它。
     */
    val sheetAppear: TweenSpec<Float> = tween(300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))

    /**
     * 面板 / 抽屉**收起**：260ms + `FastOutSlowInEasing`。
     *
     * 同样逐字沿用 `SokuouTweens.SheetDismiss`。收起短促收敛、避免拖尾。
     */
    val sheetDismiss: TweenSpec<Float> = tween(260, easing = FastOutSlowInEasing)

    /** 封面 / 大图淡入：400ms + `CubicBezier(0.2, 0, 0, 1)`（沿用 `SokuouTweens.CoverFade`）。 */
    val coverFade: TweenSpec<Float> = tween(400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))

    /**
     * 列表 item **入场**：220ms 淡入 + 上滑（任务书 §3.3「列表 item 出现：淡入 + 上滑」）。
     *
     * 上滑距离见 [LIST_ITEM_RISE_DP]。这条动效**必须有界**（铁律 15）——
     * 只对下标 < `ListItemAppear.MAX_ANIMATED_INDEX` 的 item 播放，
     * 判定抽在 `ui/components/ListItemAppear.kt` 并配单测。
     */
    val listItemEnter: TweenSpec<Float> = tween(220, easing = FastOutSlowInEasing)

    /** 列表 item 入场上滑距离。8dp：只够「推上来」的暗示，不足以让列表跳动。 */
    val LIST_ITEM_RISE_DP: Dp = 8.dp

    /**
     * 主题色切换过渡：300ms。
     *
     * 注意：**颜色本身不做逐帧插值**（那会逐帧重组）。这条 spec 只用于驱动
     * 「叠层 alpha」这类零重组手段（Kanesumi 的 `MetroBottomNav` 双 Icon 叠 Alpha 是参照实现）。
     */
    val colorTransition: TweenSpec<Float> = tween(300, easing = FastOutSlowInEasing)

    // ─────────────────────────────────────────────────────────────────────
    // 页面转场：**本版不启用**（有意为之，见下）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 页面转场时长。**本版没有任何调用点** —— 保留它是为了把「为什么没有页面转场」
     * 这件事写在代码里，而不是让它变成一个看起来像遗漏的空白。
     *
     * `ui/navigation/NavGraph.kt` 显式把四个转场都设成 `EnterTransition.None` /
     * `ExitTransition.None`，并且**写明这是用户决策**：
     * 「转场期间新旧两页同帧渲染, slide/fade 每帧都要全屏合成, 低端机上
     * 是切换动作的主要掉帧源」。本应用的目标下限是 Android 7.0 / 3GB RAM，
     * 而铁律 15 要求动效不得影响性能 ——
     * 所以任务书 §3.3「页面切换：AnimatedContent 方向性转场」这一条
     * **与项目已有的、有明确理由的决策直接冲突**，本版**不做**，
     * 并把它写进未验证/遗留清单交回产品决策。
     *
     * 真要启用时，用这个时长 + [spatialDefault]，并且**必须**在低端真机上
     * 用 `dumpsys gfxinfo` 对照转场前后的掉帧数（模拟器结论不构成证据）。
     */
    const val PAGE_TRANSITION_MS: Int = 260
}
