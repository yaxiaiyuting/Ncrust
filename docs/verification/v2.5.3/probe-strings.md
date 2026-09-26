# probe-strings.md · v2.5.3 · P0：`Strings` 现状与拆分选型

> **探针方法**：`docs/verification/v2.5.3/probe-strings.py`（可重跑）
> **原始输出**：`docs/verification/v2.5.3/probe-strings.raw.txt`
> **探针提交**：HEAD = `9e0f67e`（v2.5.2-gpl，`versionCode 43`）
> **铁律 2**：本节所有数字都从 HEAD 的源码里数出来，不采信任何转述（含本任务书的初步判断）。

```bash
python3 docs/verification/v2.5.3/probe-strings.py | tee docs/verification/v2.5.3/probe-strings.raw.txt
```

---

## 1. 现状：245 个参数 = 255 个 dex 槽，**余量 0**

两种互相独立的解析法（按顶层逗号切分取 `val` / 数深度为 1 的 `    val x:` 行）**都数出 245**：

```
解析法 A : 245
解析法 B : 245
两法一致 : 是
dex 槽位 : this(1) + N(245) + mask ceil(245/32)=8 + marker(1) = 255
上限 255 / 余量 0   ← 天花板：再加一个参数即真机启动崩
```

**任务书里「Strings 余量 245/245 = 天花板」的判断成立**，而且比字面更紧：
余量不是「还剩 245 个」，而是 **245 是已经用满的那个数**。
这正是 `AGENTS.md` v2.5.1 一节写的「余量现在是 0，必须先腾出一个位置再加组」。

> 复现 v2.0.0 · HF1 的形状：`N = 245` 时
> `zh_CN.kt` 的 `<clinit>` 里那条 invoke 已占满 255 个寄存器；
> `N = 246` 会让它在**类加载期**抛 `ClassFormatError`（编译照过、真机启动崩）。
> 单测能挡住它，是因为 `StringsConstructorBudgetTest` 显式 `Class.forName` 了 `Strings`。

---

## 2. 既有嵌套组：7 个，合计 136 个参数

| 组 | 参数数 | 定义位置 | 引入版本 |
|---|---|---|---|
| `SourceStrings` | 57 | `Strings.kt:616` | v2.1.0 · A |
| `LocalPlaylistStrings` | 26 | `Strings.kt:846` | v2.3.0 |
| `OfflineStrings` | 21 | `Strings.kt:572` | v2.0.0 · HF1 |
| `PlaylistsStrings` | 17 | `Strings.kt:770` | v2.2.0 |
| `QueueStrings` | 8 | `Strings.kt:926` | v2.5.0 · D |
| `TagsStrings` | 7 | `Strings.kt:819` | v2.3.0 |
| `MotionStrings` | 2 | `Strings.kt:957` | v2.5.1 · F |

**关键观察：前四版全都在「腾 2 花 1」**。
`Strings` 主构造器的历史轨迹是 `240 → 261（崩） → 244 → 245 → 244 → 245`，
**余量始终在 0~1 之间** —— 每加一条文案都要先搬家一次。这不是可持续的状态，
也正是本版做「真正的拆分」而不是「再挪一次」的理由。

另一个关键事实：`Strings` 类体里已经积累了 **111 条转发属性**
（`val tagPlayable: String get() = tags.playable` 这种写法）。
这意味着**「搬家不改调用点」这套机制在本仓库已经被验证过四遍**，不是新发明的风险点。

---

## 3. 调用点分布：36 个文件，全部在 `ui/` + `MainActivity`

探针先解决了一个方法论问题：`strings.source` / `strings.clear` / `strings.playlists`
这些**短名字在别的类型上也存在**（`SongItem.source`、`MutableList.clear()`）。
按「属性名匹配」统计会大量误报（第一版就报出 `source` 324 次读取）。
正确做法是先把每个文件里**确实绑定为 `Strings` 的标识符**算出来
（`val s = LocalStrings.current`、`x: Strings` 形参、以及一跳别名），
只统计这些标识符的读取。修正后：

```
消费 Strings 的文件数      : 36
被读取过的 key 总数        : 361 / 426（含组内字段与转发属性）
嵌套组参数名（7）          : localPlaylist, motion, offline, playlists, queue, source, tags
类体转发属性（111）
· 外层真参数未被读取       : 12
· 嵌套组内未被读取         : 45（多数是「组内字段名 ≠ 扁平转发名」造成的，
                             如 tags.playable 是通过 strings.tagPlayable 读的）
```

### 3.1 按消费文件（前 20，降序）

| 文件 | key 数 | 功能面 |
|---|---|---|
| `ui/screen/UserScreen.kt` | **78** | 设置页 |
| `ui/screen/ArtistDetailScreen.kt` | 29 | 艺人详情 |
| `ui/screen/AboutScreen.kt` | **25** | 关于页 |
| `ui/screen/AlbumDetailScreen.kt` | 23 | 专辑详情 |
| `ui/screen/SearchScreen.kt` | 23 | 搜索 |
| `ui/screen/HomeScreen.kt` | 22 | 首页 |
| `MainActivity.kt` | 21 | 编排 |
| `ui/components/QqPhoneLoginDialog.kt` | 21 | QQ 登录 |
| `ui/screen/LibraryPlaylistsTab.kt` | 21 | 库·歌单 |
| `ui/screen/LibraryScreen.kt` | 21 | 库 |
| `ui/screen/LocalPlaylistDetailScreen.kt` | 20 | 本地歌单 |
| `ui/screen/PlaylistDetailScreen.kt` | 18 | 歌单详情 |
| `ui/player/FullPlayerControls.kt` | 16 | 播放器控件 |
| `ui/player/PlayerCard.kt` | 16 | 播放器卡片 |
| `ui/screen/OfflineCacheOverlay.kt` | 16 | 离线缓存 |
| `ui/screen/SongDetailScreen.kt` | 15 | 单曲详情 |
| `ui/components/AddToPlaylistSheet.kt` | 14 | 加入歌单 |
| `ui/components/QqQrLoginDialog.kt` | 13 | QQ 扫码 |
| `ui/screen/QqPlaylistDetailScreen.kt` | 13 | QQ 歌单详情 |
| `ui/components/EditPlaylistDialog.kt` | 11 | 编辑歌单 |

**`UserScreen.kt` 一个文件就吃掉 78 条**，是第二名（29）的 2.7 倍 —— 这是最强的分组信号。

### 3.2 按功能面汇总

```
screen           249 keys
components        97 keys
player            36 keys
MainActivity      21 keys
theme              2 keys
```

### 3.3 高频 / 低频

| | |
|---|---|
| 高频 top 5 | `source`(32) / `actionInsertNext`(11) / `loadFailed`(10) / `close`(9) / `cancel`(9) |
| 只被读过 1 次的 key | **252 个**（占 361 的 70%） |

**「70% 的文案只在一个地方出现」这件事本身就是拆分的许可**：
低频文案搬家的影响面天然是局部的，而高频的那几条（`cancel` / `close` / `retry` /
`loading` / `back` / `loadFailed`）恰恰是**跨功能通用**的，本版**一条都不动**。

---

## 4. 语言包：**方案 B（按语言拆）被数据否决**

```
各语言文件解析到的顶层实参个数:
  zhCN 245 / zhTW 245 / en 244 / jpJP 245 / jpMY 244 / koNK 244 / deDE 244 / ruRU 244
  8 个语言文件都给的 key : 244
  ⚠ en / jpMY / koNK / deDE / ruRU 各缺 1 条: aboutDesignSystemLabel（有默认值，故意省略）
在所有 8 个语言里取值**逐字相同**的 key: 1（uidLabel）
```

**只有 1 条文案（`uidLabel = "UID: …"`）在 8 种语言里逐字相同** ——
也就是说 8 个语言文件是 8 份**实质不同**的数据，不存在「可以共享的语言包」。
按语言拆分**根本不会减少任何参数数量**（外层 `Strings` 仍然要持有全部语言），
只会在运行期多一层间接。**方案 B 否决，理由是算术，不是偏好。**

顺带钉住一条真实存在的迁移语义：
`aboutDesignSystemLabel: String = "Design System"` 是**唯一带默认值的文案**，
5 个语言文件故意省略它、靠构造器默认值回落。拆分时这条默认值必须跟着声明一起搬，
否则要么编译失败（好结果），要么把「没翻译」悄悄变成「别的语言的串」（坏结果）。

---

## 5. 三种拆分方案的评估

| | 方案 A：按功能模块 | 方案 B：按语言包 | 方案 C：组合 |
|---|---|---|---|
| 外层参数能降到 | 显著降低 | **不变**（语言维度不在外层参数里） | 同 A |
| 迁移成本 | 纯机械（有 111 条转发属性的先例） | — | 同 A |
| API 兼容 | 转发属性 ⇒ **调用点零改动** | — | 同 A |
| 单测覆盖 | 可对着「拆分前的运行时取值快照」逐值证明 | — | 同 A |
| 结论 | ✅ **采纳** | ❌ 算术上无效 | 语言维**没有可组合的东西** ⇒ 退化为 A |

**选型：方案 A**，分组以「消费文件」为唯一判据（见 §3.1）。
`ui/` 下的 screen 文件天然就是功能边界，不需要再发明一套命名法。

---

## 6. 采纳的分组（三个，合计搬走 120 条）

| 组 | 条数 | 判据（主要消费文件） | 净腾槽位 |
|---|---|---|---|
| `SettingsStrings` | 64 | `ui/screen/UserScreen.kt` 及其卫星（存储 / 背景 / 账户 / 电池 / 扫码入口） | +63 |
| `AboutStrings` | 25 | `ui/screen/AboutScreen.kt` | +24 |
| `PlayerUiStrings` | 31 | `ui/player/` 下的 `PlayerCard` / `FullPlayerControls` / `LyricsView` / `QueueView` | +30 |

**算术**：`245 − 120 + 3 = 128` ⇒ `1 + 128 + 4 + 1 = 134` 槽，**余量 121**（原为 0）。

为什么是这三个而不是更多：`screen` 面 249 个 key 里，`UserScreen`(78) 与 `AboutScreen`(25)
是**内聚度最高的两块**（各自的 key 几乎不跨文件）；剩下 146 条分散在 14 个详情页里，
每页 11~29 条，继续拆只会产生一堆 20 参数的小组、把 `Strings` 的类体撑得更长，
而**收益（余量从 121 涨到 150+）没有实际意义** —— 128 已经在验收线（<150）之内且余量充足。

**不动的**：跨功能通用的 `tabHome/tabLibrary/tabSearch/tabUser`、`cancel/close/retry/loading/back`、
`loadFailed/trackCount/playAllButton`、以及 12 条静态分析读不到的 key。
它们分散在 8+ 个文件里，「放在哪个组」没有正确答案，留原位最不容易让下一个人找错地方。

---

## 7. 迁移成本与 API 兼容（评估结论）

| 项 | 评估 |
|---|---|
| 调用点改动 | **0 处**（每搬一条补一条同名转发属性，与 `OfflineStrings` 同一套做法） |
| 语言文件改动 | 8 个文件 × 各搬 120 条实参；**纯机械**，由 `split-strings.py` 生成 |
| 拆分后主构造参数 | **128**（验收要求 < 150） |
| 余量 | 121 个槽位（原 0） |
| 单测覆盖方式 | 「拆分前逐值快照 vs 拆分后逐值」——见 §8 |
| 可重放性 | `split-strings.py` 对 v2.5.2 源码重跑得到逐字节相同的结果 |

### 7.1 为什么「搬家」必须用快照证明，而不是靠 diff 读

`Strings` 里有 **47 条 lambda 文案**（30 条扁平的 + 17 条已在组内的，形如 `(Int) -> String`），
值就是格式串里的空格与标点。**任何一次顺手的格式化都是静默的文案改动**，
而它在编译期完全看不出来。120 条 × 8 语言 ≈ 960 个字符串，人眼 diff 不可靠。

做法（本版实际执行的）：

1. 在 v2.5.2 上用反射把 8 种语言的**全部可达文案逐值导出**成
   `app/src/test/resources/i18n/strings-snapshot-v2.5.2.json`
   （494 条路径 × 8 语言，含 lambda 的**实际调用结果**，用哨兵实参 `❰s1❱` 调一次）；
2. 拆分；
3. `StringsMigrationTest` 用同一套反射导出一次，与快照**逐值比对**。

这条测试同时证明了任务书 §3.1 要的四件事：
旧 key 路径可达 / 新 key 路径可达 / 默认值回落 / 多语言一致性。

---

## 8. 未确认项（如实）

1. **`themeColorNames` 为什么不进 `SettingsStrings`**：它由 `ui/theme/ThemeColorSelector.kt`
   消费（1 次），但语义上是主题设置的一部分。探针按「主要消费文件」把它判给了 `theme` 面，
   而 `theme` 面只有 2 个 key，不够成组 —— 于是留在了外层。**这是归属上的取舍，不是遗漏。**
2. **45 条「组内未被读取」的字段**没有逐条核对调用点。原因是本版的迁移快照
   已经覆盖了「取值是否变化」，而「有没有人读」不影响搬家安全性 ——
   但**下一次清理死文案时不能直接拿这份清单当依据**（会有假阳性：
   `tags.playable` 的实际读取路径是转发名 `strings.tagPlayable`）。
3. **`Strings` 的 245 条里到底有多少条是真的死文案**：探针给出 12 条外层未读，
   但没有做「跨语言引用 / 反射引用 / 仍在文档里被承诺」的二次核对，
   **因此本版一条都没删**。
4. `LanguageManager` 的 `en-UK → en` 兼容映射、以及 `stringsForCode` 的未知 code 回落
   未在本次拆分中复核（本版没碰这两个函数）。
5. 拆分的**运行期开销**未测量：转发属性是普通 getter，理论上是零成本（会被内联），
   但**没有做 release 包的帧时间 A/B** —— 本次不要求实机验证，列入缺口清单。
