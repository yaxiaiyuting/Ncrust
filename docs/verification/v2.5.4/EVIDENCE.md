# EVIDENCE.md · v2.5.4-gpl

> **产物**：`app/build/outputs/apk/release/app-release.apk`（`versionCode = 45` / `versionName = "2.5.4-gpl"`）
> **溯源**：HEAD `da5c9f8` + 本版工作区改动（`git status` 见 §7）
> **设备**：S6 `SM-G9209`（Android 7.0 / API 24 / arm64 / 已 root）、
> Cuttlefish `aosp_cf_x86_64_only_phone`（API 37，**本版把显示改成 sw800dp 横屏当平板用**，见 §3）、
> WGR-W09 平板（Android 12 / API 31，**本版大部分时间处于锁屏，未能进入**，见 §6）
> **铁律 3**：本文件**分开写**「单测覆盖了什么」与「真机验证了什么」，
> 并且**不用单测冒充真机证据**。

---

## 1. 自动化测试（可复现）

| 项 | 命令 | 结果 |
|---|---|---|
| 单元测试 | `./gradlew :app:testDebugUnitTest` | **全绿**，`1209 tests completed, 0 failed`（本版新增 5 个测试类、63 例） |
| lint | `./gradlew :app:lintDebug` | **无 Error / Fatal**（`lint-results-debug.html`） |
| 构建 | `./gradlew :app:assembleDebug :app:assembleRelease` | **BUILD SUCCESSFUL** |
| benchmark 模块 | `./gradlew :benchmark:assembleDebug` | **BUILD SUCCESSFUL** |

新增/改动的测试类与用例数：

| 文件 | 例数 | 覆盖 |
|---|---|---|
| `library/SearchHistoryCodecTest` | 17 | 三种落盘形状（稳定名 / 真机 `a`~`e` / 未知 key 按声明顺序）、真机 v1 样本、坏条目逐条丢弃、`id<=0` 丢弃、缺 timestamp 补 0、key 名单自证、`Gson` 反射为何不够用 |
| `library/SearchHistoryMigrationTest` | 15 | bit62 推断、显式 source 优先、读不懂的 source 回落、去重键三态、`isIncomplete` 与 `isResolvable` 同源、重建载荷 |
| `qq/QqFallbackStatsTest` | 20 | 计数口径、比率分母、结论串、落盘迁移（缺字段→null→0）、seed 续算、**8 线程 × 1000 次并发不丢计数**、兜底判据 `isSynthesizedQqId`、单例去重 |
| `ui/player/PlayerLayoutVisualizerTest` | 11 | **六格 A/B 矩阵**、开关关闭时任何形态都不挂载、大屏模式短路语义、高度夹取 |
| `ui/player/TrayLyricTest` | 11 | 取行边界（前奏 / 行首 / 跨行前一刻 / 末行 / 空白行）、**行级节流（51 个采样点只产出 5 次新值）**、与面板同源 |
| `ui/i18n/StringsConstructorBudgetTest`（改） | — | 精确值 128 → **129**（`searchHistoryLegacyHint`），dex 槽位 136，余量 119 |

---

## 2. 真机：S6（SM-G9209 / Android 7.0 / API 24）

安装：`adb install -r app-release.apk` → `Success`（覆盖 v2.5.2 / versionCode 43 → 45）。
`dumpsys package` 自证：`versionCode=45 versionName=2.5.4-gpl`。

### 2.1 搜索历史音源修复（特性 B）

| 步骤 | 事实 | 证据文件 |
|---|---|---|
| 升级后冷启 | 旧形状的历史**一条没丢**（`search_history.xml` 仍是 `{"a":…,"e":…}`，App 正常读取并渲染出两条） | 本地取证（见 §5 的 XML 原文） |
| 打开搜索页 | 两条同名历史都在（一条 QQ 一条网易云 —— 这正是原 bug 的现场） | `s6-search-history-list.png` |
| **点第一条（QQ 那条）** | 弹出 `这条记录来自旧版本，缺少音源标识，已为你重新搜索`，并把标题填回搜索框重搜 | `s6-search-history-legacy-qq-toast.png` |

**这为什么能证明音源被正确恢复了**：`isIncomplete(item)` 为真**当且仅当**
`effectiveSource(item) == QQMUSIC && sourceId == null`。而老条目里 `source` 是 `null`，
所以它只能来自 **bit62 推断**。若推断没生效（`source` 缺失被当成网易云），
`isIncomplete` 会是 false，点击就会走 `onSongClick` 直接入队播放 —— 观察到的是弹窗，
所以推断确实生效了。这是**行为级**的证据，不是「看角标」那种主观证据。

> **已知不足（如实记录）**：`SearchHistoryItemCard` **不显示音源角标**，
> 所以用户在列表里仍然分不出哪条是 QQ、哪条是网易云。本版修的是「恢复正确 + 不静默失败」，
> 没有改历史列表的视觉。列在 §6 的未验证/未做里。

### 2.2 竖屏托盘改版（特性 E）

播放《残酷な天使のテーゼ》（网易云，有逐行歌词），收起播放器，间隔 12 秒取两张：

| 观察点 | 结果 |
|---|---|
| 第一行 = 实时歌词 | 12 秒后从 `残酷な天使のように 少年よ神話に…` 变成 `蒼い風がいま 胸のドアを叩いても` —— **跟随播放按行变化** |
| 第二行 = 歌名 + 作者 + 音源 | `残酷な天…`（歌名，主视觉、省略）`高橋洋子`（作者）`网易云`（角标） |
| 长歌词 | `maxLines = 1 + Ellipsis`（截图里两行都带省略号） |
| 无歌词降级 | 见 §2.3：只有一行（歌名+作者+角标），不显示空行 |

证据：`s6-tray-lyric-line1-A.png` / `s6-tray-lyric-line1-B.png`（两张的托盘条带裁剪）。

**真机推翻了我自己写在 KDoc 里的一句话（已修代码 + 已改注释）**：
我原先写「空串的 `MetroText` 仍占一行高，所以托盘高度不跳」。S6 实测**空串量出来是 0 高**
（截图 `s6-player-expanded.png` 之后收起态只有一行）。已改成渲染一个**空格**并在注释里
写明理由 —— 空行会消失、歌词就绪的那一刻托盘会跳一下。

### 2.3 QQ 兜底统计（特性 C）

| 项 | 事实 |
|---|---|
| 落盘位置 | `/data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_qq_probe.xml`（**本地私有目录**） |
| 写入时机 | `Activity.onStop`（按 HOME 触发） |
| 第一次读出的 blob | `{"a":30,"b":0,…,"k":1790406025803,"l":1790406025806,"m":1}` |
| 为什么是单字母 | **又被 R8 混淆了**（本版新增规则 1 的第五个实例，这次是在本版自己新加的文件上） |
| 处置 | 给 `QqFallbackCounters` 的每个字段补 `@SerializedName` → 重新构建 → 重装 |
| 第二次读出的 blob | `{"parsedTotal":0,"parsedNoSongId":0,"parsedNoMediaMid":0,"resolveTotal":0,…,"schemaVersion":1}` |
| 迁移代价（如实） | 修好之前那份 `{"a":30,…}` 被新版本读成全 0 —— 这正是「字段名是跨版本契约」的现场演示。**v2.5.4 是第一个带计数的发布版**，所以用户侧没有真实的数据连续性损失 |
| **本次真机样本的结论** | 第一份样本里 **`parsedTotal = 30`、`parsedNoSongId = 0`** —— 解析了 30 首 QQ 曲目，**没有一首**触发「无 songid」散列兜底 |
| 网络出口 | 新增代码里 `grep -rn "RetrofitClient\|QqClient\|HttpURLConnection" QqFallbackStats.kt QqProbeStore.kt` → **0 命中** |

**关于「跑 1~2 天」**：本版交付的是**短窗口真机样本 + 可导出的统计文件 + 读出入口**。
1~2 天的长期窗口**没有跑**，如实列在 §6。另外一个更根本的不确定性也照实写：
探针在真实 QQ 搜索响应里**没有观察到「服务端不下发 `id`」的样本**，
所以分子有可能天然恒为 0 —— 这需要更长的窗口或多个入口才能定论。

---

## 3. 真机：平板形态的波浪条 A/B（特性 D）

### 3.1 为什么不是 WGR-W09

WGR-W09 在本版执行期间**处于锁屏**（`mWakefulness=Asleep` → 唤醒后停在
「未识别成功，双击屏幕重试」的锁屏界面）。我没有用户的解锁凭据，
**不绕过锁屏**，因此改用一个**显示参数与它逐项相同**的模拟器做 A/B：

| 参数 | WGR-W09（实测） | Cuttlefish（本版设置） |
|---|---|---|
| `smallestScreenWidthDp` | 800 | **800** |
| 横屏 `screenWidthDp × screenHeightDp` | 1280 × 768 | **1280 × 800** |
| `densityDpi` | 320 | **320** |
| orientation | landscape | **landscape** |
| API | 31 | 37 |

两者命中的是**同一条布局分支**（`isWidePlayer` 为真、`bigScreenActive` 为假），
而本版改动就是这条分支上的挂载判据 —— 所以这个 A/B 对**谓词**是充分的。
**它不是 WGR-W09 的真机证据**，这一条如实列在 §6。

### 3.2 A/B 结果

| 组 | 构建 | 布局 | 波浪条 |
|---|---|---|---|
| **修前** | WGR-W09 + `v2.5.2-gpl`（真机，`docs/verification/v2.5.4/verification/wgr-landscape-player-BEFORE-v2.5.2.png`） | 宽屏两栏 | **无**（封面与歌名之间是空的） |
| **修后** | Cuttlefish sw800dp-land + `v2.5.4-gpl`（`cf-tablet-land-landscape-player-AFTER-v2.5.4.png`） | 宽屏两栏 | **有**（封面与歌名之间出现 28 根最小高度的柱子 = 那条绿色虚线，`cf-tablet-land-visualizer-bars-AFTER-crop.png`） |

**柱子是平的、不是随机高度，这是预期的**：可视化只在 ExoPlayer 真的在解码时才有数据，
而模拟器上没有在播的音频流（`WaveformStore` 收到的是一串 0 ⇒ 每根柱子取最小高度 1dp）。
也就是说这次 A/B 证明的是**「挂载发生了、位置与横屏大屏左栏一致」**，
**没有**证明「平板横屏下柱子会随音乐跳动」——后者列在 §6 的未验证清单里。

`AudioVisualizerBars` 本身「有数据就画」这一点由既有的
`WaveformRingTest` / `TransparentWaveformSinkTest` 覆盖（与 v1.8.0 逐字节相同，本版未改）。

### 3.3 其余五格（不回归）

由 `PlayerLayoutVisualizerTest` 的六格矩阵逐个钉住（§1）；
手机竖屏与手机横屏的**真机**形态在 S6 上确认过没有出现波浪条
（`s6-player-expanded.png` 是手机竖屏展开态，封面与歌名之间没有任何柱子）。

---

## 4. Macrobenchmark（release 包，铁律 16）

| 项 | 值 |
|---|---|
| 被测包 | `app/build/outputs/apk/release/app-release.apk`（R8 + 签名，**非 debuggable**） |
| 驱动 | `benchmark/run_benchmark.sh all`（不重装、不卸载被测应用） |
| Benchmark APK | `:benchmark:assembleDebug` → `benchmark-debug.apk`（arm64 资产） |
| 设备 | S6（API 24 / arm64） |
| 场景 | `StartupBenchmark`（冷启 8 迭代）、`HomeScrollBenchmark`（6）、**`SettingsScrollBenchmark`（本版新增，6）**、`ExpandPlayerBenchmark`（HOT，6） |
| 指标 | `StartupTimingMetric` / `FrameTimingMetric`（`frameDurationCpuMs` p50/p90/p95/p99 + jank） |
| 原始数据 | `docs/verification/v2.5.4/verification/benchmark/`（`*-benchmarkData.json` + perfetto trace，取自设备 `/storage/emulated/0/Android/media/com.takahashirinta.ncrust.benchmark/`） |

**基线状态**：本版之前**没有任何 release 基线**（`benchmark/output/` 不存在，全盘 0 个
`benchmarkData.json`）。所以本版**建立**基线，**不做**版本间 Δ 比较。

**归因边界（不许含糊）**：转发属性是否带来开销，本版的证据是**产物级**的
（120 条转发属性在 release 里 0 条残留方法体，见 `probe-forwarding-attr.md` §2），
**不是**因果实验。因果归因需要一份「直读对照包」，本版不做（理由见 CHANGELOG 的特性 A 一节）。

> **数据有效性备注（如实）**：本轮基准执行期间，我在同一台 S6 上做过搜索/播放操作
> （见 §2），可能与 benchmark 的迭代互相干扰。受影响的具体场景与重跑结果记录在
> `docs/verification/v2.5.4/verification/benchmark/RUN-NOTES.md`。

---

## 5. 落盘格式取证（真机 XML 原文）

`/data/data/com.takahashirinta.ncrust/shared_prefs/search_history.xml`（升级前，v2.5.2 写入）：

```xml
<string name="songs">[{"a":4611686018530183086,"b":"残酷な天使のテーゼ",
"c":"https://y.qq.com/music/photo_new/T002R500x500M000000pmPam3gTtA5_1.jpg",
"d":"高橋洋子","e":1790344822158},
{"a":657666,"b":"残酷な天使のテーゼ",
"c":"http://p1.music.126.net/Mn6LGBzwfGW1GOFC_lg7sw==/109951172618401349.jpg",
"d":"高橋洋子","e":1790344385651}]</string>
```

两条同名同艺人、音源不同 —— 这是原 bug 的真实现场；
第一条的 `a = 4611686018530183086 = 2^62 + 102795182`（bit62 置位 ⇒ QQ）。

`mapping/release/mapping.txt`（v2.5.3 构建产物，与上面逐字对得上）：

```text
com.takahashirinta.ncrust.library.SearchHistoryManager$HistoryItem -> G4.k:
    long id -> a
    java.lang.String title -> b
    java.lang.String coverUrl -> c
    java.lang.String subtitle -> d
    long timestamp -> e
```

`mapping/release/mapping.txt`（**v2.5.4** 构建产物 —— 修完之后前五个字母没变，
所以即使 codec 的旧形状分支失效，老数据仍然读得出来；这是第二道防线）：

```text
com.takahashirinta.ncrust.library.SearchHistoryManager$HistoryItem -> G4.m:
    long id -> a
    java.lang.String title -> b
    java.lang.String coverUrl -> c
    java.lang.String subtitle -> d
    long timestamp -> e
    java.lang.String source -> f
    java.lang.String sourceId -> g
    java.lang.String mediaId -> h

com.takahashirinta.ncrust.cache.OfflineTrack -> F4.h:      ← 与本版无关的结构，字母表逐字未变
    long songId -> a
    …（a~i 九个字段全部保持）
```

**最后一条是本版最重要的回归证据**：本版往 `HistoryItem` 里加了 3 个字段，
而 `cache.**`（`ncrust_offline/tracks`）的混淆字母表**一个字母都没动** ⇒
老用户的离线曲目索引在升级后照常可读。这正是「不加 `-keep class …library.**`」这个
取舍想要保住的东西（加 keep 会改全局映射）。

---

## 6. 未验证 / 未做（不许用单测或推断冒充）

### 6.1 未真机验证

| # | 项 | 为什么没做 | 需要什么才能做 |
|---|---|---|---|
| 1 | **WGR-W09 平板横屏波浪条 A/B** | 设备在本版执行期间锁屏，我没有解锁凭据，**不绕过锁屏** | 用户解锁平板后重跑 §3.2 的两步 |
| 2 | **平板横屏下波浪条随音乐跳动** | 模拟器上没有在播的音频流；平板又进不去 | 平板解锁 + 联网播放一首歌 |
| 3 | **平板竖屏不出现波浪条** | 同上（模拟器可验但是另一套配置，没跑） | 同上，转竖屏取一张 |
| 4 | **QQ 兜底统计跑满 1~2 天** | 需要真实日常使用的时间窗 | 装着 v2.5.4 日常用 1~2 天后 `adb pull` 那个 XML |
| 5 | **兜底发生率是否恒为 0** | 探针在真实 QQ 搜索响应里没观察到「不下发 `id`」的样本 | 更长的窗口，或换入口（艺人/专辑/歌单）覆盖 |
| 6 | **托盘「点作者进艺人页」** | 需要点中第二行那一段的精确坐标，本轮没做（它需要 `artist.id` 非空） | 真机点一次并确认跳到艺人页 |
| 7 | **托盘「点歌词展开并显示歌词」** | 同上 | 收起态点第一行，确认展开且面板是歌词 |
| 8 | **手机横屏（大屏模式）波浪条不回归** | 需要在 S6 上进大屏模式；本轮时间用在了建立基准上 | 点 ⤢ 后取一张 |
| 9 | **搜索历史新写入走稳定字段名** | 本轮只在 S6 上验证了「读旧数据」，没有产生一条**新的**搜索历史后再读回 | 搜一个新词 → 点结果（写入）→ 清空 → `cat` XML |
| 10 | **跨源恢复：历史里的 QQ 曲目直接播放** | 老条目缺 songmid 本来就播不了（这是有意的降级）；要验证「新条目可播」需要先产生一条新的 QQ 历史 | 同 #9 且那条是 QQ 结果 |

### 6.2 单测证明了、但**不能**推到真机的结论

- `PlayerLayoutVisualizerTest` 证明的是**谓词**；它不证明真机上 `LocalConfiguration`
  给出的 `smallestScreenWidthDp` 与预期一致（那是 §3.1 用 `dumpsys` 单独量的）。
- `TrayLyricTest` 证明的是**节流判据**；它不证明真机上歌词行真的跟着唱到的那一句走
  —— 后者由 §2.2 的截图（12 秒后换行）单独证明。
- `SearchHistoryCodecTest` 证明的是**解析形状**；它不证明 R8 在**未来**某一版里
  仍把前五个字段映射成 `a`~`e`（这一条由「写路径用 `@SerializedName`」从根上绕开）。
- `QqFallbackStatsTest` 的并发用例证明的是计数器不丢数；它不证明埋点**位置**正确
  —— 后者只有真机 blob 的数值能说明，而本轮那个数值是 30/0。

### 6.3 本轮**发现但未修**的既有缺陷（不在本版范围）

| # | 缺陷 | 证据 |
|---|---|---|
| 1 | **平板上进不了「大屏幕模式」**（⤢ 入口只存在于竖屏控制条变体） | `probe-waveform-tablet.md` §4；UI dump 里控制条只有 歌词/队列/加入库/上一首/播放/下一首/音质偏好 |
| 2 | **`PlayReporter` 没有音源闸门** —— QQ 曲目的 2^62 合成 id 会被 POST 给网易云 webLog | `probe-qq-fallback.md` §6.3 |
| 3 | **`cache.**` 的 `ncrust_offline/tracks` 仍是 R8 单字母 key**（`a`~`i`） | §5 的真机 XML 与 mapping |
| 4 | `library/LibraryManager` 的收藏/点赞用裸 id，QQ 的合成 id 会被发给网易云的 like 接口 | `probe-search-history.md` §10.2 |
| 5 | `SearchHistoryItemCard` 不显示音源角标（用户仍分不出 QQ / 网易云两条同名历史） | §2.1 的截图 |

---

## 7. 产物与源码一致性

| 项 | 命令 | 结果 |
|---|---|---|
| 工作区 | `git status --porcelain` | 只有本版改动（见下），无未跟踪的临时文件 |
| `HEAD` == 产物源码 | `git tag` 打在**构建该 APK 的那个提交**上，打之前用 `git show v2.5.4-gpl:app/build.gradle.kts \| grep version` 自证 | 见 §8 |
| versionCode 三源交叉 | `tools/next-version.sh`（带 fetch） | `MAX = 44` ⇒ 下一个可用 **45**；tag / APK badging / build.gradle 三处都是 45（`version-check.txt`） |
| APK badging | `aapt2 dump badging app-release.apk` | `versionCode='45' versionName='2.5.4-gpl'` |
| 签名 | 项目既有 keystore（`keystore.properties` → `../ncrust-release.jks`） | 覆盖安装成功（S6 从 v2.5.2/43 直升 45，签名一致） |

---

## 8. 本版产物清单

| 文件 | 说明 |
|---|---|
| `probe-forwarding-attr.md` / `probe-search-history.md` / `probe-qq-fallback.md` / `probe-waveform-tablet.md` / `probe-miniplayer.md` | 五份探针（各自带证据表与「未确认」） |
| `PROBE-SUMMARY.md` | 五问五答 + 探针推翻任务书两处前提的记录 |
| `CHANGELOG-v2.5.4.md` | 面向用户的发布说明 + 取舍 + 遗留风险 |
| `EVIDENCE.md` | 本文件 |
| `next-version.txt` / `version-check.txt` | versionCode 三源交叉证据 |
| `verification/` | 截图、benchmark 原始数据、真机 XML 导出 |
