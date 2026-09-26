# 平板横屏「音频可视化波浪条不显示」探针（v2.5.4）

> **只读调研。本文没有改动任何源码。** 所有 `file:line` 都用 read/grep 工具在
> `/home/duanjb666/deepseek/ncrust-gpl/Ncrust` 当前工作区（HEAD = `da5c9f8`，`versionName = 2.5.4-gpl`）
> 上核对过，引用的是文件里的真实代码；行号即当前工作区行号。
>
> 设备侧**只做被动只读查询**：`adb shell getprop / wm size / wm density / dumpsys / settings get / logcat -d`。
> **没有启动 App、没有写 prefs、没有 `settings put` / `wm size` 改动**——依据本仓库既有纪律
> 「与主会话在同一台 WGR-W09 上并发采集会互相污染，按协调要求让出设备」
> （`docs/verification/v2.1.6/p1-huawei-whitelist/whitelist-criterion.md` §8 U6，该条自身引用的
> `EVIDENCE.md` §B-6 现内容为「截图有效性」，编号疑有漂移，以 U6 原文为准），本轮主动让出设备。
> 凡属推断而非读到的，一律标在文末「未确认 / 不确定」。

---

## 0. 结论先行

### 0.1 最可能的根因：可视化**只有一个挂载点**，而平板横屏走的不是那个分支

`AudioVisualizerBars` 全仓库**只有一个挂载点**（`grep -rn "AudioVisualizerBars" app/src` 命中 3 处：
定义 1 处、KDoc 1 处、挂载 1 处）：它在 `PlayerCard.kt` 的
`when { bigScreenActive -> … }` 分支里，且还被 `if (visualizerEnabled)` 再包一层：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt:899-900（分支起点）
                when {
                    bigScreenActive -> {
...
// app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt:952-966（唯一挂载点）
                                if (visualizerEnabled) {
                                    AudioVisualizerBars(
                                        activeProvider = {
                                            isPlaying && !playerViewModel.isBuffering.value
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                            .height(visualizerHeightDp),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
```

**失败的是哪个谓词**：不是「sw600dp 判据没满足」，而是**恰恰因为它满足了**。
平板两个方向都满足 `windowWidthDp >= 600`（WGR-W09 实测：横屏 `w1280dp`、竖屏 `w800dp`、
`smallestScreenWidthDp = 800dp`），于是被路由到**宽屏两栏分支**——而那个分支**一个挂载点都没有**：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt:181
    val isWidePlayer = LocalConfiguration.current.screenWidthDp >= 600
// app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt:186-192
    val bigScreenActive = PlayerLayout.isBigScreenActive(
        requested = bigScreen,
        orientationLandscape = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE,
    )
```

真实生效的、会失败的谓词是 **`PlayerLayout.isBigScreenActive(requested = bigScreen, orientationLandscape = true)`
里的 `requested == false`**，即「大屏幕模式（`bigScreenMode`）没有被激活」。
`bigScreenMode` 是 Activity 里的一个**纯内存、不落盘**的 `mutableStateOf(false)`
（`MainActivity.kt:169`），没有任何「平板自动开启」逻辑；它变 true 只有两条路：

1. `AutoRotateWatcher` 自动进入：**应用内 `auto_rotate` 开** + 播放器展开 + 窗口已横屏 + 不在大屏
   （`MainActivity.kt:708-723` + `BigScreenOrientation.kt:120-125`）；
2. 控制条里的 ⤢ 手动入口（`FullPlayerControls.kt:457-473`）。

**而这条路在平板上是断的**：⤢ 入口只存在于**竖屏控制条变体**里。`FullPlayerControls` 用
`if (landscape) { … } else { … }` 分成两套控制条（`FullPlayerControls.kt:148` / `306`），
⤢ 入口在 `else` 那一套的 `Row`（`FullPlayerControls.kt:402`）里：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/ui/player/FullPlayerControls.kt:148-153
    if (landscape) {
        // 宽屏横向控件条：进度行（位置 · 进度 · 时长）+ 操作行（面板开关 · 音质 · 传输），
        // 扁平铺开，不再照搬手机的竖向大按钮堆叠。
...
// app/src/main/java/com/takahashirinta/ncrust/ui/player/FullPlayerControls.kt:457-464（⤢ 入口，只在竖屏那一套里）
            // P1：大屏幕模式入口。同一槽位在横屏大屏下变成"退出大屏"。
            Box(
                modifier = Modifier
                    .size(toggleBtn)
                    .clickable {
                        tick()
                        onToggleBigScreen()
                    },
```

而 `PlayerCard` 传给它的 `landscape` 是 `usesSideCover`：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt:195
    val usesSideCover = isWidePlayer || bigScreenActive
// app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt:727-729
                        // 大屏模式也用扁平横向控制条（竖屏那套大按钮堆叠在 300dp 高里放不下）。
                        landscape = usesSideCover,
                        compact = usesSideCover,
```

⇒ **平板上 `isWidePlayer` 恒为 true ⇒ `landscape` 恒为 true ⇒ 平板永远渲染「横向控制条」那套
⇒ 平板两个方向都没有 ⤢ 入口**。手机不一样：手机竖屏 `windowWidthDp ≈ 360 < 600 ⇒ isWidePlayer=false`
⇒ 手机竖屏有 ⤢ 入口（先按 ⤢ 再转横屏即可进大屏）。**「平板进不去大屏模式」这件事是平板独有的。**

**结论一句话**：平板横屏的可视化不可达，根因是
**「可视化只挂在 `bigScreenActive` 分支」＋「平板两个方向都命中 `isWidePlayer` 分支（无挂载点）」＋
「平板两个方向都没有 ⤢ 入口，唯一入口是自动进大屏」** 三者叠加。
其中第三条让第二条从「需要先点一下 ⤢」升级成「用户无法自救」。

### 0.2 次因排序（按排查成本从低到高）

| # | 怀疑 | 判据 / 证据 | 现象 |
|---|---|---|---|
| 1 | **未进入大屏模式**（主因） | `PlayerCard.kt:900` vs `:1051`；`MainActivity.kt:169/358`；`FullPlayerControls.kt:148/457` | 波浪条整块不存在；同目录既存截图正长这样（§3 E13/E14） |
| 2 | **设置开关被关**（`ncrust_settings/audio_visualizer=false`） | `AudioVisualizer.kt:38/40/47-55` + `PlayerCard.kt:222/952` | 连 `LaunchedEffect` 帧循环都不跑，任何形态都看不到；即便进了大屏也没有 |
| 3 | **挂载了但是"平的"**：数据侧没有非零 RMS | `AudioVisualizer.kt:120`（`enabled` 为 false 直接丢弃）、`TransparentWaveformSink.kt:82-83`（未知编码早退）、`:86-87`（RMS 异常吞成 0.0）；`AudioVisualizer.kt:232/238-239` | 28 根柱全部只剩 `minBar = 1.dp`，视觉上是「一条 1dp 细线」，容易被判成"没显示" |
| 4 | 暂停 / 缓冲中 | `PlayerCard.kt:957-959` + `AudioVisualizer.kt:213-219` | `pump(active=false)` 把柱高按 `RELEASE_TAU_MS=130ms` 衰减到 0（`WaveformRing.kt:95-104`），这是**预期行为**，不是 bug |
| 5 | 高度 / 尺寸类 | `PlayerCard.kt:223-224`（平板横屏 768dp → 84.5dp → clamp **56dp**）；`AudioVisualizer.kt:228` 只在 `size.height <= 0f` 时早退 | 平板横屏不可能算出 0 高度；**排除** |

---

## 1. A/B 矩阵（核心交付）

设备实测 dp（WGR-W09，`dumpsys window displays` 的 `overrideConfig`）：
`sw800dp w1280dp h768dp 320dpi … land`，`mBounds=Rect(0,0-2560,1600)`，`mRotation=ROTATION_90`。
手机列用仓库既有基线 PCL110（`PlayerLayoutTest.kt:19-22`：横屏 800×363dp、竖屏 363dp 宽）。

分支行号（`PlayerCard.kt`）：
`bigScreenActive ->` **899–1050**；`isWidePlayer ->` **1051–1126**；`else ->`（窄屏）**1128–1316**；
`}   // end when { 大屏 / 宽屏两栏 / 窄屏 }` **1317**（该 `isWidePlayer` 块整体缩进比大屏分支浅一级，
是既有代码的缩进瑕疵，不改变语义）。
（注：1126/1127 两行是 `Row(1054)` 与分支块的收口，`else` 在 1128 —— 分支归属以分支标签行为准。）

### 1.1 默认设置（`audio_visualizer=true`、应用内 `auto_rotate=true`）

| 形态 | windowWidthDp | windowHeightDp | smallestScreenWidthDp | `isWidePlayer` | `orientationLandscape` | `requested`(bigScreen) | `bigScreenActive` | 命中分支 | 可视化挂载 | 高度 |
|---|---|---|---|---|---|---|---|---|---|---|
| 手机竖屏 | 360–363 | ~800 | 360–363 | **false** | false | false | false | `else` 1128–1316 | ❌ 无挂载点（设计如此） | — |
| 手机横屏 | **800**（PCL110 实测） | 363 | 363 | **true** | true | **true**（自动进大屏） | **true** | `bigScreenActive` 900–1050 | ✅ 952–966 | 363×0.11≈**39.9dp** |
| 平板竖屏 | **800** | 1280 | **800** | **true** | false | false（自动进入要求横屏） | **false** | `isWidePlayer` 1051–1126 | ❌ **无挂载点** | — |
| 平板横屏 | **1280** | **768** | **800** | **true** | true | **true**（自动进大屏） | **true** | `bigScreenActive` 900–1050 | ✅ 952–966 | 768×0.11=84.5→**56dp** |

### 1.2 应用内 `auto_rotate=false`（或曾用返回键/⤢ 退出大屏之后未「收起再展开」）

| 形态 | `isWidePlayer` | `orientationLandscape` | `requested` | `bigScreenActive` | 命中分支 | 可视化挂载 | 说明 |
|---|---|---|---|---|---|---|---|
| 手机竖屏 | false | false | false | false | `else` | ❌ | 但**有 ⤢ 入口**（竖屏控制条）⇒ 按一下转横即进大屏 |
| 手机横屏 | true | true | false | false | `isWidePlayer` | ❌ | 横向控制条里**没有 ⤢ 入口**；要进大屏只能先转回竖屏按 ⤢ |
| 平板竖屏 | true | false | false | false | `isWidePlayer` | ❌ | 控制条已是横向变体 ⇒ **没有 ⤢ 入口** |
| 平板横屏 | true | true | false | false | `isWidePlayer` | ❌ | **= 用户报障现象**；平板上无法自救 |

`requested=false` 的来源：`MainActivity.kt:169`（内存态、不落盘、无平板自动开启）；
变 true 的路径见 `MainActivity.kt:231-234 / 359-361`；变 false 的路径见
`MainActivity.kt:583-589 / 2587-2591 / 414-428`。

### 1.3 判断「当前在不在大屏模式」的可见代理

`bigScreenActive` 与沉浸式判据是同一份（`MainActivity.kt:255-260` 注释 + `:772-782`）：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt:772-782
private fun ImmersiveEffect(
    bigScreen: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
) {
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val active = bigScreen && landscape
```

⇒ **状态栏可见 ≈ 不在大屏模式**（华为 ROM 是否稳定放行沉浸式见 §6 U4）。
更强的判据是**控件条的位置**：大屏分支把控制条画在**右栏底部**（`PlayerCard.kt:1047`），
宽屏两栏分支画在**左栏歌名/歌手之下**（`PlayerCard.kt:1101-1110`）。

---

## 2. 代码事实

### 2.1 `ui/player/AudioVisualizer.kt`（252 行，全文读过）

**`VisualizerSetting`（开关的唯一读写入口）**

| 项 | 值 | 证据 |
|---|---|---|
| prefs 文件 | `ncrust_settings` | `AudioVisualizer.kt:37` |
| prefs key | `audio_visualizer` | `AudioVisualizer.kt:38` |
| 默认值 | `true`（**默认开**） | `AudioVisualizer.kt:40` `const val DEFAULT_ENABLED = true` |
| 读 | `read(context)`：首次读盘，并把结果镜像到 `WaveformStore.enabled` | `AudioVisualizer.kt:47-55` |
| 写 | `write(context, enabled)`：落盘 + `WaveformStore.enabled = enabled` | `AudioVisualizer.kt:57-65` |

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/ui/player/AudioVisualizer.kt:47-55
    fun read(context: Context): Boolean {
        if (!loadedFromDisk) {
            val enabled = prefs(context).getBoolean(KEY, DEFAULT_ENABLED)
            stateHolder.value = enabled
            WaveformStore.enabled = enabled
            loadedFromDisk = true
        }
        return stateHolder.value
    }
```

调用点：`MainActivity.kt:204`（`onCreate`，早于 `setContent`）、`PlaybackService.kt:378`。
设置页开关：`UserScreen.kt:153`（初值）/`UserScreen.kt:474-482`（`SettingSwitchRow` → `VisualizerSetting.write`）。

**`WaveformStore`（音频线程 → UI 线程的唯一交汇点）**

| 项 | 值 | 证据 |
|---|---|---|
| `BAR_COUNT` | 28 | `AudioVisualizer.kt:89` |
| `BARS_PER_SECOND` | 30（media3 建议区间上限） | `AudioVisualizer.kt:98` |
| 环容量 | `CAPACITY = 256`（≈8.5s 积压，溢出丢最旧） | `AudioVisualizer.kt:101-103` |
| 音频线程侧开关镜像 | `@Volatile var enabled = DEFAULT_ENABLED` | `AudioVisualizer.kt:110-111` |
| 音频线程写 | `onBar(rms)`：`if (enabled) ring.push(...)` —— **enabled=false 时数据被丢弃** | `AudioVisualizer.kt:118-121` |
| UI 线程抽帧 | `pump(active, dtMs)`：有变化才 `generationState.intValue++` | `AudioVisualizer.kt:127-129` |
| UI 线程取快照 | `snapshot(destination)` → `ring.copyInto` | `AudioVisualizer.kt:132` |

**`AudioVisualizerBars` 签名**（`AudioVisualizer.kt:185-190`）：

```kotlin
@Composable
fun AudioVisualizerBars(
    activeProvider: () -> Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = WaveformStore.BAR_COUNT,
)
```

**内部 gating（会被误当成"卡住"的三处）**

1. **低内存 / SDK 档位只改重绘间隔，不改可见性**（`AudioVisualizer.kt:141-166`）：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/ui/player/AudioVisualizer.kt:160-166
fun visualizerFrameIntervalMs(context: Context): Long {
    val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val lowTier = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        activityManager?.isLowRamDevice == true
    return if (lowTier) VISUALIZER_FRAME_INTERVAL_SLOW_MS else VISUALIZER_FRAME_INTERVAL_FAST_MS
}
```
   `FAST = 16L`（`:142`）、`SLOW = 33L`（`:145`）。WGR-W09 是 API 31 且非 low-ram ⇒ **16ms**。
   **这一项不可能让柱子不可见**（30 柱/秒的数据、16 或 33ms 重绘都能画出来）。

2. **帧循环**（`AudioVisualizer.kt:198-221`，原文）：

```kotlin
    LaunchedEffect(barCount, frameIntervalMs) {
        val budgetNs = frameIntervalMs * 1_000_000L
        var lastFrameNs = 0L
        var lastPumpNs = 0L
        while (true) {
            if (currentActive.value()) {
                withFrameNanos { now ->
                    if (lastFrameNs == 0L || now - lastFrameNs >= budgetNs) {
                        val dtMs = if (lastPumpNs == 0L) frameIntervalMs.toFloat()
                        else ((now - lastPumpNs) / 1_000_000f).coerceIn(1f, 100f)
                        lastFrameNs = now
                        lastPumpNs = now
                        WaveformStore.pump(active = true, dtMs = dtMs)
                    }
                }
            } else {
                // 暂停 / 缓冲：用 delay 而不是帧时钟 —— 归零过程不需要跟着刷新率走。
                delay(frameIntervalMs)
                lastFrameNs = 0L
                lastPumpNs = 0L
                WaveformStore.pump(active = false, dtMs = frameIntervalMs.toFloat())
            }
        }
    }
```

3. **`WaveformStore` 一直收不到数据时会发生什么**（最重要）：
   - `generation` 永不递增 ⇒ `Canvas` 的 draw lambda（`:225` 读 `WaveformStore.generation`）只在首次
     组合/尺寸变化时画一次；
   - 环里 `bars` 全是 0 ⇒ `amplitude = sqrt(0) = 0` ⇒
     `height = (0f).coerceAtLeast(minBar)`，`minBar = 1.dp.toPx()`（`AudioVisualizer.kt:232/238-239`）
     ⇒ 画出来是 **28 根 1dp 高的方块**，视觉上就是「一条细线 / 什么都没有」；
   - `pump` 在"没有新柱且已收敛"时返回 false（`WaveformRing.kt:92-93/103`）⇒ 不再请求重绘，
     症状**静止**、不会自愈；
   - 若 `enabled=false`（设置开关关掉过，或 `write(context,false)` 被调用过），
     `onBar` 的 `if (enabled)` 直接把数据丢掉（`:120`），即使 UI 侧"以为开着"也永远收不到数据。

### 2.2 `ui/player/PlayerCard.kt`（1699 行）—— 全部分支与唯一挂载点

| 位置 | 内容 |
|---|---|
| `:117-118` | `bigScreen: Boolean = false`（**用户意图**）、`onToggleBigScreen` |
| `:181` | `isWidePlayer = screenWidthDp >= 600` |
| `:186-192` | `bigScreenActive = PlayerLayout.isBigScreenActive(requested = bigScreen, orientationLandscape = …)` |
| `:195` | `usesSideCover = isWidePlayer \|\| bigScreenActive` |
| `:222` | `visualizerEnabled = VisualizerSetting.state.value`（**组合期读**） |
| `:223-224` | `visualizerHeightDp = (screenHeightDp.dp * 0.11f).coerceIn(32.dp, 56.dp)` |
| `:269` | `expandedMounted = progress.value > 0.01f` |
| `:673` | `if (hasSong && expandedMounted) {` —— 展开态子树的总闸 |
| `:899` | `when {` |
| `:900-1050` | **大屏分支** `bigScreenActive ->`：左栏＝封面区(weight 1f) + **可视化(952-966)** + 歌名/作者/音质/旋转；右栏＝面板 + 控制条(1047) |
| `:1051-1126` | **宽屏两栏分支** `isWidePlayer ->`：左栏＝封面区(1063-1073) + 歌名/作者(1075-1099) + 控制条(1101-1110) + `Spacer(8.dp)`；右栏＝面板(1115-1125)。**没有任何可视化挂载点** |
| `:1128-1316` | **窄屏分支** `else ->`：顶部标题栏 + 整宽面板 + 底部控件 |
| `:1317` | `}   // end when { 大屏 / 宽屏两栏 / 窄屏 }` |
| `:1476` | `if (hasSong && !bigScreenActive) {` 收起按钮叠加层（大屏下整层不挂载） |
| `:746-765` | 大屏下控制条右端的 **退出** 大屏按钮（`CloseFullscreen`），仅在 `bigScreenActive` 时作为 `trailing` |

大屏分支的动态高度（**原文，含注释**）：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt:219-224
    // v1.8.0 · T3：可视化条的开关与高度。
    // 高度 = 窗口高 × 11%，夹在 32~56dp：PCL110 横屏（363dp 高）得 40dp、
    // S6（480dp 高）得 53dp —— 矮屏少占、高屏多给，封面区用 weight(1f) 自动让位。
    val visualizerEnabled = VisualizerSetting.state.value
    val visualizerHeightDp =
        (LocalConfiguration.current.screenHeightDp.dp * 0.11f).coerceIn(32.dp, 56.dp)
```

### 2.3 `ui/player/PlayerLayout.kt`（85 行，全文读过）+ `requested` 的源头

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerLayout.kt:31-41
    /**
     * 第三谓词：大屏幕模式是否生效。
     * ...
     */
    fun isBigScreenActive(requested: Boolean, orientationLandscape: Boolean): Boolean =
        requested && orientationLandscape
```

* 宽屏断点：`const val WIDE_BREAKPOINT_DP = 600`（`PlayerLayout.kt:23`），KDoc 明确写着
  「与全仓库其余 6 处保持一致，不要改」（`:22`）。
* `WIDE_RIGHT_FRACTION = 0.56f`（`:26`）、`BIG_SCREEN_LEFT_FRACTION = 1f - WIDE_RIGHT_FRACTION`（`:29`）、
  `wideLeftFraction`（`:47-48`）、`splitBoundaryPx`（`:58-59`）、`bigScreenLeftBoundaryPx`（`:62-63`）、
  `squareCoverSizePx`（`:72-73`）、`coverFallbackSizePx`（`:83-84`）。
* `isWidePlayer` 的**唯一**计算点在 `PlayerCard.kt:181`（不是 `PlayerLayout`），
  所以「可视化该不该挂」这件事今天**没有任何纯逻辑可测点**。
* `requested` 的源头链：`MainActivity.kt:169` `private val bigScreenMode = mutableStateOf(false)`
  → `MainActivity.kt:358` `bigScreen = bigScreenMode.value` → `MainScreen(… bigScreen …)`（`MainActivity.kt:799`）
  → `PlayerCardOverlay`（`:37/:71`）→ `PlayerCard(bigScreen = bigScreen)`（`MainActivity.kt:2132`）。
  **没有任何 prefs key 存"大屏幕模式"**（`grep -rn "big.?screen" --include=*.kt` 只有状态与回调，无 `getBoolean/putBoolean`）。
* 600dp 断点在全仓库的其余用法（都**不**管可视化）：`MainActivity.kt:852`（`isWideLayout`，侧栏）、
  `ResponsiveContent.kt:29`、`DetailScaffold.kt:231`、`HomeScreen.kt:130`、`UserScreen.kt:139`、
  `BottomOverlayInset.kt:22`，以及 `MainActivity.kt:528` 的
  `isLargeScreen = resources.configuration.smallestScreenWidthDp >= 600`。
* **没有任何 `res/layout-sw600dp` 之类的平板专用布局**（`find app/src/main/res -type d -name '*sw600*'` 0 命中）——
  本 App 是纯 Compose，「平板布局」就是那几个 `screenWidthDp >= 600` 的 Kotlin 谓词。
  所以"某处存在一个平板专用布局、它漏了可视化"这一假设**不成立**；真实情况是**平板复用了宽屏两栏布局，而那份没有可视化**。

### 2.4 `MainActivity.kt`（2593 行）—— 方向策略与"大屏幕模式是否自动开启"

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt:161-183
    /**
     * 大屏模式总开关（用户意图）。布局侧只读这个值 + 当前窗口方向，见
     * [com.takahashirinta.ncrust.ui.player.PlayerLayout.isBigScreenActive]。
     * ...
     */
    private val bigScreenMode = mutableStateOf(false)
    ...
    /** 应用内「自动旋转」开关的镜像。... */
    private var autoRotateEnabled = RotationSetting.DEFAULT_ENABLED
```

方向策略（`applyOrientationPolicy`，`MainActivity.kt:522-541` 摘录）：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt:522-540
    private fun applyOrientationPolicy() {
        autoRotateEnabled = RotationSetting.read(this)
        val desired = BigScreenOrientation.orientationFor(
            autoRotate = autoRotateEnabled,
            bigScreen = bigScreenMode.value,
            bigScreenRelaxed = bigScreenOrientationRelaxed,
            isLargeScreen = resources.configuration.smallestScreenWidthDp >= 600,
        )
        val requested = when (desired) {
            BigScreenOrientation.DesiredOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            BigScreenOrientation.DesiredOrientation.SENSOR_LANDSCAPE ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            BigScreenOrientation.DesiredOrientation.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            BigScreenOrientation.DesiredOrientation.UNSPECIFIED ->
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != requested) {
            requestedOrientation = requested
        }
    }
```

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/BigScreenOrientation.kt:98-105
    ): DesiredOrientation = when {
        bigScreen && !autoRotate -> DesiredOrientation.SENSOR_LANDSCAPE
        bigScreen && !bigScreenRelaxed -> DesiredOrientation.SENSOR_LANDSCAPE
        bigScreen -> DesiredOrientation.SENSOR
        autoRotate -> DesiredOrientation.SENSOR
        isLargeScreen -> DesiredOrientation.UNSPECIFIED
        else -> DesiredOrientation.PORTRAIT
    }
```

**平板是否自动进入大屏？—— 只有"自动旋转 + 播放器展开 + 窗口已横屏"同时成立才会：**

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/BigScreenOrientation.kt:120-125
    fun shouldAutoEnterBigScreen(
        autoRotate: Boolean,
        playerExpanded: Boolean,
        windowLandscape: Boolean,
        bigScreen: Boolean,
    ): Boolean = autoRotate && playerExpanded && windowLandscape && !bigScreen
```

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt:702-723（AutoRotateWatcher 摘录）
    val windowLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val autoRotate = RotationSetting.state.value
    val expanded = playerExpanded.value
    val currentOnAutoEnter = rememberUpdatedState(onAutoEnter)

    LaunchedEffect(autoRotate, expanded, windowLandscape) {
        if (!BigScreenOrientation.shouldAutoEnterBigScreen(
                autoRotate = autoRotate,
                playerExpanded = expanded,
                windowLandscape = windowLandscape,
                bigScreen = false,
            )
        ) return@LaunchedEffect
        delay(BigScreenOrientation.AUTO_ENTER_SETTLE_MS)
        currentOnAutoEnter.value()
    }
```

`playerExpanded` 的来源是 MainScreen 的 `progress.value > 0.99f`（`MainActivity.kt:913-920`），
即**播放器卡片真的全展开**；`AUTO_ENTER_SETTLE_MS = 250L`（`BigScreenOrientation.kt:41`）。

**"平板横屏 + 默认设置"下 `bigScreenActive` 为 true 的确切条件**（缺一不可）：
`RotationSetting`（prefs `ncrust_settings/auto_rotate`，默认 true，`RotationSetting.kt:38-42`）为 true
且 `playerExpanded == true` 且窗口方向为 LANDSCAPE 且 `bigScreenMode == false`（自动进入前）
⇒ 250ms 后 `enterBigScreenMode(auto=true)`（`MainActivity.kt:233 / 557-580`）⇒ `bigScreenMode = true`
⇒ `bigScreenActive = true && landscape = true`。

**"大屏幕模式"在平板上的自动开启总结**：**开（默认）时会自动开**，前提是播放器已展开且窗口已横屏；
**关掉"自动旋转"后不会自动开，而且平板上没有任何手动入口**（§0.1）。
另有两条退出路径：返回键（`MainActivity.kt:2587-2591`：`if (bigScreen) onToggleBigScreen() else collapseCard()`）、
窗口转回竖屏（`MainActivity.kt:419-424` + `BigScreenOrientation.kt:156-157`）。
`onConfigurationChanged` 已声明在 Manifest（`android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"`，
`AndroidManifest.xml:49-50`，`resizeableActivity="true"`），所以旋转**不重建 Activity**、`bigScreenMode` 不丢。

### 2.5 PCM → `WaveformStore` 的链路（`player/VisualizerRenderersFactory.kt` + `TransparentWaveformSink.kt` 全文读过）

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/player/VisualizerRenderersFactory.kt:55-77（摘录）
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val waveformSink = TransparentWaveformSink()
        val processors = arrayOf<AudioProcessor>(TeeAudioProcessor(waveformSink))
        return DefaultAudioSink.Builder(context)
            .setAudioProcessors(processors)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
```

* 装配点：`PlaybackService.kt:378-380`
  ```kotlin
        VisualizerSetting.read(this)
        val renderersFactory = VisualizerRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
  ```
  `ExoPlayer.Builder(this, renderersFactory, mediaSourceFactory)`（`PlaybackService.kt:394`）。
* **线程**：`WaveformAudioBufferSink`/tee 的回调发生在 **ExoPlayer playback 线程**（`VisualizerRenderersFactory.kt:39-47`
  的 KDoc：`ExoPlayer:Playback`、THREAD_PRIORITY_AUDIO；并明确「只允许一次数组写 + 一次 volatile 自增」）。
* **格式**：tee 看到的是**解码后、未跳静音未变速的原始 PCM**（`VisualizerRenderersFactory.kt:44-47`）；
  `TransparentWaveformSink.flush` 只记录声道/编码/字节宽，**不建声道矩阵**（`TransparentWaveformSink.kt:69-74`）。
* **`WaveformStore.enabled` 是否 gate**：**不 gate 数据采集，只 gate 存储**——
  `handleBuffer` 恒调 `WaveformStore.onBar(rms)`（`TransparentWaveformSink.kt:88`），
  而 `onBar` 里 `if (enabled) ring.push(...)`（`AudioVisualizer.kt:120`）。
  所以关掉开关时音频线程只剩一次 volatile 读（与 `AudioVisualizer.kt:106-109` 的注释一致）。
* **哪些解码器经过它**：`buildAudioSink` 是 media3 里唯一构造 `AudioSink` 的地方，
  MediaCodec 与 FFmpeg（`EXTENSION_RENDERER_MODE_ON`）两条路径**共用同一个 AudioSink 实例**
  （`VisualizerRenderersFactory.kt:34-37` 的 KDoc，引用 `DefaultRenderersFactory.java:532-543`）。
  即 FLAC / MP3 / AAC 全都过这条链 —— **没有"某种编码不过 tee"的分支**。
* **无数据流动的条件（逐个排除）**：
  1. **offload / 直通 / tunneling：已关闭（by design）**。`PlaybackService.kt:293-306` 的 KDoc 写明
     「offload 会绕过应用侧音频处理链」「因此这里只做能力探测并打日志」，
     实现只有 `logAudioOffloadCapability()`（`PlaybackService.kt:307-328`，日志尾部
     `"AudioOffload: mp3 supported=" + supported + " (decision: disabled by design)"`），
     没有 `setEnableAudioOffload`、没有 `AudioOffloadSupportProvider`
     （`grep -rn "setEnableAudioOffload\|AudioOffload" app/src/main` 只命中这段注释与探测）。
  2. **未知编码**：`bytesPerSampleOf` 只认 `ENCODING_PCM_16BIT`(2B) / `ENCODING_PCM_FLOAT`(4B)，
     其余返回 0 ⇒ `handleBuffer` 直接 return（`TransparentWaveformSink.kt:82-83` + `:106-110`）
     —— **宁可没有可视化也不抛异常**（`:79-80` 的注释）。
  3. **RMS 计算异常**：`runCatching { … }.getOrDefault(0.0)`（`:86-87`）⇒ 失败时推 **0.0**，
     柱子只会是"平的"，不会崩。
  4. **暂停 / 缓冲**：只有 UI 侧 `activeProvider` 为 false（`PlayerCard.kt:957-959`），
     数据仍照写（`handleBuffer` 与播放状态无关），只是画面按衰减归零（`AudioVisualizer.kt:213-219`）。
  5. **播放器实例在旧版本被 6 声道打坏**（历史 P0，已修）：`TransparentWaveformSink.kt:1-43` 记录了
     v1.8.0 用 `WaveformAudioBufferSink(…,1,…)` 在 6→1 声道矩阵上抛异常、
     导致同一 player 实例之后**任何档位都播不出声**的完整因果链；v2.2.1 已改为本文件的"不混音"实现。
     **这与"波浪条不显示"相关但不是同一个 bug**（那个是彻底没声音）。

### 2.6 既有测试现状

| 测试 | 用例数 | 覆盖 | 与本 bug 的关系 |
|---|---|---|---|
| `app/src/test/java/com/takahashirinta/ncrust/ui/player/WaveformRingTest.kt` | 10（`:33/49/60/70/84/98/109/123/135/145`） | 环缓冲消费顺序、平滑收敛、静止不重绘、**刷新率无关**、起音快于回落、溢出丢最旧、暂停衰减归零、NaN/越界钳制、`clear`、`copyInto` 复用 | 只覆盖"有数据之后"的纯逻辑；**不覆盖"是否挂载"** |
| `app/src/test/java/com/takahashirinta/ncrust/player/TransparentWaveformSinkTest.kt` | 6（`:48/57/67/83/89/103`） | 6→1 矩阵必抛的回归钉、6 声道不抛、RMS 与声道数无关、float RMS、未知编码不抛、空/半个 buffer 不炸 | 只覆盖音频线程侧；**不覆盖 UI 挂载** |
| `app/src/test/java/com/takahashirinta/ncrust/ui/player/PlayerLayoutTest.kt` | 12（`:29/34/40/48/55/61/68/80/85/93/101/106`） | `isBigScreenActive` 四组合、`wideLeftFraction`、分栏边界回归（44%~50% 窄带）、封面正方形/兜底 | **有 `isBigScreenActive` 的纯逻辑可测点**，但**没有**"可视化该挂在哪"的谓词 |

`grep -rn "Visualizer|WaveformStore" app/src/test/` 的结果只有 i18n 快照 JSON 里的
`audioVisualizerLabel/Description` 字面量 —— **「挂载条件」这条纯逻辑今天完全不存在，也没有任何单测**。
换句话说：这个 bug 之所以能长期存在，是因为**决定"挂不挂"的判断被写在 `@Composable` 里（`PlayerCard.kt:952`），
既不可单测、也没有任何断言钉住"哪些形态必须有波浪条"**。

---

## 3. 证据表

| # | 断言 | 证据（file:line） | 方法 |
|---|---|---|---|
| E1 | 可视化全仓库只有一个挂载点 | `PlayerCard.kt:952-966`（另 2 处命中是定义 `AudioVisualizer.kt:186` 与 KDoc `:33`） | `grep -rn "AudioVisualizerBars"` |
| E2 | 该挂载点在 `bigScreenActive` 分支内且被 `visualizerEnabled` 再包一层 | `PlayerCard.kt:899-900`、`:952`、`:222` | read（899–1050 区间） |
| E3 | `bigScreenActive = requested && orientationLandscape` | `PlayerLayout.kt:40-41`；调用点 `PlayerCard.kt:186-192` | read 全文 |
| E4 | 宽屏两栏分支没有可视化挂载点 | `PlayerCard.kt:1051-1126`（左栏 1055–1112 只有封面区/歌名/控制条/Spacer） | read（1051–1130） |
| E5 | `isWidePlayer = screenWidthDp >= 600`，平板两方向都成立 | `PlayerCard.kt:181`；平板实测 `sw800dp w1280dp h768dp` | read + `adb shell dumpsys window displays` |
| E6 | 三套布局就是全部（无平板专用布局） | `PlayerCard.kt:899/900/1051/1128/1317`；`find app/src/main/res -type d -name '*sw600*'` 0 命中 | read + find |
| E7 | `bigScreenMode` 是内存态、不落盘、无平板自动开启 | `MainActivity.kt:169`；`grep` 无 `getBoolean/putBoolean` 命中 | read + grep |
| E8 | 自动进大屏需 auto-rotate + 播放器展开 + 窗口横屏 | `BigScreenOrientation.kt:120-125`；`MainActivity.kt:702-723`、`:913-920`；`RotationSetting.kt:38-42`（默认 true） | read 全文 |
| E9 | 平板两个方向都没有 ⤢ 入口 | `FullPlayerControls.kt:148`（`if (landscape)`）/`:306`（竖屏 Column）/`:402`（操作行）/`:457-473`（⤢ 入口）；`PlayerCard.kt:195` + `:727-729`（`landscape = usesSideCover`） | read + `grep -rn "OpenInFull\|onToggleBigScreen"` |
| E10 | 大屏下退出按钮只在 `bigScreenActive` 时出现（可用于反证"当前不在大屏"） | `PlayerCard.kt:746-765` | read |
| E11 | 大屏把控制条画在右栏、宽屏两栏画在左栏（截图的判别依据） | `PlayerCard.kt:1047`（右栏 `playerControls()`）vs `:1101-1110`（左栏） | read |
| E12 | 平板横屏高度 768dp ⇒ 可视化高度 clamp 到 56dp（不可能为 0） | `PlayerCard.kt:223-224`；平板 `h768dp` | read + dumpsys |
| E13 | 既存截图（横屏）显示的是**宽屏两栏**分支、且不在大屏模式 | `docs/verification/v2.5.4/verification/wgr-landscape-player-BEFORE-v2.5.2.png`（2560×1600）：控制条在**左栏**歌名之下、无 `CloseFullscreen`、状态栏可见 | read_image（既有产物，非本轮生成） |
| E14 | 既存截图（竖屏）同样是宽屏两栏 + 横向控制条（⇒ 平板竖屏也没有 ⤢ 入口） | `docs/verification/v2.5.4/verification/wgr-portrait-player-BEFORE-v2.5.2.png`（1600×2560） | read_image |
| E15 | 沉浸式判据与 `bigScreenActive` 同源（状态栏可见 ⇒ 不在大屏，华为例外见 U4） | `MainActivity.kt:255-260`（注释）、`:772-782` | read |
| E16 | `audio_visualizer` 默认开 | `AudioVisualizer.kt:38/40`；设置页 `UserScreen.kt:153/474-482` | read |
| E17 | `WaveformStore.enabled=false` 会把音频线程的数据整条丢掉 | `AudioVisualizer.kt:110-111`、`:118-121`；写入口 `:51/:64` | read 全文 |
| E18 | 没有数据时柱高只剩 `1.dp`（"看起来没显示"） | `AudioVisualizer.kt:228`、`:232`、`:238-239` | read 全文 |
| E19 | 帧间隔只按"设备档位"（API<26 或 low-ram）降级到 33ms，不影响可见性 | `AudioVisualizer.kt:141-166` | read 全文 |
| E20 | PCM 恒经过 tee；`enabled` 不 gate 采集 | `VisualizerRenderersFactory.kt:55-77`；`TransparentWaveformSink.kt:81-89`；`PlaybackService.kt:378-380/394` | read 全文 |
| E21 | 未知编码/RMS 异常都只导致"数据为 0"，不会崩 | `TransparentWaveformSink.kt:82-83`、`:86-87`、`:106-110`、`:112-138` | read 全文 |
| E22 | offload/tunneling 关闭（by design），不是数据断流原因 | `PlaybackService.kt:293-306`、`:307-328`；`grep -rn "setEnableAudioOffload\|AudioOffload"` 无实现命中 | read + grep |
| E23 | 旋转不重建 Activity（`configChanges` 含 orientation） | `AndroidManifest.xml:49-50` | grep（只读查看 Manifest） |
| E24 | 挂载条件没有纯逻辑可测点，也没有任何单测 | `PlayerCard.kt:952`（判断在 `@Composable` 内）；`grep -rn "Visualizer\|WaveformStore" app/src/test/` 仅 i18n 快照 | grep |
| E25 | 平板实测参数（API 31 / 2560×1600 / 320dpi / 已装 2.5.2-gpl vc43） | `adb -s WVQ6R22124000968 shell getprop ro.build.version.sdk`(=31)、`wm size`、`wm density`(=320)、`dumpsys package`(=2.5.2-gpl/43) | adb 只读 |
| E26 | 平板系统层自动旋转**被锁**（`accelerometer_rotation=0`、`user_rotation=1`） | `adb shell settings get system accelerometer_rotation`(=0)、`user_rotation`(=1)、`global display_size_forced`(=null) | adb 只读 |
| E27 | 当前 logcat 缓冲区没有 `NcrustBigScreen` 记录（无法据此证明平板历史行为） | `adb -s … logcat -d -s NcrustBigScreen:V` → 空 | adb 只读 |

---

## 4. 修复设计（**未施工，仅设计**）

### 4.1 最小改动：把"挂不挂"从 `@Composable` 里提出来，并让宽屏两栏也挂

**目标**：平板横屏（以及平板竖屏）在**不进大屏模式**时也能看到波浪条；
手机竖屏完全不变；大屏分支行为逐字节不变。

**第一步（纯逻辑，可单测）** —— 在 `PlayerLayout.kt` 增补两个纯函数（**不改任何现有函数**）：

```kotlin
// 设计草案（PlayerLayout.kt）
enum class VisualizerSlot { NONE, BIG_SCREEN_LEFT, WIDE_LEFT }

/** 与 MainActivity.applyOrientationPolicy 的 isLargeScreen 同一判据（MainActivity.kt:528）。 */
fun isLargeScreen(windowSmallestWidthDp: Int): Boolean =
    windowSmallestWidthDp >= WIDE_BREAKPOINT_DP

fun visualizerSlot(
    enabled: Boolean,
    bigScreenActive: Boolean,
    isWidePlayer: Boolean,
    isLargeScreen: Boolean,
): VisualizerSlot = when {
    !enabled -> VisualizerSlot.NONE                       // 开关关掉 = 不挂载（连帧时钟都不跑）
    bigScreenActive -> VisualizerSlot.BIG_SCREEN_LEFT      // 既有行为，一行不改
    isWidePlayer && isLargeScreen -> VisualizerSlot.WIDE_LEFT  // ← 本次新增
    else -> VisualizerSlot.NONE                            // 手机竖屏：维持"只在大屏出现"
}

/** PlayerCard.kt:223-224 的算式原样搬出来（32..56dp 夹取 + 窗口高 11%）。 */
fun visualizerHeightDp(screenHeightDp: Float): Float =
    (screenHeightDp * 0.11f).coerceIn(32f, 56f)
```

**第二步（`PlayerCard.kt`）** —— 保留 `bigScreenActive` 分支的挂载点不变，
在宽屏两栏分支的**左栏、封面区与控制条之间**（即 `:1073` 的封面 `Box` 之后、`:1075` 的歌名 `Box` 之前）
插入同一段挂载，抽成一个私有 `@Composable fun VisualizerSlot(...)`，让"挂载点"重新变成**一个**
（两份内联代码必然漂移，这是本仓库 AGENTS.md 反复强调的教训）。

**为什么放在"封面下、歌名上"**：与大屏分支的既有位置（`PlayerCard.kt:944-946` 注释：
「音频可视化条（**封面下、歌名/作者上**）」）一致；且宽屏两栏左栏的封面 `Box` 是 `weight(1f)`
（`:1063-1073`），固定高度的条会被它自动吸收，**不会溢出**，与 `:947-948` 的既有理由相同。

### 4.2 该不该挂到 `isWidePlayer`（两栏）布局？—— **该挂，但要加设备档位闸门**

* **该挂的理由**：平板的"横屏播放器布局"**就是**宽屏两栏（没有第二套平板布局，§2.3 E6）；
  可视化是"大屏/宽屏"特性，只有左栏有 0.44×1280dp ≈ **563dp** 宽 × 768dp 高，28 根柱完全放得下；
  不挂就等于**在平板上不可达**——这正是报障。
* **闸门怎么选（两个方案，取一）**：

  | 方案 | 谓词 | 手机横屏 | 平板竖屏 | 平板横屏 | 代价 |
  |---|---|---|---|---|---|
  | **A（推荐）** | `isWidePlayer && isLargeScreen`（`smallestScreenWidthDp >= 600`） | **不变**（sw360 < 600） | ✅ 显示 | ✅ 显示 | 平板竖屏也显示（与"只在大屏出现"的旧文案不符，需改文案 §4.6） |
  | B（更大面） | 仅 `isWidePlayer` | 也显示（PCL110 横屏 363dp 高里再占 ~40dp ⇒ 封面区少 40dp） | ✅ | ✅ | 手机横屏封面变小，需要 PCL110/S6 真机 A/B 才能判定可接受 |

  推荐 **A**：`smallestScreenWidthDp >= 600` 在本仓库就是"平板/折叠展开/车机"的既有定义
  （`MainActivity.kt:528`、`applyOrientationPolicy` 的 KDoc `:511-516`），用它做闸门可以得到
  **「手机四个方向的行为逐字节不变」**这个最强的非回归保证；而它恰好也满足用户诉求（平板横屏要看到）。
* **若产品要求"平板竖屏也不显示"**：把谓词收紧成
  `isWidePlayer && isLargeScreen && orientationLandscape` —— 但**不推荐**：
  竖屏左栏 800dp 宽、1280dp 高，空间比横屏更充裕，做成"只有横屏有"反而更难解释。

### 4.3 平板上的高度：沿用现有动态算式，不要新增常量

现有算式（`PlayerCard.kt:223-224`，原文见 §2.2）：

```kotlin
    val visualizerHeightDp =
        (LocalConfiguration.current.screenHeightDp.dp * 0.11f).coerceIn(32.dp, 56.dp)
```

代入实测窗口高：平板横屏 `768dp × 0.11 = 84.5dp → clamp 56dp`；平板竖屏 `1280 × 0.11 = 140.8 → 56dp`；
手机横屏（PCL110）`363 × 0.11 ≈ 39.9dp`；极小窗口（`240dp`）`→ 32dp`。
⇒ **平板横屏本来就该拿 56dp**，所以"高度为 0"不是本 bug 的成分（§0.2 #5），
修复时**只需把这段算式搬进纯函数**（§4.1 的 `visualizerHeightDp`），行为不变、可单测。

### 4.4 第二处必修：平板上没有 ⤢ 入口（否则大屏分支永远进不去）

即使按 §4.1 修好了宽屏两栏，**"大屏幕模式"在平板上仍然不可达**：`FullPlayerControls.kt:457-473` 的 ⤢
只在 `landscape == false` 那一套里，而平板恒为 `landscape = true`（`PlayerCard.kt:195/728`）。
两种修法（建议都做，或至少做第一种）：

1. **把 ⤢ 入口补进横向控制条**（`FullPlayerControls.kt:148-304` 的右组，
   即 `PlayerCard.kt:746-765` 的 `trailing` 槽位旁）：`if (bigScreen) 退出 else 入口`，
   这与 `PlayerCard.kt:746-765` 的既有退出按钮语义天然对齐（`trailing` 现在是 `if (bigScreenActive) {…} else null`，
   把它改成 `else` 也渲染入口即可，无需新增参数）。
2. 把"是否显示 ⤢ 入口"从"控制条变体"里解耦：入口的显示条件应该是
   "这个形态下存在大屏模式这个去处"（= `orientationLandscape || isLargeScreen`），而不是 `!landscape`。
   这条同时修掉**手机横屏也没有 ⤢ 入口**（§1.2）这个既有小坑。

### 4.5 异常隔离与性能（必须原样保持的不变量）

* **不要动音频线程侧**：`TransparentWaveformSink.handleBuffer` 已经是
  `runCatching { … }.getOrDefault(0.0)` + 未知编码早退（`TransparentWaveformSink.kt:82-87`），
  RMS 数学是纯函数（`:112-138`）。**新增挂载点不改变这条链**，可视化失败最多是"平的"，
  永远不会把异常抛回播放线程（v2.2.1 P0 的教训，见 `TransparentWaveformSink.kt:1-43`）。
* **不要在组合期读 `WaveformStore`**：`generation` 只能在 `Canvas` 的 draw lambda 里读
  （`AudioVisualizer.kt:223-226`），否则 30Hz 数据会把整张播放器卡片变成 30Hz 重组——
  与本仓库"GPU 零重组"原则冲突。
* **保持"开关关掉 = 整块不挂载"**：`visualizerSlot` 的第一个分支就是它（`AudioVisualizer.kt:32-33`
  的既有契约：关掉「连帧时钟都不跑」）。
* **不会出现双份帧循环**：`bigScreenActive` 与 `isWidePlayer && isLargeScreen` 是两个互斥分支
  （同一个 `when` 的不同分支，`PlayerCard.kt:899/1051`），`enum VisualizerSlot` 保证只会返回一个槽位；
  这一点要在 KDoc 里写明，防止以后被"顺手"改成两处都渲染。
* **帧循环仍是"按需"的**：暂停/缓冲走 `delay`（`AudioVisualizer.kt:213-219`），
  数据归零且平滑收敛后 `pump` 返回 false 不再失效（`WaveformRing.kt:92-93/103`），
  所以平板待机时不会多烧 GPU。新增的条只在**有数据时**重绘 28 次 `drawRect`。
* **不新增命中区**：`AudioVisualizerBars` 只有一个 `Canvas`（`AudioVisualizer.kt:223`），
  **没有 `pointerInput`/`clickable`**，因此不会重演 AGENTS.md 第 1/5 条那类"看不见的死带"问题。
* **验证方式（建议随修复一起做）**：修复前后各抓一次
  `adb shell dumpsys gfxinfo com.takahashirinta.ncrust framestats`（平板横屏、播放中、宽屏两栏态），
  对比 frame time 分布；并复用 `benchmark/` 的 HOT-start expand 流程（`benchmark/ExpandPlayerBenchmark`）。

### 4.6 文案与文档连带（容易被漏掉，且有测试会红）

* 设置项描述现在明确写着"只在大屏幕模式"：
  `zh_CN.kt:50` `audioVisualizerDescription = "在大屏幕模式左栏显示随音乐起伏的波形条；关闭后不产生任何开销"`、
  `en.kt:50` `"Show a waveform under the cover in big-screen mode; turning it off costs nothing"`。
  方案 A 之后应改成"大屏/宽屏播放器左栏"之类；**8 个语言文件都要改**（`zh_CN/zh_TW/en/jp_JP/jp_MY/ko_NK/de_DE/ru_RU`）。
* ⚠️ **改文案会让既有单测变红**：`StringsMigrationTest.kt:143-170`
  （`v2_5_2 的全部路径逐值不变（搬家 ≠ 改文案）`）逐路径比对
  `app/src/test/resources/i18n/strings-snapshot-v2.5.2.json`，`expected != actual` 就会 assert 失败。
  改文案必须同步更新该 golden（或按仓库纪律另开一条记录），**不要**在修 bug 的提交里悄悄改。
* KDoc 也要改：`AudioVisualizer.kt:32-33` 写着「可视化只出现在横屏大屏模式，不影响竖屏日常使用」；
  `PlayerCard.kt:944-948` 的注释写的是大屏左栏的位置说明（新增的宽屏挂载点需要自己的注释说明为什么也在"封面下、歌名上"）。
* **不要**改 `PlayerLayout.WIDE_BREAKPOINT_DP`（`:22-23` 明确"与全仓库其余 6 处保持一致，不要改"），
  也不要把可视化塞进那 6 处 `screenWidthDp >= 600` 的通用宽屏判定里 —— 新谓词只给播放器读。

### 4.7 修复后的回归矩阵（预期）

| 形态 | 修复前 | 修复后（方案 A） | 非回归判定 |
|---|---|---|---|
| 手机竖屏 | ❌（设计如此） | ❌（不变） | 逐像素不变：`else` 分支未动 |
| 手机横屏 | 仅"自动进大屏"时 ✅ | 不变（`sw360 < 600` ⇒ 仍只在大屏时显示） | 无变化；**但建议同时做 §4.4.2**（补 ⤢ 入口） |
| 平板竖屏 | ❌ | ✅ 宽屏两栏左栏、56dp | 新增；封面区 `weight(1f)` 自动让位 |
| 平板横屏 | ❌（报障） | ✅ 宽屏两栏左栏、56dp | 新增；**核心验收项** |
| 大屏模式（三形态） | ✅ | ✅（代码未动） | 大屏分支一行不改 |
| 设置开关关闭 | ❌ | ❌（`visualizerSlot` 首分支） | 保持不变：整块不挂载、零开销 |

---

## 5. 待写的纯逻辑单测（挂载谓词）

建议落在 `app/src/test/java/com/takahashirinta/ncrust/ui/player/PlayerLayoutTest.kt`（追加）
或新建 `VisualizerSlotTest.kt`；数值全部取本报告里的真机 dp。

**挂载谓词 `visualizerSlot(...)`**

1. `开关关掉时任何形态都不挂载` —— `(enabled=false, bigScreenActive=true, isWidePlayer=true, isLargeScreen=true) → NONE`（契约：关掉 = 零开销）。
2. `大屏分支优先于宽屏分支` —— `(true, true, true, true) → BIG_SCREEN_LEFT`（保证大屏行为不变）。
3. `平板横屏（不进大屏）必须挂载` —— `(true, false, isWidePlayer=true, isLargeScreen=true) → WIDE_LEFT`（**本 bug 的回归钉**）。
4. `平板竖屏同样挂载` —— `(true, false, true, true) → WIDE_LEFT`。
5. `手机竖屏绝不挂载` —— `(true, false, isWidePlayer=false, isLargeScreen=false) → NONE`（窄屏"只在大屏出现"不变）。
6. `手机横屏在方案 A 下不变` —— `(true, false, isWidePlayer=true, isLargeScreen=false) → NONE`
   （若最终选方案 B，则此例改成断言 `WIDE_LEFT`，并把差异写进用例名/注释）。
7. `大屏模式关掉但仍在横屏的平板（requested=false）依旧挂载` —— 同 3，但显式带上"plan 1.2 的失败态"注释
   （把 `requested=false` 这个真实失败条件钉在测试里）。
8. `isLargeScreen 断点=600` —— `isLargeScreen(599)=false`、`isLargeScreen(600)=true`、`isLargeScreen(800)=true`。

**高度 `visualizerHeightDp(screenHeightDp)`**

9. `平板横屏 768dp 夹到 56dp`（WGR-W09 实测窗口高）。
10. `平板竖屏 1280dp 夹到 56dp`。
11. `手机横屏 363dp 得约 39.9dp`（PCL110 基线，容差 0.5dp）。
12. `矮窗口下限 32dp` —— `240dp → 32dp`、`0dp → 32dp`（防"高度算成 0"）。
13. `大屏与宽屏两栏共用同一算式` —— 同一窗口高下两处取值相等（防止以后各写一份常量）。

**跨文件一致性（可选，但很有价值）**

14. `可视化挂载谓词不依赖宽屏断点常量以外的东西` —— 断言 `PlayerLayout.WIDE_BREAKPOINT_DP == 600`
    且 `visualizerSlot` 内部用的是它（把"不要改断点"这条纪律变成红灯）。

> 纯 JVM 可达性说明：以上全部只依赖 `PlayerLayout` 里的纯函数与 `Int/Float/Boolean`，
> 不需要 `ComposeTestRule`/Robolectric —— 与本仓库既有 `PlayerLayoutTest` / `WaveformRingTest` 同一模式。

---

## 6. 未确认 / 不确定

**U1. 平板上的两个 prefs 实际取值读不到。**
`ncrust_settings/auto_rotate` 与 `ncrust_settings/audio_visualizer` 决定"是否自动进大屏"与"是否挂载"，
但 WGR-W09 **无 root**（生产 ROM：`su` 不存在、`adb root` 被拒 —— 见
`docs/verification/v2.1.6/p1-huawei-whitelist/whitelist-criterion.md` §0），
且机上装的是 **release 包**（实测 `versionName=2.5.2-gpl`、`versionCode=43`，不是本工作区 2.5.4），
`run-as` 同样读不到 `/data/data/…/shared_prefs/*.xml`。
⇒ 需要真机 A/B：设置页看两个开关，再按 §1.1/§1.2 两种状态各复现一次。

**U2. 用户报障时到底在不在大屏模式，没有当时的截图/log。**
本次 logcat 缓冲区里 **0 条** `NcrustBigScreen`（E27），所以不能证明"当时 requested=false"。
现有两张 `BEFORE-v2.5.2` 截图（E13/E14）能证明**那些截图拍摄时**不在大屏（控制条在左栏 + 无退出按钮 + 状态栏可见），
但它们是**既有产物**（命名指向 v2.5.2 之前），不是针对本 bug 拍的，且时间点未知。
⇒ 复现时请同时给出：截图 + `adb shell dumpsys window displays | grep -E "overrideConfig|mCurrentFocus"`。

**U3. "数据侧是平的"这条无法在真机上观测。**
音频线程路径**刻意没有日志**（`VisualizerRenderersFactory.kt:39-43`），
`WaveformStore` 也没有任何计数器。
如果修复后平板仍看不到条，必须在"已进入宽屏两栏 + 开关开 + 正在播放"的前提下再区分
"没挂载" vs "挂载了但 RMS 恒 0"。
⇒ 建议（仅调试包）：给 `TransparentWaveformSink.handleBuffer` 加一个
**只在 `BuildConfig.DEBUG` 下自增的 volatile 计数器**（不做分配、不打日志），
由设置页或 `dumpsys` 侧读；**不要**在音频线程打日志。

**U4. 截图里的"状态栏可见"只是代理判据，华为 ROM 上可能失准。**
`ImmersiveEffect`（`MainActivity.kt:772-782`）是否真能隐藏状态栏取决于 ROM；
华为/HarmonyOS 上曾出现过"从最近任务回来窗口标志被重置"的注释（`MainActivity.kt:438-441`）。
⇒ 判"是否在大屏模式"应优先用**控件条位置**（左栏 vs 右栏，E11）或
`dumpsys window` 的 `requestedOrientation`，而不是只看状态栏。

**U5. 平板当前处于"系统层锁横屏"状态（E26：`accelerometer_rotation=0`、`user_rotation=1`）。**
这说明**用户本人可能不喜欢自动旋转**，因而更可能也把应用内 `auto_rotate` 关掉 ——
但这只是相关性推断，缺少 U1 的证据。若成立，则 §0.1 的主因就是**唯一**路径。

**U6. `wm size` 的 "Override size: 1600x2560" 与 `display_size_forced=null` 并存。**
我按 `dumpsys window displays` 的 `overrideConfig`（`sw800dp w1280dp h768dp`）做 dp 换算；
若后续有人真的执行了 `wm size`/`wm density` 覆盖，本报告里的 dp 数值需要重算。
（本轮**没有**做任何此类改动。）

**U7. 修复方案 A 的真机表现未验证**（本轮只读，未施工、未装机）：
平板竖屏 56dp 条 + 左栏封面区在同一屏里的实际观感、`wideSplit` 动画过程中条的位置、
以及"平板竖屏也显示"是否符合产品预期，都需要真机确认。

**U8. 性能数字未采集**：新增挂载点对 `dumpsys gfxinfo` / 宏基准的影响没有实测
（`benchmark/` 只有启动/滚动/展开三条，且面向手机 HOT start）。

**U9. 与本任务无关但顺带发现的事实**：机上装的是 **v2.5.2-gpl（vc43）**，而本工作区是 2.5.4 ——
任何"平板上复现"的结论都必须在**升到同一版本**之后才成立（v2.2.1 的可视化 tee 修复在这两版之间，
虽然它影响的是"有没有声音"而不是"有没有条"）。
