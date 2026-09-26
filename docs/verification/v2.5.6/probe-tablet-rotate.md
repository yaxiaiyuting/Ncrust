# 平板大屏模式（⤢）在 EMUI 上不生效 —— 真机探针报告（v2.5.6）

> **设备**：`WVQ6R22124000968` = HUAWEI WGR-W09（MatePad Pro）· EMUI `EmotionUI_14.2.0` · Android 12 / API 31 · `ro.build.characteristics=tablet` · **真机，非模拟器**
> **被测包**：`com.takahashirinta.ncrust` `versionName=2.5.5-gpl` `versionCode=46` `targetSdk=36` `minSdk=24`（即 v2.5.5 release，`91a6321` 已包含）
> **仓库基线**：`git log` HEAD = `fef5e7b`（v2.5.5 收尾文档提交）
> **纪律**：本次探针**未修改任何源码**、未 commit、未改动应用内任何设置项；设备侧仅临时改过 `user_rotation`，已还原（§1.2 / §1.7）。
> **范围**：不涉及华为控制中心媒体卡片。

---

## 0. 结论先行

### 0.1 一句话结论

**任务书的两条前提一条被推翻、一条现象成立但根因不同：**

| 任务书前提 | 探针实测 | 判定 |
|---|---|---|
| 「EMUI 不采纳窗口管理器旋转 override ⇒ 点 ⤢ 没反应」 | **系统采纳方向请求**。受控 A/B：应用持 `SENSOR_LANDSCAPE` 时把 `user_rotation` 强制成 0（竖屏），显示**10 秒以上保持横屏**；退出大屏释放请求后同一 `user_rotation=0` 下显示**立刻变竖屏** | **推翻**（“系统不采纳”不成立） |
| 同上 | **「点 ⤢ 没反应」在*平板竖屏*下确实成立，但原因在应用内布局**：⤢ 图标被画出来了，却**没有命中区**——它的布局槽位被居中的传输组（上一首/播放）**完全盖住**，点那儿实际触发的是播放/暂停 | **现象成立、根因改写** |
| （隐含）「走的是 WindowManager rotation override」 | 应用**从未**使用 `WindowManager.LayoutParams.rotation`；用的是 `Activity.requestedOrientation = SCREEN_ORIENTATION_SENSOR_LANDSCAPE`（`MainActivity.kt:556-563`）。全仓库 `WindowManager` 只用于 `FLAG_KEEP_SCREEN_ON`（`MainActivity.kt:526-529`） | **推翻用词** |

**平板横屏下 ⤢ 是好的**：真机点击后进入大屏布局（侧栏消失、左栏大封面+可视化、右栏歌词、状态栏隐藏），日志 `NcrustBigScreen: big screen: manual enter, holding landscape (auto-rotate off)`，`dumpsys` 里 `mOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，截图见 `verification/02-after-bigscreen-tap.png`。

### 0.2 ⤢ 今天到底调用什么

```
⤢ 点击
 └─ FullPlayerControls.kt:246-263  Box{ .size(40.dp).clickable { onToggleBigScreen() } }
     └─ PlayerCard.kt:771  onToggleBigScreen = onToggleBigScreen
         └─ MainActivity.kt:361-362  if (bigScreenMode.value) exitBigScreenMode() else enterBigScreenMode()
             └─ MainActivity.kt:581-604  enterBigScreenMode()
                 ├─ bigScreenMode.value = true            // 只是「用户意图」
                 └─ applyOrientationPolicy()              // MainActivity.kt:546-565
                     └─ requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        该分支来自 BigScreenOrientation.kt:93-105 orientationFor(...)
             ↓ 窗口真的转过去后
             PlayerCard.kt:197-203  bigScreenActive = PlayerLayout.isBigScreenActive(requested, orientationLandscape)
             PlayerLayout.kt:150-151  = requested && orientationLandscape
                 ↓ bigScreenActive = true
                 PlayerCard.kt:941  bigScreenActive -> { ... 横屏桌面播放器布局 ... }
```

即：**⤢ = ①请系统转屏 + ②窗口确实横过来之后才切应用内布局**。第②条是硬门槛（`requested && orientationLandscape`），所以一旦转屏没发生，布局一定不变——这正是任务书把根因猜成「系统不转屏」的原因。本探针证明真正的断点在**第①步之前的按钮命中测试**上。

### 0.3 为什么手机能进、平板不能（根因一句话）

| 形态 | 控件条变体 | ⤢ 在哪 | 结果 |
|---|---|---|---|
| 手机竖屏 | **竖屏变体**（`landscape=false`，`FullPlayerControls.kt:437` `SpaceEvenly` 一行） | 第 4 个（`:490-506`） | ✅ 可点 |
| 手机横屏 | 横屏变体（左栏 0.44×800dp=352dp） | **不挂载**（`bigScreenEntrySlot(isLargeScreen=false)`） | 需先转回竖屏 |
| **平板横屏** | 横屏变体（左栏 0.44×1280dp=**563dp**） | 左组第 4 个（`:246-263`） | ✅ 可点（真机已验证闭环） |
| **平板竖屏** | 横屏变体（左栏 0.44×800dp=**352dp**） | 左组第 4 个 | ❌ **图标画出来但无命中区**：左组 8~168dp 与居中传输组 103~249dp 重叠，⤢ 的 128~168dp 被 `上一首`(103~149dp)+`播放`(149~203dp) **100% 覆盖** |

平板**两个方向**都走同一条横屏控件条（`PlayerCard.kt:206` `usesSideCover = isWidePlayer || bigScreenActive`，而平板 `screenWidthDp` 恒 ≥600）。v2.5.5 · `91a6321` 把 ⤢ 加进这条控件条的**左组**后：横屏放得下 ⇒ 修好了；竖屏放不下 ⇒ **半修**（看得见、点不到）。

### 0.4 下一步建议（一句话）

不要再研究系统旋转（已验证系统是听话的）。修 `FullPlayerControls.kt:188-263` 的三段式 `Box` 重叠：**让左组与居中传输组不可能重叠**（或把 ⤢ 移到 `trailing`／给 `bigScreenEntrySlot` 加「左栏宽度够」的门控）。细节见 §8。

---

## 1. 探针方法与原始证据

### 1.1 设备与环境确认（真机）

```bash
$ adb -s WVQ6R22124000968 shell getprop | grep -iE "emui|huawei|ro.build.version|characteristics"
[ro.build.characteristics]: [tablet]
[ro.build.description]: [WGR-W09-user 104.2.0 HUAWEIWGR-W09 213-CHN-LGRP3 release-keys]
[ro.build.hw_emui_api_level]: [31]
[ro.build.version.emui]: [EmotionUI_14.2.0]
[ro.build.version.release]: [12]
[ro.build.version.sdk]: [31]
[ro.config.marketing_name]: [HUAWEI MatePad Pro]
[ro.product.model]: [WGR-W09]
[ro.product.cpu.abi]: [arm64-v8a]

$ adb -s WVQ6R22124000968 shell dumpsys package com.takahashirinta.ncrust | grep -i versionName
    versionCode=46 minSdk=24 targetSdk=36
    versionName=2.5.5-gpl
```

### 1.2 设备基线（探针开始前 / 结束后的回读）

`raw/00-baseline.txt`（开始前）与 `raw/80-restore-final.txt`（结束后）逐项一致：

```
### BASELINE captured 2026-09-26T19:29:20+08:00 ###
--- wm size ---            Physical size: 2560x1600 / Override size: 1600x2560
--- wm density ---         Physical density: 320
--- accelerometer_rotation --- 0
--- user_rotation ---      1
--- overrideConfig ---     ldltr sw800dp w1280dp h768dp 320dpi xlrg hdr land ... mRotation=ROTATION_90
--- Display: ---           init=2560x1600 320dpi base=1600x2560 320dpi cur=2560x1600 app=2560x1600
--- mCurrentFocus ---      com.takahashirinta.ncrust/.MainActivity
```

* **`sw800dp`**：与方向无关的「平板」判据（`smallestScreenWidthDp`）；竖屏 `w800dp h1248dp`、横屏 `w1280dp h768dp`（320dpi ⇒ 1dp = 2px）。⇒ `isLargeScreen = sw800dp >= 600` **恒真**，`isWidePlayer = screenWidthDp >= 600` **两个方向都真**。
* **基线不是「用户原状」**：`user_rotation=1`（锁横屏）与 `wm size` 的 `Override 1600x2560` 都是**前几轮探针的遗留**——v2.5.5 自己的 `EVIDENCE.md:284` 写着「WGR-W09 user_rotation = 1 ← 与验证前一致（v2.5.4 遗留值，本版未改）」，`EVIDENCE.md:274` 记录了那次 `wm size`/`wm user-rotation` 尝试。本探针**未新增改动**，并把 `user_rotation` 还原成遗留值 `1`（§1.7）。
* `settings get global display_size_forced` = `null`，`display_density_forced` = `null`（`raw/70-release-ab.txt`）：即 `wm size` 报的 override 不落在这两个 setting 上，属 EMUI 自身实现；**不是本探针设置的，原样保留**。

### 1.3 关键系统策略查询（只读）

`raw/30-orientation-policy.txt`：

```bash
$ adb -s WVQ6R22124000968 shell wm get-ignore-orientation-request
ignoreOrientationRequest false for displayId=0          # ← 本 display 不忽略应用的方向请求
$ adb -s WVQ6R22124000968 shell wm user-rotation
lock 1
$ adb -s WVQ6R22124000968 shell wm fixed-to-user-rotation
default
```

`ignoreOrientationRequest=false` 是**关键否定证据**：系统层没有开启「忽略应用方向请求」，与 `§1.6` 的 A/B 结果一致。

### 1.4 关键实验 A：**平板横屏**点 ⤢ → 成功（闭环）

`raw/60-landscape-controls.xml`（横屏 2560×1600，控件条命中节点）：

```
'歌词'       [43,1502][91,1550]
'队列'       [123,1502][171,1550]
'加入库'     [203,1502][251,1550]
'大屏幕模式' [283,1502][331,1550]     ← 节点存在
'上一首'     [437,1498][493,1554]
'暂停'       [527,1490][599,1562]
'下一首'     [633,1498][689,1554]
'音质偏好'   [979,1508][1099,1545]
```

```bash
$ adb -s WVQ6R22124000968 logcat -c
$ adb -s WVQ6R22124000968 shell input tap 307 1526        # ⤢ 节点中心
$ adb -s WVQ6R22124000968 logcat -d | grep NcrustBigScreen
09-26 19:35:57.595  I NcrustBigScreen: big screen: manual enter, holding landscape (auto-rotate off)
09-26 19:35:57.596  I WindowManager: ... currentAppOrientation=6, keyguardDrawComplete=true, ...
09-26 19:35:57.649  I NcrustBigScreen: immersive: status bar hidden

$ adb -s WVQ6R22124000968 shell dumpsys activity activities | grep -A40 "mActivityComponent=com.takahashirinta.ncrust" | grep mOrientation
          mOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE
```

`currentAppOrientation=6` = `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`（`ActivityInfo` 常量值 6），即**系统已受理该请求**。
`verification/02-after-bigscreen-tap.png`（2560×1600）：侧栏消失、左栏大封面 + 绿色可视化条 + 歌名/音质 chip/旋转开关、右栏歌词与进度条、状态栏已隐藏 ⇒ **`bigScreenActive ->` 分支（`PlayerCard.kt:941`）确实生效**。

### 1.5 关键实验 B：**平板竖屏**点 ⤢ → 无反应（命中区被覆盖）

把显示切成竖屏（`wm user-rotation lock 0`）后复测。竖屏窗口 `w800dp h1248dp`、`cur=1600x2560`、截图 1600×2560（`verification/07-portrait-player-expanded.png`）。

`raw/52-ui-now.xml`（竖屏、播放器展开、同一条控件条）：

```
clickable [16,2438][96,2534]    icon '歌词'   [40,2462][88,2510]
clickable [96,2438][176,2534]   icon '队列'   [120,2462][168,2510]
clickable [176,2438][206,2534]  icon '加入库' [200,2462][206,2510]   ← 被裁到 30px 宽
clickable [206,2438][298,2534]  icon '上一首' [226,2458][282,2514]
clickable [298,2432][406,2540]  icon '播放'   [316,2450][388,2522]
clickable [402,2438][498,2534]  icon '下一首' [422,2458][478,2514]
clickable [560,2439][680,2535]  icon '音质偏好' / '超清母带'
                     ← 没有 '大屏幕模式' 节点
```

**但图标确实画出来了。** 把截图按 ×5 放大（`verification/10-row-icons-x5.png`，裁剪 `x150..520, y2420..2560`）可逐一辨认，从左到右：

| 视觉图标 | 实际像素 x | 对应 a11y 节点 |
|---|---|---|
| ✓ 加入库 | ≈226 | `[176,206]`（裁切） |
| ⏮ 上一首（`|◀`） | ≈258 | `[206,298]` |
| **⤢ OpenInFull（两支斜箭头）** | **≈304** | **无节点** |
| ⏸ 暂停（两竖条） | ≈352 | `[298,406]` |
| ⏭ 下一首（`▶|`） | ≈448 | `[402,498]` |

**点击验证**（`raw/55-tap-icons.txt`，y=2465）：

```
tap x=258 -> rot=port  mOrientation=SCREEN_ORIENTATION_UNSPECIFIED  content-desc="暂停"   logs:（无）
tap x=280 -> rot=port  mOrientation=SCREEN_ORIENTATION_UNSPECIFIED  content-desc="暂停"   logs:（无）
tap x=304 -> rot=port  mOrientation=SCREEN_ORIENTATION_UNSPECIFIED  content-desc="播放"   logs:（无） ← 变成播放 ⇒ 命中了「播放/暂停」
tap x=330 -> rot=port  mOrientation=SCREEN_ORIENTATION_UNSPECIFIED  content-desc="暂停"   logs:（无） ← 又变回暂停
```

⇒ **在平板竖屏下，点 ⤢ 的位置实际触发的是播放/暂停**；`bigScreenMode` 从未被置位（无 `NcrustBigScreen: big screen` 日志、`mOrientation` 恒为 `UNSPECIFIED`、`overrideConfig` 恒为 `port`）。用户看到的就是「点了没反应（顶多音乐停了一下）」。

> 附带记录一次方法学陷阱（与 v2.5.5 `EVIDENCE.md:259` 遇到的是同一个）：`input tap` 点在收起态 mini bar 上时，落到了歌曲行/菜单上，弹出了歌曲菜单而非展开播放器。本探针改用「点 mini bar 左侧封面区 `(60,2450)`」成功展开，并每一步都用 `uiautomator dump` 校验状态。

### 1.6 关键实验 C：**系统到底认不认方向请求**（受控 A/B，决定性）

设计：进入大屏后应用持有 `SENSOR_LANDSCAPE`；此时**把 `user_rotation` 强制成 0（竖屏）**。若系统采纳应用请求 ⇒ 显示必须保持横屏；若不采纳 ⇒ 显示必须变竖屏。随后退出大屏释放请求，同一 `user_rotation=0` 下再观察。

`raw/62-decisive-test.txt` + `raw/70-release-ab.txt`：

```
### STEP 3: tap ⤢ (307,1526) ###
NcrustBigScreen: big screen: manual enter, holding landscape (auto-rotate off)
WindowManager: ... currentAppOrientation=6 ...
mOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE

### STEP 4: 强制 user_rotation=0（竖屏）###
[t+2s]  cfg: land | user_rotation=0 | appOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE
[t+4s]  cfg: land | user_rotation=0 | appOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE
[t+6s]  cfg: land | user_rotation=0 | appOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE
[t+8s]  cfg: land | user_rotation=0 | appOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE
[t+10s] cfg: land | user_rotation=0 | appOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE
（截图 verification/11-bigscreen-holds-landscape-vs-userrotation0.png，2560×1600 横屏）

### B: 点「退出大屏幕模式」(2496,1542) 释放请求 ###
NcrustBigScreen: immersive: status bar shown
cfg: port                    ← 同一 user_rotation=0，请求一撤显示立刻回竖屏
appOrientation: mOrientation=SCREEN_ORIENTATION_UNSPECIFIED
user_rotation=0
（截图 verification/12-after-exit-bigscreen.png）
```

**结论**：`request=SENSOR_LANDSCAPE` 与 `userRotation=0` 冲突时，**应用请求赢**；请求撤掉后 `userRotation=0` 立即生效。
⇒ **EMUI 14.2.0 / Android 12 在这台平板上采纳 `Activity.setRequestedOrientation`**，「系统不采纳旋转 override」的前提不成立。

### 1.7 本探针对设备做的改动与还原（逐项回读）

| 项 | 改动 | 还原 | 回读证据 |
|---|---|---|---|
| `user_rotation` | 临时 `wm user-rotation lock 0`（竖屏）多次 | `wm user-rotation lock 1` | `raw/80-restore-final.txt`：`user_rotation=1`、`accelerometer_rotation=0`、`overrideConfig` 横屏 `w1280dp` ⇒ 与 §1.2 基线一致 |
| `wm size` / `wm density` | **未改** | — | 前后均为 `Physical 2560x1600 / Override 1600x2560 / 320` |
| `ignore-orientation-request` | 未改（只读查询） | — | 前后均 `false` |
| 应用内设置（`auto_rotate`、音质偏好等） | **未改**（未点旋转开关、未进设置页；全程日志始终 `auto-rotate off`，说明进程内值未变） | — | `raw/62` 等日志逐字一致 |
| 应用数据（附带影响） | 为定位命中区做过一轮 x 扫描（`raw/51-row-scan.txt`），其中部分点按可能落到 `加入库`／`下一首`：**_当前播放曲目可能被切过、该曲可能被加入收藏_** | 未回滚（属用户数据的正常操作，非破坏性） | `raw/51` / `raw/55-tap-icons.txt` |
| 应用状态 | 进/出大屏若干次 | 已退出大屏；应用留在前台 | `raw/80`：`mCurrentFocus=…ncrust/.MainActivity`、`cfg: land` |
| 源码 / git | **未改、未 commit** | — | 本报告为唯一新增产物 |

> 说明：把 `user_rotation` 从 0 改回 1 后，显示回到横屏；这与探针开始时**完全一致**。另有一次「应用切到后台（华为桌面）后 `user_rotation` 被系统自行改回 1」的观察（`raw/35-state-after-launcher-return.txt`），说明 EMUI 桌面会强制横屏并回写该 setting——这也是 v2.5.4/v2.5.5 遗留值 `1` 的成因。这一点对评估「用户实际拿到的是竖屏还是横屏」很重要，见 §7。

### 1.8 证据文件清单

```
docs/verification/v2.5.6/probe-tablet-rotate.md          ← 本报告
docs/verification/v2.5.6/verification/
  01-baseline-screen.png                          横屏基线（库页 + mini bar）
  02-after-bigscreen-tap.png                      ★横屏点 ⤢ 后：大屏布局生效
  07-portrait-player-expanded.png                 ★竖屏播放器展开态（1600×2560）
  08-portrait-control-row-zoom.png                竖屏控件条 ×2
  10-row-icons-x5.png                             ★竖屏控件条 ×5：⤢ 图标确实被绘制
  11-bigscreen-holds-landscape-vs-userrotation0.png  ★实验 C：请求压制 user_rotation=0
  12-after-exit-bigscreen.png                     实验 C 对照组：撤请求后回竖屏
docs/verification/v2.5.6/raw/
  00-baseline.txt 30-orientation-policy.txt 31-portrait-test-state.txt 35-state-after-launcher-return.txt
  36-userrotation-stability.txt（竖屏 override 稳定性：16s 内 4 次采样均 port）
  10/11/21/32/33/34/37/38/39/40/41/52/53/54-ui-*.xml（uiautomator 层次）
  20-after-tap-logcat.txt 50-portrait-tap-logcat.txt 51-row-scan.txt 55-tap-icons.txt
  60-landscape-controls.xml 61-step12.txt 62-decisive-test.txt 70-release-ab.txt 80-restore-final.txt
```

---

## 2. ⤢ 当前实现（file:line）

### 2.1 入口挂载：两个挂载点、一条判据

| 挂载点 | 位置 | 变体 | 判据 |
|---|---|---|---|
| 竖屏控件条（v1.8.0） | `FullPlayerControls.kt:490-506` | `landscape == false`（`:437` `Arrangement.SpaceEvenly` 一行） | 恒挂（`else` 分支） |
| **平板横向控件条左组（v2.5.5 · E）** | `FullPlayerControls.kt:246-263` | `landscape == true` | `showBigScreenEntry` |

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/ui/player/FullPlayerControls.kt:246-263（v2.5.5 新增）
if (showBigScreenEntry) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable {
                tick()
                onToggleBigScreen()          // ← ⤢ 点击的唯一出口
            },
        contentAlignment = Alignment.Center
    ) {
        MetroIcon(
            imageVector = Icons.Default.OpenInFull,
            contentDescription = strings.bigScreenEnter,   // "大屏幕模式"
            tint = LocalMetroColors.current.onBackground,
            sizeDp = 24.dp
        )
    }
}
```

纯判据（与 UI 解耦、有单测）：

```kotlin
// PlayerLayout.kt:120-121
fun bigScreenEntrySlot(isLargeScreen: Boolean, bigScreenActive: Boolean): Boolean =
    isLargeScreen && !bigScreenActive
```

装配与两个谓词的定义：

```kotlin
// PlayerCard.kt:775-778
showBigScreenEntry = PlayerLayout.bigScreenEntrySlot(
    isLargeScreen = isLargeScreen,          // :238-239  smallestScreenWidthDp >= 600  ← 平板真
    bigScreenActive = bigScreenActive,      // :197-203  见下
),
// PlayerCard.kt:762-763
landscape = usesSideCover,                  // :206  usesSideCover = isWidePlayer || bigScreenActive
compact   = usesSideCover,                  // :192  isWidePlayer = screenWidthDp >= 600  ← 平板两个方向都真
```

⇒ **平板上 `landscape=true` 恒成立** ⇒ 永远走横屏变体 ⇒ v1.8.0 那个 ⤢（`:490-506`）在平板上**结构性不可达**（这正是 `91a6321` 的提交理由），v2.5.5 那个 ⤢（`:246-263`）是平板唯一入口。

### 2.2 点击链路

```kotlin
// MainActivity.kt:360-362
bigScreen = bigScreenMode.value,
onToggleBigScreen = {
    if (bigScreenMode.value) exitBigScreenMode() else enterBigScreenMode()
},
```

`enterBigScreenMode()`（`MainActivity.kt:581-604`）只做两件事：置 `bigScreenMode.value = true`（`:583`），然后 `applyOrientationPolicy()`（`:603`）。**它自己不改布局**。

### 2.3 方向策略与「布局切换」的硬门槛

```kotlin
// MainActivity.kt:546-565
private fun applyOrientationPolicy() {
    autoRotateEnabled = RotationSetting.read(this)
    val desired = BigScreenOrientation.orientationFor(
        autoRotate = autoRotateEnabled,
        bigScreen = bigScreenMode.value,
        bigScreenRelaxed = bigScreenOrientationRelaxed,
        isLargeScreen = resources.configuration.smallestScreenWidthDp >= 600,
    )
    val requested = when (desired) {
        DesiredOrientation.PORTRAIT        -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        DesiredOrientation.SENSOR_LANDSCAPE-> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        DesiredOrientation.SENSOR          -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        DesiredOrientation.UNSPECIFIED     -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    if (requestedOrientation != requested) {
        requestedOrientation = requested        // ← 唯一的方向请求
    }
}

// BigScreenOrientation.kt:98-105
) = when {
    bigScreen && !autoRotate       -> DesiredOrientation.SENSOR_LANDSCAPE   // 保持横屏（v2.0.0 · T1-A）
    bigScreen && !bigScreenRelaxed -> DesiredOrientation.SENSOR_LANDSCAPE   // 进入时先转过去
    bigScreen                      -> DesiredOrientation.SENSOR             // 已放宽
    autoRotate                     -> DesiredOrientation.SENSOR
    isLargeScreen                  -> DesiredOrientation.UNSPECIFIED        // ← 平板不在大屏时的默认
    else                           -> DesiredOrientation.PORTRAIT           // ← 手机锁竖屏
}
```

```kotlin
// PlayerLayout.kt:150-151
fun isBigScreenActive(requested: Boolean, orientationLandscape: Boolean): Boolean =
    requested && orientationLandscape
```

⇒ **平板默认（`isLargeScreen=true`、不在大屏、auto-rotate 关）= `SCREEN_ORIENTATION_UNSPECIFIED`**：不限制方向、**已经允许自由旋转**（`raw/00-baseline.txt` 的 `dumpsys activity` 也确实是 `mOrientation=SCREEN_ORIENTATION_UNSPECIFIED`）。这一点很关键：**平板并不需要 ⤢ 去「解锁」旋转**，它本来就没被锁。

### 2.4 「大屏模式」判据看的是**方向**，不是宽度

* 布局分支：`PlayerCard.kt:941 bigScreenActive ->`（横屏桌面布局）/ `:1085 isWidePlayer ->`（宽屏两栏）/ `:1181 else ->`（窄屏）。
* `bigScreenActive` 只由 `requested && orientationLandscape` 决定（`PlayerLayout.kt:150-151`），**与宽度无关**。
* 反过来，全仓库那 7 处宽屏判定用的是 `screenWidthDp >= 600`（`PlayerLayout.kt:15-23` 的注释），与方向无关——**两套谓词刻意分开**（`PlayerCard.kt:193-196`、`AGENTS.md:1040-1041`）。
* ⇒ 在**平板竖屏**下，即使把 `bigScreenMode` 置为 true，`orientationLandscape=false` ⇒ `bigScreenActive=false` ⇒ **布局不变**。所以 ⤢ 在平板竖屏下**必然依赖系统转屏**才能真正生效；宽度分支（宽屏两栏）本来就已经是平板竖屏的布局，不存在「宽度谓词已满足导致空操作」的问题。

### 2.5 Manifest / 权限

```xml
<!-- app/src/main/AndroidManifest.xml:46-56 -->
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:resizeableActivity="true"
    android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"
    android:theme="@style/Theme.Ncrust">
```

* **没有 `android:screenOrientation`**（`:46-56` 全块内无该属性）⇒ 方向完全由运行时 `setRequestedOrientation` 决定。
* `resizeableActivity="true"` ⇒ 不参与 Android 12 对「不可调整大小」的强制拉伸逻辑。
* `configChanges` 含 `orientation|screenSize|smallestScreenSize` ⇒ **转屏不重建 Activity**，`bigScreenMode` 不丢（这一点被 `raw/62` 的实测证实：转屏后大屏态保持）。
* 权限：全仓库 `grep -rn "WindowManager" app/src/main/java` 只有 `MainActivity.kt:526-529` 的 `FLAG_KEEP_SCREEN_ON`；**没有任何 `WindowManager.LayoutParams.rotation` / `SET_ORIENTATION` / `WRITE_SETTINGS` 使用**。

---

## 3. EMUI 上的实际表现

| 场景 | 真机表现 | 证据 |
|---|---|---|
| 平板**横屏**，点 ⤢ | ✅ 进入大屏：侧栏消失、左栏大封面 + 可视化条 + 歌名/音质 chip/旋转开关、右栏歌词 + 进度 + 控制条、状态栏隐藏 | `verification/02-after-bigscreen-tap.png`、`raw/62-decisive-test.txt` |
| 平板**横屏**，应用的方向请求 | `mOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，`WindowManager` 记 `currentAppOrientation=6` | 同上 |
| 平板**竖屏**，点 ⤢ 图标 | ❌ 无任何反应；同一坐标实际触发**播放/暂停** | `raw/52-ui-now.xml`、`raw/55-tap-icons.txt`、`verification/10-row-icons-x5.png` |
| 平板竖屏，a11y 树 | **不存在** `大屏幕模式` 节点；`加入库` 节点被裁到 30px | `raw/52-ui-now.xml` |
| 系统是否采纳方向请求 | ✅ 采纳：请求 `SENSOR_LANDSCAPE` 时强制 `user_rotation=0` 也**保持横屏 10s+**；撤请求后立刻回竖屏 | `raw/62-decisive-test.txt`、`raw/70-release-ab.txt` |
| 显示层策略 | `ignoreOrientationRequest false for displayId=0` | `raw/30-orientation-policy.txt` |
| 平板默认方向策略 | `SCREEN_ORIENTATION_UNSPECIFIED`（不限方向、本来就允许旋转） | `raw/00-baseline.txt` |

**顺带纠正一条既有文档**：v2.5.5 `EVIDENCE.md:274` 记「`wm size` / `wm user-rotation` 尝试强制竖屏 ❌ EMUI 未采纳（`wm size` 仍显示 override 1600×2560，实窗仍是 2560×1600）」。本探针实测：`wm size 1600x2560` **已被采纳**（`base=1600x2560`），只是当时显示旋转是 90°，所以 `cur=2560x1600` —— 那不是「没采纳」，而是「改了自然尺寸但没改旋转」。用 `wm user-rotation lock 0` 改旋转后**确实能拿到竖屏**（`cur=1600x2560`、`port`、截图 1600×2560），且 16s 内 4 次采样稳定（`raw/36-userrotation-stability.txt`）。注意命令要写成 `wm user-rotation lock 0`；`wm user-rotation 0` 不含 `lock` 关键字，语义不同。

---

## 4. 手机 vs 平板差异的根因

### 4.1 直接根因：横屏控件条的「三段式 `Box`」在窄左栏下重叠

```kotlin
// FullPlayerControls.kt:188-263（横屏变体，平板两个方向都走这一支）
Box(modifier = Modifier.fillMaxWidth()) {                 // ← 三段各自独立对齐，互不知情
    Row(modifier = Modifier.align(Alignment.CenterStart),  // :189-190  左组
        verticalAlignment = Alignment.CenterVertically) {
        … 歌词(40dp) :193-209 · 队列(40dp) :210-227 · 加入库(40dp) :228-241 ·
        if (showBigScreenEntry) { Box(Modifier.size(40.dp)) { … } }   // :246-263  ← ⤢
    }
    Row(modifier = Modifier.align(Alignment.Center),        // :265-266  居中的传输组
        verticalAlignment = Alignment.CenterVertically) {
        … 上一首(44dp) :269-284 · 播放(54dp) :285-… · 下一首(44dp) …
    }
    if (showQuality) { PlayerQualityChip(…, Modifier.align(Alignment.CenterEnd)) }  // :320-331
    else if (trailing != null) { Box(Modifier.align(Alignment.CenterEnd)) { trailing() } }  // :332
}
```

`Box` 的三个子节点各自按 `Alignment` 独立测量/摆放，**没有 `Row`+`weight` 的排他分配**，因此当容器宽度不足时它们会**重叠**，后声明的子节点（居中的传输组）在绘制与命中测试上都压住先声明的左组。

用**实测 a11y 边界本身**代入算术（竖屏左栏 = `wideLeftFraction × 窗宽 = 0.44 × 800dp = 352dp`；右栏文字左沿 744px 与 0.44×1600px=704px 之差正是右栏 20dp 内边距，二者自洽）：

| 组 | 声明顺序 | 竖屏（dp，1600px 窗口） | 横屏（dp，2560px 窗口 / 左栏 0.44×1280=563dp） |
|---|---|---|---|
| 左组 | 先（`CenterStart`） | **由左栏宽 352dp 推出的 40dp 槽位**：歌词 8–48 · 队列 48–88 · 加入库 88–128 · **⤢ 128–168** | 同左（由 563dp 推出）：歌词 ~21–45 · 队列 ~61–85 · 加入库 ~101–125 · **⤢ ~141–165** |
| 传输组 | 后（`Center`） | **a11y 实测边界 ÷2**：上一首 103–149 · 播放 149–203 · 下一首 201–249 | a11y 实测 ÷2：上一首 218–246 · 播放 ~263–300 · 下一首 ~316–344 |
| 重叠？ | — | ⤢ 的 128–168dp 与 `上一首`(103–149)+`播放`(149–203) **完全重叠 ⇒ 命中区为 0** | 165 < 218 ⇒ **不重叠**，⤢ 正常 |

判定门槛可解析写成：不重叠要求 `左组末端 ≤ (W − 142dp) / 2`，即 **`W ≥ 478dp`**（W = 左栏宽）。平板竖屏 352dp < 478dp ⇒ 必然重叠；平板横屏 563dp ≥ 478dp ⇒ 安全。与实测完全一致。

### 4.2 这不是「某个谓词已满足导致空操作」

`bigScreenEntrySlot` 在平板竖屏下**返回 true**（挂载了、图标也画了），断点在**它之后的命中测试**上——所以现象虽然是「点了没反应」，但既不是「按钮没挂载」，也不是「宽度谓词已满足」，更不是「系统不转屏」。这是本次探针与任务书猜测最大的分歧点。

### 4.3 代码自己预言过这次失败

```kotlin
// PlayerCard.kt:766-769
// 大屏幕模式开关（入口/出口同一个回调、同一个图标语义）。
// 竖屏时它在下方那排操作按钮里（第 4 个，SpaceEvenly）；
// 横屏大屏时它在控制条右端（音质让出来的位置）—— 竖屏那排的
// 第 4 个在横向三段式布局里会顶到居中的传输组（实测与"上一首"重叠）。
```

这段注释明确写过「第 4 个在横向三段式布局里会顶到居中的传输组（实测与"上一首"重叠）」，因此 v1.8.0 才**没有**把 ⤢ 放进横向控件条、而是放进 `trailing`（`:787-806` 的退出按钮）。v2.5.5 · `91a6321` 为了让平板有入口，把它加回了**左组末尾**（`FullPlayerControls.kt:246-263`）——横屏左栏够宽（563dp）所以没复现，竖屏左栏不够（352dp）于是**正好复现了那条注释警告的重叠**。本探针的实测（⤢ 槽位被 `上一首`+`播放` 完全覆盖）与注释逐字吻合。

### 4.4 为什么手机没有这个问题

手机竖屏 `screenWidthDp ≈ 360dp < 600` ⇒ `isWidePlayer=false` ⇒ `usesSideCover=false` ⇒ `landscape=false` ⇒ 走 `FullPlayerControls.kt:337` 之后的**竖屏变体**，⤢ 在 `:437` `Arrangement.SpaceEvenly` 的一整行里，按钮之间由 `SpaceEvenly` 分配、不可能重叠（`:490-506`）。手机横屏时该按钮不挂载（`bigScreenEntrySlot(isLargeScreen=false, …)` = false，单测 `PlayerLayoutVisualizerTest.kt:197`），所以手机横屏「没有入口」是**设计如此**、且不影响可用性（手机可以转回竖屏再点，`AGENTS.md:1027-1031` 记录 PCL110 就是这样进大屏的）。

---

## 5. 候选路径可行性评估

| 候选 | 可行性 | 证据 / 理由 |
|---|---|---|
| **(a) `Activity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE / SENSOR_LANDSCAPE)`** | ✅ **本机完全可行（已在用）** | `MainActivity.kt:556-563` 现在就在用；实验 C 证明本机采纳（请求压制 `user_rotation=0`）；`ignoreOrientationRequest=false`。**任务书担心的「API 26+ 对 resizeable/大屏忽略 `SCREEN_ORIENTATION_LANDSCAPE`」「API 31+ 在大屏多窗口忽略方向请求」在本机 fullscreen 下未复现**；本 Manifest 用 `resizeableActivity="true"` 且无 `screenOrientation`，本身也不落在那些限制的动机里。⚠️ 大屏**多窗口/PC 模式**下是否仍被采纳 —— **未验证**（§7）。 |
| **(b) `WindowManager.LayoutParams` rotation override** | ❌ 不可行 / 不需要 | 全仓库**零使用**（`grep -rn WindowManager`，仅 `FLAG_KEEP_SCREEN_ON`）。该字段是 hidden API，设置显示旋转对第三方应用不可用（需 `SET_ORIENTATION` 一类 signature/privileged 权限）；改 `Settings.System.USER_ROTATION` 需 `WRITE_SETTINGS` 特殊权限，本应用未申请。**具体权限名与判定未在本机验证**，但结论「不需要走这条路」由实验 C 独立支撑。 |
| **(c) 应用内布局切换、不依赖系统旋转** | ✅ **最稳，但要注意语义** | 现状 `isBigScreenActive = requested && orientationLandscape`（`PlayerLayout.kt:150-151`）刻意要求窗口真的横过来（KDoc `:144-148`：避免「竖屏窗口里塞一个横屏两栏布局」）。若要让**平板竖屏**也能进大屏，不能只删 `&& orientationLandscape` —— 那会把一个为 1280×768 设计的横屏桌面布局塞进 800×1248 的竖屏窗口（左栏 352dp 装不下封面+信息+控件条）。**正确做法**是新增一个「竖屏也可用」的大屏变体，或明确「大屏模式只服务横屏」并把入口门控掉（见 §8）。 |
| **(d) 设置页加一个「大屏模式」开关** | ⚠️ 可行但不解决本 bug | 加设置项能让用户在横屏时手动开，但**不能修复竖屏下 ⤢ 的命中区**；而且设置项本身要生效仍需方向请求（同 (a)）。适合作为可发现性的补充，不适合当主修。 |
| **(e) 修命中区（本次新发现，推荐）** | ✅ **最小改动、零回归面** | 让 `FullPlayerControls.kt:188-263` 的三段不再重叠（`Row`+`weight` 或把 ⤢ 移到 `trailing`），或给 `bigScreenEntrySlot` 加「左栏宽度 ≥ 478dp」门控，使「看不见就不存在」。任一改法都只影响控件条布局，不动 `isBigScreenActive` / 方向策略 / 手机行为。 |

---

## 6. 对任务书前提的验证或推翻

| # | 任务书前提 | 结论 | 依据 |
|---|---|---|---|
| 1 | 「EMUI 上系统不采纳窗口管理器的旋转 override」 | **推翻** | 实验 C：`SENSOR_LANDSCAPE` 请求压制 `user_rotation=0`，显示保持横屏 ≥10s；撤请求后立刻回竖屏（`raw/62`、`raw/70`）。另 `ignoreOrientationRequest=false`（`raw/30`） |
| 2 | 「所以点 ⤢ 没反应」 | **现象成立，但仅在平板竖屏；根因是应用内命中测试** | `raw/52`（无 ⤢ 节点）+ `raw/55`（点 304 触发播放/暂停）+ `verification/10`（图标确实画了）。平板横屏下 ⤢ 可用（`verification/02`、`raw/62`） |
| 3 | 「override」这个用词 | **推翻**：应用用的是 `Activity.requestedOrientation = SCREEN_ORIENTATION_SENSOR_LANDSCAPE`（`MainActivity.kt:556-563`），不是 `WindowManager` 的 rotation override | `grep -rn WindowManager app/src/main/java` 命中仅 `MainActivity.kt:526-529`（`FLAG_KEEP_SCREEN_ON`） |
| 4 | 「平板默认已被解锁旋转 ⇒ ⤢ 的宽度/方向谓词已满足 ⇒ 空操作」 | **部分成立但不构成根因**：平板默认确实是 `SCREEN_ORIENTATION_UNSPECIFIED`（本来就允许旋转，`BigScreenOrientation.kt:103`），但 `bigScreenEntrySlot` 在平板竖屏下**返回 true、按钮也画出来了**，空操作发生在命中区而不是谓词 | `raw/00`（`UNSPECIFIED`）+ `raw/52`（无节点但图标可见） |
| 5 | 「平板竖屏 / 横屏都进不去大屏」（v2.5.5 修的就是这个） | **半修**：横屏已修好并真机闭环；竖屏可见但不可点 | 实验 A vs 实验 B |

---

## 7. 未验证项与原因

1. **大屏多窗口 / 华为 PC 模式（桌面模式）下方向请求是否仍被采纳** —— **未验证**。本机 `ro.config.hw_emui_desktop_mode=true`、`ro.config.hw_emui_dp_pc_mode=true`（存在该能力），但探针全程为 `mWindowingMode=fullscreen`，没有把应用拖进分屏/PC 模式复测。任务书提到的「Android 12+ 在大屏多窗口忽略方向请求」属这一类，需单独一轮实验。
2. **从*已经竖屏*的状态发起 `SENSOR_LANDSCAPE` 的首次请求** —— **未直接验证**（时序差异）。实验 C 验证的是「请求已生效时系统拒绝跟随 `user_rotation` 变竖屏」，它走的是同一个 `DisplayRotation` 决策函数（同一组输入：top app 请求方向、user rotation、`ignoreOrientationRequest`）。严格来说「先竖屏、后收到请求」这一步没有被单独复现，原因是：平板竖屏下唯一的入口 ⤢ 恰好不可点（本 bug 本身），因此无法用应用自身在竖屏下发起该请求。
3. **应用内 `auto_rotate` 开（默认值）时的表现** —— **未验证**。本机应用内该设置为**关**（日志逐字 `manual enter, holding landscape (auto-rotate off)`），为不污染设备状态，探针**没有**去改它。`auto_rotate=on` 时进入大屏同样是先写 `SENSOR_LANDSCAPE`（`BigScreenOrientation.kt:100-101`），之后由 `OrientationEventListener` 放宽成 `SENSOR`（`MainActivity.kt:627-651`）；平板平放在桌上时传感器回报 `ORIENTATION_UNKNOWN` ⇒ `isPhysicallyLandscape=false` ⇒ **不会放宽**（`BigScreenOrientation.kt:135-139`），因此预期与本次一致，但**未实测**。
4. **「用户手上那台平板当时是竖屏还是横屏」** —— **无法从本次证据判定**。本机当前是 `accelerometer_rotation=0 + user_rotation=1`（锁横屏），且观察到华为桌面会在前台化时把 `user_rotation` 回写成 1（`raw/35`）；若用户设备默认锁横屏，则 ⤢ 应可用，「点了没反应」就要求用户当时处于竖屏/锁竖屏。这属于用户现场信息，本探针**无法覆盖**，报障复现需向用户确认取机方向或旋转锁状态。
5. **手机侧（PCL110/S6）本轮未复测** —— 手机竖屏走的是另一条控件条（§4.4），本轮只在代码与既有文档层面论证「手机不受影响」，**没有**在手机上实跑对照。
6. **`WindowManager` rotation override 的权限细节** —— 按 Android API 一般知识判断普通应用不可用（§5(b)），**未在本机做实验**（无 root：`adb shell su` 返回 `inaccessible or not found`；应用为 release 包 `run-as: package not debuggable`）。

---

## 8. 对下一步实现的建议

> 前提：不要再把预算花在「让系统接受旋转」上 —— §1.6 已证明这台 EMUI 平板是听话的。要修的是**应用内**。

**P0（必修，最小面）· 让竖屏平板下的 ⤢ 真的可点**

三选一，建议 B（或 B+A 组合）：

* **A. 门控**：把 `PlayerLayout.bigScreenEntrySlot` 从「平板 且 不在大屏」改成「平板 且 不在大屏 且 横向控件条左栏放得下」。判据用纯函数 + 单测钉住，例如传入 `leftColumnWidthDp` 并要求 `>= 478`（= 左组末端 168dp + 传输组半宽 71dp 的临界值，推导见 §4.1；实现上更稳的写法是直接算 `leftGroupEnd + transportHalfWidth <= availableWidth`）。效果：竖屏平板不再出现「画出来却点不到」的死图标（回归面：手机横屏逐格不变、平板横屏不变）。
* **B. 修布局（治本）**：把 `FullPlayerControls.kt:188-263` 的三段式 `Box` 改成**单一 `Row` + `weight` 分配**（左组 / `Spacer(weight=1f)` / 传输组 / `Spacer(weight=1f)` / 右组），使子节点**在结构上不可能重叠**；宽度不足时由压缩/隐藏策略处理，而不是静默重叠。⚠️ 注意 `PlayerCard.kt:766-769` 的既有约定（传输组必须视觉居中）与 `PlayerCard.kt:787-806`（大屏退出按钮在 `trailing`，与音质 chip 互斥）—— 改这条要同时复核这两处。
* **C. 换槽位**：把平板 ⤢ 从 `CenterStart` 左组移到 `trailing`（`:320-332`）。v2.5.5 的提交信息说 `trailing` 被音质 chip 占着，但 `showQuality = !bigScreenActive`（`PlayerCard.kt:765`）而 ⤢ 只在 `!bigScreenActive` 时挂载 —— **两者条件互补，理论上不冲突**，可以复核后采用。

**P0 验收（必须真机、必须两种方向各一条）**

1. 平板**竖屏**：`uiautomator dump` 里必须出现 `content-desc="大屏幕模式"` 节点，且点击后日志出现 `NcrustBigScreen: big screen: manual enter…`、`dumpsys` 出现 `mOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE`、窗口转横屏、`bigScreenActive ->` 分支生效（截图）。
2. 平板**横屏**：现状不得回归（`verification/02-after-bigscreen-tap.png` 作为基线）。
3. 手机竖屏 / 横屏：逐格不变（`PlayerLayoutVisualizerTest.kt` 六格矩阵 + 真机点按）。

**P1 · 把「点不到」类问题纳入既有铁律**

本次事故的形状是「条件挂载对了、谓词有 5 条单测、真机点击却打到了隔壁按钮」。建议在 `AGENTS.md` 的「Compose 触摸陷阱」一节补一条（与第 1/2/5 条同类）：**`Box` + 多个 `Alignment.XxxStart/Center/End` 的「三段式」布局在窄容器下必然重叠，且后声明者赢命中测试；任何新增按钮都必须用真机 `uiautomator dump` 验证「节点存在 + bounds 不与他节点相交」**。v2.5.5 `EVIDENCE.md:259` 已经把「平板 ⤢ 入口的点击闭环 ❌ 未做」列为未验证项 —— 本次探针正好证明那条缺口不是形式主义。

**P2 · 文案/预期对齐**

若决定「大屏模式只服务横屏」（因为 `bigScreenActive` 结构上要求 `orientationLandscape`），那就应当让 ⤢ 在竖屏下**明确表达意图**（例如点击时提示「请横持设备」或直接以 `requested` 为唯一判据并提供竖屏可用的布局），而不是画一个点不到的图标。这一条属于产品决策，需要与用户确认，**本探针不代替该决策**。

**不要做**

* 不要引入 `WindowManager` rotation override / `WRITE_SETTINGS`（§5(b)）。
* 不要为了让竖屏进大屏而直接删掉 `isBigScreenActive` 里的 `&& orientationLandscape`（`PlayerLayout.kt:150-151`）—— KDoc `:144-148` 说明了它挡的是什么（竖屏窗口里塞横屏两栏），删掉等于把 352dp 左栏的竖屏窗口强行套 1280×768 的横屏桌面布局。
