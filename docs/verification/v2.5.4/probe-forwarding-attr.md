# v2.5.4 · 转发属性（转发属性 / forwarding properties）运行期开销探针

> **性质**：只读调查。本轮**没有修改任何源码、没有安装任何包、没有跑任何 benchmark**。
> 所有行号以当前工作区（`app/build.gradle.kts` → `versionName = "2.5.3-gpl"` / `versionCode = 44`）为准。
> 第 4 节（测量方案）与第 5 节（判据）是**待执行**的计划，不是实测结果；本轮**没有任何实测性能数字**，
> 缺口逐条列在第 8 节「未确认 / 不确定」。

被测对象：v2.5.3 · P0 把 120 条文案从 `Strings` 主构造器搬进 `SettingsStrings`(64) / `AboutStrings`(25) /
`PlayerUiStrings`(31)，并在 `Strings` 类体里留了 120 条**同名转发属性**（连同 v2.0.0～v2.5.0 的 111 条，共 **231** 条）。

## 版本锚点（**先读这一条**）

本报告的**所有 `file:line` 都锚定 `HEAD` = `v2.5.3-gpl`**（已用 `git show HEAD:<path>` 逐条复核；
`app/build.gradle.kts` → `versionCode = 44`）。原因：本报告写作期间工作区**正在被 v2.5.4 的另一路改动并发修改**，
行号已经漂移。写作时的实测漂移：

| 文件 | HEAD（= 本报告引用） | 工作区（本次快照） | 漂移 |
|---|---|---|---|
| `ui/i18n/Strings.kt` | 1253 行，主构造参数 **128** | 1262 行，主构造参数 **129** | 第 288 行之后 **+9 行** |
| └ 新增参数 | — | `val searchHistoryLegacyHint: String,`（工作区 :295） | 加在**主构造器**上，不是组里 |
| └ `qualitySectionTitle` 转发属性 | :476 | :485 | +9 |
| └ `SettingsStrings` / `AboutStrings` / `PlayerUiStrings` 类声明 | :1031 / :1153 / :1200 | :1040 / :1162 / :1209 | +9 |
| 8 个 locale 文件 | — | 各 +1 行 | 新文案 |
| `ui/player/PlayerCard.kt` | 本报告引用的三个 a11y 锚点 :1304 / :1414 / :1488 | :1343 / :1525 / :1599 | +39 / +111 / +111 |
| `ui/screen/UserScreen.kt`、`benchmark/**` | — | **未改动**（设置页 66 个读点、`LazyColumn` :312 仍成立） | 0 |

**两条必须一起带走的后果：**

1. `StringsConstructorBudgetTest` 里那两条**精确值**断言（测试文件 :182 `assertEquals(128, …)`、:236 `128, 245 - 120 + 3`）
   在**当前工作区是红的**（实测主构造参数 = **129**）；范围断言（≤ 150、组 ≤ 120、最宽构造器 + this ≤ 255）仍然绿。
   若 v2.5.4 有意新增这条文案，按断言自己的提示同步锁定值并在提交信息里说明；否则应把它移进语义相符的组 + 补转发属性。
2. §2.4 的 R8 证据来自 **v2.5.3 的 release APK（09-26 09:16 构建）**，与当前工作区源码**已不同源**。
   建立 v2.5.4 基线前必须重跑 `:app:assembleRelease`，并用新 `mapping.txt` 重新扫一遍（转发属性条数不变 = 231，
   但主构造参数已经变成 129）。

---

## 0. 结论先行

**① 转发属性不是字段，是"每次访问都求值"的计算型 getter；但它们不分配任何对象。**
每一条的形状都是 `val X: T get() = <group>.X`（无初始化器、无 backing field、不进构造函数），
运行时 = 一次 `this.<group>` 字段读 + 一次组对象的 getter 调用。**没有任何 `new`**：字符串、lambda、
`List<String>` 都在 locale 文件的顶层 `val zhCN = Strings(...)` 里**只创建一次**（`LanguageManager.kt:12-21`），
转发 getter 只是把已有引用转手返回。所以"每帧分配 231 个对象"这种开销在结构上不可能发生。

**② 更关键的是：在 release 产物里，这些 getter 连方法体都没有了 —— R8 已经把它们内联掉了。**
对已经在本地构建好的 v2.5.3 release APK 的 R8 映射做扫描（`app/build/outputs/mapping/release/mapping.txt`）：

- 120 条转发属性名**没有一条**作为字段出现在 `Strings` 的映射块里（0/120），而 10 个组字段
  （`settings` / `playerUi` / `about` / `offline` / `source` / `playlists` / `tags` / `localPlaylist` / `queue` / `motion`）
  都在（`mapping.txt:446857` 起）；
- 120 条里**没有一条**以"真方法"（`:0:0 -> <名>`）形式存在（0/120）；其中 65 条留下了**内联帧**记录，
  形如 `mapping.txt:433970`：`68:71:java.lang.String com.takahashirinta.ncrust.ui.i18n.Strings.getQualitySectionTitle():476 -> invoke`
  —— 紧跟下一行就是它内联掉的组 getter `SettingsStrings.getQualitySectionTitle():1034`。
  也就是说调用点上执行的已经是"读 `settings` 字段 + 读 `SettingsStrings.qualitySectionTitle` 字段"两条 `iget`。

**③ 因此"转发属性的开销"在 release 包里的理论上界是「每次访问 2 条 iget」**，
而不是"一次虚方法调用"。可测量的真实成本只能来自两处：
(a) 编译器**没有**内联的那几条（本轮只对 120 条做了全量扫描，未逐条分类 231 条）；
(b) 读点落在 draw / 动画阶段导致**每帧**都读（本轮已核 `PlayerCard.kt` / `FullPlayerControls.kt` /
`UserScreen.kt` 的 `graphicsLayer { }` 块内转发属性读取数 = **0**，所以现状不存在每帧读取路径）。

**④ 现有的 release 性能基线：没有。** `benchmark/output/` 目录不存在（`.gitignore:25` 忽略），
全盘搜不到任何 `*-benchmarkData.json` 或 `*.perfetto-trace`；`docs/verification/v2.5.3/` 下没有 benchmark 产物、
也没有 `verification/` 子目录；`docs/verification/v2.5.3/EVIDENCE.md:286` 自己把"转发属性运行期开销"列为本轮**未做**的缺口，
指向的正是 macrobenchmark（必须 release 包）。**结论：v2.5.4 要建立第一条 release 基线。**
仓库里唯一的 release 帧时间数据是 **v2.5.1** 的 `dumpsys gfxinfo framestats`（release 42 / S6），
精度 1ms、且不是 v2.5.3（见 §3.4）。

**⑤ 要对 benchmark 套件补什么**（详见 §3.5–§3.7）：
- **必须新增** `SettingsScrollBenchmark`（设置页滚动）—— 这是**转发属性最密**的面（`UserScreen.kt` 一处就有 66 个扁平读点），
  而现有三个 benchmark 里**没有任何一个**覆盖它；
- **已有、需一并跑**：`HomeScrollBenchmark`（首页列表滚动）、`ExpandPlayerBenchmark`（播放器展开/收起）、
  `StartupBenchmark`（冷启动）；但注意 **`HomeScreen.kt` 对搬家后的 120 条文案读取数 = 0**，
  所以首页滚动对"转发属性"这个变量**几乎没有区分度**，只能当回归护栏；
- **必须新增**一个"直读对照包"（把 146 个调用点改成 `strings.settings.x` 直读、删掉类体转发属性）才能给出**因果**结论，
  否则只能比较"v2.5.3 vs v2.5.4"，那测的是两个版本的全部差异，不是转发属性。

**⑥ 判据（§5）**：三层全过才算"可忽略" —— L1 release 产物内 0 条残留方法体；L2 单次代价 × 每帧读取上界 < 1ms；
L3 S6（API 24）release 包三场景 A/B 的 `frameDurationCpuMs` p90 差值 ≤ 1.0ms 且不超噪声带 2 倍、janky% 差值 ≤ 2pp。
**任一层不过，报告必须写「未达判据、不可判定为可忽略」并给出定位与可选修法，不得用"理论上会被内联"顶替实测**（AGENTS.md:3190-3201）。

---

## 1. `Strings.kt` 全文结论

文件：`app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt`（共 **1253** 行）。

| 项 | 值 | 证据 |
|---|---|---|
| 类声明 | `data class Strings(` | Strings.kt:53 |
| 主构造器结束 | `) {` | Strings.kt:334 |
| **主构造器参数个数** | **128** | 统计 54–333 行的 `    val …` 声明；测试硬钉 `assertEquals(128, primaryParams(clazz))`（StringsConstructorBudgetTest.kt:182） |
| 类体内容 | **只有 231 条转发属性 + 注释**，无函数、无其它成员，`}` 收在 603 行 | 335–602 行非注释、非 `val` 的行只有 `603: }` |
| 转发属性总数 | **231**（= 旧 111 + 新 120），**全部**是 `val … get() = …` | 231 条 `^    val …: … get\(\) = …`，其中 settings 64 / about 25 / playerUi 31 / source 37 / localPlaylist 26 / offline 21 / playlists 17 / tags 7 / queue 3 / motion 0 |
| 嵌套组声明方式 | 都是**主构造器参数**（⇒ 真实字段，一次赋值） | `val settings: SettingsStrings,`（70）；`val playerUi: PlayerUiStrings,`（74）；`val about: AboutStrings,`（254）；另有 offline(103)/source(112)/playlists(121)/tags(128)/localPlaylist(136)/queue(150)/motion(165) |

**转发属性的确切形状**（逐字引用，四类代表）：

```kotlin
// Strings.kt:476
val qualitySectionTitle: String get() = settings.qualitySectionTitle
// Strings.kt:479 —— 非 String 类型只是返回类型不同，形状不变
val qualityOptions: List<String> get() = settings.qualityOptions
// Strings.kt:528 —— 函数类型文案（lambda）同样是"转手返回已存在的函数对象"
val nicknameLabel: (String) -> String get() = settings.nicknameLabel
// Strings.kt:543 / 571 / 601
val aboutTitle: String get() = about.aboutTitle
val prevButton: String get() = playerUi.prevButton
val clearQueue: String get() = playerUi.clearQueue
```

**是字段还是 getter？→ 计算型 getter（per-access）。** 判据有三条，互相独立：

1. **源码**：`val X: T get() = …` 只有自定义 getter、没有初始化器 —— Kotlin 不可能为它生成 backing field
   （有初始化器 + 自定义 getter 是编译错误）；
2. **构造函数**：这 231 个名字全部**不在**主构造器参数表里（128 个参数里没有它们），所以它们不参与
   `Strings` 的实例状态；`StringsConstructorBudgetTest.kt:105-106` 的 dex 槽算式也只按 128 个参数算；
3. **release 产物**：v2.5.3 release APK 的 R8 映射里，120 条新转发属性 0 条作为字段出现（见 §0-② 与 §2.4）。

**运行期代价模型**（本报告后续所有论证都基于它）：

```
strings.qualitySectionTitle
  → getQualitySectionTitle()            // 可被 R8 内联（release 里确实内联了）
  → this.settings                       // 1 次 iget（字段，数据类构造参数）
  → settings.getQualitySectionTitle()   // 1 次 iget（字段，数据类构造参数）
  = 0 次分配、0 次 hash 查找、0 次字符串构造
```

**"每次访问"发生在哪？** 编译期只发生在 composition 期（Compose 的 recompose 才会重新求值），
不在 draw/动画期 —— 本轮对 `PlayerCard.kt`（18 个 `graphicsLayer` 块）、`FullPlayerControls.kt`、
`UserScreen.kt` 做过扫描：**块内转发属性读取数 = 0**。这一点决定了"每帧成本"在现状下不存在。

**KDoc 里作者的自我陈述**（可与上面互证，但不能当证据用）：Strings.kt:38-41 写"转发属性不进构造函数，
所以既不占 dex 槽，又让 `strings.qualitySectionTitle` 这种写法继续可用"；Strings.kt:49-51 写
"`StringsConstructorBudgetTest` 会加载本类并断言主构造器参数 **< 150**"。

---

## 2. 三个新组

### 2.1 重要更正：三个组**没有独立文件**

任务前提里的 `ui/i18n/SettingsStrings.kt`、`AboutStrings.kt`、`PlayerUiStrings.kt` **不存在**。
三个 `data class` 都声明在 `Strings.kt` 文件末尾
（`ls app/src/main/java/com/takahashirinta/ncrust/ui/i18n/` 只有 `Strings.kt` + `LanguageManager.kt` + 8 个 locale 文件）。
这一点影响施工：新增/调整组字段改的都是同一个文件。

### 2.2 组规模与构造参数个数

| 组 | 声明位置 | 参数个数 | 参数区间 | 备注 |
|---|---|---|---|---|
| `SettingsStrings` | Strings.kt:1031（`data class SettingsStrings(`） | **64** | 1034–1133，`)` 在 1134 | 无默认值参数 |
| `AboutStrings` | Strings.kt:1153 | **25** | 1156–1180，`)` 在 1181 | **唯一带默认值的一条**：`val aboutDesignSystemLabel: String = "Design System",`（1169） |
| `PlayerUiStrings` | Strings.kt:1200 | **31** | 1203–1252，`)` 在 1253 | 无默认值参数 |

参考（同文件内的其它组，用于判断"哪一组新增更便宜"）：
`OfflineStrings` 21（622–657）、`SourceStrings` **57**（666–810）、`PlaylistsStrings` 17（821–856）、
`TagsStrings` 7（870–885）、`LocalPlaylistStrings` 26（897–950）、`QueueStrings` 8（977–994）、`MotionStrings` 2（1008–1013）。

### 2.3 三个组各自的转发属性条数与"扁平读点"分布

| 组 | 转发属性条数 | `app/src/main` 里**扁平**读取处数（走转发 getter） | 直读组路径（`strings.settings.x`）的处数 |
|---|---|---|---|
| settings | 64（Strings.kt:476–539） | **76** | 0 |
| about | 25（543–567） | **24** | 0 |
| playerUi | 31（571–601） | **46** | 0 |
| 合计 | **120** | **146** | **0** |

> 方法：拿 `app/src/test/resources/i18n/strings-migration-map-v2.5.3.tsv`（120 行映射表）里的旧名，
> 在 `app/src/main/**/*.kt`（排除 `Strings.kt` 与 8 个 locale 文件）里正则统计 `.<name>`，
> 并剔除 `settings.` / `about.` / `playerUi.` 前缀的命中。
> **结论：目前全仓库没有任何一处读 `strings.settings.x` 这类新路径 —— 146 个读点全部走转发 getter。**
> （146 / 76 / 24 / 46 / 66 / 44 这几个数在 `HEAD` 与写作时的工作区快照上**都实测过，完全一致**；
> `LocalStrings.current` 的 77 处也两次一致 —— 并发的 v2.5.4 改动动的是新文案与播放器卡片，没有改这些读点。）

按文件排（扁平读点）：

| 文件 | 读点数 | 是否热面 |
|---|---|---|
| `ui/screen/UserScreen.kt` | 66 | **设置页（转发属性最密）** |
| `ui/screen/AboutScreen.kt` | 24 | 关于页（非滚动热路径） |
| `ui/player/FullPlayerControls.kt` | 23 | **播放器页** |
| `ui/player/PlayerCard.kt` | 13 | **播放器卡片（含展开/收起动画所在层）** |
| `ui/player/QueueView.kt` | 5 | 播放器队列面板 |
| `ui/components/BackgroundActivityDialog.kt` | 4 | 对话框 |
| `ui/player/LyricsView.kt` | 3 | 歌词页 |
| `ui/screen/SongDetailScreen.kt` | 3 | 详情页 |
| `MainActivity.kt` / `SearchScreen.kt` / `OfflineCacheOverlay.kt` / `ThemeColorSelector.kt` | 各 1 | — |
| `ui/screen/HomeScreen.kt` | **0** | **首页列表（对搬家文案零读取）** |

### 2.4 release 产物内的证据（R8 映射）

来源：`app/build/outputs/mapping/release/mapping.txt`（67.9 MB，v2.5.3 / versionCode 44 的 `assembleRelease` 产物，
构建时间 09-26 09:16；`app/build/outputs/apk/release/output-metadata.json` 记为 `"versionCode": 44, "versionName": "2.5.3-gpl"`）。

| 断言 | 证据（mapping.txt 行号） |
|---|---|
| `Strings` 在 release 里被重命名/合并为 `O4.A` | `mapping.txt:446857` → `com.takahashirinta.ncrust.ui.i18n.Strings -> O4.A:` |
| 组字段是**真字段** | 该块内含 `com.takahashirinta.ncrust.ui.i18n.SettingsStrings settings` / `PlayerUiStrings playerUi` / `AboutStrings about` … 各 1 条 |
| 120 条转发属性**没有一条**是字段 | 对 446857–450932（Strings 块，下一类头在 450933）扫描：0/120 命中 `^    <type> <name> -> <x>$` |
| 转发 getter 已被**内联**，不作为方法存在 | 全文件扫描：120 条名以"真方法"（`:0:0 -> `）出现的次数 = **0**；其中 **65 条**留有内联帧记录（全文件里 `…i18n.Strings.get<Name>():<line>` 形式的内联帧共 **500 行**，新旧 231 条合计），例如 `mapping.txt:433970` `…Strings.getQualitySectionTitle():476 -> invoke` 紧接 `…SettingsStrings.getQualitySectionTitle():1034`；`mapping.txt:450888` `…Strings.getPlayButton():572 -> invoke` 紧接 `…PlayerUiStrings.getPlayButton():1204` |
| 对照组字段确实是字段 | `mapping.txt:445054` `java.lang.String clearQueue -> E` 属于 `PlayerUiStrings`（构造参数，Strings.kt:1252） |

> ⚠️ 边界：映射文件里的帧记录不是"完整调用图"，**未记录内联帧 ≠ 未内联**（可能只是没有位置帧）。
> 但"**0 条以方法存在**"是强结论：如果 R8 保留了 getter，它一定会在映射里有一条 `:0:0 ->` 的成员记录。
> 本轮的 0/120 只覆盖新 120 条；111 条旧转发属性只抽样（`actionAddToNext` / `networkOfflineTitle`，同样 0 字段），
> 全量分类留给 v2.5.4（见 §6-③）。

---

## 3. 逐项调查（对应任务书的 9 问）

### 3.1 已有测试钉住了什么

**`app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt`（323 行）**

| 钉住的东西 | 值 / 断言 | 行号 |
|---|---|---|
| `Strings` 主构造参数硬上限 | `private val maxPrimaryParams = 150` | 73 |
| 预警线 | `warnPrimaryParams = 140` | 81 |
| 单个组参数硬上限 / 预警线 | `maxGroupParams = 120` / `warnGroupParams = 80` | 84 / 87 |
| 受监控的家族（11 个类，加组必须同步） | `family = listOf("…Strings", "…OfflineStrings", …, "…SettingsStrings", "…AboutStrings", "…PlayerUiStrings")` | 90–102 |
| dex 槽算式 | `1 + params + (if (hasDefaults) (params + 31) / 32 else 0) + (if (hasDefaults) 1 else 0)` | 105–106 |
| 家族全部可被 JVM 加载（ClassFormatError 防线） | `Class.forName(name)` | 128–135 |
| 主构造器 ≤ 150 且最宽构造器 + this ≤ 255 且算式自洽 | `assertTrue(primary <= maxPrimaryParams)` / `widest + 1 <= 255` / `assertEquals(dexSlots(primary, hasSynthetic(clazz)), widest + 1)` | 150–166 |
| **精确值 128** | `assertEquals(128, primaryParams(clazz))`；`assertEquals(134, dexSlots(128, true))`；余量 ≥ 100 | 182 / 185 / 186 |
| 每个组自身受监控 | `n <= 120`、`widestCtor + 1 <= 255` | 211–219 |
| **三个新组规模钉死** | `SettingsStrings to 64` / `AboutStrings to 25` / `PlayerUiStrings to 31`（227–231）；`assertEquals("搬家账不对：245 - 120 + 3 应当等于 128", 128, 245 - 120 + 3)` | 236 |
| 旧转发属性可达性（**只覆盖 offline / queue**） | `assertEquals(s.offline.networkOfflineTitle, s.networkOfflineTitle)`（245–246）；`assertEquals(s.queue.actionInsertNext, s.actionInsertNext)`（258–259）；`actionAddToNext != actionInsertNext`（276–279） | 242–281 |
| tags / localPlaylist 文案非空（走扁平名，等于转发 getter） | `assertTrue(s.tagPlayable.isNotBlank())` … `s.localPlaylistAdopted("X").isNotBlank()` | 284–303 |
| motion 组 8 语言非空且不撞词（**直读 `s.motion.*`，无扁平转发属性**） | 306–322 | 306–322 |

**`app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsMigrationTest.kt`（391 行）**

| 钉住的东西 | 值 / 断言 | 行号 |
|---|---|---|
| 黄金快照 | `app/src/test/resources/i18n/strings-snapshot-v2.5.2.json`（v2.5.2 运行期逐值导出，含 lambda 用哨兵实参实调一次） | 71 / 85–89 |
| 迁移映射表 | `app/src/test/resources/i18n/strings-migration-map-v2.5.3.tsv`（**120** 行 = 64+25+31） | 92–95 |
| v2.5.2 全部路径逐值不变 | `compared > 3000` 个路径，任一语言不一致即红 | 143–170 |
| **旧 key ↔ 新分组路径逐条等值（转发属性的正确性护栏）** | `assertEquals("迁移映射表的条目数应当等于搬走的文案数", 120, mapping.size)`；组集合恰为 `{settings, about, playerUi}`；8 语言 × 120 条 `assertEquals(flat, grouped)` | **181 / 186 / 188–200** |
| 三个新组真的被填充 | 每组字段数 64/25/31 + 组 getter 非 null | 205–223 |
| 默认值回落 | `aboutDesignSystemLabel` 在 5 个语言回落到 `"Design System"`；并 `assertEquals(actual, preset.strings.about.aboutDesignSystemLabel)`（扁平 vs 组路径） | 238–258 |
| 默认值搬进了组 | `AboutStrings` 有合成构造器、主构造 25 / 合成 27；`SettingsStrings`/`PlayerUiStrings` 无合成构造器 | 270–291 |
| 8 语言路径集合完全一致 | `reference.size > 490` | 302–323 |
| 跨语言该不同的确实不同（探针含 `settings.*` / `about.*` / `playerUi.*` 路径） | 327–344 | 327–344 |
| 跨语言全同集合只增不减且新增可解释 | 359–390 | 359–390 |

**问题 3 的直接答案：**
> **有，转发属性的"取值等于组内取值"已经有正确性护栏** —— `StringsMigrationTest.kt:188-200`（120 条 × 8 语言 = **960** 次比对，
> 旧名走反射读 `Strings.getXxx()`，新名走 `settings.xxx`），外加 `:255` 对唯一带默认值的那条再钉一次。
> **但覆盖是不完整的**：111 条旧转发属性里，只有 offline 的 2 条（`networkOfflineTitle` / `networkOfflineHint`）
> 与 queue 的 2 条（`actionInsertNext` / `actionAppendToQueue`）有 `flat == group` 断言；
> `source`(37) / `playlists`(17) / `tags`(7) / `localPlaylist`(26) / offline 其余 19 条 —— 共 **107 条没有等值断言**
> （tags/localPlaylist 只断言了"非空"，而 `source`/`playlists` 连非空都没有）。

### 3.2 composable 如何消费文案

- `LocalStrings` 定义：`LanguageManager.kt:23` → `val LocalStrings = compositionLocalOf { zhCN }`，默认 `zhCN`；8 个 preset 在 `:12-21`。
- `LocalStrings.current` 调用点：**77 处**（`app/src/main/**/*.kt`）。
- Top 5 文件：`MainActivity.kt` **12**、`HomeScreen.kt` **5**、`FullPlayerControls.kt` **5**、`UserScreen.kt` **4**、`LibraryScreen.kt` / `LibraryPlaylistsTab.kt` 各 **4**。
- 写法：绝大多数是就地 `LocalStrings.current.xxx`（`val s = LocalStrings.current` 全仓只有 1 处），
  例如 `UserScreen.kt:121/851/1085/1204`、`FullPlayerControls.kt:125/555`（后者的 `:653-655` 是三个 `LocalStrings.current.qualityXxxBadge`）。
- **热面判定（结合 §2.3 的扁平读点）**：
  - **设置页 = 最热**：`UserScreen.kt` 66 个转发读点 + 整个设置页就是一个 `LazyColumn`（`UserScreen.kt:312`，`flingBehavior = rememberMetroFlingBehavior()`，`:315`）；
  - **播放器页/卡片 = 次热**：`FullPlayerControls.kt` 23 + `PlayerCard.kt` 13 + `QueueView.kt` 5 + `LyricsView.kt` 3 = **44**；其中 `PlayerCard.kt` 是展开/收起动画的宿主；
  - **首页列表 = 冷**：`HomeScreen.kt` 对搬家文案 **0** 读取（搬走的 120 条里没有首页文案）。

### 3.3 现有 macrobenchmark 套件（逐字核对）

**`benchmark/build.gradle.kts`（74 行）**

```kotlin
plugins { id("com.android.test"); id("org.jetbrains.kotlin.android") }   // :3-6
android {
    namespace = "com.takahashirinta.ncrust.benchmark"                    // :24
    compileSdk = 36                                                      // :25
    targetProjectPath = ":app"                                           // :27
    defaultConfig {
        minSdk = 24                                                      // :33
        targetSdk = 36                                                   // :34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"  // :35
    }
    packagingOptions { resources { excludes += listOf(
        "assets/trace_processor_shell_x86", "assets/trace_processor_shell_x86_64",
        "assets/trace_processor_shell_arm", "assets/tracebox_x86", "assets/tracebox_x86_64", "assets/tracebox_arm") } }  // :49-60
    experimentalProperties["android.experimental.self-instrumenting"] = true  // :64
}
dependencies { … "androidx.test.uiautomator:uiautomator:2.3.0"            // :70
               "androidx.benchmark:benchmark-macro-junit4:1.4.1" }       // :73
```

**三个 benchmark 的 `MacrobenchmarkRule` 配置**

| 类 | metrics | iterations | startupMode | compilationMode | measureBlock 内容 | 目标 |
|---|---|---|---|---|---|---|
| `StartupBenchmark.coldStart`（:27-36） | `StartupTimingMetric()` | **8** | `COLD` | `CompilationMode.DEFAULT`（:33） | `startActivityAndWait()`（:35） | 冷启动到首帧 |
| `HomeScrollBenchmark.homeScroll`（:32-57） | `FrameTimingMetric()` | **6** | `COLD` | 未指定（默认 `DEFAULT`） | `startActivityAndWait()` → `waitForIdle()` → 6×`swipe(UP)` + 3×`swipe(DOWN)`，每次 `waitForIdle()`（:39-56） | 首页列表滚动帧时间 |
| `ExpandPlayerBenchmark.expandCollapse`（:29-58） | `FrameTimingMetric()` | **6** | `HOT`（注释 :34-37 说明：避免启动帧污染 P90+） | 未指定 | `startActivityAndWait()` → `waitForIdle()` → `sleep(1500)` → `swipe(w/2, 0.92h → w/2, 0.15h, 40 步)` → `sleep(600)` → 反向 `swipe` → `sleep(400)`（:39-57） | mini bar 上拉展开 / 全屏下拉收起 |

**目标包 / Activity**：三个类都有 `private const val PACKAGE = "com.takahashirinta.ncrust"`（各文件 :12 / :17），
`measureRepeated(packageName = PACKAGE, …)`；**没有**显式 Activity →
`startActivityAndWait()` 走 launcher intent，即 `app/src/main/AndroidManifest.xml:46-56` 的 `.MainActivity`（MAIN/LAUNCHER）。

**`AndroidManifest.xml`（22 行）**：`<instrumentation android:targetPackage="com.takahashirinta.ncrust">`（:5-8）；
显式 `tools:node="remove"` 掉 `READ/WRITE_EXTERNAL_STORAGE`（:16-21，注释解释 API 33+ 的 `pm grant` 必失败）。

**输出路径约定**

- `run_benchmark.sh:139-141`：结果在**设备**上 `/storage/emulated/0/Android/media/com.takahashirinta.ncrust.benchmark/`，
  "把需要的 trace pull 回：`adb pull … benchmark/output/`"；
- `benchmark/build.gradle.kts:21`：`benchmark/build/outputs/connected_android_test_additional_output/benchmark/`（这是 `connectedCheck` 路径，脚本**不用**它，:17-19 说明了原因）；
- `.gitignore:23-25`：`benchmark/build/`、`benchmark/cookie.secret`、`benchmark/output/` 全部忽略。

**结果解析器：仓库里没有 macrobenchmark JSON 解析器。** 唯一的帧工具是
`/home/duanjb666/deepseek/ncrust-gpl/tools/framestats.py`（101 行，**解析 `dumpsys gfxinfo <pkg> framestats` 的 CSV**，
按阶段给 P50/P90/P99/max：`:3-12`），配套脚本 `/home/duanjb666/deepseek/ncrust-gpl/tools/d2-measure.sh`
（默认 serial `0715f763f54c023a` = S6；`gfxinfo reset` → 22s 窗口 → `framestats` → 喂给 framestats.py）。
`tools/` 按仓库惯例**不入 git**。→ v2.5.4 需要新写 JSON 解析器（见 §4.5）。

### 3.4 v2.5.3 有没有性能基线？→ **没有**

| 检索 | 结果 |
|---|---|
| `docs/verification/v2.5.3/` 内容 | `EVIDENCE.md` / `PROBE-SUMMARY.md` / `CHANGELOG-v2.5.3.md` / `probe-*.md` / `probe-*.py` / `probe-*.raw.txt` / `split-strings.py` / `apk-reproducibility.md` / `build-summary.txt` / `version-check.txt` / `next-version.txt` + `tools/StringsSnapshotDumpTest.kt`。**没有 `verification/` 子目录，没有任何 benchmark JSON / perfetto trace** |
| `benchmark/output/` | **目录不存在**（被 `.gitignore:25` 忽略） |
| 全盘 `find /home/duanjb666/deepseek -name "*benchmarkData.json"` / `-name "*.perfetto-trace"` | **0 命中** |
| `docs/verification/v2.5.3/EVIDENCE.md` 对 perf 的说法 | `:286`：缺口 #2「转发属性的**运行期开销**」→「111 + 120 = 231 条 getter 的理论开销是零（会被内联），但**没有做 release 包帧时间 A/B**」；建议 = macrobenchmark `ExpandPlayerBenchmark` / `HomeScrollBenchmark`（**必须 release 包**） |
| 同项在探针报告里 | `docs/verification/v2.5.3/probe-strings.md:231-232`：「拆分的**运行期开销**未测量：转发属性是普通 getter，理论上是零成本（会被内联），但**没有做 release 包的帧时间 A/B**」 |
| 仓库内唯一的 release 帧时间数据 | `docs/verification/v2.5.1/verification/s6c-*-summary.txt` + `s6c-*-framestats.txt`（**v2.5.1 / release 42 / S6 / dumpsys gfxinfo**）：滚动 407 帧、janky 84.03%、p50 18 / p90 **19** / p95 29 / p99 48 ms；展开收起 133 帧、janky 86.47%、p50 20 / p90 **27** / p95 29 / p99 38 ms。**不是 v2.5.3，也不是 macrobenchmark，且 gfxinfo 直方图只有 1ms 分辨率** |
| 设备上的现状 | S6（`0715f763f54c023a`）与平板（`WVQ6R22124000968`）装的都是 **`versionName=2.5.2-gpl` / `versionCode=43`**（不是 v2.5.3 的 44） |
| 本地产物 | `app/build/outputs/apk/release/app-release.apk` = **v2.5.3 / 44（已签名）**，可直接装来建基线，无需重新构建 |

**明确结论：不存在任何 release-build 性能基线（既无 macrobenchmark 基线，也无 v2.5.3 的 gfxinfo 基线）。v2.5.4 将建立第一条。**

### 3.5 缺口 (i)：设置页滚动 —— **未覆盖**

**现状**：三个 benchmark 没有一个进入设置页。`HomeScrollBenchmark.kt:24` 的注释甚至写着
"Ncrust 默认落在「库」tab——必须切到首页再抓纵向主列表"，说明作者知道 tab 需要切换，但只切到首页。

**要加什么**（`SettingsScrollBenchmark.kt`，放在 `benchmark/src/main/java/com/takahashirinta/ncrust/benchmark/`）：

| 需要的锚点 | 事实 | 证据 |
|---|---|---|
| 设置页**不是**导航路由 | `NavGraph.kt:25-101` 的路由只有 `home / album / artist / playlist / qqplaylist / localplaylist / song`（+ 带 source 的三段版），**没有 settings 路由** | NavGraph.kt:26-55 |
| 设置页 = 底部导航第 4 项（index 3） | `navTabs` 列表按 `Home, LibraryMusic, Search, Person` 顺序，标签取 `navStrings.tabHome/tabLibrary/tabSearch/tabUser`；默认 `selectedTab = 1`（库） | MainActivity.kt:2046-2049 / 2388-2392 / **818** |
| tab 标签文案（UiAutomator 可点） | zh-CN：`tabUser = "用户"`；en-US：`tabUser = "Profile"` | zh_CN.kt:7 / en.kt:7 |
| 设置页滚动容器 | `LazyColumn`（内容限宽 720dp、`contentPadding = BottomOverlayInsetDp`） | UserScreen.kt:312-316 |
| 页面锚点文案（可用于断言"确实到了设置页"） | `qualitySectionTitle = "音质偏好"`、`themeSectionTitle = "主题色"`、`languageSectionTitle = "显示语言"`、`storageSectionTitle = "存储与缓存"` | zh_CN.kt:16 / 51 / 57 / 59 |
| 现有 benchmark 怎么找滚动容器 | 点 `By.text("首页")`（失败回退 `By.textContains("Home")`）→ `By.scrollable(true)` 里取 `visibleBounds.height()` 最大者 | HomeScrollBenchmark.kt:60-83 |

**实现要点**：与 `findHomeVerticalList` 同法，只把"切 tab"的目标文案换成 `tabUser`，并用
`By.text("音质偏好")`（或 `themeSectionTitle`）做一次 `device.wait(Until.hasObject(...))` 断言，
避免"点错 tab 也照样滚"的假通过；`HomeScrollBenchmark` 里那段 `StaleObjectException` 重试逻辑（:45-53）要照抄
（长列表滚动中 a11y 节点会被回收）。
**前置条件**：语言必须是 zh-CN（默认）或显式回退到 `textContains("Profile")`；需登录（首页/设置页的账号块与缓存占用）；播放器 mini bar 常驻（`ExpandPlayerBenchmark` 同款前置）。

### 3.6 缺口 (ii)：播放器展开/收起 —— **已覆盖**

`ExpandPlayerBenchmark`（HOT / 6 iterations / 真实 `swipe`）就是这一项，且 v2.5.3/EVIDENCE.md:286 指定的正是它。
现有实现的**已知弱点**与可改进点：

- 走的是**坐标**（`0.92h → 0.15h`，:44-50），不校验 mini bar 是否真的有歌（注释 :20-21 只说"前置：队列非空"）；
- 仓库里其实有现成的可访问性锚点可换成选择器（更稳、且能顺带断言状态）：
  `strings.controlsHandleLabel`（`PlayerCard.kt:1304` 的 `.semantics { contentDescription = strings.controlsHandleLabel }`，
  zh-CN = "控制栏把手：向上拖收起、向下拖恢复、点按切换"，zh_CN.kt:96）、
  `strings.collapsePlayer`（`PlayerCard.kt:1488`，zh-CN = "收起"，zh_CN.kt:104）、
  `strings.playButton`（`PlayerCard.kt:1414`，zh-CN = "播放"，zh_CN.kt:85）。
- 注意 `PlayerCard.kt` 的 mini bar 是**故意**保留 `alpha` 渐隐且始终可命中的（AGENTS.md「Compose 触摸陷阱」§1），
  所以用它做锚点时"能点到"不代表"看得见"。

### 3.7 缺口 (iii)：列表滚动 —— **只覆盖了首页**

| 列表 | 是否覆盖 | 说明 |
|---|---|---|
| 首页主列表 | ✅ `HomeScrollBenchmark`（COLD / 6 iters / 6 上 + 3 下） | 但该页对搬家文案读取数 = **0**（§2.3），对转发属性几乎无区分度 |
| **设置页列表** | ❌ | 见 §3.5（最该补的一个） |
| 库页 / 歌单 tab / 详情页列表 | ❌ | 非本版重点；若要测，`LibraryScreen.kt` / `LibraryPlaylistsTab.kt` 各有 4 个 `LocalStrings.current` |
| 播放器队列面板 | ❌ | `QueueView.kt` 有 5 个 `playerUi` 扁平读点；需要先展开播放器并点队列按钮（`strings.queueButton`：`PlayerCard.kt` 队列切换） |

**小结（要加的东西）**：新增 1 个 benchmark 类（`SettingsScrollBenchmark`）；
把 `StartupBenchmark` / `HomeScrollBenchmark` / `ExpandPlayerBenchmark` 三个都纳入本轮（交叉参照 + 回归护栏）；
另外新增 1 个**直读对照包**（见 §4.2）才能做因果 A/B。

### 3.8 macrobenchmark 怎么拿到"签名的 release 包"

**重要更正（任务前提与代码不符）**：`benchmark/build.gradle.kts` 里**没有** `buildTypes`、**没有** `signingConfig`、
**没有** `targetProjectPath` 之外的构建类型声明，也**没有** `matchingFallbacks`。
它只有 `targetProjectPath = ":app"`（:27）与 `experimentalProperties["android.experimental.self-instrumenting"] = true`（:64）。
签名与 build type 完全由 **`:app` 模块 + `run_benchmark.sh`** 承担：

```kotlin
// app/build.gradle.kts
val keystorePropertiesFile = rootProject.file("keystore.properties")   // :9
if (keystorePropertiesFile.exists()) { keystoreProperties.load(…) }    // :11-13
signingConfigs { if (keystorePropertiesFile.exists()) { create("release") {
    storeFile = file(keystoreProperties["storeFile"] as String) … } } } // :20-29
buildTypes { release {
    isMinifyEnabled = true; isShrinkResources = true                   // :246-247
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")  // :248-251
    if (keystorePropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")     // :252-254
} }                                                                    // :241-256
```

```bash
# benchmark/run_benchmark.sh
BENCH_APK="benchmark/build/outputs/apk/debug/benchmark-debug.apk"      # :22
APP_APK="app/build/outputs/apk/release/app-release-unsigned.apk"       # :23
install_release() {
  ./gradlew :app:assembleRelease --console=plain -q                    # :50
  if [ -f keystore.properties ]; then signed_apk=…/app-release.apk     # :53-55  ← 本仓库走这条
  else 用临时 key 签 app-release-unsigned.apk（:57-65）
  $ADB install "$signed_apk"（签名冲突则先 uninstall 再装，:68-76）
}
ensure_bench_apk() { ./gradlew :benchmark:assembleDebug; $ADB install -r -t "$BENCH_APK" }  # :81-85
run() { $ADB shell am instrument -w -e class "$cls" \
        "$BENCH_PKG/androidx.test.runner.AndroidJUnitRunner" }          # :91-92
# 只有 startup 关系统动画；scroll/expand 必须开着（Compose 的 MotionDurationScale）  # :115-123
```

- **结论：是。** 测的是 **release 包**（R8 全量优化、签名走 `keystore.properties`），不是 `connectedCheck` 的 debug 包
  —— 与 AGENTS.md:13 的注释一致：`# Macrobenchmark (performance; drives a signed release build, not connectedCheck)`。
- `keystore.properties` **存在**（仓库根，gitignored），含 `storeFile / storePassword / keyAlias / keyPassword` 四个键（值未打印）；
  `ncrust-release.jks` 也在仓库根。
- 纪律：AGENTS.md:3190-3201 规则 1 要求"性能验证必须用 release 包"，且**必须校验设备上装的是 release**
  （"`dumpsys package | grep versionName` 不够 —— debug/release 的 versionName 相同，要看**签名**或重新安装并记录"）。
- 副作用提醒：`adb install` 会覆盖设备上的 v2.5.2（43），脚本已警告"重装后要重新扫码登录并让缓存热起来"（:77）。

### 3.9 设备约束

| 项 | 值 | 证据 |
|---|---|---|
| benchmark 模块 `minSdk` / `targetSdk` / `compileSdk` | **24** / 36 / 36 | benchmark/build.gradle.kts:33 / :34 / :25 |
| 被测 app 的 `minSdk` / `targetSdk` | 24 / 36 | app/build.gradle.kts:33-34 |
| benchmark 库的 minSdk | **23**（`<uses-sdk android:minSdkVersion="23" />`） | `benchmark-macro-junit4-1.4.1.aar` 内 `AndroidManifest.xml`（unzip 实证） |
| `CompilationMode.DEFAULT` 在 API 24 的实际含义 | `SDK_INT >= 24 ? Partial(BaselineProfileMode.UseIfAvailable, warmupIterations=0) : Full`（字节码实证） | `benchmark-macro-1.4.1.aar` 的 `CompilationMode.class` 静态初始化器 |
| 旧 API 的帧数据路径存在的迹象 | `FrameTimingMetric` 暴露 `jankyFrameCountLegacy` / `deadlineMissedFrameCountLegacy`；`MacrobenchmarkKt` 引用 atrace | 同上 AAR 的 class 常量池 |
| 当前连着的设备 | `0715f763f54c023a` = **SM-G9209 / API 24 / arm64-v8a**（目标下限机）；`WVQ6R22124000968` = WGR-W09 / API 31 / arm64-v8a；`127.0.0.1:6524` = Cuttlefish x86_64 / API 37 | `adb devices -l` + `getprop` 实测 |
| APK 的 ABI 资产裁剪 | 排除了 `trace_processor_shell_{x86,x86_64,arm}` 与 `tracebox_{x86,x86_64,arm}`，**只留 arm64** | benchmark/build.gradle.kts:49-60（注释 :46-48 说明"只保留 arm64……若要在 x86_64 模拟器上跑，把排除项换一下即可"） |

**→ 能不能在 API 24（S6 / Android 7.0）上跑？** 库层面支持（minSdk 23 < 24；模块 minSdk 24 刚好卡住下限），
`arm64` 资产也在；但**本仓库从未在 S6 上跑过 macrobenchmark**（无任何产物），且 `CompilationMode.DEFAULT` 在 API 24
会走 `Partial(UseIfAvailable, 0)`（即 `cmd package compile -f -m speed-profile` 之类），在**非 root 零售机**上是否可用**未确认**（§6-①）。
**x86_64 的 Cuttlefish 模拟器当前跑不了**（资产被裁掉），不建议为它改配置。

---

## 4. 测量方案（v2.5.4 执行清单）

### 4.1 被测对象与 A/B 设计

| 组 | 内容 | 怎么来 |
|---|---|---|
| **B（现状）** | 含 231 条转发属性的 release 包 | **现成**：`app/build/outputs/apk/release/app-release.apk`（v2.5.3 / 44）或 v2.5.4 的 release 包 |
| **A（直读对照）** | 146 个调用点改写成 `strings.settings.x` / `strings.about.x` / `strings.playerUi.x`，并**删除** `Strings` 类体里的 231 条转发属性 | 需要一次性改动（**只在测量分支工作区做，不进发布产物**）。删属性后所有漏改的调用点会**编译报错** → 调用点清单被编译器强制枚举完整 |

> 没有 A 组，就只能比"v2.5.3 vs v2.5.4"，那测的是两个版本的**全部**差异，不是转发属性。
> 若因纪律不允许改代码，则**必须在报告里写明"只建立了基线，未做因果 A/B"**，并把 §5 的 L3 标为"未完成"。

### 4.2 要跑/要加的 benchmark 类

| # | 类 | 状态 | 配置建议 | 覆盖的转发读点 |
|---|---|---|---|---|
| 1 | **`SettingsScrollBenchmark`（新增）** | ❌ 不存在 | `FrameTimingMetric()`，`iterations = 10`，`startupMode = HOT`（与 `ExpandPlayerBenchmark` 同理：避免启动帧污染 P90+）；block = `startActivityAndWait()` → `waitForIdle()` → 点 `By.text("用户")`（回退 `By.textContains("Profile")`）→ `wait(Until.hasObject(By.text("音质偏好")))` → 找设置页 `By.scrollable(true)` 最大可见高度者 → 6×UP + 3×DOWN，每次 `waitForIdle()` | **76**（settings 组，最密） |
| 2 | `HomeScrollBenchmark`（已有） | ✅ | 原样跑（COLD / 6 iters） | **0**（回归护栏） |
| 3 | `ExpandPlayerBenchmark`（已有） | ✅ | 原样跑（HOT / 6 iters）；建议把坐标 swipe 保持不动（改动会让历史可比性归零），只在报告里注明它是坐标驱动 | **46**（playerUi 组） |
| 4 | `StartupBenchmark`（已有） | ✅ | 原样跑（COLD / 8 iters）；顺带关闭 v2.5.3/EVIDENCE.md:285 的缺口 1（128 参数构造器在真机 dex/ART 上不触发 `ClassFormatError`） | 启动路径（含 `Locale` 顶层 `Strings(...)` 构造一次） |
| 5 | 可选 `QueuePanelBenchmark` | ❌ | 展开播放器 → 点 `strings.queueButton` → 滚队列 | 5（`QueueView.kt`）——优先级低 |

### 4.3 设备、轮数、指标

| 项 | 取值 | 理由 |
|---|---|---|
| 主设备 | **S6 SM-G9209 / API 24 / arm64（serial `0715f763f54c023a`）** | 项目定义的**目标下限设备**（v2.5.1 的基线也是它） |
| 次设备 | **WGR-W09 / API 31 / arm64（`WVQ6R22124000968`）** | 第二数据点；V2.5.1 曾因"只有 S6 数据"被列为缺口（v2.5.1/EVIDENCE.md:166-168） |
| 不跑 | Cuttlefish x86_64 / API 37 | 资产裁剪（benchmark/build.gradle.kts:49-60）；要跑必须先改排除项 |
| iterations | `iterations = 10` + `warmupIterations` 默认 1；**每个配置连跑 3 轮** | 现有 6/8 对 p90 偏少；3 轮用来估噪声带 |
| 帧指标 | `FrameTimingMetric` → `frameDurationCpuMs` 的 **P50/P90/P95/P99** + janky 百分比 | µs 级分辨率（gfxinfo 只有 1ms 直方图，测不出 1ms 级差异）；v2.5.1 的 p90 参考：滚动 19ms / 展开 27ms |
| 启动指标 | `StartupTimingMetric` → `timeToInitialDisplayMs` | 转发属性不在启动路径上，只作回归护栏 + 验证 128 参数类可加载 |
| 内存 | 首选 `MemoryUsageMetric(Mode.Max, listOf(SubMetric.HeapSize, SubMetric.RssAnon))`（benchmark-macro 1.4.1 确有该类：`Mode = {Last, Max}`、`SubMetric = {HeapSize, RssAnon, RssFile, RssShmem, Gpu}`，实测自 AAR 字节码）；**API 24 上是否产出未确认**（它是 `TraceMetric`，走 trace processor），失败则回退 `adb shell dumpsys meminfo com.takahashirinta.ncrust \| grep -E "TOTAL"` 每轮前后各一次 | macrobenchmark 1.4.1 **没有"分配次数/字节"指标**（`MemoryCountersMetric` 是 trace counters，也不是分配计数），所以"零分配"这一条只能靠 §7-② 的 JVM 断言 |
| 系统动画 | startup 关；scroll/expand **必须开** | run_benchmark.sh:115-123（关掉会让 Compose 动画瞬间完成，测不到真实帧） |
| 包校验 | 每轮前记录 `dumpsys package` + **签名**（`apksigner verify --print-certs`）+ APK 的 `sha256` | AGENTS.md:3197-3199：versionName 在 debug/release 相同，必须看签名 |

### 4.4 结果采集与落盘

```bash
# 0) 装 B 组（现成 v2.5.3 release 44）并记录自证
adb -s 0715f763f54c023a install -r app/build/outputs/apk/release/app-release.apk
adb -s 0715f763f54c023a shell dumpsys package com.takahashirinta.ncrust | grep -E "versionName|versionCode|debuggable"
$ANDROID_HOME/build-tools/*/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk

# 1) 跑（脚本已封装 build/install/instrument/animation 处理）
benchmark/run_benchmark.sh all          # 或分别 startup|scroll|expand；新增类用 am instrument -e class 直跑

# 2) 取回（脚本 :139-141 给的就是这条）
adb -s 0715f763f54c023a pull /storage/emulated/0/Android/media/com.takahashirinta.ncrust.benchmark/ \
    benchmark/output/s6-v2.5.3-44-forwarding/
```

**落盘位置：`docs/verification/v2.5.4/verification/`**（与 `v2.5.1/verification/` 同构）：

| 文件 | 内容 |
|---|---|
| `device.txt` | serial / model / API / ABI / 语言 / 登录态 / 构建 versionCode+versionName / APK sha256 / 签名指纹 |
| `s6-settings-scroll-{forwarding,direct}-benchmarkData.json` | 原始 JSON（A/B 各一份） |
| `s6-home-scroll-*.json`、`s6-expand-*.json`、`s6-startup-*.json` | 同上 |
| `s6-*-summary.txt` | 人读汇总（仿 `v2.5.1/verification/s6c-scroll-summary.txt` 的形态）：每轮 P50/P90/P95/P99 + janky% + 噪声带 + A/B 差值 |
| `meminfo-*.txt` | 每轮前后 PSS |
| `parse-benchmark.py` | 新增的 JSON 解析器（见下） |

**关于 Perfetto trace**：单次 trace 通常几十 MB，`benchmark/output/` 已在 `.gitignore` 里；
建议 trace 只留在 `benchmark/output/`，`summary.txt` 里记录文件名 + sha256，不进 git。

### 4.5 解析器（必须新写）

- 现状：只有 `tools/framestats.py`（解析 `dumpsys gfxinfo framestats`，不是 macrobenchmark JSON）；
  全仓 `tools/*.py|*.sh` 里没有任何 `benchmarkData` / `frameDurationCpuMs` / `startupMs` 的处理代码。
- 需要新增 `docs/verification/v2.5.4/tools/parse-benchmark.py`（与 v2.5.3 把 `probe-*.py` 放同目录的惯例一致），职责：
  1. 读 `*-benchmarkData.json` 的 `benchmarks[]`（`name` / `params` / `metrics.frameDurationCpuMs.{P50,P90,P95,P99,minimum,median,maximum}` / `metrics.startupMs…` / `context.build.{model,sdk,fingerprint}`）；
  2. 按 (类名, 场景, 配置) 聚合 **3 轮**，输出"每轮 p90 + 中位数 + 极差"；
  3. 计算 A/B 差值（Δp50/p90/p99、Δjanky%）与噪声带（同配置两轮 |Δp90| 的最大值）；
  4. 直接打印成 `*-summary.txt` 的表格。

---

## 5. 「开销可忽略」的判据（三层，**全过**才算）

| 层 | 判据 | 判定方式 |
|---|---|---|
| **L1 · 产物层**（已部分满足） | release APK 的 R8 映射里：231 条转发属性中，**以真方法（`:0:0 ->`）残留的条数 = 0**；以字段残留的条数 = 0 | 扫 `app/build/outputs/mapping/release/mapping.txt`（本报告 §2.4 已对 120 条做完：0/120；v2.5.4 需扩到 231 条） |
| **L2 · 帧预算层** | `单次转发读取代价上界 × 每帧最多读取次数上界 < 1.0ms`。<br>读取次数上界取**结构性最坏值**：设置页 `UserScreen.kt` 66 处 / 播放器四文件合计 44 处（按组算：settings 76 / playerUi 46，§2.3）。<br>单次代价上界取 JVM 微基准（§7-②）实测值（并注明 JVM ≠ ART，只作量级上界） | JVM 微基准 + 计数 |
| **L3 · 真机 A/B 层** | S6（API 24，release 包）三场景（settings 滚动 / home 滚动 / player 展开收起）：<br>① `frameDurationCpuMs` **p90 差值 ≤ 1.0ms**；<br>② 差值不超过**同配置重复跑噪声带的 2 倍**；<br>③ **janky% 差值 ≤ 2 个百分点**；<br>④ 冷启动 `timeToInitialDisplayMs` 差值 ≤ 5ms（护栏） | macrobenchmark 3 轮 × A/B，见 §4 |

> 为什么是 1ms：任务书给的目标就是 "< 1ms/frame = negligible"；同时 1ms ≈ 60fps 帧预算（16.7ms）的 6%，
> 而 S6 的实测 p90 是 19～27ms（v2.5.1），**1ms 已在这台机器的噪声量级边缘**，
> 所以 L3 必须叠加"噪声带 2 倍"这一条，不能只报一个绝对差值。

**如果判据不满足（任一层不过），报告必须写清：**

1. **明确结论句**：「未达判据 —— 转发属性的开销**不能**判定为可忽略」，不得用"理论上是零成本/会被内联"顶替实测（AGENTS.md:3190-3201 规则 1）；
2. **数字**：出问题的场景、Δp50/Δp90/Δp99/Δjanky 的绝对值、A/B 各自 3 轮的逐轮值与噪声带；
3. **定位**：结合 L1 的"残留方法体清单"与该场景的调用点清单，指出是哪几条属性/哪些调用点；
4. **可选修法**（择一或组合，并给出改后的复测数据）：
   - 把热点调用点改成直读（`strings.settings.x` / `strings.playerUi.x`）—— 146 处里挑热点即可；
   - 把同一 composition 内反复读的文案提到 composition 外一次读取（`val title = strings.qualitySectionTitle`）；
   - 若是某条 getter 未被内联，检查它是否被反射/跨模块边界引用（R8 不敢内联），必要时加 `@JvmField`/改直读；
5. **复现**：附 trace 文件名与 sha256、设备/构建自证，保证可复跑。

---

## 6. 证据表汇总

| 断言 | 证据（file:line） | 方法 |
|---|---|---|
| `Strings` 是 `data class`，主构造器 128 个参数，`SettingsStrings`/`PlayerUiStrings`/`AboutStrings` 是其中 3 个参数 | Strings.kt:53 / :70 / :74 / :254 / :334；StringsConstructorBudgetTest.kt:182 | read 全文件 + 逐行计数 |
| 类体只有 231 条转发属性，无其它成员 | Strings.kt:335-602（非注释非 `val` 行仅 `:603 }`） | awk 结构扫描 |
| 转发属性是"每次访问求值"的 getter（无 backing field） | Strings.kt:476 / 479 / 528 / 543 / 571 / 601；`^    val …: … get\(\) = …` 共 231 条 | read + 正则计数 |
| 新 120 条 = settings 64 + about 25 + playerUi 31 | Strings.kt:476-539 / 543-567 / 571-601；StringsConstructorBudgetTest.kt:227-231；StringsMigrationTest.kt:206 | 计数 + 测试断言 |
| release 产物里 120 条**没有一条**是字段、**没有一条**留下方法体，65 条有内联帧 | mapping.txt:446857（`Strings -> O4.A`）；:433970；:450888；:445054（对照：`PlayerUiStrings.clearQueue` 是真字段） | 对 67.9MB mapping 全量扫描（字段/方法/内联帧三类分类） |
| 146 个调用点全部走转发 getter，**0 处**直读 `strings.settings.x` | `app/src/main/**/*.kt`（排除 Strings.kt 与 8 个 locale 文件） | 用 TSV 的 120 个旧名做正则 + 前缀剔除 |
| 设置页是最密面（66 个读点），播放器 44，首页 0 | 按文件计扁平读点：`UserScreen.kt`=66 / `FullPlayerControls.kt`=23 + `PlayerCard.kt`=13 + `QueueView.kt`=5 + `LyricsView.kt`=3（=44）/ `HomeScreen.kt`=0 | 同上 |
| `graphicsLayer { }` 块内转发属性读取 = 0（无每帧读取路径） | PlayerCard.kt（18 个块）、FullPlayerControls.kt（0 块）、UserScreen.kt（0 块） | 花括号配对定位块 + 行号比对 |
| `LocalStrings.current` 77 处；Top5 = MainActivity 12 / HomeScreen 5 / FullPlayerControls 5 / UserScreen 4 / LibraryScreen+LibraryPlaylistsTab 4 | `app/src/main` 全量 grep | grep 计数 |
| `LocalStrings` 是 `compositionLocalOf { zhCN }` | LanguageManager.kt:23 / :12-21 | read |
| 主构造器预算 150、组预算 120、精确值 128、三组规模 64/25/31 被硬钉 | StringsConstructorBudgetTest.kt:73 / 84 / 182 / 227-231 / 236 | read |
| **转发属性取值正确性已有护栏**（120 × 8 语言 = 960 次 flat==grouped） | StringsMigrationTest.kt:181 / 186 / 188-200；默认值 :255 | read |
| 旧 111 条转发属性**多数没有**等值断言（仅 offline 2 条 + queue 2 条） | StringsConstructorBudgetTest.kt:245-246 / 258-259 | read + 逐条比对 231 条名单 |
| 三个组不在独立文件，全在 Strings.kt | `ls ui/i18n/` 只有 Strings.kt / LanguageManager.kt / 8 个 locale 文件；Strings.kt:1031 / 1153 / 1200 | ls + read |
| 现有 benchmark 只覆盖：冷启动 8 iters、首页滚动 6 iters、播放器展开 6 iters（HOT） | StartupBenchmark.kt:28-36；HomeScrollBenchmark.kt:33-38；ExpandPlayerBenchmark.kt:30-57 | read |
| benchmark 模块没有 buildTypes / signingConfig / matchingFallbacks；release + 签名由 `:app` 与脚本承担 | benchmark/build.gradle.kts:23-65（无相关块）；app/build.gradle.kts:20-29 / 241-256；run_benchmark.sh:48-78 | read |
| macrobenchmark 走 `am instrument` 驱动已装的 release 包，不重装不卸载 | run_benchmark.sh:1-19 / 91-92；AGENTS.md:13 | read |
| 输出约定：设备 `/storage/emulated/0/Android/media/<bench_pkg>/` → `adb pull` 到 `benchmark/output/` | run_benchmark.sh:139-141；.gitignore:25 | read |
| 无 macrobenchmark JSON 解析器，只有 gfxinfo 的 framestats.py | `/home/duanjb666/deepseek/ncrust-gpl/tools/framestats.py:1-13`；`tools/d2-measure.sh:10-16`；tools 下无 `benchmarkData` 处理 | read + grep |
| v2.5.3 无 release 性能基线（作者自认缺口） | EVIDENCE.md:286；probe-strings.md:231-232；`benchmark/output/` 不存在；全盘 0 个 `*benchmarkData.json` | grep + find |
| 仓库内唯一 release 帧数据是 v2.5.1/S6/gfxinfo（滚动 p90 19ms、展开 p90 27ms） | s6c-scroll-summary.txt:7-12；s6c-expand-summary.txt:7-12 | read |
| 设置页不是路由，= 底部导航第 4 个 tab（默认选中 index 1） | NavGraph.kt:25-101（无 settings 路由）；MainActivity.kt:818 / 2046-2049 / 2388-2392 | read + grep |
| 可用 UiAutomator 锚点：`"用户"`(tabUser) / `"音质偏好"`(qualitySectionTitle) / `"收起"`(collapsePlayer) / 控制栏把手 / 队列滚动容器 | zh_CN.kt:7 / 16 / 104 / 96；UserScreen.kt:312；PlayerCard.kt:1304 / 1414 / 1488 | read + grep |
| benchmark/被测 app：minSdk 24、targetSdk 36；库 minSdk 23 → API 24 库层面可用 | benchmark/build.gradle.kts:33-34；app/build.gradle.kts:33-34；AAR manifest（unzip） | read + unzip |
| x86_64 模拟器跑不了（资产被裁），arm64 设备可以 | benchmark/build.gradle.kts:49-60；`getprop ro.product.cpu.abi` = arm64-v8a（S6/平板） | read + adb |
| 三台设备现状：S6=API 24/arm64、平板=API 31/arm64、Cuttlefish=API 37/x86_64；前两台装的是 v2.5.2(43) | `adb devices -l` + `getprop` + `dumpsys package` 实测 | adb 只读 |
| benchmark-macro 1.4.1 可用的指标类：`StartupTimingMetric` / `FrameTimingMetric` / `FrameTimingGfxInfoMetric` / `MemoryUsageMetric`(Mode{Last,Max} × SubMetric{HeapSize,RssAnon,RssFile,RssShmem,Gpu}) / `MemoryCountersMetric` / `PowerMetric` / `ArtMetric`；**没有**"分配次数"指标 | `benchmark-macro-1.4.1.aar` 内 `androidx/benchmark/macro/*.class` 清单 + `javap` | unzip + javap |
| 本地有现成 v2.5.3 已签名 release 包可直接建基线 | `app/build/outputs/apk/release/output-metadata.json`（versionCode 44 / 2.5.3-gpl）+ `app-release.apk`（10,042,824 B） | read + ls |
| 报告写作期间工作区被并发修改：`Strings.kt` 主构造参数 128 → **129**（新增 `searchHistoryLegacyHint`，工作区 :295），转发属性仍 231；`PlayerCard.kt` 大改（a11y 锚点 1304/1414/1488 → 1343/1525/1599）；`UserScreen.kt` 与 `benchmark/**` 未动 | `git status --porcelain` / `git diff --stat` / `git show HEAD:<path>` 对照；工作区行号实测 | git + 计数 |

---

## 7. 值得补的纯逻辑单测

> 已存在的**不要重写**：`StringsMigrationTest.kt:179-201`（120 × 8 语言的 flat==grouped）已经是"转发属性取值正确"的护栏。
> 下面 4 条是它**没覆盖**的部分。

1. **`ForwardingPropertyCensusTest`（新增，最高价值）** —— 反射枚举 `Strings` 的所有无参 getter，
   减去主构造器参数的 getter，得到"转发属性集合"，断言：
   - 总数 = **231**，分组计数 = settings 64 / about 25 / playerUi 31 / source 37 / localPlaylist 26 / offline 21 / playlists 17 / tags 7 / queue 3 / motion 0
     （现状只有**组**规模被钉住，转发属性这一侧没有任何计数护栏，删/加一条不会变红）；
   - **每一条的取值都等于"某个组对象上同名（或映射表登记的同义名）字段"的取值**，8 语言 × 231 条。
     同义名（历史改名）用例：`playlistsSectionOwned -> playlists.sectionOwned`（Strings.kt:408）、
     `localPlaylistNew -> localPlaylist.newPlaylist`（:438）、`cacheSizeLabel -> offline.cacheSizeLabel`（:347）。
     没有这条，**"新加一条转发属性但指向了错的组"** 这种 typo 只有 120 条新文案那条路径能被抓到，
     旧 111 条里 107 条无人看守。
2. **`ForwardingAllocationTest`（新增，性能冒烟）** —— 用 `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`
   量"读 1e6 次转发属性"前后的分配增量，断言 **≤ 1KB**（即每次读取零分配）；
   同一次里记录"转发读 vs 直读组字段"的耗时比，**只打印不设硬门槛**（JVM JIT 抖动会让硬门槛 flaky）。
   这条把"不分配"从论述变成机器可验证的事实，且是 §5 L2 的输入之一。
   （`app/build.gradle.kts:268-270` 的 `unitTests.isReturnDefaultValues = true` 不影响它 —— 不碰 android.jar。）
3. **`ForwardingDrawPhaseGuard`（脚本型，不是单测）** —— 断言 `app/src/main/**/*.kt` 里
   `graphicsLayer { … }` / `drawWithContent { … }` / `Canvas` 块内**没有** `LocalStrings.current` / `strings.` 读取。
   本轮手工核过 3 个文件 = 0 命中；用脚本固化，防止将来有人在 draw 阶段读文案，把"每次 composition 一次"
   变成"每帧一次"——那才是唯一能把转发属性变成真实每帧开销的路径。
4. **（可选）`StringsConstructorBudgetTest` 扩一条**：把"组内字段名不得与 `Strings` 主构造器参数撞名"
   也断言一次 —— 目前靠 Kotlin 编译期（同一类体里重复 `val` 会编译错）兜着，
   但**跨类**的重名（组字段 vs 主构造器字段同名）不会报错，会让"扁平名到底读的是哪一个"变得含糊。

---

## 8. 未确认 / 不确定

| # | 未确认项 | 已知到什么程度 | 怎么消掉 |
|---|---|---|---|
| 1 | **macrobenchmark 能否在 API 24（S6）上真正跑通** | 本仓库**从未跑过**（无产物）；库 minSdk 23、`CompilationMode.DEFAULT` 在 API 24 = `Partial(UseIfAvailable, 0)`（字节码实证）、存在 legacy 帧字段与 atrace 引用 → 方向上支持，但**没有实测** | 在 S6 上跑一次 `StartupBenchmark`（最小成本）验通，再跑全套 |
| 2 | API 24 非 root 零售机上 `cmd package compile -f -m speed-profile` 是否可用（`CompilationMode.Partial` 的实现路径） | 未在源码/真机核实；AAR 常量池里没有 root 相关报错串 | 同上实跑；若失败，退到 `CompilationMode.None` 并在报告里注明编译模式差异 |
| 3 | `frameOverrunMs` 与 `MemoryUsageMetric` / `MemoryCountersMetric` 在 API 24 上是否产出 | 三个类在 AAR 里都存在（`FrameTimingMetric` 只读 trace 里的 `frameDurationCpuMs` / `frameOverrunMs` 两个 key，类内**没有** SDK 门禁；`MemoryUsageMetric` 是 `TraceMetric`）；但 API 24 走的是 legacy/atrace 路径，这些 key/计数器**预期**要 API 29+ 才有（**未核实**） | 实跑看 JSON 里有没有这些 key；没有就退到 `frameDurationCpuMs` + legacy janky 字段 + `dumpsys meminfo` |
| 3b | 是否存在比 `FrameTimingMetric` 更适合 API 24 的帧指标 | AAR 里另有 `FrameTimingGfxInfoMetric`（基于 `dumpsys gfxinfo`，内部 `JankCollectionHelper$GfxInfoMetric`），以及 `FrameTimingMetric` 产出的 `jankyFrameCountLegacy` / `deadlineMissedFrameCountLegacy` 字段 | 在 S6 上两种都跑一次，比一比哪个在 API 24 上真有数据 |
| 4 | 231 条是否**全部**被内联 | 只对 120 条做了全量分类（0 条残留方法体、65 条有内联帧记录）；111 条旧转发属性只抽样 2 条（`actionAddToNext` / `networkOfflineTitle`，同样 0 字段）。**"未记录内联帧" ≠ "未内联"** | v2.5.4 把 231 条一起扫 mapping，并列出"残留方法体"清单 |
| 5 | 单次读取的真实 ART 代价 | 本报告只给了"2 条 iget + 0 分配"的结构性模型，**没有任何实测数字**；JVM 微基准（§7-②）只能给量级上界，JVM ≠ ART | 跑 §7-②；若要 ART 数字，需在设备上跑 instrumentation 计时（本轮未做） |
| 6 | "每帧最多读取次数"上界 | 用的是**源码处数**（composition 读点：settings 66 / player 44），不是运行期每帧真实读取次数 | 用 Perfetto / `TraceSectionMetric` 或给文案读取加计数器才能量到真实值；本轮只声明它是结构性上界 |
| 7 | 工作区源码与 v2.5.3 release APK 是否严格同源 | 用的是工作区源码 + 09-26 09:16 构建的 `app-release.apk`（versionCode 44）；**没做** `git status` 洁净性核对；且 `docs/verification/v2.5.3/apk-reproducibility.md` 已说明该 APK **不可逐字节复现**（差异只在 VCS 戳） | 测量前记录 `git rev-parse HEAD` + `git status --porcelain` + APK sha256 三者 |
| 8 | v2.5.4 是否允许"直读对照包"（A 组） | 需要改 146 个调用点 + 删 231 条属性（**不进发布产物**）。若纪律不允许，L3 只能退化为"建立基线" | 由发布负责人拍板 |
| 9 | 平板（API 31）与宽屏（`MetroSidebar`）下的 tabUser 锚点 | 宽屏不用 `MetroBottomNav` 而用侧栏（AGENTS.md「Theming & Responsive Layout」）；本轮**没有**读 `MetroSidebar` 的 item 语义 | 若要在平板上跑 `SettingsScrollBenchmark`，先确认侧栏条目的 a11y 文案 |
| 10 | 本报告的"0 处直读 `strings.settings.x`"是基于**源码文本**统计 | 用的是正则 + 前缀剔除，不是编译期符号表 | 可以接受；若要做成护栏，见 §7-① 的反射版 |
| 11 | 行号的有效期 | 本报告锚定 `HEAD`（v2.5.3-gpl）；工作区正在被 v2.5.4 的另一路改动并发修改（本次快照：`Strings.kt` +9 行 / 129 参数、`PlayerCard.kt` a11y 锚点 +39~+111 行），见开头「版本锚点」 | 每次引用前用 `git show HEAD:<path>` 或在自己那一版的树上重新核一遍；v2.5.4 定稿后把行号整体刷新一次 |
| 12 | 主构造参数已经从 128 变成 129（工作区）对 §5 L1/L2 的影响 | 转发属性条数仍 231、分组计数不变，所以 L1/L2 的**计数**判据不变；但"dex 槽余量"从 121 降到 120，`StringsConstructorBudgetTest` 的精确值断言需要同步 | 由 v2.5.4 的发布负责人在改测试时一并处理（本报告不改代码） |

---

## 9. 复现本报告（全部只读）

```bash
cd /home/duanjb666/deepseek/ncrust-gpl/Ncrust

# 1) 源码事实
sed -n '53,60p;330,345p;474,545p;569,603p;1031,1034p;1153,1181p;1200,1206p' \
    app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt
ls app/src/main/java/com/takahashirinta/ncrust/ui/i18n/          # 确认三个组没有独立文件

# 2) 计数（128 主构造参数 / 231 转发属性 / 64-25-31 组规模）
awk 'NR>=54&&NR<=333&&/^    val [a-zA-Z]/' …/Strings.kt | wc -l
awk '/^    val [a-zA-Z][a-zA-Z0-9]*: .* get\(\) *=/' …/Strings.kt | wc -l

# 3) 测试与基准
cat app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt
cat app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsMigrationTest.kt
cat benchmark/build.gradle.kts benchmark/run_benchmark.sh benchmark/src/main/AndroidManifest.xml
cat benchmark/src/main/java/com/takahashirinta/ncrust/benchmark/*.kt

# 4) release 产物内的 R8 证据
grep -n "ui.i18n.Strings -> " app/build/outputs/mapping/release/mapping.txt
grep -n "Strings.getQualitySectionTitle():476" app/build/outputs/mapping/release/mapping.txt
grep -c "i18n\.Strings\.get[A-Za-z0-9_]*():" app/build/outputs/mapping/release/mapping.txt   # = 500（内联帧）

# 5) 设备（只读）
adb devices -l
adb -s 0715f763f54c023a shell "getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.product.cpu.abi"
adb -s 0715f763f54c023a shell dumpsys package com.takahashirinta.ncrust | grep -E "versionName|versionCode"
```
