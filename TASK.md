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

## 8. ⚠️ 待修：UI/元数据与实际音频不同步（用户 2026-09-22 报告，未修）

**症状（PCL110，v1.5.1 release）**：播放器标题 / 歌词面板 / 媒体卡片都显示下一首（如 Crucified、江南），
但耳朵里放的仍是上一首的音频。用户确认「状态栏和歌词界面对，音频不对」。

**不是 v1.5.1 引入的**：v1.5.1 只改了 metadata 的 ARTIST 文案与歌词渲染，没有动 ExoPlayer / 预载 / 切歌路径。

**下一轮从这里开始排查**：

1. PlaybackService.onSongTransitioned（ExoPlayer onMediaItemTransition 回调）里 UI 直接切到 preloadedSongId；
   若这次 transition 实际播的是旧 media item（预载 URL 是上一首的、或预载缓存 PreloadCacheEntry 的
   requestedLevel 与降级重试串了），就会出现「UI 提前、音频滞后」。
2. PlayerViewModel 的 preloadedSongId / preloadedResult / preloadedRequestedLevel 与
   PlaybackService.pendingNextTitle 的配对时机（约 292-313 行）。
3. 复现思路：连续快速切歌；或让一首歌在降档重试（handlePlaybackError 的 qualityRetryLadder）之后自然结束。
4. 抓证据：听到不同步的那一刻抓 adb logcat -d | grep -E "PlayerViewModel|SongUrlFetcher|PlaybackService"。

**临时绕过**：按一次「下一首」强制重建 media item，或 force-stop 后重进（已对用户说明）。