# TASK.md —— v1.5.1 施工记录与交接

> 本文件是**任务状态与决策记录**（AGENTS.md 是知识库，二者不重复）。每轮任务结束时更新。
> 最近更新：v1.5.1 全自动执行（任务 A–E + 发布）。

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
