# Ncrust 「横屏歌词手动滑动后留在原地」——真机证据笔记

包名 `com.takahashirinta.ncrust` · `versionName 2.2.1-gpl` · `versionCode 38`
真机 serial `0715f763f54c023a` · 全部数据来自本轮 adb 实测，未编辑任何代码、未改动任何
SharedPreferences、未卸载、未清数据。

> 证据目录：`docs/verification/v2.3.0/`
> 截图：`docs/verification/v2.3.0/screenshots/` · UI dump / logcat：`docs/verification/v2.3.0/`
> 原始过程记录：`probe-raw/landscape-raw-runlog.txt` · 数值总表：`probe-raw/lyric-panel-measurements.txt`
> 像素级证明：`probe-raw/pixel-diff-report.txt`

---

## 1. 设备与版本

| 项 | 实测值 | 来源 |
|---|---|---|
| serial | `0715f763f54c023a` | `adb devices -l` |
| 型号 / 设备码 | `SM-G9209` (`zerofltectc`) | `getprop ro.product.model` |
| Android | 7.0 | `getprop ro.build.version.release` |
| 物理分辨率 | `1440x2560` | `wm size` |
| 密度 | `640` ⇒ **1dp = 4px**，逻辑 **360x640 dp** | `wm density` |
| 应用版本 | `2.2.1-gpl` (38) | `dumpsys package com.takahashirinta.ncrust` |
| 起始方向 | 竖屏，`screencap` = 1440x2560 | `screenshots/01-launch-portrait.png` |
| 大屏模式后方向 | 横屏，`screencap` = **2560x1440** | `screenshots/03-player-landscape.png` |
| rotation | **1**（ROTATION_90） | `dumpsys display` → `mOverrideDisplayInfo … app 2560 x 1440, real 2560 x 1440, rotation 1, density 640` |
| 大屏模式进入时刻 | `16:47:45.002` | logcat：`NcrustBigScreen: big screen: manual enter, holding landscape (auto-rotate off)` |

⚠️ **记录一处命令差异（不是失败，是 ROM 差异）**：任务书要求的
`adb shell dumpsys window | grep -E "mCurrentFocus|mRotation"` 在这台 Android 7 三星 ROM 上
**只输出 `mCurrentFocus`，没有 `mRotation` 这个键**。已改用等价的
`dumpsys display`（`mOverrideDisplayInfo … rotation 1`）+ `screencap` 实际像素尺寸
（2560x1440）+ 应用自身的 `NcrustBigScreen` 日志三条互相独立地证明已进入横屏。
`mCurrentFocus` 全程为 `com.takahashirinta.ncrust/.MainActivity`（未离开应用）。

进入大屏模式的方式：在 `ui-02-player.xml` 里按 `content-desc` 找到 `大屏幕模式`
节点 `bounds=[933,2160][1061,2288]`，`input tap 997 2224`（节点中心）。

---

## 2. 面板几何（px 与 dp）

### 2.1 竖屏（`ui-02-player.xml`）

| 对象 | bounds | 尺寸 |
|---|---|---|
| 歌词面板（scrollable 节点） | `[80,320][1360,1136]` | 1280x816 px = **320.0 x 204.0 dp**，top=320 bottom=1136 |
| 当前行 `我不停走在一条不回头的路` | `[80,653][1360,781]` | 文本 top=653 bottom=781，h=128 |
| 第 2 行 `没有走到终点我绝对不认输` | `[80,867][1232,983]` | h=116 |
| 第 3 行 `我相信希望就在前方不远处` | `[80,1081][1130,1136]` | h=55（被面板底边裁掉） |

竖屏顶部留白 = `min(200dp, 0.36 × 204.0dp) = min(200dp, 73.4dp) = 73.4dp = 294px`
⇒ 预期当前行 item top = `320 + 294 = 614`；实测 `653 − 40(10dp 内边距) = 613`
⇒ **frac = (613−320)/816 = 0.3591 ≈ LEAD_FRACTION 0.36**。

> 注意：这台 360x640dp 手机在**竖屏就已经处于「留白被夹取」的区间**
> （面板仅 204dp < 556dp），并不是 `LyricsPanelScroll` 注释里假设的「竖屏约 700dp」。

### 2.2 横屏（`ui-03-landscape.xml`，后续所有 dump 相同）

| 对象 | bounds | 尺寸 |
|---|---|---|
| 歌词面板（scrollable 节点） | `[1206,96][2480,1024]` | 1274x928 px = **318.5 x 232.0 dp** |
| 面板屏幕 y 范围 | **y = 96 … 1024**（屏幕高 1440） | 高 928px = 232.0dp |
| 另一个 scrollable 节点 | `[96,671][2464,1440]` | 播放器卡片后面的页面列表，非歌词面板 |

横屏顶部留白 = `min(200dp, 0.36 × 232.0dp = 83.5dp) = 83.5dp = 334px`
⇒ **自动定位时当前行 item top = 96 + 334 = 430px（即 0.36）**。

### 2.3 度量口径（后面所有数字都按这个口径）

`NcrustLyricsPanel.kt` 的三个事实决定了怎么量：

- 每个歌词 item 是 `Box(...).padding(vertical = 10.dp)`（第 376 行）⇒ 密度 640 下 **40px**；
- `inactiveScale = 0.82f`（第 159 行），`scale = lerp(1f, 0.82f, (dist/1.8).coerceIn(0,1))`（第 385 行）
  ⇒ **当前行 dist=0，缩放恰为 1.00，其 uiautomator bounds 就是未缩放的布局 bounds**；
  相邻行 dist=1 → 0.900（实测 116/128 = 0.906，与公式吻合）；
- `LyricsPanelScroll.leadOffsetPx(vh) = -(vh * 0.36)`（第 84 行）⇒ 应用自己的不变量是
  **当前行 item top = 面板 top + 0.36 × 面板高**。

所以与 `LEAD_FRACTION` 可直接比较的量是

```
frac_itemtop = (节点文本 top − 40px − 面板 top) / 面板高
```

竖屏校验通过（0.3591 vs 0.36），横屏 6 次独立基线全部为 **0.3599**。

**「当前行」如何认定**（两个互相独立的信号，结果始终一致）：

1. 面板根节点的无障碍 `liveRegion` 文本（`NcrustLyricsPanel.kt:327-330`）——dump 里
   bounds 等于面板矩形那个节点的 `text` 属性；
2. **像素判据**：当前行是屏幕上唯一用主题主色 `#8B5CF6 = (139,92,246)` 绘制的文本
   （`LyricsView.kt:335-336`：已唱行 60% 白、未唱行 40% 白）。实测当前行 99.9% 的墨迹像素
   为紫色，最亮像素恰为 `(139,92,246)`；其译文/音译槽继承同一颜色（60% α），
   所以在当前行下方紧邻处会出现第二条紫色带 —— 那是译文，不是另一行。

> **为什么必须用像素判据**：本轮的歌里有重复句（`We are electric` 同屏出现 3 次），
> 纯文本匹配会挑中错误的那一次（`05d-electric-paused` 上文本匹配给出 frac 0.0205，
> 像素判据给出正确的 0.3599）。

⚠️ **任务书的 `(lineCenter − panelTop) / panelHeight` 不是稳定量**：当前行折行成两行时它会变。
同一自动位置下实测 `frac_center` = **0.4720（一行）/ 0.5490（两行）**，而 `frac_itemtop` 恒为 0.3599。
这个差别在 T3 上滑那一轮会**导致误判**（见 §4.3）。

---

## 3. T2 —— 横屏自动位置实测

`ui-03-landscape.xml`（进入大屏后第一帧）：当前行 `只有听到舞曲才会让我感到欣慰`

- 当前行 bounds `[1206,470][2480,741]` ⇒ top=470, bottom=741, centerY=605.5（**折行两行**）
- 面板 top=96, bottom=1024, 高=928
- 任务书公式：`(605.5 − 96) / 928 = ` **0.5490**
- 同一行按本笔记口径：`(470 − 40 − 96) / 928 = ` **0.3599** = `LEAD_FRACTION 0.36` ✅

**6 次独立基线（不同歌、不同行、播放/暂停、单行/折行）全部给出 `frac_itemtop = 0.3599`：**

| 证据 | 歌 / 状态 | 当前行 | 节点 top | frac_itemtop | frac_center |
|---|---|---|---|---|---|
| `04-landscape-playing-a` | 绝不认输 播放 1:11 | 我相信希望就在前方不远处 | 470 | **0.3599** | 0.4720 |
| `05-landscape-playing-b` | 绝不认输 播放 1:18 | 当我决定踏上这征途 | 470 | **0.3599** | 0.4822 |
| `05b-paused-baseline` | 绝不认输 暂停 1:56 | 这就是我一个不屈不挠的小人物 | 470 | **0.3599** | 0.5490 |
| `05c-playing-baseline` | 绝不认输 播放 2:04 | 当我用音乐让他们清醒 | 470 | **0.3599** | 0.4720 |
| `05d-electric-paused` | We Are Electric 暂停 0:08 | We are electric | 470 | **0.3599** | 0.4822 |
| `20-paused3-baseline` | 日文曲 暂停 0:11 | めき煌めきと君も | 470 | **0.3599** | 0.4822 |

⇒ **自动定位本身完全正常**：横屏下当前行稳定落在面板 36% 高度处（item top = 430px）。

---

## 4. T3 —— 「手动调整后留在原地」核心复现

每次试验都记录了**实测**偏移（进程内用 `time.time()` 计时），不是「大概等了 8 秒」。
暂停态下截图与 dump 是同一瞬间（见 §4.4 静止性对照），因此可以合成一个 frac。

### 4.1 【决定性】暂停 + 下滑（`PAUSED3-*`）

歌：日文曲（含译文/音译），**暂停在 0:11**，当前行全程为同一句 `めき煌めきと君も`
（暂停 ⇒ `NcrustLyricNotify` 在此期间没有任何换行日志，不可能有换行掩盖结果）。

滑动：`input swipe 1843 620 1843 980 400`（面板 x 1206..2480、y 96..1024 内部，慢速 400ms）

| 时刻 | 当前行文本 | 节点 bounds | frac_itemtop | frac_center | 相对自动位置位移 |
|---|---|---|---|---|---|
| 滑动前（`20-paused3-baseline`） | めき煌めきと君も | `[1206,470][2480,617]` | **0.3599** | 0.4822 | 0 |
| 滑动后 **+1.64 s**（`PAUSED3-A-immediate`） | めき煌めきと君も | `[1206,838][2480,985]` | **0.7565** | 0.8788 | **+367.9px = +39.65%** |
| 静置 **+8.57 s**（`PAUSED3-B-8s`） | めき煌めきと君も | `[1206,838][2480,985]` | **0.7565** | 0.8788 | 不变 |
| 静置 **+20.61 s**（`PAUSED3-C-20s`） | めき煌めきと君も | `[1206,838][2480,985]` | **0.7565** | 0.8788 | 不变 |

像素级验证（`probe-raw/pixel-diff-report.txt`）：

- `PAUSED3-B-8s.png` 与 `PAUSED3-C-20s.png` **md5 完全相同**
  （`985ecce31a2a7326fd0214eda46cfd38`）⇒ 8.57s 之后再等 12s，画面**逐字节没变**；
- `PAUSED3-A` 与 `PAUSED3-B` 有 11813.7 px（0.32%）差异，全部落在歌词文本行内，且
  **所有节点 bounds 完全一致**（详见 §4.5 说明）；B 与 C 之差为 0。

### 4.2 暂停 + 下滑（第二次，另一首歌，`06p/07p/07p2`）

歌：绝不认输，暂停在 1:56。实际间隔由文件 mtime 算出：**+37.3s / +51.9s**（都远超 5s 超时）。

| 时刻 | 当前行 | 节点 top | frac_itemtop |
|---|---|---|---|
| 滑动前（`05b-paused-baseline`） | 这就是我一个不屈不挠的小人物 | 470 | **0.3599** |
| 滑动后（`06p-paused-after-manual-scroll`） | 同上 | 836 | **0.7543** |
| **+37.3 s**（`07p-paused-after-8s-idle`） | 同上 | 836 | **0.7543** |
| **+51.9 s**（`07p2-paused-after-20s-idle`） | 同上 | 836 | **0.7543** |

`07p` 与 `07p2` 的 png **md5 相同**（`3a8a73ede9b553364dc77c344f04b145`）。

### 4.3 暂停 + 上滑（相反方向，`10p/11p`）

实际间隔 **+10.6 s**（> 5s 超时）。

| 时刻 | 当前行 | 节点 bounds | frac_itemtop | frac_center |
|---|---|---|---|---|
| 滑动后（`10p-paused-after-up-scroll`） | 这就是我一个不屈不挠的小人物 | `[1206,289][2480,560]` | **0.1649** | 0.3540 |
| **+10.6 s**（`11p-paused-after-8s-idle-up`） | 同上 | `[1206,289][2480,560]` | **0.1649** | 0.3540 |

> 🪤 **这一轮正是任务书那个中心点公式的陷阱**：`frac_center = 0.3540` 看起来「差不多就是 0.36」，
> 若只用它就会误判成「没有 bug」；而 `frac_itemtop = 0.1649`（当前行 item top 在 289px，
> 自动位置应为 430px）清楚表明面板被用户移走了 141px，且 10.6s 后**一动没动**。

### 4.4 播放中 + 下滑（`PLAYING-A/B/C`，严格计时）

歌：We Are Electric，**播放中**。偏移：+1.80s / +8.62s / +20.68s。

| 时刻 | 观察（截图 / dump） | 结论 |
|---|---|---|
| **+1.80 s** | 截图里当前行（主色紫）墨迹在 y=854..941，**位于面板底部并已进入底部渐隐带**；同标签 dump（晚 1.9s）把该行记在 `[1206,859][2353,1024]`，frac_itemtop **0.7791** | 手动滑动后当前行被甩到面板下部 |
| **+8.62 s** | 截图中面板内**完全没有紫色墨迹** ⇒ 当前行**整行在可视区之外** | 未回到 0.36 |
| **+20.68 s** | dump 显示当前行 `We are electric` 回到 `[1206,470][2480,617]`，frac_itemtop **0.3599** | 已回正 |

**为什么播放态最后回正了**：应用自己的换行日志给出了权威时刻 ——
`16:56:58.082 NcrustLyricNotify: re-post on lyric line change: We are electric`
= 滑动后 **+6.8 s**，即**刚刚越过 5s 超时**；此时 `userScrolling` 已被清掉，于是这次换行
触发了 `LaunchedEffect(currentIndex)` 的自动回正。**播放态下换行不断发生，所以 bug 被掩盖；
暂停态没有换行，bug 就永久暴露** —— 这正是 §4.1 的决定性证据。

### 4.5 关于「A → B 有 0.3% 像素差」的诚实说明

`PAUSED3-A`（+1.64s）与 `PAUSED3-B`（+8.57s）之间有 11813px 差异，但：

- 两者**所有歌词节点的 bounds 完全相同**（含 `[1206,838][2480,985]`，`frac_itemtop` 都是 0.7565）；
- 差异行集中在歌词文本行（如 06p/07p 那轮的差异行 211–294 / 419–503 / 625–729 / 844–945），
  静态 UI（`A-` 按钮 `[2264,138][2312,199]`）**没有任何差异**；
- `B` 与 `C`（再等 12s）**逐字节相同**。

⇒ 这是滑动手势结束后约 1~2 秒内的一次**亚像素级渲染沉降**（不足 1px，bounds 取整后不变），
**不是位移**；`B==C` 证明此后彻底静止。我**没有**能确证其成因（未去改代码验证），
所以只如实记录现象与量级，不推断机理。

**静止性对照（证明「暂停 + 不碰屏幕」时屏幕是静态的，上面的静置窗口因此干净）**：
暂停状态下连拍 3 张、间隔 1.5s、全程不触碰 —— `q1/q2/q3` **md5 完全相同**
（`edb5547f348e739f4cac95d6a2c59e98`），`compare -metric AE` 均为 **0 px**。
同时这也证明**暂停时卡拉 OK 逐字光标不会自行推进**，所以暂停态确实是「只可能由 idle 逻辑引起变化」的干净环境。
（文件：`probe-raw/quiescence-control-q1/q2/q3.png`）

**仪器灵敏度对照（证明这套方法看得见真实位移）**：
`05b`(自动) vs `06p`(滑动后) = **79347.7 px 差异**；
`20-paused3-baseline` vs `PAUSED3-A` = **61031 px 差异**。

---

## 5. T4 —— 播放/暂停/拖动进度是否会重置计时器

结论：**都不会**。唯一的回正触发是「歌词换行」。

### 5.1 暂停时滑走 → 按播放（`12-t4-paused-scrolled-away` + `T4-play` 时间线）

- 暂停在 0:08，当前行 `We are electric` 被滑到 frac_itemtop **0.4170**（节点 top 523，自动应 470）；
- 按下播放后：

| 时刻 | 播放位置 | 当前行 | frac_itemtop |
|---|---|---|---|
| +2.0 s | 0:10 | We are electric | **0.4170**（未回正） |
| +5.6 s | 0:13 | **Three**（换行了） | **0.3599** ← 回正发生在此刻 |
| +9.3 / +12.8 / +16.4 / +19.9 s | 0:17 / 0:21 / 0:24 / 0:28 | Let's go … | 0.3599 |

⇒ **按播放本身没有回正**；回正发生在 +5.6s 的那次换行。

### 5.2 暂停时滑走 → 点歌词行 seek（`13-t4b-paused-scrolled` + `T4-seek` 时间线）

- 暂停在 0:45，滑走后当前行 `Move your body and soul` 位于 frac_itemtop **0.8772**；
- 点歌词行 `(1843,560)`：**seek 确实生效** —— 位置从 0:45 跳到 **0:40**（随后冻结在 0:40）；
- 当前行变成 `Just move your body again`，frac_itemtop **0.4041**，此后 **+1.8s → +23.8s 共 7 次采样全部为 0.4041**。

⇒ **seek 不回正**，24 秒内一次都没回。

### 5.3 补充对照：确认「只有换行才会回正」

从上面 0.4041 的位移状态继续（此时 5s 超时早已过期），按播放：

| 时刻 | 播放位置 | 当前行 | frac_itemtop |
|---|---|---|---|
| +1.9 s | 0:41 | Just move your body again | 0.4041（仍未回正） |
| +5.4 s | 0:45 | **Move your body and soul**（换行） | **0.3599** ← 精确回到 0.36 |
| +8.9 … +26.2 s | 0:49 … 1:06 | Move your body and soul | 0.3599 |

⇒ 回正与「换行」严格同刻发生，与「按播放」无关。

---

## 6. 代码层对照（只读，未修改任何文件）

`app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt`

- 第 305-316 行：用户滚动结束 → `delay(5000)` → `userScrolling = false; lastAutoScrolledIndex = -1`。
  **这个分支里没有任何滚动调用**；
- 第 289-302 行：`LaunchedEffect(currentIndex)` 是唯一带 `animateScrollToItem` 的跟随路径，
  而它开头就是 `if (userScrolling || currentIndex == lastAutoScrolledIndex) return@LaunchedEffect`；
- 另外三条定位路径（`LaunchedEffect(lines)` 第 229 行、`LaunchedEffect(isVisible)` 第 247 行、
  `LaunchedEffect(forcedScrollTrigger)` 第 265 行）分别只在「换歌词表 / 面板显现 / 外部强制」
  时触发，手动滑动不会引起它们。

因此：**5 秒超时只解除「暂停跟随」这个标志，不做回正**；如果超时之后一直没有换行
（暂停、间奏、长句），面板就会永久停在用户放手的位置。实测结果与该代码路径完全一致。

---

## 7. VERDICT（结论）

### ✅ **BUG 复现成功 —— 「手动调整后留在原地」确认为真**

**判定依据（最有说服力的一条）**：`PAUSED3` 暂停态试验，歌暂停在 0:11，
当前行全程是同一句 `めき煌めきと君も`（暂停 ⇒ 不可能有换行掩盖）：

| | frac_itemtop | 相对自动位置 |
|---|---|---|
| 滑动前（自动位置） | **0.3599** | — |
| 滑动后 +1.64 s | **0.7565** | +367.9px = +39.65% 面板高 |
| 静置 +8.57 s | **0.7565** | 不变 |
| 静置 +20.61 s | **0.7565** | 不变（且与 +8.57s 的截图 **md5 相同**） |

即：用户把当前行放到面板 76% 高度处之后，**8.57 秒和 20.61 秒都过去了（5 秒超时的 1.7× / 4.1×），
面板一次都没有回正**；截图逐字节相同，不存在「缓慢回正」或「动画未完成」。

**另两条独立复现**：
- 下滑（另一首歌，实际 +37.3s / +51.9s）：0.3599 → **0.7543** → 0.7543 → 0.7543；
- 上滑（反方向，+10.6s）：0.3599 → **0.1649** → 0.1649。

**T4 结论**：播放 / 暂停 / seek **都不会**重置那个 5 秒计时器、也不会触发回正；
回正**只**发生在「歌词换行」这一刻（§5.1 +5.6s、§5.3 +5.4s 两次实测与换行严格同刻）。

**播放态为何有时看不到 bug**：播放时换行不断发生（本机实测每 2–3 秒一次，见
`NcrustLyricNotify` 日志），滑动后跨过 5 秒超时的**第一次换行**就会把面板拉回 0.36
（`PLAYING` 轮的 +6.8s 换行即如此）。所以该 bug 的稳定复现条件是
**超时之后不再发生换行**（暂停、间奏、长句、以及用户正看着的那一句很长时）。

**未复现 / 未能验证的部分（如实记录）**：
- 竖屏下未做同一试验（本轮只验证竖屏的自动位置 0.3591，未在竖屏做「滑走后静置」的对照），
  因此「该现象是否仅在横屏出现」本轮**没有**数据支撑；
- 滑动后的亚像素沉降成因未确证（§4.5）；
- 一次 `PAUSED` 试验选中的是纯音乐曲目（`纯音乐，请欣赏`，面板只有一行、无可滚动内容），
  该轮**无信息量**，已记录不采用。

---

## 8. 本轮产出的全部文件

### 截图 `docs/verification/v2.3.0/screenshots/`（均为 1440x2560 或 2560x1440 PNG）

| 文件 | 说明 |
|---|---|
| `01-launch-portrait.png` | 启动后竖屏（库页 + mini 播放条，队列已持久化） |
| `02-player-portrait.png` | 竖屏全屏播放器（歌词面板可见） |
| `03-player-landscape.png` | 点「大屏幕模式」后横屏全屏播放器（2560x1440） |
| `04-landscape-playing-a.png` | 横屏播放中，当前行 `我相信希望就在前方不远处` @0.3599 |
| `05-landscape-playing-b.png` | 上帧 4 秒后，当前行 `当我决定踏上这征途` @0.3599 |
| `05b-paused-baseline.png` | 暂停态自动位置基线 `这就是我一个不屈不挠的小人物` @0.3599 |
| `05c-playing-baseline.png` | 播放态自动位置基线 `当我用音乐让他们清醒` @0.3599 |
| `05d-electric-paused.png` | 换歌后（We Are Electric）暂停态自动位置 @0.3599（含重复句，验证像素判据） |
| `06-after-manual-scroll.png` | **播放态**滑动后 +1.80s（= PLAYING-A，当前行被甩到面板底部） |
| `07-after-8s-idle.png` | **播放态** +8.62s（= PLAYING-B，面板内无紫色 ⇒ 当前行在可视区外） |
| `06p-paused-after-manual-scroll.png` | **暂停态**下滑后，当前行 @0.7543 |
| `07p-paused-after-8s-idle.png` | **暂停态** +37.3s，仍 @0.7543 |
| `07p2-paused-after-20s-idle.png` | **暂停态** +51.9s，仍 @0.7543（与上帧 md5 相同） |
| `10p-paused-after-up-scroll.png` | **暂停态**上滑后，当前行 @0.1649（反方向） |
| `11p-paused-after-8s-idle-up.png` | **暂停态** +10.6s，仍 @0.1649 |
| `12-t4-paused-scrolled-away.png` | T4：暂停下滑走歌词（@0.4170），随后按播放 |
| `13-t4b-paused-scrolled.png` | T4：暂停下滑走歌词（@0.8772），随后点歌词行 seek |
| `20-paused3-baseline.png` | 决定性试验的滑动前基线（日文曲暂停 @0:11）@0.3599 |
| `PAUSED3-A-immediate.png` | 决定性试验 +1.64s，@0.7565 |
| `PAUSED3-B-8s.png` | 决定性试验 +8.57s，@0.7565（与 C md5 相同） |
| `PAUSED3-C-20s.png` | 决定性试验 +20.61s，@0.7565 |
| `PAUSED-A/B/C-*.png` | 纯音乐曲目那一轮（**无信息量**，面板仅一行不可滚动），保留以示未采用 |
| `PLAYING-A-immediate.png` | 严格计时播放态 +1.80s（= `06-after-manual-scroll.png` 原件） |
| `PLAYING-B-8s.png` | 严格计时播放态 +8.62s（= `07-after-8s-idle.png` 原件） |
| `PLAYING-C-20s.png` | 严格计时播放态 +20.68s（XML 显示已回到 0.3599） |

### UI dump（`docs/verification/v2.3.0/`）

| 文件 | 说明 |
|---|---|
| `ui-01-launch.xml` | 启动页层级（用于定位 mini 播放条 `[0,2016][1440,2240]`） |
| `ui-02-player.xml` | 竖屏全屏播放器层级（竖屏面板几何出处） |
| `ui-03-landscape.xml` | 横屏第一帧层级（横屏面板几何 + T2 数字出处） |
| `ui-06-after-scroll.xml` | = `PLAYING-A-immediate.xml`（播放态滑动后） |
| `ui-07-after-8s.xml` | = `PLAYING-B-8s.xml`（播放态 +8.62s） |
| `04-…`、`05-…`、`05b-…`、`05c-…`、`05d-…`、`06p-…`、`07p-…`、`07p2-…`、`10p-…`、`11p-…`、`12-…`、`13-…`、`20-…`、`PAUSED-A/B/C-…`、`PAUSED3-A/B/C-…`、`PLAYING-A/B/C-…` `.xml` | 与同名截图同一标签抓取的 uiautomator 层级，所有 bounds / frac 数值的直接出处（共 22 个；播放态下比截图晚约 1.9s，暂停态下与截图等效同刻 —— 见 §4.4 / §4.5） |

> `docs/verification/v2.3.0/` 下共 **27 个 XML**：上述 22 个 + `ui-01/02/03/06/07` 5 个；
> 截图 **27 张**。（计数由 `ls -1 *.xml | wc -l`、`ls -1 screenshots/*.png | wc -l` 实测。）

### logcat（`docs/verification/v2.3.0/`）

| 文件 | 说明 |
|---|---|
| `logcat-landscape-baseline.txt` | 全量 logcat，12758 行，覆盖 16:46:12–16:59:29（开机启动 → 全部试验） |
| `logcat-landscape-filtered.txt` | 过滤 `ncrust\|lyric\|choreographer\|skipped`，1235 行（含换行日志、大屏模式日志、jank） |

### 分析与原始记录（`docs/verification/v2.3.0/probe-raw/`）

| 文件 | 说明 |
|---|---|
| `landscape-device-notes.md` | **本文件**——证据笔记与结论 |
| `landscape-raw-runlog.txt` | 逐步原始过程记录（每条 adb 命令的目的与结果、命令差异、失败与重做记录） |
| `lyric-panel-measurements.txt` | 22 个 dump 的逐节点测量总表（bounds / frac_itemtop / frac_center / 当前行判定） |
| `pixel-diff-report.txt` | 像素级「静置期间没动」证明：md5 + `compare -metric AE` 全部数值 |
| `quiescence-control-q1/q2/q3.png` | 暂停静止性对照三连拍（三张 md5 相同，0px 差异） |
| `invalid-timing/README.txt` + 2 个 `.xml` | **作废留档**：首次播放态试验误把 8s 写成 2.4s（< 5s 超时，不足以验证），已隔离并重做。⚠️ 那次的两张 PNG 已被正确计时的同名文件 `cp` 覆盖、不再单独存在（README 内已写明），保留下来的是那次的 2 个 XML dump |

> **归属说明**：`probe-raw/` 目录里另有大量**不是本轮产生**的文件
> （`ne-*.json`、`q-*.json`、`sem-*.json`、`urlcheck-*.json`、`probe-copyright-*.txt`、
> `probe-qq-fields.txt`、`probe-judgement-validity.txt`、`device-prefs-list.txt` 等，
> 时间戳 16:40–16:44，属于其它探针任务）。上表**只列本轮为这次横屏歌词验证产出的文件**。
> 本轮未改动上述任何既有文件。
