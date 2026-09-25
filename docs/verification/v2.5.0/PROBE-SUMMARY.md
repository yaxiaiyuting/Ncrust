# PROBE-SUMMARY · v2.5.0（UI 视觉重构 + 添加到下一首播放）

> 本文件是 `docs/verification/v2.5.0/` 五份探针的收敛结论，**先于任何代码改动**落盘。
> 逐条可核的原始结论见对应 `probe-*.md`；本文件只做收敛与判断。
>
> | 探针 | 文件 | 回答的问题 |
> |---|---|---|
> | 主题系统 | [`probe-theme.md`](probe-theme.md) | 主题定义在哪 / 有没有动态取色 / 圆角规范现状 |
> | 专辑封面 | [`probe-cover.md`](probe-cover.md) | 封面渲染方式 / 有无圆角边框 / 改造点清单 |
> | 动效 | [`probe-motion.md`](probe-motion.md) | 过渡与转场现状 / 播放器展开收起 / 列表反馈 |
> | 队列管理 | [`probe-queue.md`](probe-queue.md) | Player 访问方式 / `addMediaItem` / 播放模式 / 边界行为 |
> | SPlayer-Next 参考 | [`probe-splayer-ref.md`](probe-splayer-ref.md) | 取色算法 / 圆角规范 / 动效规格的**一手出处** |

---

## 0. 最重要的三条：任务书有三处前提被探针**证伪**

铁律 2 要求「探针先行，不直接采信本 prompt 中的初步判断」。实测下来，
任务书有 **3 处**前提不成立。**结论不是"因此什么都不做"**，而是
「**把依据换成一手的，把不成立的部分如实标出来**」。

### 证伪 1：SPlayer-Next 不是 Android 应用，任务书的引用链断了

任务书把「Material 3 圆角规范」与「Expressive 弹簧动效」都归因于 **SPlayer-Next**。
实测（`probe-splayer-ref.md` §1）：

- `SPlayer-Dev/SPlayer-Next` 是 **Electron + Vue 3 + TypeScript + Rust 原生模块**的
  **跨平台桌面端**播放器（AGPL-3.0，v1.1.0）；
- **硬证伪**：浅克隆后 `find` 查 `*.gradle*` / `AndroidManifest.xml` / `*.kt` /
  `pubspec.yaml`（排除 `node_modules`）**返回空** —— 没有 Gradle、没有清单、没有 Kotlin、没有 Flutter；
- 同名近亲有 **4 个**，任务书把它们混成了一个：`imsyy/SPlayer`（Vue 3 Web/桌面）、
  `SPlayer-Dev/SPlayer-for-Android`（**Vue 3 + Capacitor WebView**，也不是 Compose）、
  `anilbeesetti/nextplayer`（Android Kotlin，但是**视频**播放器）。

**任务书抄的是一句过期的代码注释。** 「50×50 采样」与「`Score` 评分」两条**只存在于**
该项目 `color.ts` 的一句 JSDoc 里：真实常量是 `COVER_SAMPLE_SIZE = 64`，
而该文件**从未 `import Score`**（它用的是手写的
`population^0.72*0.58 + chroma*0.28 + tone*0.14` 评分器）。`Score` 确实被用了 ——
但在**另一个**项目里（`SPlayer-for-Android` 的 `coverColor.worker.ts`），采样边长是 **32**。
所以任务书在**尺寸**与**评分方法**两点上都不对。

**处置**：圆角**取值**照收（因为它另有 Google 一手出处，见下），
但**不引用 SPlayer-Next 作为依据**，且不在发布说明里把它写成参考实现。

### 证伪 2：M3 Expressive 动效不是「四档」

任务书 §3.3 给的是 `spatialMedium/spatialFast` + `effects/effectsFast` 的骨架。
MDC-Android `Motion.md` 原文是：

> The spring system provides springs in **three speeds** - fast, slow, and default…
> for each speed there are **two types** of springs - spatial and effects…
> This makes for a total of **six spring attributes**.

androidx `ExpressiveMotionTokens.kt` 里正好是 **6 个** token。已核实的官方取值：

| | dampingRatio | stiffness |
|---|---|---|
| spatial fast | **0.6** | **800** |
| spatial default | **0.8** | **380** |
| spatial slow | **0.8** | **200** |
| effects fast | **1.0** | **3800** |
| effects default | **1.0** | **1600** |
| effects slow | **1.0** | **800** |

(Standard 与 Expressive **只在 spatial 三档不同**，effects 六值完全相同。)

**并且官方没有任何「弹簧 → tween」的对应值**：`MotionScheme` 的 12 个 spec
（Standard 6 + Expressive 6）**全部**是 `spring(...)`，代码里没有 tween 分支。
所以任务书里的 `effects = tween(200, …)` 不是官方 token。
（「四档」的可能来源是旧的 M3 **时长**刻度：Short/Medium/Long/ExtraLong × 4 = 16 档。）

**处置**：`AppMotion` 按「官方空间三档 + 官方 effects 三档 + 本项目既有的 tween 词汇」组织，
每类出处写进代码注释；任务书给的 `effects`/`effectsFast` tween 作为
「本项目自己的短促非物理过渡」保留但**标明非官方**。

### 证伪 3（部分）：动态取色**已经有了**，本版要补的不是「接入」

任务书 §3.2 的口气是「接入动态取色」。实测（`probe-theme.md` §2）：
**v1.2.0 · B2-C 就已经落地了**，而且比任务书假设的完整 ——
`AccentSource` 三档（预设 / 封面 / 系统）、`androidx.palette` 取色、
后台线程、按 URL 缓存、世代号防串色、灰度回落、WCAG 4.5:1 对比度治理，**全都在**。

真正的增量只有两条：**多角色调色板（HCT）** 与 **播放页背景派生**。
算法也不同（任务是 `QuantizerCelebi` + HCT，现状是 `androidx.palette` 的 `ColorCutQuantizer`）。

---

## 1. 五个探针问题的明确回答

### 1.1 当前主题系统现状与差距

**现状**

| 项 | 实测 |
|---|---|
| 主题入口 | `ui/theme/ThemeManager.kt:80` `NcrustTheme(primaryColor, isDark)` |
| 配色板 | `ui/theme/NcrustColors.kt:22` 的 `@Immutable data class NcrustColors`（14 色槽） |
| 注入方式 | `LocalNcrustColors`（`:97`）+ `LocalNcrustTypography`；桥接到 Kanesumi `MetroColors`（`:113`） |
| Material 3 | **依赖为 0、import 为 0 处**。唯一命中是一句注释（`NcrustColors.kt:15`）。UI 组件来自外部 Kanesumi 库（组合构建） |
| 动态取色 | **有**，三档：`PRESET` / `COVER` / `SYSTEM`（`ui/theme/AccentSource.kt:24`） |
| 圆角 | **零**。`RoundedCornerShape` 0 命中；`clip(`/`border(`/`shadow`/`CircleShape`/`CornerSize` 合计 **13** 处，其中唯一带 shape 的是 `SongCard.kt:284` 的一个圆 |
| 圆角是否统一 | **统一地没有** —— 所以「把散落的收敛」这个验收项在本版不适用 |

**差距（本版要补的）**

1. **没有圆角 token** —— 引入圆角的那一刻才会产生散落风险，所以必须**先**建单一落点 +
   一条会变红的守卫测试；
2. **没有动效 token** —— 41 处手写 `tween`、0 处 `spring`；
3. **取色只有一个角色**（accent），没有 primary/secondary/tertiary/neutral 多角色调色板；
4. **播放页背景不跟随封面**（只有强调色跟随）。

### 1.2 专辑封面渲染方式与改造点

**现状**（`probe-cover.md`）

- 渲染点 **21 个**（22 次 Image 绘制）；**带 `clip` 的 0 个、带 `border` 的 0 个、带 `shadow` 的 0 个**；
- 组件：Coil `AsyncImage` / `rememberAsyncImagePainter`（`coil-compose:2.6.0`）；
- `CoverUrls` 只有 **两个**尺寸档：`small()`=640、`large()`=1080，
  URL 规则是 `?param=<N>y<N>`（字母 **y**，不是 x），已带 `param=` 的原样返回不降级；
- 全屏播放器封面：`PlayerCard.kt:1424` → `StableCover`（`:1513`），
  窄屏 `fillMaxWidth().aspectRatio(1f)`（整屏宽正方形），`ContentScale.Crop`，无裁剪无描边；
- **mini bar 封面没有独立渲染点** —— 它就是同一节点被 `scale` 到 56dp 的状态；
- 通知栏封面：Coil 1024×1024 → `downscaleArtwork()` 夹到 `ARTWORK_MAX_PX=512` →
  `setLargeIcon` + `setArtworkData`，`.setColorized(true)` + `.setColor(dominant)`。
  **应用侧不做圆角**（Android 系统 chrome 自己的事）；
- 「图片不裁圆角」是**写进 6 处注释的正典**，不是疏忽 —— 改造必须同步改这些注释，
  否则代码与自己的注释互相矛盾。

**改造点（本版）**

| 类别 | 处数 | 处置 |
|---|---|---|
| 播放页大封面 | 1 个渲染点 | `AppShapes.large`(16dp) + 1dp `outlineVariant` 描边 |
| 列表 / 菜单 / 网格小封面 | 18 处 | `AppShapes.small`(8dp) + 1dp 描边 |
| 通知栏封面 | 0 处 | **不动**（系统 chrome） |
| 非封面图片（用户头像、二维码、Markdown 正文图、自定义背景） | 6 处 | 头像 -> `AppShapes.full`（圆形）；其余不动 |

**⚠️ 一条必须遵守的实现约束（探针踩到的）**：播放页封面**不能在外面套 `Modifier.clip`**。
该节点的布局在卡片 (0,0)，视觉位置全靠自己的
`graphicsLayer { translationX/Y/scale }` 搬过去；若把 clip 排在那个 `graphicsLayer`
**之前**（= 外层），裁切边界是**未平移的布局边界**（屏幕左上角），封面会被整块裁掉。
正确顺序是 **变换在外、裁切在内**。

### 1.3 动效现状与升级方向

**现状**

| 原语 | 命中数 |
|---|---|
| `tween(` | **41**（手写，散落 10+ 文件） |
| `spring(` | **0** |
| `Crossfade` | 17 |
| `AnimatedContent` | 8 |
| `AnimatedVisibility` | 7 |
| `Animatable(` | 11 |
| `animateFloatAsState` | 5（与项目「GPU 零重组」约定相悖的**既有点**） |

- **页面切换：无动效，且是有意的用户决策** —— `NavGraph.kt:113` 四个转场全部显式 `None`，
  注释写明理由是「低端机上 slide/fade 每帧全屏合成是主要掉帧源」；
- **播放器展开/收起：有**（`tween(400, CubicBezier(0.2,0,0,1))` / `tween(260, FastOutSlowIn)`），
  并且**是承重结构** —— `progress` 同时是命中测试开关（`<0.01f` 卸载子树、`>0.99f` 吞事件）；
- **列表 item 点击反馈：有**，走 Kanesumi `MetroIndication`（`Modifier.Node` + draw 阶段，零重组）；
- **列表 item 出现动效：没有**；
- **统一规格对象：不存在**，但参数已收敛成一套词汇（260/220/200/150/120 + 3 条曲线）。

**升级方向（本版实际做的）**

1. 建 `AppMotion` 单一落点，取值**归纳既有 + 补官方空间三档**；
2. 播放器转场改弹簧，但**固定临界阻尼（`dampingRatio = 1.0`）** ——
   官方三档都会过冲（0.6/0.8/0.8），会让 `progress` 反复跨越 0.99/0.01 两个阈值；
   配单测断言「播放器转场不得过冲」；
3. 新增**有界**的列表入场动效（只对下标 < 12 的 item，纯函数判定 + 单测）；
4. **不启用页面转场** —— 见下「有意不做」。

### 1.4 队列管理接口能力

**现状**（`probe-queue.md`）

- **队列归应用层**：`MainScreen` 的 `playbackQueue: List<SongItem>` + `currentQueueIndex`
  是唯一事实源；ExoPlayer 的播放列表**不是队列**，只承载 `[当前项]` 或 `[当前项, 待播项]`；
- 关键不变量：`playbackQueue[currentQueueIndex] == 正在播的那首`；
- 播放模式 **5 种**：`CYCLE` / `SINGLE` / `SHUFFLE` / `LINE` / `INFINITY`；
- `addMediaItem` 的**唯一入口**是 `PlayerViewModel.preloadNextSong(...)`，全应用 **4 处**调用；
- **「添加到下一首播放」已经存在**（`insertNext`），核心不变量写对了。

**能力缺口（本版修的三个真实缺陷）**

1. **队列为空时只改队列、不起播** —— 点了完全没反应（任务书 §6.2 要求直接播）；
2. **乱序模式下功能静默失效** —— 只改线性队列不改编排序列，下一首仍是原来那首随机歌；
3. **不重新同步待播槽位** —— ExoPlayer 已经预载了旧的"下一首"，
   插入新歌后它**仍然会播那一首**，而队列面板显示新插入的歌。
   **这正是 v1.5.2「串台」的形状**，只是触发者换成了本版新增的入口。

**⚠️ 任务书 §6.2 的 `player.addMediaItem(index, mediaItem)` 在本工程里是错的**：
真按那个写法做，会往 ExoPlayer 列表里塞第三项，**直接破坏 v1.5.2 立下的待播槽位不变量**。
正确做法是改应用队列 + 重新同步槽位。

**需要确认的一条（任务书要求确认）**：同一首歌已在下一首 -> **去重且幂等**。
理由：重复项会踩 v1.5.2 的串台形状；且本应用**所有**队列写入都去重，
只有这一处例外会让「队列里同一首歌最多一份」这条事实不变量失效。

### 1.5 SPlayer-Next 可参考的设计要素

**结论：作为设计参考，它的可参考性是 0 —— 因为它不是 Android 应用，也没有设计 token 文件。**
它的「设计系统」是 CSS 自定义属性 + UnoCSS。

但探针在核实过程中拿到了**真正可参考的一手材料**，这些才是本版的依据：

| 要素 | 出处 | 本版是否采用 |
|---|---|---|
| M3 shape scale：`extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28` dp | androidx `ShapeTokens.kt`（VERSION 14_1_0）+ MDC `docs/theming/Shape.md`，**两处独立来源逐值一致** | ✅ 逐值采用 |
| `full` = 50%（与上五档并列的第六档） | 同上 | ✅ 采用（命名也用官方的 `full`） |
| M3 Expressive **六个**弹簧的取值 | `ExpressiveMotionTokens.kt` | ✅ spatial 三档 + effects 三档逐值采用 |
| `Score` 的 `TARGET_CHROMA=48` / `desired=4` / Google Blue 兜底 | material-color-utilities | ✅ 用于新取色通路 |
| HCT = CAM16 hue + CAM16 chroma + L\* tone；**tone 差 ≥ 40 ⇒ 对比度 ≥ 3.0:1，≥ 50 ⇒ ≥ 4.5:1** | material-color-utilities 文档 | ✅ 用于多角色调色板的对比度保证 |
| `QuantizerCelebi = QuantizerWu -> QuantizerWsmeans`（确定性、种子可复现） | 同上 | ✅ 移植 |
| **`androidx.palette` ≠ material-color-utilities**（前者没有 HCT / Celebi / Score） | 两者源码 | ⚠️ 故**两条通路并存**，不号称一致 |
| 取色的工程要点：降采样后量化 / 灰度与单色显式回退 / 种子色后置钳制 / 前景色取不同 tone / 后台线程 + 按 URL 缓存 + 超时回退 + 单调竞态令牌 | 两个 SPlayer 项目 + androidx 文档 | ✅ 现状已有大部分；本版补齐多角色与背景派生 |

**注意（如实记录）**：`m3.material.io` 的页面**全部抓不到内容**（每页都是约 62 KB 的
客户端渲染外壳，Wayback 快照同样是外壳）。所以本文件里所有 shape/motion 结论
**出处是 Google 的代码仓库，不是 m3.material.io**。这一点在 `probe-splayer-ref.md` §9 有完整清单。

---

## 2. 本版的四条**有意不做**（不是遗漏，理由在此）

| 不做 | 理由 |
|---|---|
| **页面切换转场**（任务书 §3.3） | `NavGraph.kt` 四个转场被**显式**关掉，注释写明是**用户决策**、理由是低端机掉帧。任务书没有给出任何新证据说明"现在不掉了"。铁律 15 与目标下限 Android 7.0/3GB 支持现状。**交回产品决策**，不擅自推翻 |
| **把 41 处存量 `tween` 批量改走 `AppMotion`** | 逐处判断"空间类还是效果类"需要读 41 处上下文，改错会让手感变化且**无法归因**；其中 `PlayerCard.kt` / `NcrustLyricsPanel.kt` 被 `AGENTS.md` 标注 load-bearing、禁止批量重构 |
| **改 5 处既有 `animateFloatAsState`** | 它们是既有点、不在本版范围；且集中在被标注为承重的文件里。改为列入未验证/遗留清单 |
| **改 Kanesumi 库**（用户裁定） | 那会让发布产物依赖另一个仓库的未发布提交，破坏「`git clone` 即可复现」这条 v1.5.0 立下的纪律（搬 `MetroLyricsPanel` 进本仓库正是同一条理由）。代价：库内部的弹窗/按钮背板保持直角，已列入遗留清单 |

## 3. 本版新增的单一落点（后续改这套视觉只需动这些文件）

| 文件 | 作用 |
|---|---|
| `ui/theme/AppShapes.kt` | 圆角 token（**唯一**允许出现圆角构造器的文件） |
| `ui/theme/AppMotion.kt` | 动效 token（**唯一**允许出现 `spring(` 的文件） |
| `ui/theme/color/**` | HCT 取色与多角色调色板（纯 Kotlin，可 JVM 单测） |
| `ui/components/AppVisualModifiers.kt` | `appCoverFrame`（封面圆角+边框）、`appPressScale`（按压回弹） |
| `ui/components/ListItemAppear.kt` | 列表入场（含**有界性**的纯判定） |
| `ui/components/AppSnackbar.kt` | 应用级 Snackbar（此前全仓库 0 处） |
| `player/QueueInsert.kt` | 「添加到下一首播放」的队列边界判定（纯逻辑） |

## 4. 三条把「约定」变成「会变红的测试」的守卫

这是本版对铁律「不伪造、可验证」的落实方式 —— 约定写在文档里会被绕过
（41 处手写 `tween` 就是证据），写进测试才会变红：

| 测试 | 钉住的事实 |
|---|---|
| `ui/theme/AppShapesSingleSourceTest` | 圆角构造器**只能**出现在 `AppShapes.kt`（文本扫描全源码树，并自检"确实扫到了文件"以防空扫描恒绿） |
| `ui/theme/AppMotionSpecTest` | ① 官方六弹簧逐值；② **播放器转场不得过冲**（`dampingRatio >= 1.0`）；③ `spring(` 只能来自 `AppMotion` |
| `ui/components/ListItemAppearTest` | 入场动效**必须有界**（阈值在 1..64，边界是半开区间） |
| `player/QueueInsertTest` | 铁律 16 的五条边界 + 乱序排列合法性（穷举 4! 排列） |

## 5. 未确认项（与各探针的 §未确认 汇总）

1. Kanesumi `MetroDialog` / `MetroBottomSheet` 内部有无 shape 注入点 **未逐行读**（本版不改该库，故未查）。
2. 两条取色通路的**颜色一致性未做 A/B**，且**不应**被当作验收项（它们是两套算法）。
3. 41 处 `tween` 的逐处语义未全部读过 -> 本版因此不做批量替换。
4. `QueueModes.SINGLE` 下"添加到下一首播放"的**用户预期**未做真人验证。
5. 队列去重用**裸 `song.id`**，跨源撞号是**既有**风险（本版未引入、也未加剧）。
6. 通知栏封面是否被 SystemUI 自行加圆角 **未确认**（需要真机截图比对）。
7. 1dp 描边在深/浅色下的实际对比度 **未做真机比对**（`outlineVariant` 在深色 `#2A2A2A`、
   浅色 `#E2DACB`，与封面图像的对比取决于封面本身）。
8. 转场（本版未启用）对播放器卡片命中区的影响**只有真机能判**。
