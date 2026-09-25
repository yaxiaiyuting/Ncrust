# EVIDENCE · v2.5.1

> 本文件是 v2.5.1 全部证据的**索引**。每条都指向可复现的命令或落盘文件，
> 不含任何未跑过的结论。凡是**没验证到**的，统一收在最后一节。

## 0. 一句话

`clean testDebugUnitTest lint assembleDebug assembleRelease` **全绿**
（81 类 / **1087** 单测 0 失败、lint **0 Error / 0 Fatal**）；
「页面切换动效」在 **release 包 + S6(Android 7.0) 真机**上验证了
**迁移默认开 / 可切换 / 立即生效（不重启）/ 重启后保持**四件事，
并用 `dumpsys gfxinfo framestats` 记录了 90 分位帧时间与转场开/关的 A/B。

> ⚠️ **用户在本轮中途指示停止一切设备测试**，因此有**三项真机验证未执行**（见 §6.2）。
> 这三项**没有**用别的方式替代，也**没有**写成「已验证」——它们是本版如实的缺口。

## 1. 构建与测试

| 项 | 值 | 落盘 |
|---|---|---|
| 构建 | `BUILD SUCCESSFUL in 3m 14s`（421 tasks） | [`verification/build-summary.txt`](verification/build-summary.txt) |
| 单测 | **81 类 / 1087 用例 / 0 失败 / 0 错误 / 0 跳过**（进场基线 79 类 / 1068） | 同上 |
| lint | **0 Fatal / 0 Error** / 8 Warning（全部是既有的 `ApplySharedPref`）/ 2 Information | 同上 |
| debug APK | 30,745,966 B，`versionCode=42 versionName=2.5.1-gpl`，sha256 `32fb2140…` | `app/build/outputs/apk/debug/app-debug.apk` |
| release APK | 10,042,824 B，`versionCode=42 versionName=2.5.1-gpl`，sha256 `d00d3ddb…` | `app/build/outputs/apk/release/app-release.apk` |
| 签名者 | `CN=Ncrust GPL Fork, OU=Personal, O=yaxiaiyuting, C=CN` | 同上 |

**单测增量**：+2 类 / **+19 用例**：
`ui/theme/PageTransitionSettingTest`（10）、`ui/components/SongMenuSheetLayoutTest`（8）、
`ui/i18n/StringsConstructorBudgetTest` +1。

## 2. 版本三源交叉校验（铁律 9）

命令：`bash tools/next-version.sh`（**带 `git fetch --tags --prune`**）。完整输出见
[`verification/version-check.txt`](verification/version-check.txt)。

| 源 | 值 |
|---|---|
| ① 最近 5 个 tag 指向的 `build.gradle.kts` | `v2.5.0-gpl` → **41** |
| ② `dist/*.apk` 的 `aapt2 dump badging`（唯一可信的「已发布出去」来源） | `Ncrust-v2.5.0-gpl-{debug,release}.apk` → **41** |
| ③ 仓库当前 `build.gradle.kts`（**定号前**） | **41** |
| **三源 max** | **41** ⇒ 本版 **42** |
| 本版两个产物的 badging | debug **42** / release **42** ✅ 一致 |

**为什么是 v2.5.1 而不是重打 v2.5.0 的 tag**：任务书 §5.1 允许「仅补验证 + 设置项 + 单测」时
并入 v2.5.0 draft 并重打 tag（v2.5.0-gpl 当时仍是 Draft，技术上可移动）。**没有采纳**：

1. 本仓库自己的先例 —— v2.1.1-gpl / v2.1.2-gpl 的 tag 同样只对应 draft（release 已删），
   `AGENTS.md` 记的处置是「按本项目纪律不移动已发布的 tag，所以另起一版而不是改它」；
2. v2.5.0 的 draft release 里**已经上传了两个已签名 APK**（sha256 记在 v2.5.0/EVIDENCE.md），
   重打 tag 就要用**同一个 versionCode 41** 换掉它们 ⇒ 「41」这一个号对应两份不同的产物。

代价如实记录：多占一个版本号（用户已知悉并同意）。

## 3. 特性 A：页面切换动效用户可配（默认启用）

### 3.1 实现要点（每条都有对应单测或真机证据）

| 要求 | 落地 | 证据 |
|---|---|---|
| 设置项「页面切换动效」，开关，默认启用 | `ui/theme/PageTransitionSetting.kt`（唯一读写入口） | `PageTransitionSettingTest` 10 例 + 真机迁移验证 |
| 说明文字「关闭可提升低端机流畅度」 | `MotionStrings.pageTransitionDescription`，8 语言 | `StringsConstructorBudgetTest` 新用例 |
| **加字段 = 加迁移逻辑 = 加单测** | `resolveEnabled(stored: Boolean?)`：键不存在 → 默认**开**；脏键 → 回落默认 | `PageTransitionSettingTest` 的迁移/脏键 3 例 |
| 切换后**立即生效**，不需要重启 | 状态提升在 `MainScreen`，四个转场 lambda 在导航时求值 | 真机：pid 不变（23342）而行为改变 |
| 关闭时 `PAGE_TRANSITION_MS` 走 0，不挂动画 | `durationMs(false) == 0`；`NavGraph` 直接 `EnterTransition.None` | `关闭时时长严格为 0` + 录屏单帧跳变 |
| 「用户决策」注释更新为「用户可配，默认启用」 | `NavGraph.kt` / `AppMotion.kt` 的 KDoc 都改写了完整沿革 | 源码 |
| 不引入新性能开销 | 关闭路径不构造任何 spec（不是 `tween(0)`） | 见 §4 |

### 3.2 真机迁移与开关行为（S6 / SM-G9209 / Android 7.0 / **release 42**）

| 步骤 | 观测 | 结论 |
|---|---|---|
| 升级前 | `ncrust_settings.xml` **没有** `page_transition_enabled` 键 | 这就是 v2.5.0 老用户的形状 |
| 升级后（键仍不存在） | UI 开关 `checked = **true**` | **迁移判定「键不存在 → 默认启用」在真机成立** |
| 升级后 | 盘上键**仍然不存在** | `readEnabled` 是纯读、不写回（与 KDoc 一致） |
| UI 点一下 | 盘上 → `"false"` | 写盘路径通 |
| 再点一下 | 盘上 → `"true"` | |
| **不重启**切换 | **pid 恒为 23342**，而录屏逐帧差从「连续 7~8 帧渐变」变成「单帧跳变 41.5」 | **立即生效，不是靠重启** |
| `am force-stop` + 重启 | 盘上 `"true"`，UI `checked=true` | **持久化成立** |

截图：[`screenshots/v251-s6-migration-default-on.png`](screenshots/v251-s6-migration-default-on.png)、
[`screenshots/v251-s6-setting-on-after-restart.png`](screenshots/v251-s6-setting-after-restart.png)、
转场中间帧 [`screenshots/v251-s6-transition-on-midframe.png`](screenshots/v251-s6-transition-on-midframe.png)
（详情页返回箭头与列表**同帧半透明叠加** —— 这是「真的在转场」的直接视觉证据）。

## 4. 动效流畅度量化（**release 包**，`dumpsys gfxinfo framestats`）

> **debug 包的数据一律不作为基线**（铁律 15）。本节全部数据来自
> `versionCode=42` 的 **release** 包，设备为 S6/SM-G9209（Android 7.0，**目标下限设备**）。
> 原始文件：[`verification/s6c-*-framestats.txt`](verification/) 与 [`s6c-pop-*.txt`](verification/)。

| 场景 | 90 分位帧时间 | 与 16ms / 8ms 判据 |
|---|---|---|
| 列表滚动（3 组上下滑动） | **19 ms** | 未达 60fps |
| 播放页展开/收起（3 轮，v2.5.0 的弹簧动画） | **27 ms** | 未达 60fps |
| 页面切换聚合（6 次进出，转场 **ON**） | **36 ms** | 未达 60fps |
| 页面切换聚合（6 次进出，转场 **OFF** = v2.5.0 行为） | **32 ms** | 未达 60fps |
| **仅「返回」这一步**（目标页已组合好）转场 **ON** | **34 ms**（120 帧 / 6 轮，janky 97.5%） | 未达 60fps |
| **仅「返回」这一步** 转场 **OFF** | **19 ms**（30 帧 / 6 轮，janky 60.0%） | 未达 60fps |

### 4.1 怎么读这组数（**不美化，也不夸大**）

1. **S6 在任何场景下都达不到 60fps** —— 包括**转场关闭**（= 逐字节等于 v2.5.0）的那一列。
   这是这台 2015 年设备在 Android 7.0 上的既有事实，**不是本版引入的**。
   本版没有把它变成「流畅」，也没有把它变得更差到另一个量级。
2. **转场本身的边际成本是可测的**：把场景收窄到「只测返回这一步」时，
   ON = 34ms / 120 帧，OFF = 19ms / 260… 30 帧。也就是说转场期间多画了约 **15 帧/次**，
   且这几帧更重 —— 这与「转场期间新旧两页同帧渲染」的既有判断一致。
3. **`OFF` 那一列就是本版关闭开关时的对照基线**，它证明「关掉 = 回到 v2.5.0」不是一句空话。
4. **调参实验（做过了，结论是不改）**：把转场改成**纯不透明位移**（去掉 alpha 混合，
   理由是怀疑离屏混合是瓶颈）后重测「仅返回」：90 分位中位 **34ms → 40ms，反而更差**。
   于是**回退**，本版最终保留 `fade + 1/12 屏宽位移 + 260ms`。
   这是一条**负结果**，如实记录：它说明瓶颈不在 alpha 混合，而在整页内容的绘制本身。
5. 因此**没有**按任务书 §3.1 的「若掉帧严重，调整动效参数」去调参 —— 不是跳过，
   而是**调过一轮、数据说没用**。真正能改善 S6 的是「关掉这个开关」，
   而那正是本版交给用户的那一个开关。

## 5. 遗留验证清点（v2.5.0 的缺口 → 本轮处置）

| v2.5.0 的缺口 | 本轮处置 | 结果 |
|---|---|---|
| ① 动效流畅度没有量化，且原报告用 **debug** 冷启动时间讨论性能 | 用 **release 包**在 S6 上跑 `gfxinfo framestats`，覆盖滚动 / 展开收起 / 页面切换，并做转场 ON/OFF A/B | **已关闭**（§4）；debug 数据明确不作为基线 |
| ② 灰度封面降级只有单测 | 已在 S6 上把主题色来源切到「跟随封面」并检索到目标曲目 | **未关闭** —— 用户中途指示停止测试，见 §6.2 |
| ③ 单曲循环 / LINE / INFINITY 三模式只有单测 | 未执行 | **未关闭**，见 §6.2 |
| ④ S6 菜单第 10 条是否 release-only | 在 **release 42** 上复核：滚动前可见到「转到歌手」，上滑一次后「单曲信息」落在 [224,2411][468,2486] ⇒ **10 条全部可达** | **已关闭**：该缺陷**不是 release-only**（v2.5.0 是在 debug 上抓到的），并补了纯逻辑回归单测 `SongMenuSheetLayoutTest`（8 例） |
| ⑤ Kanesumi 库内部圆角未统一 | 在 release notes 与 `AGENTS.md` 写明「上游限制」；fork 成本评估记为 v2.5.1 之后的 TODO | **已记录**（本版不做） |

### 5.1 S6 菜单回归的 release 复核（原始坐标）

```
滚动前：加入歌单[224,875] 加入本地歌单[224,1099] 加入库[224,1323] 插播[224,1547]
        最后播放[224,1771] 移除收藏[224,1995] 添加到下一首播放[224,2219] 转到歌手[224,2443]
        （转到专辑 / 单曲信息 在屏幕外）
上滑一次：加入库[224,843] 插播[224,1067] 最后播放[224,1291] 移除收藏[224,1515]
        添加到下一首播放[224,1739] 转到歌手[224,1963] 转到专辑[224,2187]
        单曲信息[224,2411][468,2486]   ← 屏幕内，可点
```

## 6. 未验证缺口清单（**如实列出，不做美化**）

### 6.1 有意不做（不是遗漏）

| 项 | 理由 |
|---|---|
| 把 **41 处存量手写 `tween`** 批量改走 `AppMotion` | 逐处判断「空间类/效果类」要读 41 处上下文，改错会让手感变化且**无法归因**；其中 `PlayerCard.kt` / `NcrustLyricsPanel.kt` 被 `AGENTS.md` 标注 load-bearing、禁止批量重构 |
| 5 处既有 `animateFloatAsState` | 同上 |
| 改 Kanesumi 库（**用户裁定**） | 会让发布产物依赖另一个仓库的未发布提交，破坏「`git clone` 即可复现」。代价：库内部的弹窗/按钮背板**仍是直角** |
| 动效参数调优 | **调过一轮**（去 alpha 混合）→ 数据更差 → 回退。见 §4.1 第 4 条 |

### 6.2 未验证到的（**本轮明确未做**）

> ⚠️ **原因**：用户在本轮中途指示「停止一切测试，做完本轮代码命令后发布」。
> 以下三项因此**没有**执行，**不是**「做了没写」，也**不是**「用单测代替」。
> 它们与 v2.5.0 的缺口状态相同（单测级证据），必须在下一轮补。

1. **灰度封面降级的真机 A/B 未做**。已完成的准备工作：S6 上把
   「主题色来源」切到**跟随封面**、检索到灰度候选曲目
   （用宿主机脚本对封面做 HSV 饱和度统计筛出，例如 Keith Jarrett《The Köln Concert》
   与 Joy Division《Closer》，最大饱和度 0.011~0.075，HCT chroma 远低于阈值 4.0），
   但**没有**播放并取样。因此「灰度封面回退默认主题、不崩溃、不闪烁、
   多角色调色板降级一致」这条验收项的证据强度仍是**单测级**。
2. **单曲循环 / LINE / INFINITY 三模式的「添加到下一首播放」真机补验未做**。
   v2.5.0 那一轮 PLC110 上跑的是随机模式（`play_mode=2`）；本版**仍未**补上这三模。
   现有证据只有 `QueueInsertTest`（含 `nextIndexAfterInsert` / `shouldPreloadAfterInsert`
   的各模式分支）。
3. **第二台设备的 gfxinfo 量化未做**。PLC110 已完成 release 42 的安装与数据保留
   （1066 首队列 / 登录态完好），但没有跑帧数据。因此 §4 的量化结论
   **只对 S6 成立**，不能外推到 PLC110 或平板。

### 6.3 既有风险（本版未引入、也未修）

4. **队列去重仍用裸 `song.id`** —— 跨源裸 id 撞号时会把其中一首当成重复。
5. **`Strings` 主构造器余量现在是 0**（245/245，`motion` 用掉了 v2.5.0 腾出的最后一个空位）。
   下一个加文案的人**必须先把一条既有文案搬进嵌套组**。`StringsConstructorBudgetTest`
   会挡住越界的那一次，但**不会**提醒你去腾位置。
6. **`jp_MY` / `ko_NK` 的 2 条新文案没有母语校对**，只按各自既有风格翻译。
