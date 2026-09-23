# TASK.md —— 施工记录与交接

> 本文件是**任务状态与决策记录**（AGENTS.md 是知识库，二者不重复）。每轮任务结束时更新。
> 最近更新：v1.6.0（D4/D2/D1/D3 四方向 + 发布），见第 11 节；v1.5.2 见第 9–10 节。

## 1. 当前状态

| 项 | 值 |
|---|---|
| 分支 | `feature/v1.5.1` → 已 **fast-forward 合并进 `master`** |
| master HEAD | `2f22bc6`（`build: 升级至 v1.5.1-gpl (versionCode 16)`） |
| tag / release | `v1.5.1-gpl` —— **已公开**（`gh release edit --draft=false`） |
| APK | `Ncrust-v1.5.1-gpl-release.apk`，sha256 `5662b5ad6a8b920628a3a18b34b0a075238e29c46025c813b73be52f2bf004d1` |
| 已完成 commit | `27921c5` A 逐字三模式 + API 36 修复 / `cb3ad00` B 手势排除 / `449c0b3` D 媒体面板歌词 / `d1b9309` E 字号调节 / 本 docs commit |
| 五个任务 | A ✅ B ✅ C ✅（见下方"commit 归属"） D ✅ E ✅ |
| 设备 | S6 `0715f763f54c023a` ✅ / PCL110 `3B15CD00GB700000` ✅ / emulator-5554 ✅ |

### ⚠️ commit 归属的一处偏差（本次唯一的流程偏离）

**任务 C（无网络启动）的实现内容没有独立 commit，被 `449c0b3 feat(media): …` 一并带入**
（`NetworkAvailability.kt` / `HomeSnapshot.kt` / `HomeScreen.kt` / `AppWarmup.kt` / 相关 i18n）。

- 原因：实现 C 之后我先做 D，提交 D 时用了 `git add -A`，把尚未提交的 C 一起扫进去了；
- 为什么没有事后拆分：任务书红线第 4 条「不 amend / rebase 已提交的 commit」。
  这些 commit 虽然都还没 push，但**遵守红线优先于提交粒度** —— 因此选择保留历史、在此显式记录；
- 影响：**只有提交粒度**。C 的全部内容都在仓库里、都参与构建与发布，且已按第 3 节实测。

## 2. 关键决策与理由（争议点都记在这里）

| # | 决策 | 理由 |
|---|---|---|
| D1 | 逐字动画三模式**用 Int 存 prefs**（`lyrics_word_animation`），并**迁移** v1.5.0 的布尔开关 | 老用户"关掉过逐字"的意图必须保留 → 迁移成模式 2；迁移只发生一次，之后布尔键不再写 |
| D2 | 设置页把原来的「逐字歌词」开关**换成三选一下拉**，而不是并列两个控件 | 布尔开关与三模式会互相冲突（关掉开关但模式=渐变扫过，用户无法预期）。旧文案 `lyricsWordByWordLabel` 保留但已无入口 |
| D3 | 渲染层**不用 `CompositingStrategy.Offscreen`**，改用显式 `canvas.saveLayer` | 它在低版本会回落成 Auto，DstIn 就作用到整个目标画布、会把底下的文字一起擦掉（S6/API 24 是主要使用机型） |
| D4 | 高亮层与底层**各自排版**（同一 TextStyle、只差颜色），而不是复用一份 layout + `drawText(color=)` | v1.5.0 失效的正是"同一节点 + clipPath + drawText 颜色覆盖"这套组合；颜色烤进 style 后不再依赖任何颜色覆盖行为。代价：当前行多一次排版（只有当前行） |
| B1 | 底部手势带**先用 systemGestureExclusion 试，失败后加"按导航模式上抬 32dp"** | 实测排除矩形注册成功但系统照样吞掉底部 0~13dp 起手的上滑；上抬是唯一实测有效的办法（28dp 起手即安全） |
| B2 | 上抬**只对手势导航**（`navigation_mode == 2`）生效 | 三键导航/老设备没有这个手势带，上抬只会平白改视觉。API < 29 读不到该设置项 → `getInt` 默认 0 → 不上抬 |
| B3 | 排除矩形**只声明把手这一条** | 系统在同一屏幕边缘只采信最靠上的那个矩形；若把收起态的悬浮播放键也一起排除，把手反而失效 |
| C1 | 连通性判据只用 `NET_CAPABILITY_INTERNET`，**故意不要求 VALIDATED** | 国内 ROM 上 Google 连通性探测不可达，validated 常年 false，用它会把"有网"误判成"没网" |
| C2 | 新增**磁盘**快照 `HomeSnapshot`，而不是把 ContentCache 改成持久化 | AGENTS 明确写了 ContentCache「网络加载的临时数据快照，不需要持久化」。另加一层只读优先、可随时丢弃的快照，不推翻既有设计 |
| C3 | 8s 超时降级放在 **UI 层**，不动 OkHttp 的全局 timeout | 全局 30s timeout 是播放/下载路径共用的；改它影响面太大。UI 层到点撤掉转圈即可解决"一直转" |
| D-media1 | 媒体面板歌词**默认关**，开关打开后写进 ARTIST（「艺人 · 歌词行」） | Android 13+ 的面板第二行只认 ARTIST（DISPLAY_SUBTITLE 被忽略，AOSP 源码级结论）。默认改 ARTIST 就是破坏既有语义 → 让用户显式选择 |
| D-media2 | **不接**小米超级岛 / OPPO 流体云 / vivo 原子通知等厂商能力 | 要么需白名单 + MiPush，要么只能走不含 MediaStyle 的 Live Updates；引入私有接口违背"不引入非公开依赖"的约束 |
| D-media3 | 不重新 post 通知（只更新 MediaSession metadata） | API 26- 的通知 body 想显示歌词只能靠重新 post，频率=每行一次；那正是"通知重绘"风险的来源。13+ 的面板读 session metadata，已经够用；S6 因此保持原行为（也算"不退化"） |
| E1 | 字号档位用 5 档（0.7/0.85/1.0/1.2/1.5），界面文案是纯数字 | 档位文案与语言无关，不必进 8 语言 i18n |
| E2 | 快捷调节放**歌词区右上角 A-/A+**，不放底部 | 底部是控制栏把手与系统手势区（任务 B 刚处理），A-/A+ 放底部会跟它们抢空间与手势 |
| E3 | 字号**立即生效、落盘 150ms 防抖** | 立即生效是手感要求；防抖只针对磁盘写，3GB 设备连点不会每次都写 prefs |

## 3. 验证矩阵（真机实测，不是推断）

| 项 | 设备 | 结果 |
|---|---|---|
| A 模式 0 渐变扫过 | PCL110 / S6 | ✅ 逐帧像素剖面：扫过点右移、软边可见（PCL110 剖面 129→106→84→63→58；S6 129→80→59） |
| A 模式 1 逐字硬切 | PCL110 | ✅ 剖面 129 直接跳 58（无中间灰阶）= 硬切 |
| A 模式 2 关闭逐字 | PCL110 | ✅ 当前行整行高亮、非当前行弱化（= v1.4.1 行为） |
| A 老设置迁移 | S6 | ✅ S6 的 `lyrics_word_by_word=true` → 读回 `lyrics_word_animation=0` |
| A API 36 根因 | PCL110 | ✅ 探针实测 `getPathForRange` 正常（pathOk=true、bounds 非空）⇒ 失效在绘制组合，不在取路径 |
| B 上拖收起 / 下拖恢复 / 点按 | PCL110 | ✅ 三项都通过（像素级判定：transport 5022↔0、悬浮键出现/消失） |
| B A/B 对照 | PCL110 | ✅ v1.5.0 release 包同位置同方向上滑 → `mCurrentFocus=launcher`（即用户报的 bug） |
| B S6 不回归 | S6 | ✅ 无 `navigation_mode` 设置项 ⇒ 不上抬；把手仍在最底部 |
| C 无网冷启动（before） | emulator | ✅ v1.5.0 实测：`am start -W` 0.5s、无 ANR、无 crash；**但首页三块全空、无空态/无重试，22s 不变** |
| C 无网冷启动（after） | emulator | ✅ release 包实测：`am start -W` 0.5s、无 ANR/crash，首页**显示上次落盘的快照内容**（榜单/推荐都在），不再是一片空白 |
| D 媒体面板歌词 | PCL110 | ✅ `dumpsys media_session` → `description=修炼爱情, 林俊杰 · 我们那些信仰要忘记多难`；QS 媒体卡片显示「修炼爱情 / 林俊杰 · 谁说太阳…」 |
| D 默认关不破坏语义 | S6 / PCL110 | ✅ 默认 false 时 ARTIST 原样；无歌词时字段完全回落 |
| E 字号 0.7x / 1.5x | PCL110 | ✅ 视觉差异明显、1.5x 下长句正常折行且逐字高亮正常 |
| E 档位与防抖 | PCL110 | ✅ A+ ×2 → prefs 1.5；A- ×4 → prefs 0.7 |
| E 设置项渲染 | S6 | ✅ 三个新行都在（逐字动画模式/歌词字号/媒体面板歌词），下拉可展开 |
| 构建 | 本机 | ✅ `assembleDebug` / `assembleRelease` / `test`（JVM 单测）全绿 |

## 4. 未验证项（醒来后请人工确认）

1. **S6 的字号"选中"动作**：设置页与下拉在 S6 上渲染正常，但自动化点击没命中选项（坐标漂移，非功能问题）；
   S6 上实际选一次 0.7x / 1.5x 更稳妥。
2. **S6 的 A-/A+ 快捷按钮**：需要在有歌词的歌上确认（S6 上搜索页当时卡在加载）。
3. **逐字歌词的主观观感**：渐变带 12% 行宽是代码里的常量（`SWEEP_EDGE_FRACTION`），
   真机上"够不够软/会不会太软"需要你的眼睛定，改一个常量即可。
4. **PCL110 锁屏界面**（不是通知栏）的媒体卡片：本轮验的是 QS 媒体卡片 + session metadata；
   锁屏走同一份 metadata，但没有单独截图。
5. **厂商灵动岛/实况窗**：按要求只做了调研，未做任何厂商接入（结论见 AGENTS.md 的 D 节）。
6. **长时间播放的内存/发热**：未做长跑测试。

## 5. 已知问题

- **用户报告过一例「歌词与实际播放歌曲不符（串台）」（PCL110）**：当场复核播放器标题 = Crucified、
  歌词面板 = Crucified 的英文歌词 + 中文翻译、媒体卡片第二行 = 同一首歌的当前行，**三者一致、未能复现**。
  代码侧已核对：所有切歌路径都先 `resetLyricsForNewSong()` 再写 `currentSongId`；歌词的缓存命中与网络
  返回两条路径都有 `currentSongId == songId` 守卫；媒体面板歌词行在 `lyrics` 变空（切歌）时会被清成 null。
  **下次遇到请记录「正在播的歌」+「当时显示的歌词首句」**，可直接定位。另需注意：本轮我在 PCL110 上做过
  "搜索并播放 → 播放队列被搜索结果替换 → 之后又回到原队列"的操作，若当时看到的是那个中间态，属测试污染。
- `lyricsWordByWordLabel`（v1.5.0 的布尔开关文案）仍在 8 语言里保留但没有 UI 入口 —— 为了迁移路径与最小改动，
  没有删；下一个大版本可以清掉。
- 任务 C 的 commit 归属偏差（见第 1 节）。
- 媒体面板歌词打开后，**蓝牙 / 车机会同步显示歌词**（AVRCP 的 subtitle/artist 路径）—— 这是 ARTIST 方案
  的固有副作用，设置页的描述文案里已写明。

## 6. 下次从哪里继续（v1.5.2 候选）

1. 离线下载（v1.5.0 已做完 D1+D2 调研，落地顺序见 AGENTS.md 的「离线下载」节；
   第一步必须先解决 Kotlin 1.9.24 / AGP 8.5.0 与 Room/WorkManager 的版本兼容）；
2. S6 上把第 4 节的 1、2 两项补测；
3. 渐字渐变带宽度（`SWEEP_EDGE_FRACTION`）与硬切模式二选一的默认值，按你的观感定。

## 7. 本次使用的临时手段（都已清理）

- PCL110 上用 root 读取 prefs（只读，未写入）；`su` 直接可用，无需密码；
- emulator-5554 上 `adb root` + `svc wifi/data disable` 做无网测试；
- 为定位 API 36 根因临时加过一个探针（mode 2 复刻 v1.5.0 画法 + `NcrustProbe` 日志），**提交前已删除**；
- 未对任何设备做永久性系统修改；未删除任何用户数据。
- PCL110 上遗留的测试状态：**设置 → 播放 → 「媒体面板显示歌词」= 开**（方便你直接看效果，关掉即可）；
  逐字动画模式 / 字号已改回默认（渐变扫过 / 1.0x）。

### 8 节（v1.5.2 串台）排查时用的临时手段

- **只用 emulator-5554**，PCL110 / S6 全程未动。`adb root` + 把 `shared_prefs` 备份到
  `/data/local/tmp/ncrust-backup`，再用 `run-as`（debug 包）写入 4 首匿名可播歌的队列复现。
  注意：root 直接 `cat >` 新建的 prefs 文件属主/MLS 类别不对，App 读不到（日志是
  `SharedPreferencesImpl: Permission denied` + `avc: denied { rename }`），必须走 `run-as`。
- 临时加过 `NcrustProbe` 日志（`PRELOAD_ADD` / `TRANSITION` / `PRELOAD_REQ`），**提交前已删除**；
  修复后的验证改用代码里长期保留的 `Queued next ... count=` 与 `gapless transition ->` 日志。
- 工具（均在仓库外 `tools/`，不入 git）：`repro-desync.sh`、`verify-desync.sh`、`verify-slot.sh`、
  `find-free-songs.py`、`song-url.py`、`seed-queue-desync.py`；证据在 `tools/evidence/`。
  提醒：用 `am` 传带 `&` 的音频 URL 会被远端 shell 截断（后面的 `--es` 一起被吃掉），
  必须先 `cut -d\& -f1`；`songId` 要用 `--el`（long extra），`--es` 读不到。
- 遗留设备状态：**emulator-5554 装的是本次修复的 debug 包**（`dist/` 里没有 v1.5.1 的 APK，原 release
  包无法还原）；app 数据已恢复为改动前的备份（无登录态、单曲队列）。

## 8. ✅ 已修：UI/元数据与实际音频不同步（v1.5.2 · 串台）

**症状（PCL110，v1.5.1 release）**：播放器标题 / 歌词面板 / 媒体卡片都显示下一首（如 Crucified、江南），
但耳朵里放的仍是上一首的音频。用户确认「状态栏和歌词界面对，音频不对」。

**根因：预载项被重复 `addMediaItem`，播放列表累积；而元数据只是 last-write-wins 的旁路变量。**

真机复现：API 24 模拟器（emulator-5554）+ `tools/repro-desync.sh`，完整 logcat 见
`tools/evidence/desync-before.log`。关键三行：

```
PRELOAD_REQ  songId=25640004 current=400876427 preloadedSongId=-1        ← 切歌瞬间预载一次
PRELOAD_ADD  before count=1 songId=25640004 → after count=2
PRELOAD_REQ  songId=25640004 current=400876427 preloadedSongId=25640004  ← 最后 60s 又预载同一首
PRELOAD_ADD  before count=2 songId=25640004 → after count=3              ← 重复入队（不变量被破坏）
...
TRANSITION reason=1 itemId=song:25640004 刀马旦 count=4
           pendingTitle=The truth that you leave pendingSongId=139774    ← 元数据已是「下下首」
```

触发条件链：

1. `MainScreen` 对同一首下一曲预载**两次**：`playFromQueue` / `LaunchedEffect(songTransitioned)` 一次，
   `needsPreload`（进入最后 60s）再一次（MainActivity.kt:540 / 706 / 772）。
2. 上游 `preloadNextSong` 有一条「URL 已缓存就整体 return」的去重，`19f2969`（2026-09-10，
   「无缝播放单曲循环/拖带…」）为了让缓存命中时也能入队把它删了 —— 第二次预载于是又
   `player.addMediaItem` 了一遍，播放列表变成 `[当前, 下一首, 下一首]`。
3. `onMediaItemTransition` 只判断 `reason==AUTO && pendingNextTitle != null` 就把 `pendingNext*` 写进
   通知栏 / 歌词；播到那个重复项（= 刚放完的上一首）时，`pendingNext*` 已被后来的预载改成下下首
   ⇒ **UI 显示下一首、音频是上一首**。`removeMediaItem(0)` 每次只清一项，错位会一直保留并随歌累积。
4. 用户描述的临时绕过因此完全成立：按一次「下一首」会 `setMediaItem` 重建整个播放列表，错位立刻消失。

**修复（3 个 commit）**：

- `6a3706d` `test(player): 抽出预载槽位不变量并加 JVM 单测` —— 新增
  [PreloadSlot.kt](app/src/main/java/com/takahashirinta/ncrust/player/PreloadSlot.kt)（纯逻辑，无 Android 依赖）：
  `decide()` → APPEND / REPLACE / IGNORE，`transitionMatches()` 判定起播项是否就是槽位项；13 条单测。
- `e787755` `fix(player): 待播槽位至多一首，杜绝重复预载造成的串台` —— 服务端「待播槽位」以新字段
  `pendingNextUrl` 作占用标记（PlaybackService.kt:426），重复预载幂等忽略、换歌替换
  （`removePendingItems()`，PlaybackService.kt:685）；`playUrl` / `stop` / `onTaskRemoved` 统一走
  `clearPendingNext()`；ViewModel 侧 `preloadedSongId` 作为槽位镜像，`clearPreloadedState()`
  （PlayerViewModel.kt:681）在每条替换播放列表的路径上成对清空，`preloadNextSong` 对「已在槽位」的同一首
  直接返回（PlayerViewModel.kt:705）。
- `4900004` `fix(player): 媒体元数据跟随 media item，transition 加槽位守卫` —— 预载项自带
  `mediaId=song:<id>` 与 `MediaMetadata`（`buildPreloadMediaItem`，PlaybackService.kt:661）；
  `onMediaItemTransition` 只在「起播项 == 槽位项」时才写元数据（PlaybackService.kt:340），
  否则 `Log.w` + 清槽位 + UI 不动（宁可不更新，也绝不显示错歌）。

**不变量（写进 PreloadSlot 的 KDoc）**：ExoPlayer 播放列表里当前项之后**至多一首**预载项，
且 `pendingNext*` 必须与它一一对应。

**验证**：

- 单测 `./gradlew test` **69/69 通过**（PreloadSlotTest 13 条 + 既有 56 条）；`./gradlew :app:minifyReleaseWithR8` 通过。
- API 24 模拟器（debug 包）跑与复现完全相同的驱动序列：`Queued next ... count=2` 恒定、
  每条 `gapless transition -> songId=X title=<X 的标题>` 自洽、无重复预载（`tools/evidence/desync-after-natural.log`）。
- 直接 `am startservice` 连打两次同名 `preload_next`：第二次 `preload_next ignored, slot already holds songId=400876427`；
  换成另一首则 `preload_next replaces stale slot songId=400876427 -> 25640004`，count 始终为 2
  （`tools/evidence/desync-after-slotguard.log`）。
- **未验证**：release（R8）APK 没能在本 worktree 出包 —— `keystore.properties` 指向的 `ncrust-release.jks`
  不在本 worktree，按红线未改配置、未拷贝密钥；只跑了 R8 任务本身。发布前请在主 checkout 出 release 包复测一次。


---

## 9. v1.5.2：逐字歌词渐变重做 + 冷启动修复

### 9.1 参考实现是 AMLL，不是 SPlayer 自己

SPlayer 的逐字歌词来自 **AMLL（Apple Music-like Lyrics）**（SPlayer-Dev/SPlayer PR #726）。
本轮直接读 AMLL 源码（`Steve-xmh/applemusic-like-lyrics`，`packages/core/src/lyric-player/dom/animation/mask/`）：

| 维度 | AMLL 的做法 |
|---|---|
| 渐变载体 | **每个词一个 CSS `mask-image`**，不是整行一个遮罩 |
| 停靠点 | 亮区**正好等于词宽**，右侧再接一段 `fadeWidth` 的线性过渡（`generateFadeGradient()`） |
| 光标 | `maskPosition` 线性推进，速度 = 词宽 / 词时长（**纯线性**） |
| 停顿区间 | `advancePauseTimeline` 里 `movePx = 0` —— 冻住，到下一个词起始时刻瞬时平移 |
| 渐变带宽度 | `wordFadeWidth = 0.5`（默认）× **词高** ≈ 0.65 em |
| 未唱/已唱 α | 当前行 `--bright-mask-alpha: 1` / `--dark-mask-alpha: 0.4`；非当前行都是 0.2 |
| 首尾 | 首词额外 1.5 倍、末词额外 0.5 倍渐变带位移 |
| 缓动 | **全程没有任何缓动函数** |

⇒ 顺滑感来自「词内匀速 + 0.65em 软边」，给每个词套缓动反而变成逐词脉冲。故 `SweepEasing` 默认 `LINEAR`。

### 9.2 v1.5.1 的问题定位（真机 + 代码双向确认）

1. **光标根本不连续（主因）**：按需唤醒循环只在「行时间戳 ∪ 词起始时刻」写一次 `displayPosition`，
   绘制阶段永远读到词边界那一刻 —— `playedCutCharCount` 里的词内线性插值**在真机上从未被喂到中间值**。
2. 渐变带取「整行宽度的 12%」，短行糊、长行细，与字号无关。
3. 渐变带是「光标贴右沿、整条带向左展开」，光标与进度差半个带宽。
4. 行首把 `edge` 夹到约 1px，等于硬切。

### 9.3 实施（6 个 commit）

| commit | 内容 |
|---|---|
| `a96b487` | `SweepTrack`：yrc 词时间轴 → 连续「时间 → 光标位置」折线，纯逻辑、可脱离 Compose 单测 |
| `9ac4446` | 光标逐帧推进（只在「当前行正在唱」的窗口内）+ 渲染改用轨道；渐变带绑字号、以光标为中心、窄离屏层 |
| `1030fb9` | 渐变质量三档（自动/高级/兼容）+ 低内存自动降级 + 三个参数覆盖键 + 8 语言设置项 |
| `9f9a99e` | 修「词间隙倒扫」与「折行跨行插值倒扫」（用户真机反馈） |
| `1d71925` | 修「切歌时歌词被快速从头过一遍」（`currentPosition` 未归零） |
| `8174048` | **冷启动**：启动页不再等网络 + 让超时真正生效（见 9.5） |

新增 [SweepTrack.kt](app/src/main/java/com/takahashirinta/ncrust/lyric/SweepTrack.kt) +
[SweepTrackTest.kt](app/src/test/java/com/takahashirinta/ncrust/lyric/SweepTrackTest.kt)（21 条）。

### 9.4 两条真机反馈 bug 的根因（用户 2026-09-22 报告）

- **一句话占两行时特效从头再播一遍** = 两个倒扫缺陷叠加：
  ① 「首尾各外扩半个渐变带」被错用到**每一个**词 ⇒ 词间隙光标后退一整个带宽；
  ② 折行处上一行末词的 x 接近行尾、下一行首词的 x 接近行首，直接插值 ⇒ 整行从行尾倒扫回行首。
  修法：先筛有效词，只有整行首/末词外扩；给每个节点存「行尾保持位」，跨视觉行**保持**在上一行行尾。
- **切歌时歌词被快速过一遍** = `resetLyricsForNewSong()` 清了歌词却没清 `currentPosition`，
  2Hz 的位置流还是上一首的末尾，面板拿它去新歌词里二分找行 ⇒ 落在最后一行 ⇒ 从第一行快速滚到底再滚回。
  修法：切歌路径把 `currentPosition/progress` 归零；`LyricsView` 换歌时重置外推锚点；
  循环里加「超前真实时刻 >1s 就拉回」的守卫。

### 9.5 冷启动根因（用户 2026-09-22 报告「十秒到半分钟打不开」）

1. **超时形同虚设**：`AppWarmup` 的网络阶段套着 `withTimeoutOrNull(3000)`，但里面每个 suspend 调用
   外面都是 `runCatching { ... }.getOrNull()` —— Kotlin 的 `runCatching` 捕获 `Throwable`，会把
   协程取消异常一起吞掉。超时取消被吞 ⇒ 预算完全不生效，坏网下一路挂到 **OkHttp 的 connectTimeout(30s)**。
   **「半分钟」就是这 30s。** 修法：新增 `runCatchingCancellable`（CancellationException 原样上抛）。
2. **启动页在等网络**：`SplashScreen` 双闸门里的 `AppWarmup.ready` 原本要等完整个网络阶段
   （3 个 Home 请求 + 最多 18 张封面）才置位。修法：`_ready` 改为**只反映本地阶段**（快照回灌完成即置位）。
   为避免同一批接口打两遍，新增 `AppWarmup.homeFetchDone`，`HomeScreen` 缓存还冷时最多等它 1.2s。

实测（PCL110 / API 36）：**完全断网**（`Active default network: none`）冷启动 **2.7s 进首页**（走磁盘快照）。

### 9.6 实测记录与未验证项

已验证：单测 **79/79 通过**（含两条倒扫回归）；`assembleDebug` 通过、覆盖安装成功；
逐帧循环真机 logcat 计数**稳定 60fps**（`displayPosition` 89581→100375，60 帧 ≈ 994ms）；
相邻 `screencap` 确有像素差；断网冷启动 2.7s。

⚠️ **未验证**：① 渐变观感仅用户口头确认「还可以」，无逐档对比；② 兼容档（硬边）/ easing 未逐档真机对比；
③ 折行歌词的真机观感未复核；④ 长跑（>30 分钟）内存/掉帧未测。


---

## 10. v1.5.2 发布实测（2026-09-22，versionCode 17）

版本号 commit `6e6a930`（`build: 升级至 v1.5.2-gpl (versionCode 17)`），tag `v1.5.2-gpl`。
单测 **90/90 通过**（`testDebugUnitTest` 90、`testReleaseUnitTest` 90，0 failures / 0 errors / 0 skipped）。
产物：release 9,721,992 B（`eca16db6…abbd`）、debug 29,942,928 B（`8d5671a7…9fd4`）。

### 10.1 两台设备与安装结果

| 设备 | 系统 | 装了什么 | 结果 |
|---|---|---|---|
| 3B15CD00GB700000 PCL110 | Android 16 / API 36，KernelSU root | **v1.5.2 release** | ✅ `adb install -r` 覆盖 v1.5.1(vC16) **成功**；`firstInstallTime` 未变、`shared_prefs` 全在（登录态/队列保留） |
| 0715f763f54c023a S6 SM-G9209 | Android 7.0 / API 24，Magisk root | **v1.5.2 debug** | ⚠️ release 包 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（S6 上是 debug 签名 `e10c8b4d…`，与 release `e75af3ff…` 不同，**既有状况**，见 v1.5.0 发布说明）；同签名 debug 包 15→17 覆盖成功，数据保留 |

release 包证书与 v1.5.0 release **逐位一致**（`e75af3ff…5511`）⇒ 老 release 用户可直接覆盖升级。
装 release 到 S6 必须先卸载（丢登录态），按「不对既有用户数据做写操作」的约束**未执行**。

### 10.2 冷启动（screenrecord + 逐帧分析；「首帧→首页内容出现」）

方法：`am force-stop` → `KEYCODE_HOME` → `screenrecord --time-limit 12` → `am start -W`，
录像按 25fps 抽帧后做逐帧 MAD 分析（首页内容 = 启动页之后的第一次大幅整屏切换）。

| 设备 | 改前（旧包） | 改后（v1.5.2） |
|---|---|---|
| PCL110（release） | TotalTime **1332 / 1303 / 1267 ms**；首帧→内容 **2.40 / 2.44 / 2.64 s** | TotalTime **481 / 351 / 456 / 465 ms**；首帧→内容 **0.96 / 0.76 / 0.84 / 0.92 s** |
| S6（debug） | TotalTime 1317 / 1330 ms（另有 1 例 129848ms 伪影）；首帧→内容 **2.28 / 2.28 / 2.76 s** | TotalTime **1291 / 1300 / 1291 ms**；首帧→内容 **1.92 / 1.88 / 1.92 s** |

**断网/坏网（关键）**：PCL110 上用临时 `iptables -I OUTPUT -p tcp -d <ip> -j DROP` 把
`music.163.com` / `interface*.music.163.com` / `clientlogusf.music.163.com` 四个 IP 的 TCP 全部黑洞
（复现用户「坏网」场景），release 包冷启动 **TotalTime 520 / 490 ms、首帧→内容 0.92 / 0.52 s**，
**未出现十秒以上冷启动**。测试后规则**已立即删除并验证**（`iptables -S OUTPUT | grep -c DROP` → 0，ping 外网正常），
设备上无任何永久性系统修改。

S6 首轮 `TotalTime: 129848ms` 是 Android 7 复用 ActivityRecord 旧 `mLaunchStartTime` 的**测量伪影**
（同次 App 侧 `Displayed +1s312ms`，录屏 2.76s 就出内容），冷启动结论以 App 侧 Displayed + 录屏为准。

### 10.3 逐字渐变（S6《浮夸》47/47 行；PCL110《修炼爱情》70/70 行）

- **PCL110（release，Android 16）**：`YrcParser: attachWords: 70/70 lines got word timing`；
  逐列像素剖面确认同色相 alpha 阶跃随光标**从左向右**推进（RGB 48,53,68 → 92,101,131），**无回退**；
  `dumpsys gfxinfo` 20s：**779 帧 / Janky 1 帧（0.13%）**、50th 6ms、90th 7ms、95th 10ms、99th 14ms、Missed Vsync 0。
- **S6（debug，Android 7）**：`attachWords: 47/47`（《浮夸》）、22/30（99 Problems）；
  折行句渲染正常（第一视觉行亮、换行后的第二行暗，光标停在行尾保持位），**无 ANR、无 FATAL**（logcat 计数 0）。
- **S6 掉帧（25s 窗口，`dumpsys gfxinfo`）**：

| 档位 | 帧数 | Janky | 50th | 90th | 95th | 99th | Missed Vsync |
|---|---|---|---|---|---|---|---|
| 自动（软边 saveLayer） | 1352（54fps） | 1326（98.08%） | 19ms | 26ms | 28ms | 34ms | 6 |
| 兼容（clipRect 硬边） | 1476（59fps） | 1452（98.37%） | 19ms | 25ms | 27ms | 32ms | 5 |

**结论：S6 上的掉帧不是软边（saveLayer）造成的。** 切到「兼容」硬边只换来约 +5fps、99th −2ms，
两档都远超 16.7ms 预算 —— 2015 年 GPU 跑 1440×2560 的整屏播放器 UI 本身就是这个水平（且这是 **debug 包**）。
未能取到干净的「关掉歌词面板」基线（歌词开关的注入点击未生效），故无法把歌词面板本身的成本单独分离出来。
S6 的渐变质量设置已**复原为「自动」**。

### 10.4 回归清单

| 项 | PCL110（release） | S6（debug） |
|---|---|---|
| 首页 / 库 / 搜索 / 用户 四个 tab | ✅ 四页均正常渲染（首页榜单+每日推荐、库 1068 首、搜索历史+榜单、用户设置） | ✅ 首页榜单/每日推荐、库、搜索（含搜索结果直接播放）、用户设置 |
| 展开 / 收起播放器 | ✅ 上滑/点击展开、BACK 收起回 mini bar | ✅ 同；另测到「控制栏收起」态需拖右下悬浮键恢复（v1.4.0 既有行为） |
| 切歌 | ✅ 最后一页 → Doja，媒体元数据与歌词同步跟随，**新歌歌词从 0:00 正常开始（不快速过一遍）** | ✅ 浮夸 → Can Can，元数据/歌词跟随 |
| 拉进度条 | ✅ 点 75% → position 5931ms → **78565ms**（1:19 / 1:37） | ✅ 点 60% → position 82609ms（1:24 / 2:14） |
| 设置页 | ✅ 用户页各分区正常 | ✅ 播放分区各项正常，并实测「渐变质量」切档 → 复原 |
| 崩溃 / ANR | 0 | 0 |

### 10.5 本次新增的未验证 / 未通过项（如实）

1. S6 **没有**装上 release 包（签名不符，需先卸载丢数据，未执行）；S6 的冷启动与帧率都是 **debug 包**数字。
2. 渐变观感仍只有用户口头确认；**「高级」档未单独取值**，easing 未做 A/B，软边/硬边只比了帧统计没比主观画质。
3. 折行歌词只验证了「光标不倒退到行首」，**第二行的扫过顺滑度未逐帧跟踪**。
4. 长跑（>30 分钟）内存与掉帧未测（本次窗口均 20–25s）。
5. S6 全屏播放器 98% 帧超 16.7ms，未分离出歌词面板本身的占比（缺少干净基线）。
6. 安装后第一次冷启的 S6 录屏在 9–11s 出现「库→用户→设置」自行跳转；随后两次冷启 + 一次 6s 静置
   对照（SSIM 0.9997）均稳定，**倾向于外部/幽灵触摸而非应用缺陷，但未能证实**。

---

# 11. v1.6.0（四个方向：D4 / D2 / D1 / D3）

> 2026-09-23 全自动执行。调研 → 实施 → 双机测试 → 发布。本节的数字全部来自真实测量，
> 不是推断；工具都在仓库外的 `tools/`（不入 git），证据在 `tools/evidence/`。

## 11.1 调研报告（四方向）

### D4 · yrc 逐字歌词覆盖率 —— 最高优先级，实施完成

**最小验证的结论与任务书假设不同**：`lv/kv/tv=-1` 与 yv 的现状无关 —— **v1.5.0 起就已经在传
`yv=-1`**，而 yrc 的返回率本身就是 43%（收藏库 100 首）。逐字覆盖率低是**客户端对齐判据**造成的：

| 组合 | 有 lrc | 有 yrc | 备注 |
|---|---|---|---|
| App 当前参数（cp=false,tv=-1,lv=-1,rv=0,kv=0,yv=-1,ytv=0,yrv=0） | 100% | **43%** | 基准 |
| 全 -1（含 rv/kv/ytv/yrv） | 100% | 43% | **逐字节相同，无提升** |
| 不带 yv | 100% | 0% | 反证 yv 是开关 |
| 只带 yv | 0% | 43% | lrc 与 yrc 是两条独立开关 |
| kv=-1（klyric 卡拉OK） | — | **0 字段有内容** | 100 首全空，此路不通 |
| GET 方式 / 不带 cp | 100% | 43% | 无差别 |
| eapi + os=iphone/9.1.20、os=android/9.1.20 | — | 43% | 换身份不多给 |
| `/api/song/lyric/v1` | — | 43% | 同上 |

⇒ **接口层天花板 43%，且不存在「换个参数/换条通道就能拿到更多」的路径**（9 首无 yrc 的歌逐一
换通道复测，全部仍为 0 字节）。真正的瓶颈是 v1.5.2 的判据「yrc 行数必须 == lrc 行数」：

| 指标 | v1.5.2 | v1.6.0（LCS 对齐） |
|---|---|---|
| 逐字可用歌数（≥50% 行） | 37/100 | **42/100** |
| 行级挂载率 | 77.9% | **90.0%** |

**外部歌词源（LRCLIB 等）结论：不采纳。**①它们只有行级时间轴（plainLyrics / syncedLyrics），
拿不到逐字，解决不了 D4 的目标；②不违反「无自建 API」红线（是公开第三方 API，不用自建服务），
但会给国内用户引入一条境外依赖与一个无 SLA 的数据源；③真要有价值，只能用于「网易确无歌词」的
兜底展示，那是另一个需求。

**方案落地**：`YrcAligner.kt`（纯逻辑）+ `YrcParser.attachWords` 双路择优 + 12/15 条单测。

**与其它方向的协同点**：逐字数据随音频一起进缓存（D1 的离线场景下逐字依然可用，因为歌词缓存本来
就是持久化的）；D2 的性能结论直接约束了 D4 的渲染路径 —— 不允许为了覆盖率增加每帧绘制量。

### D2 · S6 帧预算归因 —— 已给出干净基线，结论是「无小改可做的热点」

见发布说明 D2 节的五态表。三条要点：①整屏重绘单价是设备属性（队列面板态 18.0ms 与歌词态 18.6ms
几乎一样）；②歌词面板让昂贵帧连续发生（981 帧 vs 117 帧）；③UI 线程始终 1–2ms，瓶颈纯 GPU。

**探针记录（都已还原，仓库干净）**：逐行缩放 graphicsLayer 置 1:1 → 977 帧（vs 981），无差异；
「帧预算自适应降级」实现到一半发现**判据不成立**（S6 是流水线式掉帧：帧回调仍 60Hz，而每帧延迟
19.5ms，用帧间隔量不出来），且收益仅 -2ms/帧却要牺牲软边 ⇒ 按「先量后改、不划算不改」回退，
代码未保留。

**候选方案（未实施，供决策）**：①低端机自动硬边（-2ms、v1.5.2 实测 +5fps，代价是硬边观感）；
②逐字扫过降到 30Hz（省一半昂贵帧，观感变差）；③静态 chrome 离屏缓存（要动架构）。

### D1 · 离线缓存 —— Phase 1 完成，Phase 2/3 未做

合规定位：**已播放音频流的本地缓存**，不是官方下载、不碰 DRM。技术要点与实测依据见发布说明 D1 节。
分阶段：Phase 1（音频流缓存 + 离线 URL 清单 + 离线回放 + 缓存统计/清理）✅；
Phase 2（离线曲目管理 UI、容量控制界面、已缓存标识）❌；Phase 3（显式下载入口）❌。

**架构选择说明**：没有引入 Room / WorkManager。v1.5.0 的调研把它们作为「显式下载队列」的选型，
但 Phase 1 的语义是**被动缓存**（用户播到哪存到哪），用 media3 自己的 SimpleCache + 一个有界
SharedPreferences 清单即可，引入 Room/WorkManager 只会增加 Kotlin 1.9.24 / AGP 8.5.0 的兼容风险
（v1.5.0 已把这条列为「未验证的工程风险」）。等做 Phase 3 的显式下载队列时再引入。

### D3 · 厂商灵动岛 / HyperOS / Live Updates —— 原生部分完成，厂商部分只调研

- Android 16 原生：`Notification.ProgressStyle` + `canPostPromotedNotifications()` +
  `POST_PROMOTED_NOTIFICATIONS` 权限（真机实测名），实现为**并行的第二条通知**（MediaStyle 与
  ProgressStyle 不能共存于一条通知）；
- 小米 HyperOS 超级岛 / OPPO 流体云 / vivo 原子通知 / 华为实况窗：**均无公开 SDK**，要么需要白名单
  + 厂商推送通道，要么只能走不含 MediaStyle 的私有形态 —— **不接入**，标记未验证；
- minSdk 24 兼容：API < 36 时整类不产生任何通知，媒体通知行为与 v1.5.2 完全一致；
- **真机结论（PCL110 / ColorOS / Android 16）**：权限 `POST_PROMOTED_NOTIFICATIONS: granted=true`、
  系统 AppSettings 里本应用 `promoted=true`，但 **`ncrust_live_update` 渠道从未被创建** ⇒
  `NotificationManager.canPostPromotedNotifications()` 在该 ROM 上返回 **false** ⇒ 通知没发出。
  即「代码路径跑通、平台不放行」，这正是厂商/ROM 侧不可控的部分。

## 11.2 变更清单（commit）

| commit | 内容 |
|---|---|
| `d6f7c27` | `feat(lyrics)` D4：LCS 行对齐（`YrcAligner` + `YrcParser` 双路择优） |
| `c42a12e` | `feat(offline)` D1：离线缓存 + 离线回放（含 D3 的 PlaybackService 接线，见下） |
| `4e712f3` | `feat(notify)` D3：Live Updates（`LiveUpdateNotifier` + manifest 权限） |
| 本 docs commit | `build:` 版本号 + `docs:` TASK/AGENTS/CLAUDE + 发布说明 |

### ⚠️ 又一处 commit 归属偏差（与 v1.5.1 同类，如实记录）

D3 在 `PlaybackService` 里的接线（`updateNotify` 里调用实时更新、三处 `cancel`）是在
提交 D1 之前写的，被 `git add` 一起扫进了 `c42a12e`。**只有提交粒度问题**：D3 的全部内容都在仓库里、
都参与构建与发布。没有事后拆分（红线：不 amend / 不 rebase 已推送的提交）。

## 11.3 测试

| 项 | 结果 |
|---|---|
| JVM 单测 | **120 / 120 通过**（D4：YrcAligner 12 + YrcParser 15；D1：OfflineKeys 7 + OfflineUrlIndex 7；既有 79）—— 发布说明里一度写成 129，那个数字把中途按证据回退删除的探针测试也算了进去，这里是复核后的真实值 |
| D4 真机 A/B（PCL110 release） | `Sound Of Silence`：v1.5.2 `line count mismatch lrc=40 yrc=39, skip` → v1.6.0 `39/40 lines got word timing (yrc=39, lcs)` |
| D4 真机（S6 debug） | `16/28 lines got word timing (yrc=28, lcs)`、`41/41 (index)` —— 两条路径都在真机跑通 |
| D2 真机（S6 debug） | 五态 gfxinfo（库滚动 / 队列滚动 / 歌词渐变 / 逐字关 / 队列静止）+ framestats 阶段分解 |
| 覆盖安装 | PCL110 release 17→18 ✅（`firstInstallTime` 未变）；S6 debug 17→18 ✅ |
| D1 离线链路（**真机端到端**） | ✅ PCL110 release：断网（`Active default network: none`）→ force-stop → 冷启动 → 点已缓存曲目 → `state=PLAYING`，position 91770 → 97788（+6018ms），无 "no playable url"；缓存 20.6MB + `ncrust_offline.xml: song:491424530:jymaster` |
| D3 | 代码路径 ✅（`live_update_probed` 已写入）、权限 ✅ granted、系统 AppSettings `promoted=true`；**但实时更新渠道未创建 ⇒ `canPostPromotedNotifications()` 在本 ROM 返回 false，通知未发出，视觉未验证** |

## 11.4 未验证项（如实）

1. D1 断网端到端回放、容量上限 LRU 淘汰、S6(API 24) 缓存读写性能、边播边清；
2. D1 Phase 2/3 未实现（无离线管理 UI / 无容量设置界面 / 无已缓存标识）；
3. D3 **未生效**：PCL110（ColorOS/Android 16）不允许第三方应用发提升通知
   （`canPostPromotedNotifications()` 返回 false，判据是 `ncrust_live_update` 渠道从未创建）；
   实时更新的视觉呈现与全部厂商灵动岛都未验证；
4. S6 的全部数字都是 debug 包（release 与既有 debug 签名不同，装 release 必须卸载丢数据，按红线未执行）；
5. S6 长跑（>30 分钟）内存/掉帧/发热未测；
6. D2 未做任何性能改动（只归因）；
7. 逐字渐变的**主观观感**仍只有 v1.5.2 的用户口头确认。

## 11.5 本次使用的临时手段（都已清理）

- S6：`run-as` 改 `ncrust_settings` 做逐字开关/画质档探针（debug 包，改前 force-stop，改后已还原为
  `lyrics_word_animation=0`）；全程未动登录态与用户数据；
- PCL110：只读 prefs（root）取收藏单曲 id 做覆盖率统计；未写入任何用户数据；
- 逐行缩放 graphicsLayer 探针（`PROBE-D2`）：已 `git checkout` 还原，仓库无残留；
- 「帧预算自适应降级」实现到一半按证据回退，相关文件已删除；
- 未做任何永久性系统修改，未使用 iptables（v1.5.2 用过的黑洞方案本轮不需要）。

---

# 12. v1.6.1（补丁：媒体面板两行位置修正）

**用户反馈（v1.6.0 发布当天，附 ColorOS 控制中心截图）**：「播放面板的歌词和乐曲的名字位置反了」。

## 12.1 复现与定位

截图里控制中心媒体面板是 「67」 / 「DJ R4 · You have a mental proble..」。
核对网易元数据：这首歌 name=67、artists=[DJ R4]，LRC 里确有 「You have a mental problems」
且时间戳与播放进度（1:43）对得上 —— **内容没串，是两行的「排布」不符合用户预期**：

| | v1.6.0（旧） | v1.6.1（新） |
|---|---|---|
| 第一行（TITLE） | 歌名 | **当前歌词行** |
| 第二行（ARTIST） | 艺人 · 歌词行 | **歌名 · 艺人** |

用 ask_user_question 与用户确认了他要的就是这一种（不是猜的）。

## 12.2 修法

新增纯逻辑 [MediaDisplayLines.kt](app/src/main/java/com/takahashirinta/ncrust/player/MediaDisplayLines.kt)
（JVM 单测 8 条）承载唯一一条规则，**三处复用**：媒体面板元数据（TITLE/ARTIST）、通知栏媒体通知
（contentTitle/contentText）、Android 16 实时更新（Live Update 两行）—— 三处必须同一份文案，
否则会出现「面板第一行是歌词、通知第一行是歌名」的不一致。

边界（都进单测）：空白歌词行当作没歌词；艺人缺失时第二行只写歌名（不留「歌名 · 」尾巴）；
歌名缺失只写艺人；两者都缺则第二行为空串；歌词**不 trim、不截断**（截断交给系统面板）。
另把 METADATA_KEY_DISPLAY_SUBTITLE 清空 —— 歌词已在第一行，再写一遍会让支持三行的车机重复显示
（v1.5.1 写它是为了老车机，语义已变）。

## 12.3 真机验证（PCL110 / ColorOS / Android 16 / **release** 包）

```
dumpsys media_session:
  metadata: size=5, description=制作人 : 卢文韬, 紫荆花盛开 · 李荣浩/梁咏琪, null
                              ^^^^^^^^^^^^^^ TITLE=当前歌词行    ^^^^^^^^^^^^^^^^^^^^^^^^^^ ARTIST=歌名 · 艺人

控制中心媒体面板（截图复核）:
  第一行：紫荆花飘扬            ← 当前歌词
  第二行：紫荆花盛开 · 李荣…     ← 歌名 · 艺人
```

## 12.4 单测与产物

- JVM 单测 **128 / 128 通过**（v1.6.0 的 120 + MediaDisplayLines 8）；
- release `Ncrust-v1.6.1-gpl-release.apk`，debug `Ncrust-v1.6.1-gpl-debug.apk`，sha256 见 `dist/Ncrust-v1.6.1-gpl.sha256`；
- 顺带修正 v1.6.0 文档里写错的单测总数：129 → **120**。

## 12.5 未验证项（如实）

- 车机 / 蓝牙 AVRCP 上的两行映射（TITLE 现在是歌词）**未在真车机验证**，只按字段语义推断；
- 老车机三行显示下 SUBTITLE 清空后的观感未验证；
- S6（API 24）上的锁屏媒体卡未单独截图（字段同源，行为应一致）。

# 13. v1.7.0（P0 收起态拖不上来 + P1 横屏大屏幕模式 + P2 Material3 美化）

## 13.1 P0：收起播放器后「只能拖下去不能拖上来」

**用户原话（v1.6.1）**：「虽然现在不会触发系统返回，但是收起播放器界面后实际上会直接和歌词界面进行交互，
导致只能拖下去不能拖上来。」

### 13.1.1 探针定位（先证据后改代码）

在 `PlayerCard` 的两处纵向手势里临时插 `NcrustP0` 日志（**release** 包装到 PCL110，
保持 release 签名以免丢登录态），实测值如下（`totalDragDistancePx = contentHeightPx × 0.85 = 2380px`，
触摸 slop = 42px）：

| 手势 | 旧判据 | 实测落点 | 旧行为 |
|---|---|---|---|
| 收起态 mini bar 上滑 150px/100ms | `progress >= 0.5` | endProgress 0.045 | 弹回 |
| 收起态上滑 250px/200ms | 同上 | 0.087 | 弹回 |
| 收起态上滑 400px/300ms | 同上 | 0.147 | **弹回** |
| 收起态上滑 1400px/2500ms | 同上 | 0.57 | 展开（要拖到屏幕中部） |
| 展开态内容区下滑 400px/1500ms | `progress <= 0.75` | 0.8496 | **弹回** |
| 控制栏把手上滑 266px/400ms | `delta >= 0.25` | delta ≈ 0.226 | 差 11px，弹回（h≈1005px） |

**根因不是命中测试，是吸附阈值**：卡片要走完 2380px 才让 progress 从 0 到 1，
「过中点才展开」= 手指要跨 340dp；正常一划（150~400px）全部落在 0.04~0.15，松手必弹回。
用户看到的「手势被歌词界面吃掉」是弹回后的错觉 —— 探针同时确认：
收起态下歌词面板**没有挂载**（`expandedMounted=false`），手势从未漏给歌词。

### 13.1.2 修法

抽出纯逻辑 [PlayerDragSnap.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerDragSnap.kt)（2 个 object）+ JVM 单测
[PlayerDragSnapTest.kt](app/src/test/java/com/takahashirinta/ncrust/ui/player/PlayerDragSnapTest.kt)（23 条，数值全部取自上面这张实测表）：

1. **方向敏感行程阈值收窄**：整卡上滑 0.5 → **0.08**（190px+slop ≈ 66dp）、下滑 0.25 → **0.08**；
   控制栏收起 0.25 → **0.12**，恢复仍是 0.05（v1.4.2 的手感不动）。
2. **补甩动判据**：速度 ≥ **600px/s** 按方向直接提交，不看行程。
3. 速度从 **progress 域**反推（`progressDelta × 行程距离 ÷ 耗时`）：卡片挂在被
   `graphicsLayer` 平移的节点里，拖动过程中局部坐标随卡片一起移动，直接用 `position` 差分
   会低估速度（实测同一注入在控制栏检测器能过 900px/s、在整卡检测器过不了）。
4. **越过 slop 的那一段位移补进 progress**：注入事件稀疏（快速轻扫）时整个手势可能只有
   一两个 MOVE，不补就会出现「划了但卡片没动」，松手必然弹回。

### 13.1.3 实测（PCL110 / ColorOS / Android 16 / release 包）

| 项 | 结果 |
|---|---|
| 收起态上滑 250 / 300 / 400 / 700px | ✅ 四档全部成功拖出播放器（旧版四档全弹回） |
| 收起态上滑 150px/100ms | ❌ 仍弹回（0.045 < 0.08 且注入事件太稀，甩动速度量不出来）—— 见 13.1.4 |
| 展开态下滑收起整卡 250 / 400 / 700px | ✅ 三档全部成功 |
| 控制栏把手 上滑 300px / 快上甩 246px / 下滑 100px / 快下甩 100px | ✅ 四项全过 |
| 歌词面板开 / 关两态 | ✅ 收起态上滑都能拖出播放器（行为一致） |
| **不误触歌词** | ✅ 暂停播放后，「上滑拖出」与「点按展开」两张截图的歌词区**平均像素差 = 0.00** |

证据脚本（仓库外 `tools/p0/`）：`verify-p0-v5.py`（可重复跑）、`probe-log.py`（探针日志版）。

### 13.1.4 P0 的未验证 / 残留风险（如实）

- **150px/100ms 这种极短快扫**仍会弹回：距离 0.045 不到 0.08，而 adb 注入的事件太稀、
  甩动速度量不出来。真人手指的同类手势事件密度高得多（预期能过甩动判据），**但没有真人手测**。
- 注入式手势（`adb shell input swipe`）存在**偶发不生效**：控制栏 300px/400ms 三次里成功两次
  （同参数同状态）。怀疑与注入事件的时间戳摊薄/批处理有关，未能完全归因；真人手测未发现（用户手上无此反馈渠道）。
- 阈值 0.08 / 0.12 / 600px/s 是「实测落点 + 留余量」定的，不是手感触摸定的 —— 手感需要用户上手确认。
- S6（API 24）**未做手势专项复测**（本轮 S6 只用于 P1 的横屏兼容性验证）。

## 13.2 P1：横屏「大屏幕模式」（桌面播放器）

**需求**（任务书）：左封面/歌名/音质 · 右歌词 · 沉浸式 · 明确进入/退出 · 不强制锁横屏 · minSdk 24 可用 · 复用组件不重写歌词引擎。

**实现**（commit `9f4b3db`，19 文件）：

| 项 | 做法 |
|---|---|
| 布局判定 | **第三个谓词** `PlayerLayout.isBigScreenActive(requested, orientationLandscape)`，只让播放器读；全仓库那 7 处 `screenWidthDp >= 600` 一处没改（改了会把首页/详情页/收藏页一起带进宽屏分支） |
| 布局 | 左栏：大封面（实测 952px = 272dp）+ 歌名/作者 + **就地音质选择器**；右栏：复用既有歌词/队列面板（yrc 逐字、翻译、A-/A+ 全保留）；底部控制条横跨右栏并带退出按钮 |
| 进入 | 播放器展开后底部操作行第 4 个「⤢」按钮 |
| 退出 | 同一按钮 / 系统返回键 / **把手机转回竖屏**（三条都实测过前两条，第三条由 `BigScreenOrientation.shouldExitOnConfiguration` 单测覆盖） |
| 方向 | 进入先 `SENSOR_LANDSCAPE` 转过去 → `OrientationEventListener` 检测到物理横持后放宽成 `SENSOR`（**绝不长期锁横屏**）；`applyOrientationPolicy()` 在大屏期间让路；`onConfigurationChanged` 优先判退大屏 |
| 顺手修 | 分栏**语义中线**：旧实现两处用 `screenWidthPx / 2`，真实边界是 `0.44 × 宽` —— 44%~50% 那条窄带里歌词滚动会被整卡拖拽抢走（「Compose 触摸陷阱」第 3 条的变种）。现在统一走 `PlayerLayout.splitBoundaryPx()` |
| 纯逻辑 | `ui/player/PlayerLayout.kt`（12 条单测）+ `BigScreenOrientation.kt`（6 条单测） |
| i18n | 2 条新文案 × 9 个文件（Strings.kt + 8 语言） |

**实测**：PCL110（release）：进入即 2800×1272；左封面 952px；右栏 yrc 歌词 + A-/A+ 并排、无重叠、
可点控件不落在系统栏/挖孔下；音质就地切换 `wifi_quality` 6→2 且当前歌按新档从原位置续播；
上下首/播放/进度/收藏/歌词·队列全可用；返回键与按钮都回退到竖屏全屏播放器；大屏帧 Janky 0%。
S6：见 13.4（发布前用 debug 包补测）。

**未验证（如实）**：
- **「转回竖屏自动退出」没有真机验证** —— 设备平放在桌上无法物理旋转，而方向放宽门控在物理竖持时本就不触发；
  该路径只有纯判定单测，**需要用户拿着手机实际转一次**。
- 平板/折叠屏上的大屏模式未验证（本轮只覆盖手机横屏）。
- 刘海屏/挖孔屏横屏的左右安全区只按 insets 做了处理，**没有真机（挖孔横屏）验证**。

## 13.3 P2：Material3 理念美化（不引 material3）

**先给结论**：官方 `androidx.compose.material3` **不引入**。Kanesumi 是自研的 M3 风格设计系统
（库自己的 AGENTS.md 写着「零 M3」），同时装两套 = 双主题源 + 圆角/ripple 冲突；M3 在本仓库只当**取值参照系**。
大圆角**不做**（Kanesumi 是直角设计语言，全 app 只有 `SongCard` 播放按钮一处 CircleShape，属图标语义保留）。

三个 commit（`47a8809` / `ad41138` / `c4357c7`）：

| # | 内容 | 可见效果 |
|---|---|---|
| 1 | **tonal 层级做进颜色**（不是阴影）：`NcrustColors` 新增 `surfaceContainerLowest/Low/High/Highest + outline/outlineVariant`（默认值 = 深色现值，老调用点零变化）；`toMetroColors` 把 `surfaceVariant ← surfaceContainerHighest`、`divider ← outlineVariant` | 深色有了 4 级层次：页面 `#000000` → 卡片 `#1A1A1A` → 弹窗 `#242424` → 菜单/下拉 `#2E2E2E`（修掉「深色下 surface == surfaceVariant，弹窗与页面同色」这个老问题）；浅色弹窗 `#FFFDF8 → #F2EBDE`，background/surface 关系未动 |
| 2 | **对比度**：5 处硬编码 `Color.Black`（primary 上的前景色）改用 `LocalMetroColors.current.onPrimary` | 6 个预设色下逐像素不变（它们本来就判黑）；「跟随封面/跟随系统」取色的中亮度色（例 `#3D3D99` 黑字 2.33:1）自动改白字 9.03:1 |
| 3 | **无障碍/触控**：`SettingSwitchRow` 整行加 `toggleable + Role.Switch`；4 个 <48dp 的点击目标抬到 48dp | TalkBack 能读到 / 能切开关（Kanesumi 的 `MetroSwitch` 是裸 `pointerInput`、零 semantics，**不改库**是因为改了发布产物不可复现）；最小触控目标达标 |

**明确不做**：material3 依赖、大圆角、typography 迁移（`LocalNcrustTypography` 至今 0 处读取，
要动 `MainActivity` 给 `MetroTheme` 传参才生效，收益低风险高）、任何 `tween` 时长调整、
`SweepEasing`（逐字歌词光标是**音频时间轴**问题，不能被 M3 动效统一掉）。

**未验证 / 已知**：
- **浅色主题只做了代码级对比度核算，没有浅色真机截图复核**（本轮设备都在深色模式）；
- **点 `MetroSwitch` 本体时回调会走两次**（`MetroSwitch` 不消费 tap，父级 `toggleable` 也收到）。
  两次携带同一个取反值、`checked` 是外部提升状态、各调用点副作用幂等 ⇒ 净效果仍翻转一次；
  已用「点一次变、再点一次回原样」的真机像素比对验证（`tools/p0/verify-switch.py`），并写进代码注释；
- 「跟随封面/跟随系统」取色的对比度修复**只在代码里算过**，没有真的切到该来源肉眼复核。

## 13.4 集成、回归与发布（v1.7.0）

**版本号**：`1.7.0-gpl` / **`versionCode = 20`**（任务书写 19 是错的：19 已被 v1.6.1 占用）。

**集成期由 lead 补的一处修复**（不在 P1/P2 的 commit 里）：P0 的吸附判定改为读**本地累计的拖动进度**
（`dragProgress`），不再读 `progress.value` —— `progress.snapTo` 是 `launch` 出去的异步写，
S6（慢设备 + debug 包）上一次拖动结束时 Animatable 还没追上手指，用它判定会出现
「明明拖了 400px，判据只看到 150px」→ 依旧弹回。改完 S6 上 300/400/500/600/700px 全部能拖出播放器。

**测试矩阵（发布前实测）**：

| 项 | 设备 | 结果 |
|---|---|---|
| JVM 单测 | 本机 | ✅ **169 / 169 通过**（v1.6.1 的 128 + P0 23 + P1 18） |
| release / debug 构建 | 本机 | ✅ 两个都过 |
| release 覆盖升级 | PCL110 | ✅ `versionCode=20`，`firstInstallTime` 未变（登录态保留） |
| debug 覆盖升级 | S6 | ✅ 装的是 debug 签名包（v1.6.0 debug），`install -r` 成功、`firstInstallTime` 未变 |
| P0 手势（收起态上滑拖出） | PCL110 | ✅ 250/300/400/700px 全过；展开态下滑收起 250/400/700px 全过；控制栏把手 4 项 3 过（慢速注入式上滑偶发，见 13.1.4） |
| P0 手势 | S6（debug） | ✅ 300/400/500/600/700px + 慢速 400/1200ms、500/1500ms 全过（7/7） |
| P0「不误触歌词」 | PCL110 | ✅ 拖出 vs 点按的歌词区像素差 = 0.00 |
| P1 大屏模式 | PCL110 | ✅ 进入 2800×1272、退出（BACK）回竖屏、UI 无重叠 |
| P1 大屏模式 | S6（API 24 / **debug v1.7.0**） | ✅ 安装 `versionCode=20` 成功（`firstInstallTime` 未变）、进入 2560×1440、左封面右歌词并排、控制条与退出键可用、冷启与大屏全程 **0 条 FATAL/ANR** |
| P2 开关语义 | PCL110 | ✅ 点一次变、再点一次回原样（像素比对） |
| 冷启动 / 四页导航 / 播放器展开 | PCL110 | ✅ 无 FATAL/ANR（logcat 0 条） |
| 无网启动 / 红心歌单分页 / 榜单 / 推荐卡 / 逐字三档 / yrc 覆盖率 | —— | ⚠️ **本轮未逐项复测**（这些路径本轮代码未改动，但按任务书要求应回归；见 13.5） |

**产物**（`dist/`）：
- `Ncrust-v1.7.0-gpl-release.apk` sha256 `0a986d2b771dfaadbd9858de459f71ac916aff93da00719fa9b667b3f84e7594`
- `Ncrust-v1.7.0-gpl-debug.apk` sha256 `28085a7d0873c7b988e1d201810dfeb765bae49adc0460eac9d56f2956d260ff`
- 签名证书 SHA-256 `e75af3ffbcf76a36a567188d88d132adf3c7484c53c20a3a083cb1d222025511`（与 v1.0.4 起**同一张**）
- `applicationId` 未变、`minSdk 24` / `targetSdk 36` 未变

## 13.5 v1.7.0 的未验证项与已知问题（如实汇总）

1. **P0 的 150px/100ms 极短快扫**在 adb 注入下仍会弹回；真人手指未手测。
2. **注入式手势偶发不生效**（同参数同状态，控制栏慢速上滑 3 次成功 2 次），未完全归因；真人手测无此反馈渠道。
3. **P1「转回竖屏自动退出」未真机验证**（设备平放无法物理旋转）。
4. **P2 浅色主题**只有代码级对比度核算，没有真机截图复核；「跟随封面/跟随系统」取色也一样。
5. **P2 的开关双回调**（无害，已用像素比对验证净效果），若要根治得改 Kanesumi（会破坏发布可复现性）。
6. 本轮**没有逐项复测**与本次改动无关的老功能（无网启动、红心歌单分页、榜单、推荐卡、逐字渐变三档、yrc 覆盖率、
   媒体中心歌词、离线缓存）。这些路径本轮代码未触碰，但「没碰」不等于「验过」。
7. **S6 用的是 debug 包**（该机历史装的就是 debug 签名），不是 release 包 —— release 包在 S6 上的表现未验证
   （签名不匹配，覆盖安装被系统拒绝；按红线没有卸载）。本轮已用 **debug v1.7.0（versionCode 20）**
   在 S6 上补测了冷启、P0 手势 7/7、P1 大屏（2560×1440），见 13.4。
8. P1 的**平板/折叠屏**表现、**横屏挖孔屏**左右安全区未验证。

### ⚠️ commit 归属偏差（本轮唯一一处流程偏离，如实记录）

集成期发现的 P0 补强（吸附判定改读本地累计 `dragProgress`）**没有独立 commit**，
被 `96585e1 docs: 记录 v1.7.0 …` 一并带入（写文档时用了 `git add` 带上了已改的 PlayerCard.kt）。

- 原因：改完代码先跑真机验证，验证通过后写文档，提交时把工作区里的两个逻辑单元一起扫进去了；
- 为什么没有事后拆分：遵守红线「不 amend / rebase 已提交的 commit」；
- 影响：**只有提交粒度**。代码在仓库里、参与构建与发布，且已按 13.4 的矩阵在 PCL110/S6 上实测。







## 14. v1.8.0（T1 沉浸式 / T2 竖屏音质 / T3 音频可视化 / T4 自动旋转 / T5 S6 状态栏歌词）

**版本**：`1.8.0-gpl` / **versionCode = 21**（`aapt2 dump badging` 实测：v1.7.0 = 20，本版 = 21）。

**调研结论先行**（决定了 T3 是否落地）：
- T3 的「方案 A：Media3 `AudioListener.onAudioSamples`」**在 media3 里根本不存在**（javap 核实 1.4.1/1.5.0/1.6.0/1.7.0/1.8.x/1.9.0/1.11.1 均无该类）；「方案 B：`android.media.audiofx.Visualizer`」
  从 AOSP 7.1.1 起就要 **RECORD_AUDIO**（不是 Android 10+ 才要）⇒ 触红线，**不采用**；
  **方案 C'（自研 tee）落地**：media3 官方 `TeeAudioProcessor + WaveformAudioBufferSink` 插在应用自己的
  音频处理链上（`DefaultRenderersFactory.buildAudioSink` override），**零新增权限**，且
  MediaCodec 与 FFmpeg 两条解码路径都经过它（两者共用同一个 AudioSink 实例）。⇒ **T3 落地，未砍未降级。**
- T4 设计决策定为**选项 C**：开关开 = 跟随传感器 + 播放器界面自动进出大屏；关 = 锁竖屏 + ⤢ 手动进。
  用 `SENSOR`（不是 `USER`），**既不读也不写** `Settings.System.ACCELEROMETER_ROTATION`。

**测试矩阵（发布前实测）**

| 项 | 设备 | 结果 |
|---|---|---|
| JVM 单测 | 本机 | ✅ **181 / 181 通过**（v1.7.0 的 169 + T4 状态机 5 + T3 环形缓冲 7） |
| release / debug 构建 | 本机 | ✅ 两个都过 |
| release 覆盖升级 | PCL110 | ✅ `versionCode=21`、`firstInstallTime` 未变（登录态保留） |
| debug 覆盖升级 | S6 | ✅ `versionCode=21`、`firstInstallTime` 未变 |
| T1 沉浸式 | PCL110 + S6 | ✅ 两台都 `immersive: status bar hidden`；返回键退出 → `status bar shown` |
| T2 音质二级菜单 | PCL110 | ✅ 8 档菜单弹出并高亮「超清母带」；选「无损」后 chip 变「无损」且继续播放 |
| T2 a11y / 命中盒 | S6（uiautomator） | ✅ 音质 chip 有 `content-desc=音质偏好`、旋转开关 `content-desc=自动旋转已开启`（224×224px ≈ 74dp） |
| T3 可视化布局 | PCL110 2800×1272 / S6 2560×1440 | ✅ 封面下、歌名上，28 根包络条随音乐起伏，不溢出 |
| T3 性能 A/B | S6 | ✅ 开：485 帧 / 44.12% janky / 50th **15ms**；关：157 帧 / 78.98% / 50th **20ms** ⇒ 不劣化 |
| T4 自动进大屏（窗口路径） | S6 | ✅ 「窗口已横屏 + 展开播放器」触发进入 2560×1440 |
| T4 退出后方向 | PCL110 | ✅ auto-rotate 开 → 退出后交还传感器（保持横屏，走宽屏两栏），状态栏恢复 |
| T5 状态栏歌词 | S6 | ✅ 一次播放 **14 次**跨行重 post；通知栏标题随歌词推进（截图 + uiautomator 文本复核） |
| 浅色主题（v1.7.0 遗留未验证项） | S6 | ✅ 大屏浅色主题截图复核通过 |
| 崩溃 | 两台 | ✅ 冷启动/播放/进出大屏/切音质全程 **0 条 FATAL/ANR** |

**未验证项**：真人手持旋转（传感器路径）、T2 的 FLAC 提示（两台都有 FLAC 解码器）、S6 大屏 perfetto、
无关老功能未逐项复测、S6 用的是 debug 包。详见 `dist/RELEASE-NOTES-v1.8.0-gpl.md` 的「未验证项」节。

**一处流程说明（如实）**：「幅度改平方根映射」这条渲染改动是在 T3 落地 commit **之后**、在真机截图复核发现观感问题（RMS 常落在 0.05~0.3，线性映射像"没在动"）时补的，因此是**独立的 `feat(visualizer)` commit**，而不是并进采集那一个 —— 它是可独立回退的纯绘制改动。

## 15. v1.8.1（可视化提帧 + 平滑；「加载中」非回归核实）

**版本**：`1.8.1-gpl` / **versionCode = 22**。

### 15.1 用户反馈「帧率太低」的定位与修法

**先量基线再动手**（避免修错对象）：

| PCL110 / release 场景 | 帧率 | Janky | 50th |
|---|---|---|---|
| 库列表滚动 | — | 2.61% | 5ms |
| 竖屏播放器 + 逐字歌词 | 51fps | 0.49% | 6ms |
| **大屏 + 可视化（v1.8.0）** | **~20fps** | — | — |

⇒ 全 app 帧率健康，问题只在可视化。根因是**两层都太低**：① 重绘被限成 20fps（50ms gate）；
② 柱高硬切换、柱间无过渡。而真正的瓶颈其实是**信息速率**（20 柱/秒），单提帧率不解决问题。

修法：数据 20→30 柱/秒；重绘 20fps→**60fps**（`SDK_INT < 26` 或 `isLowRamDevice` → 30fps）；
柱高改**时间常数平滑**（起音 22ms / 回落 130ms，`1-exp(-dt/tau)`，与刷新率无关）。

**实现期被单测抓到的真实缺陷**：重绘判据原本写成「本帧位移 > epsilon 才重绘」，回落尾段位移越来越小，
`pump` 在柱子还停在 **~1.5%** 时就报"不用重绘" ⇒ **暂停后柱子冻在非零值**。改成「这一根还没到位就继续重绘」
+ 收敛截断后，衰减 45 帧精确归零、之后不再重绘。

**实测（25s 窗口）**

| 设备 | v1.8.0 | v1.8.1 |
|---|---|---|
| PCL110 大屏 | ~20fps | **61fps / Janky 0.00%**；相隔 150ms 两帧在波形区有 ~1000/14000 采样点变化（柱间确实在动） |
| S6 大屏 | 20fps / 44.12% / 50th 15ms | **29.2fps / 20.99% / 50th 13ms**（三个指标都更好） |

单测 184/184（新增「平滑与刷新率无关」「起音快于回落」「收敛后不再重绘」等）。

### 15.2 用户报「用户页长时间显示加载中」——**核实为非回归，未改代码**

用户随后自行确认为网络问题并作废。核实留证：

- `UserScreen.loadProfile()` 有 `finally { isLoadingProfile = false }` ⇒ 「一直转圈」只能是**请求本身慢**；
- 共享 OkHttp **read timeout = 30s** ⇒ "约半分钟"正好一个超时窗口；
- 同一账号同一 WiFi 下 S6 4 秒内出结果 ⇒ 服务端/链路侧。

**结论：v1.8.0 无相关回归。** 唯一可讨论的是「加载中无超时/失败提示」，属既有设计，本轮不动。
