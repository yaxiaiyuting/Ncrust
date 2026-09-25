# 探针 · 动效现状（v2.5.0）

> 目标：回答任务书 §2.2 三问 —— ① 有没有过渡动画、Navigation 切换有无动效；
> ② 播放器展开/收起有无动画；③ 列表 item 点击有无反馈动效。
>
> 方法：`grep` 实测计数 + 逐点读源码。计数是**事实**，评价是**判断**，两者分开写。

## 结论（先行）

| 问题 | 实测答案 |
|---|---|
| 有没有过渡动画 | 有，但**全部手写**：`tween(` 共 **41** 处，散落在 10+ 个文件 |
| Navigation 切换有无动效 | **没有**。`NavGraph.kt:116-117` 显式 `EnterTransition.None` / `ExitTransition.None` |
| 播放器展开/收起 | 有，`MainActivity.kt:1421` `tween(400, CubicBezier(0.2,0,0,1))` / `:1427` `tween(260, FastOutSlowInEasing)` |
| 列表 item 点击反馈 | 有，走 `MetroIndication`（Kanesumi，直角闪切，`Modifier.Node` + draw 阶段，零重组） |
| 列表 item **出现**动效 | **没有**（没有入场淡入/上滑） |
| 弹簧动效 | **0 处** `spring(` 调用。全部是时长驱动 `tween` |
| 统一的动效规格对象 | **不存在**。没有 `AppMotion` 之类的单一落点；应用侧只有 Kanesumi 的 `SokuouPresets` |

> 核心判断：现状不是「没有动效」，而是「**动效词汇表存在但没被用起来**」——
> `SokuouPresets` / `SokuouTweens` 已经在 classpath 上（`ui/navigation`、`ui/player` 等有 import），
> 但 41 处 `tween(...)` 里绝大多数是**就地手写**的。

## P1. 动效技术清单（实测计数）

| 原语 | 命中数 | 说明 |
|---|---|---|
| `tween(` | **41** | 手写时长 + 缓动 |
| `spring(` | **0** | 全仓库无弹簧 |
| `Crossfade` | 17 处（跨 7 文件） | 加载↔内容切换，多用 `SokuouTweens.CoverFade` |
| `AnimatedContent` | 8 处（`LibraryScreen` 4 / 其它） | 库页 tab 切换 |
| `AnimatedVisibility` | 7 处（`AboutScreen` 3 / `DetailScaffold` 2 / …） | |
| `Animatable(` | 11 处 | 玩家卡片 `progress` / 队列滑入 / 封面稳定器 |
| `animateFloatAsState` | 5 处 | **与项目「GPU 零重组」约定相悖的既有点**（见 P4） |
| `rememberInfiniteTransition` | 3 处（`SlimProgressBar`） | 缓冲脉冲 |
| `updateTransition` | 0 | |

### 1.1 手写 `tween` 的参数分布（`grep -rhn "tween("` 去重计数）

```
 4 × tween(260, easing = FastOutSlowInEasing)
 4 × tween(220, easing = MetroDefault)
 3 × tween(150, easing = MetroDefault)
 2 × tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
 2 × tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
 2 × tween(200, easing = MetroDefault)
 2 × tween(190, easing = FastOutSlowInEasing)
 2 × tween(120, easing = MetroDefault)
 1 × tween(700, easing = LinearEasing)          ← 无限循环类
 1 × tween(1400, easing = LinearEasing)         ← 无限循环类
 …（其余为单次出现）
```

**读法**：`260 / 220 / 200 / 150 / 120` 五档时长 + `FastOutSlowInEasing` / `MetroDefault` /
`CubicBezier(0.2,0,0,1)` 三条曲线，实际上**已经在用一套词汇**，只是没有被命名和收敛。
这正是可以抽出 `AppMotion` 的现实基础 —— 抽取是**归纳既有取值**，不是发明新数值。

### 1.2 主运动曲线 `CubicBezierEasing(0.2f, 0f, 0f, 1f)`

这条曲线在本仓库出现 6 次（含 `SokuouTweens.SheetAppear` / `CoverFade`），是
「慢起快收」的 Kanesumi 面板出场曲线。

## P2. 逐问回答

### 2.1 Navigation 切换：**无动效，而且这是有意的用户决策**（本版据此**不做**转场）

`ui/navigation/NavGraph.kt:113-122`：

```kotlin
// 页面切换直接跳变, 不做转场动画(用户决策)。
// 转场期间新旧两页同帧渲染, slide/fade 每帧都要全屏合成, 低端机上
// 是切换动作的主要掉帧源; 详情页内容有 ContentCache/磁盘缓存兜底,
// 跳变"瞬间出现完整内容"反而更利落, 且零中间帧。
NavHost(
    navController = navController,
    startDestination = startDestination,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
    popEnterTransition = { EnterTransition.None },
    popExitTransition = { ExitTransition.None }
)
```

四个转场**全部显式关掉**，并且注释里写明是**用户决策**、理由是**低端机掉帧**。

> ⚠️ **本版据此不做任务书 §3.3 的「页面切换：AnimatedContent 方向性转场」。**
>
> 理由不是"没时间做"，而是三条叠加：
>  1. 它**推翻一条有明确理由的既有决策**，而任务书没有给出任何新证据说明
>     "现在低端机不掉了"；
>  2. 探针同时发现 `NavHost` 的默认转场本来就不是 `None`（navigation-compose 2.8.5
>     有默认 fade/slide）—— 也就是说"没有转场"是**改出来的**，不是"还没做"；
>  3. 铁律 15「动效不得影响播放性能」与目标下限 Android 7.0 / 3GB RAM 直接支持现状。
>
> 本版把这件事**交回产品决策**：`AppMotion.PAGE_TRANSITION_MS` 留了时长常量并在 KDoc 里
> 写明「真要启用时必须在低端真机上用 `dumpsys gfxinfo` 对照掉帧数」，
> 并写入未验证/遗留清单。**不擅自替用户改掉他自己的决策。**

**若将来要启用，风险点在这里**：本应用是三层图形架构 —— 播放器卡片是
`fillMaxSize` + `graphicsLayer` 平移到屏幕底部的独立图层。页面转场只影响
NavHost 那一层（在卡片之下），但转场引入的额外合成帧会与卡片手势命中区判断
（`isOverCardVisibleArea` 用 `cardRootOrigin.y * (1f - progress.value)` **插值**）叠加。
这项**必须真机验证**，模拟器结论不构成证据。

### 2.2 播放器展开/收起：有动效，但**是承重结构**

`MainActivity.kt:1419-1431`：

```kotlin
fun expandCard()   { coroutineScope.launch { progress.animateTo(1f, tween(400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) } }
fun collapseCard() { coroutineScope.launch { progress.animateTo(0f, tween(260, easing = FastOutSlowInEasing)) } }
```

`progress: Animatable<Float>`（0 = mini，1 = 全屏）被 `graphicsLayer` 消费，是
「GPU 零重组」的核心。**它同时是命中测试的开关**：

- `progress < 0.01f` ⇒ 整棵展开态子树**不挂载**（`AGENTS.md` 触摸陷阱 #4）；
- `progress > 0.99f` ⇒ 卡片根节点**吞掉所有事件**（触摸陷阱 #3）；
- 折叠态 `Modifier.padding(top = hitGateInsetDp)` + 等量 `offset` 收窄命中区（触摸陷阱 #6）。

> ⚠️ **这意味着「给播放器展开/收起换成弹簧」不是一次纯审美改动。**
> 会过冲的弹簧（`dampingRatio < 1`）会让 `progress` 越过 1.0 再回弹，而
> `> 0.99f` / `< 0.01f` 两个阈值是**挂载/吞事件**的判据 —— 过冲期间会反复跨越阈值，
> 造成子树挂载抖动与命中区闪烁。
>
> **本版取值**：播放器转场使用**临界阻尼（`dampingRatio = 1.0`）的弹簧**，数学上单调不过冲，
> `progress` 始终落在 `[0, 1]` 内；并配一条单测断言
> `AppMotion.playerExpand.dampingRatio >= 1.0`，把这个约束钉死。
> 这条是本版对任务书「播放器展开/收起：弹簧动效」的**受约束落地**，理由如上。

### 2.3 列表 item 点击反馈：有（Kanesumi `MetroIndication`）

`MetroTheme` 注入 `LocalIndication = MetroIndication(tint = colors.pressTint)`，
树内所有 `.clickable {}` 免费获得直角闪切。实现在 `kanesumi-core` 的
`Modifier.Node` + `DrawModifierNode`，**在 draw 阶段读 `alpha.value`** —— 零重组。

**因此任务书「列表 item 点击有无反馈动效」的答案是「有，且实现方式已经是零重组」**；
本版不应重做它，只应在其之上补「**出现**动效」（现状没有）。

### 2.4 列表 item **出现**动效：没有

没有任何 `LazyColumn` item 有入场动画。任务书 §3.3 要求「列表 item 出现：淡入 + 上滑」。

**落地约束（铁律 15：动效不得影响播放性能）**：

- Android 7.0 / 3GB RAM 是目标下限，列表快速滑动时每帧新增 N 个 item，
  若每个都起一个 `Animatable` 就是 N 个并行动画 —— 必须有界；
- 采用 `Animatable` + `graphicsLayer`（**不是** `animateFloatAsState`，见 P4），
  初次组合时启动一次，结束后不再占帧；
- **只对下标 < 阈值的 item 播放**（本版取 12），滚动到深处的 item 直接以终态出现。
  判定抽成纯函数 `ListItemAppear.shouldAnimate(index)` 并加单测。

## P3. `AppMotion` 的取值来源（本版 · 已按探针核实结果修正）

> ⚠️ **任务书 §3.3 的骨架有两处不成立，探针已证伪（见 `probe-splayer-ref.md` §5）：**
>
> 1. **不是「四档」**。Material 3 Expressive 的官方表述是
>    「**三种速度**（fast / default / slow）× **两种类型**（spatial / effects）
>    = **六个弹簧**」（MDC `Motion.md` 原文，androidx `ExpressiveMotionTokens.kt` 里正好 6 个 token）。
>    「四档」很可能是把旧的 M3 **时长**刻度（Short/Medium/Long/ExtraLong × 4 = 16 档）记串了。
> 2. **官方没有任何「弹簧 → tween」的对应值**。`MotionScheme` 的 12 个 spec
>    （Standard 6 + Expressive 6）**全部**是 `spring(...)`，代码里没有 tween 分支。
>    所以任务书里的 `effects = tween(200, …)` / `effectsFast = tween(100, …)`
>    **不是官方 token**，只是任务书自己的取值。
>
> 本版因此把 `AppMotion` 拆成**三类**，每类的出处都写进代码注释：
>
> | 类 | 出处 | 用途 |
> |---|---|---|
> | `spatialFast/Default/Slow` | **官方** M3 Expressive（0.6/800、0.8/380、0.8/200） | 位置 / 尺寸 / 卡片入场 |
> | `effectsSpringFast/Default/Slow` | **官方**（1.0/3800、1.0/1600、1.0/800，三档均临界阻尼） | 透明度等效果属性的弹簧版本 |
> | `effects` / `effectsFast`（tween） | **任务书**取值，非官方 | 短促非物理过渡 |
> | `sheetAppear` / `sheetDismiss` / `coverFade` / `listItemEnter` | **本项目既有** 41 处 `tween` 归纳而来 | 沿用已沉淀手感 |
> | `playerExpand` / `playerCollapse` / `spatialNoOvershoot` | **本项目自补**（临界阻尼） | 见 §2.2 的阈值约束 |
>
> 附带一条可核的好消息：本项目既有的 `CubicBezierEasing(0.2f, 0f, 0f, 1f)`
> 与 M3 官方的 `EasingEmphasizedCubicBezier` **逐值相同** ——
> 也就是说这条曲线本来就跟 M3 一致，**不需要为了"对齐 M3"去改它**
> （改它会让所有既有手感同时变化且无法归因，见 P5）。

**取值策略是「归纳既有 + 补上缺的物理类」，不是发明新数值**：两级结构 ——

1. **空间类** → `spring`（官方三档 + 本项目自补的不过冲档）；
2. **效果类** → 官方 effects 弹簧 **与** tween 并存，按「属性有没有质量」选。

**明确不做**：不把 41 处存量 `tween` 批量替换（理由见 P5）；本版只保证**新增**动效不散落，
并用 `AppMotionSpecTest` 断言「弹簧只能来自 `AppMotion`」（现状 `spring(` 为 0，
所以这条从第一天起就能成立）。

## P4. 一处与项目自身约定的冲突（如实记录）

项目铁律（Kanesumi `AGENTS.md` + Ncrust `AGENTS.md`「Animation Pattern」）：
**「绝不在组合阶段用 `animateFloatAsState` / `animateColorAsState`（逐帧重组）」**。

但实测有 **5 处** `animateFloatAsState`：

| 文件 | 处数 |
|---|---|
| `ui/player/QueueView.kt` | 2 |
| `ui/player/NcrustLyricsPanel.kt` | 2 |
| `ui/player/PlayerCard.kt` | 1 |

**本版不改这 5 处**：

1. 它们是**既有点**，不在 v2.5.0 的范围内（范围是「新增动效统一走 AppMotion」）；
2. `PlayerCard.kt` / `NcrustLyricsPanel.kt` 是被 `AGENTS.md` 明确标注为
   **load-bearing、禁止批量重构**的文件（「Do not batch-refactor existing hand-tuned
   player-card animations」）；
3. 顺手改它们会把「圆角/动效升级」与「播放路径重组行为变化」混在同一次提交里，
   一旦真机上出现掉帧就无法归因 —— 与铁律 15 的取证要求冲突。

**改为**：本版**新增**的动效一律走 `AppMotion` + `Animatable` + `graphicsLayer`，
并用一条单测把「`AppMotion` 的 token 不被绕过」钉在**新增代码**上；
存量 5 处作为已知缺口写入未验证/遗留清单。

## P5. 未确认项

- 41 处 `tween` 的**逐处语义**未全部读过（只读了参数分布与主路径）。
  因此本版**不做**「把 41 处全部改走 AppMotion」的批量替换 —— 那需要逐处判断它属于
  空间类还是效果类，改错会让手感变化且无法归因。本版只把**命名 token 建起来 + 新代码使用 +
  播放器转场归位**这三件事做掉，存量替换留给后续版本。
- 转场对播放器卡片命中区的影响**只有真机能判**（见 2.1 风险提示），
  模拟器结论不构成证据。
