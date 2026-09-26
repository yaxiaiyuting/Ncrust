# 探针：PLC110 离线索引注入记录清理（v2.5.6 · P0 前置）

- 探针时间：2026-09-26 19:17 – 19:33（+08:00）
- 目标设备：`adb -s 3B15CD00GB700000` = OnePlus **PLC110** / Android 16 / API 36
- 目标包：`com.takahashirinta.ncrust`，实测 `versionCode=46 versionName=2.5.5-gpl`（`lastUpdateTime=2026-09-26 19:11:50`）
- 仓库：`/home/duanjb666/deepseek/ncrust-gpl/Ncrust`，HEAD `fef5e7b`（`docs(v2.5.5): 如实记录对测试设备的改动与收尾还原（逐台回读）`）
- 本探针**只读**：未改任何源码、未 commit、未删除任何设备数据。探针结束时的 `git status --short` 只有两项，均**非本探针所为**：
  - `?? docs/verification/v2.5.6/`（本报告的存放目录，即交付物本身）；
  - `?? benchmark/src/main/java/com/takahashirinta/ncrust/benchmark/BaselineProfileGenerator.kt`（**预先存在/他人并行改动**，mtime `19:31`，本探针全程未读写过该文件）。

---

## 1. 结论先行

1. **离线索引** = SharedPreferences 文件 `ncrust_offline` 里的两个 key：`tracks`（离线曲目索引，≤300 LRU）与 `urls`（key→最后一次成功播放的 URL，≤300 LRU）。两张表**必须一起删**。
2. **注入记录恰好 1 条**（不是多条）：

   | 字段 | 值 |
   |---|---|
   | `songId` | `503616` |
   | `name` | `EM10_C_Long_Premix#070705` |
   | `artist` | `鷺巣詩郎` |
   | `albumPicUrl` | `https://p2.music.126.net/TIvN4jJ_vYAc1FuDFgui3g==/109951166108834835.jpg` |
   | `durationMs` | `137160` |
   | `level` | `jymaster` |
   | `cacheKey` | `song:503616:jymaster` |
   | `completedAt` | `1790403031347`（= 2026-09-26 14:10:31.347 +08:00） |

   它在 PLC110 上**没有对应的 `urls` 条目**，也**没有任何本地音频片段**。

3. **判别依据是三重独立证据**（不是靠"名字看着像假的"）：
   - ① 文档自证：`docs/verification/v2.5.5/EVIDENCE.md:109`（"把它原样注入 PLC110 的 `ncrust_offline/tracks`（47 条 → 48 条）"）与 `:271`（"注入一条测试用的旧形状 `tracks` 条目 …… ⚠️ **未还原**"）；
   - ② 来源自证：该条与 S6 的旧形状快照 `docs/verification/v2.5.5/verification/40-s6-offline-v254-legacy-shape.xml` 里那条 `{"a":503616,…,"i":1790403031347}` **九个字段逐一相同** → 它是**从 S6 搬过来的外来记录**，不是 PLC110 播出来的；
   - ③ 物理自证：PLC110 的 SimpleCache 索引库（`databases/exoplayer_internal.db` 的 `ExoPlayerCacheIndex508cb0e0a96ccec5` 表，57 条 key）里**没有** `song:503616:jymaster`；并且在 `tracks` 的 50 条里，它是**唯一**同时满足"无缓存片段 + 无 urls 条目"的双孤儿。

4. **需要代码改动吗？——不需要（本项是设备状态清理，不是代码缺陷）。** 现成的「打开离线缓存管理即对账」路径会**精确**删掉它：`OfflineCacheOverlay.kt:128` → `OfflineAudioCache.reconcileLibrary` → `OfflineLibrary.retain(liveSongIds)`，而 `liveSongIds` 实测就是"50 条索引 − 503616"。
5. **`urls`/`tracks` 联动不变量在这里是"空满足"**：`urls` 里本来就没有 `song:503616:*`，所以清理后两张表仍然一致（无需额外补删 URL）。
6. **不要用设置页的「清除缓存」按钮**（`UserScreen.kt:300` → `OfflineAudioCache.clear`）：它会连用户**真实的 2.3 GB / 50 首**离线数据一起清掉。v2.5.5 `EVIDENCE.md:271` 里"如需清掉，走『清空离线缓存』即可"这句话在**本设备当前状态下会造成用户数据损失**，本探针明确不建议照做。
7. **LRU 不会在可预见时间内清掉它**：`tracks` 上限 300、当前 50 条，而 503616 位于插入序**第 46/50 位（靠队尾）**，LRU 从队头淘汰 —— 要把它挤到队头需要约 250+ 次新条目 upsert。**"等它被自动淘汰"不是方案。**
8. **单测**：该清理机制**已有**单测覆盖（`OfflineLibraryTest.kt:141` `` `retainSongIds 丢掉缓存里已经没有的歌` ``）。本项若**不加代码**，新增单测没有可测的新行为；若加代码，则必须按 AGENTS.md 的"加字段 = 加迁移逻辑 = 加单测"契约补测。**任务书里"要单测"这条对应的是"如果改代码"，不是"必须改代码"。**

---

## 2. 探针方法与原始证据

### 2.1 环境与可达性

| 命令 | 结果 |
|---|---|
| `adb devices -l` | `3B15CD00GB700000 device product:PLC110 model:PLC110 device:OP60EDL1` |
| `adb -s 3B15CD00GB700000 shell getprop ro.build.version.sdk/release` | `36` / `16` |
| `adb -s 3B15CD00GB700000 shell id` | `uid=0(root) … context=u:r:ksu:s0` |
| `adb -s 3B15CD00GB700000 root` | `adbd is already running as root` |
| `adb shell run-as com.takahashirinta.ncrust ls shared_prefs/` | ❌ `run-as: package not debuggable: com.takahashirinta.ncrust`（安装的是 **release** 包） |

⇒ **必须走 root 读 prefs**（设备本就是 KernelSU root，探针未做 root/unroot 改动）。

### 2.2 快照文件与"事后重放"文件是同一份（哈希自证）

```
$ md5sum 43-plc110-offline-AFTER-replay.xml \
    ncrust-gpl/Ncrust/docs/verification/v2.5.5/verification/41-plc110-offline-after-migration.xml
0dd7c47475d96b6f9384525b3a71f354  43-plc110-offline-AFTER-replay.xml
0dd7c47475d96b6f9384525b3a71f354   …/verification/41-plc110-offline-after-migration.xml
```

即父目录那份 `43-…-AFTER-replay.xml`（74806 B）与 v2.5.5 归档的 `41-…-after-migration.xml` **逐字节相同**，是 19:18 从设备拉下来的同一份。下文引用行号以 `43-plc110-offline-AFTER-replay.xml:4`（`tracks` 单行值所在行）为准。

### 2.3 设备当前状态（19:29 回读，与快照对比）

```
$ adb -s 3B15CD00GB700000 exec-out su -c "cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_offline.xml" > /tmp/live-offline-1928.xml
$ ls -la /tmp/live-offline-1928.xml      # 75638 B（19:26 落盘）
$ md5sum /tmp/live-offline-1928.xml 43-plc110-offline-AFTER-replay.xml
e1261c736870a242cd9213b29b640268  /tmp/live-offline-1928.xml     ← 已漂移
0dd7c47475d96b6f9384525b3a71f354  43-plc110-offline-AFTER-replay.xml
```

| 项 | 19:18 快照（41/43） | 19:29 回读 | 漂移 |
|---|---|---|---|
| `tracks` 条数 | **48** | **50** | +2（`1854421610`、`561105553`，均为 19:2x 新播的歌，`completedAt` 也是新值） |
| `urls` 条数 | **145** | **145** | 0（新增的两首**没有** `urls` 条目 —— 与另 6 首老条目同一现象，见 §7） |
| 503616 是否在 `tracks` 里 | 是（数组下标 46/48） | 是（下标 46/50） | 仍在，位置未变 |
| 503616 是否有 `urls` 条目 | 否 | 否 | — |
| 字段名形状 | 9 个稳定名，48/48 一致 | 同 | v2.5.5 迁移已生效且稳定 |
| 音频缓存目录 | — | `files/offline/audio` = **2.3 GB**，`530` 个文件（529 个 `*.v3.exo` + 1 个 0 字节 `508cb0e0a96ccec5.uid`），子目录 `0`–`9` | — |
| `offline_cache_mb` | — | **4096**（设置页可选上限，合法区间 64..8192） | — |

**结论：注入记录在 19:29 依然存在，未被任何自动路径清掉。**

### 2.4 SimpleCache 的"真相源"：索引数据库

`OfflineAudioCache.get()` 用 `SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes), StandaloneDatabaseProvider(app))`（`cache/OfflineAudioCache.kt:100-104`）构造，索引落在 **`databases/exoplayer_internal.db`**（不是 `files/offline/audio` 下的文件；那里只有 0 字节的 `…uid` 锁标记与 `<uid>.<position>.<ts>.v3.exo` 片段文件）：

```
$ adb -s … exec-out su -c "cat /data/data/com.takahashirinta.ncrust/databases/exoplayer_internal.db" > /tmp/exo.db
$ python3 -c "…sqlite3…"
tables: android_metadata, ExoPlayerVersions,
        ExoPlayerCacheIndex508cb0e0a96ccec5 (57 rows), ExoPlayerCacheFileMetadata508cb0e0a96ccec5 (527 rows)
```

交叉自证：片段文件名里的 uid 去重后**恰好 57 个**，与索引表 57 条 key 一一对应（`max(id)=58`，缺 2 个 = 历史淘汰）；527 条 metadata 对 529 个 `.v3.exo` 文件（差 2，未归因，见 §7）。**`song:503616:jymaster` 不在这 57 条里。**

---

## 3. 离线索引结构（file:line）

### 3.1 prefs 文件与 key

| 项 | 值 | 出处 |
|---|---|---|
| prefs 文件 | `ncrust_offline`（`MODE_PRIVATE`） | `cache/OfflineLibrary.kt:173`、`cache/OfflineUrlStore.kt:107` |
| 曲目索引 key | `tracks` | `cache/OfflineLibrary.kt:174` |
| URL 清单 key | `urls` | `cache/OfflineUrlStore.kt:108` |
| 落盘形式 | Gson JSON 串（`tracks` 是数组、`urls` 是单层 map） | `cache/OfflineTrackCodec.kt:131`、`cache/OfflineUrlStore.kt:81` |
| 进程内单例 + 双检锁，坏 JSON 容错 | — | `cache/OfflineLibrary.kt:176-188`、`cache/OfflineUrlStore.kt:110-122` |

### 3.2 记录形状（9 字段）

`OfflineTrack`（`cache/OfflineLibrary.kt:34-57`）= `songId`(主键) / `name` / `artist` / `albumPicUrl` / `durationMs` / `level` / `cacheKey` / `approxBytes` / `completedAt`。落盘 DTO 每字段显式 `@SerializedName`（`cache/OfflineTrackCodec.kt:116-126`）：

- 除 `songId` 外**全部可空 + 有默认值** —— Gson 走 Unsafe 反序列化不调构造函数（`cache/OfflineLibrary.kt:27-32`）；
- 主键 `songId <= 0` 的条目装载时丢弃（`cache/OfflineTrackCodec.kt:170-174`、`cache/OfflineLibrary.kt:74`）；
- `approxBytes` **当前实现从不写**（`cache/OfflineLibrary.kt:49-54`），所以真机上第 8 个 key 天然缺席。

**读法三形状**（v2.5.5 修复 R8 混淆）：稳定名 → 已知旧单字母 `LEGACY_KEYS = a..i`（`cache/OfflineTrackCodec.kt:101`）→ 未知 key 时按声明顺序兜底（`cache/OfflineTrackCodec.kt:172-174`）。**schema 版本号不落盘**，`SCHEMA_VERSION = 2` 只是代码常量（`cache/OfflineTrackCodec.kt:83-93`，注释明确写了"版本号不落盘"）。

### 3.3 两张表的上限与 LRU 语义

| 表 | 上限 | 淘汰顺序 | 展示顺序 |
|---|---|---|---|
| `tracks` | `OfflineLibraryIndex.MAX_ENTRIES = 300`（`cache/OfflineLibrary.kt:150`） | **最近一次 upsert**（队头先走，`cache/OfflineLibrary.kt:84-92`） | `completedAt` 倒序、稳定排序（`:130`） |
| `urls` | `OfflineUrlIndex.MAX_ENTRIES = 300`（`cache/OfflineUrlStore.kt:84`） | 同上（`cache/OfflineUrlStore.kt:30-38`） | 插入序 |

**关键**：`upsert` 会先 `remove` 再 `put`（`cache/OfflineLibrary.kt:75,83`）⇒ **重播把条目移到队尾**；但 `completedAt` 保留首次值（`:76-82`），所以"位置"和"时间戳"是两套口径，别混用。
`record()` 写入点在 `PlaybackService` 播放心跳（`cache/OfflineLibrary.kt:164-166`、`:202-231`）。

### 3.4 `urls` / `tracks` 必须一起删（不变量）

- 纯函数 `OfflineLibraryIndex.removeWithUrls(songId, urls)`：删曲目 **+ 调 `urls.removeSong(songId)` 删该曲全部档位**；即便曲目表里没有该 id，URL 也照样清（`cache/OfflineLibrary.kt:99-112`、`cache/OfflineUrlStore.kt:49-60`）；
- 唯一入口 `OfflineLibrary.remove(context, songId)`（`cache/OfflineLibrary.kt:248-259`）走 `OfflineUrlStore.mutate { … }`，一次落盘两张表；
- 音频 span 不在这里删 —— 那是 `OfflineAudioCache.removeSong` 的职责（`cache/OfflineAudioCache.kt:179-194`，songId → `songKeys` → `removeResource` + `OfflineLibrary.remove`）；
- 全清 `OfflineAudioCache.clear` = 删全部 span + `OfflineUrlStore.clear` + `OfflineLibrary.clear`（`cache/OfflineAudioCache.kt:217-222`），三者成对；
- **反向对账** `reconcileLibrary` 只单向清 `tracks`（不动 `urls`，避免误伤"同曲另一档位还有缓存"）：`cache/OfflineAudioCache.kt:196-210`。

### 3.5 cacheKey 与音频的映射

`cacheKey = song:<songId>:<level>`（`cache/OfflineKeys.kt:34`），由 `withKey()` 挂到播放 URL 的 `ncrustkey` query 参数上（`:37-42`），`CacheDataSource` 用 `setCacheKeyFactory { OfflineKeys.keyOf(url) ?: url }` 取回（`cache/OfflineAudioCache.kt:120`）⇒ **SimpleCache 的 key 就是这条 `cacheKey`**，`OfflineAudioCache.contains(key)` 是"能不能离线播"的权威判据（`:124-126`）。

### 3.6 删除能力的 UI 暴露

| 入口 | 行为 | 出处 |
|---|---|---|
| 设置页「清除缓存」 | `OfflineAudioCache.clear` → **音频 + urls + tracks 全清** | `ui/screen/UserScreen.kt:276-308`（`:300` 那一行） |
| 设置页「离线缓存管理」行 | 打开全屏 Dialog | `ui/screen/UserScreen.kt:823`（`showOfflineCacheManager = true`）→ `:268-271` |
| Dialog **打开即对账** | `reconcileLibrary` 丢掉"缓存里已无任何片段"的索引条目 | `ui/screen/OfflineCacheOverlay.kt:126-135`（`:128`）、`:94-97` |
| Dialog 单曲删除 | `OfflineAudioCache.removeSong`（清单 + 片段一起删） | `ui/screen/OfflineCacheOverlay.kt:301` |

---

## 4. 注入记录清单（含证据来源）

### 4.1 唯一一条注入记录

原文（`43-plc110-offline-AFTER-replay.xml:4` 的 `tracks` 数组内，转义已还原）：

```json
{"songId":503616,"name":"EM10_C_Long_Premix#070705","artist":"鷺巣詩郎",
 "albumPicUrl":"https://p2.music.126.net/TIvN4jJ_vYAc1FuDFgui3g==/109951166108834835.jpg",
 "durationMs":137160,"level":"jymaster","cacheKey":"song:503616:jymaster",
 "completedAt":1790403031347}
```

- 数组下标 **46 / 48**（19:29 回读时 46 / 50）；
- `urls` 中**无** `song:503616:jymaster`（也**无** `song:503616:*` 的任何档位）；
- SimpleCache 索引表中**无**该 key（57 条已逐条核对）。

### 4.2 证据来源（三条互相独立）

| # | 证据 | 原文/命令 |
|---|---|---|
| ① | v2.5.5 报告自认注入且**未还原** | `docs/verification/v2.5.5/EVIDENCE.md:109`："把它**原样注入** PLC110 的 `ncrust_offline/tracks`（47 条 → 48 条），记下注入前快照"；`:271`："PLC110 ｜ 注入一条测试用的旧形状 `tracks` 条目（§2.4 的迁移闭环）｜ ⚠️ **未还原**"；`CHANGELOG-v2.5.5.md:33-40` 同一件事 |
| ② | 该条在 S6 上是**真实旧形状**记录（迁移闭环的"种子"），九字段逐一相同 | `verification/40-s6-offline-v254-legacy-shape.xml` → `{"a":503616,"b":"EM10_C_Long_Premix#070705","c":"鷺巣詩郎","d":"https://p2.music.126.net/TIvN4jJ_vYAc1FuDFgui3g==/109951166108834835.jpg","e":137160,"f":"jymaster","g":"song:503616:jymaster","i":1790403031347}`（S6 该表 39 条、`urls` 36 条；S6 的 `urls` **有** `song:503616:jymaster`） |
| ③ | PLC110 上没有它的音频、也没有它的 URL ⇒ 它是"被搬进来的"，不是播出来的 | §2.4 的 DB 查询；§5.2 的双孤儿统计 |

> **不是靠"名字假"判的**：`EM10_C_Long_Premix#070705` / `鷺巣詩郎` 是**真实曲目**（同专辑的 503572 `EM16_Normal_Edit#070705`、503597、503631、503666 在 PLC110 上都有真实缓存与真实播放记录）。这也解释了为什么"看着像测试数据"这条线索会失效 —— 必须用上面三条证据。

### 4.3 条数汇总

| 表 / 载体 | 总数 | 其中注入 | 逐条核对 |
|---|---|---|---|
| `tracks`（19:18 快照 41/43） | **48** | **1** | 48/48 逐条打印过（songId/completedAt/level/duration/name） |
| `tracks`（19:29 当前） | **50** | **1** | 同上 |
| `urls`（快照与当前） | **145** | **0** | 无 `song:503616:*` |
| SimpleCache 索引 key | **57** | **0** | 57 条逐条打印，无 503616 |
| 音频片段文件 `*.v3.exo` | **529** | **0**（无孤儿） | 文件名 uid 去重 = 57，与索引表一致 |
| S6 旧形状表（种子来源） | 39 | 0（在 S6 上是真实数据） | — |

---

## 5. 真实数据 vs 注入数据 的判别依据

### 5.1 判别规则（按可靠性从高到低）

1. **有没有 `urls` 条目 + 有没有本地音频片段**（最硬）：
   - 真数据：`PlaybackService.playUrl()` 会 `OfflineUrlStore.rememberFromUrl`（`player/PlaybackService.kt:926`），缓存写入走 `CacheDataSource`（`:392`）⇒ 真播过的歌**至少**有缓存片段；绝大多数还有 `urls` 条目；
   - 注入数据：两样都没有。
2. **是否与另一台设备的表重复**：503616 与 S6 表逐字段相同 ⇒ 外来。
3. **`completedAt` 时间簇**：PLC110 自己在 9/26 的播放簇是 15:43–15:46(+08) 与 19:12–19:29(+08)；503616 的 14:10:31 落在**两簇之间的空档**。这条**只能当辅助**（空档不等于没播过），不能单独定案。
4. ❌ **不能用的判据**：字段形状（迁移后 48/48 条都是稳定名，注入条也是）、`completedAt` 是否"太整"、歌名是否"像测试"（是真实曲目）、`level`（`jymaster` 是设备当前默认档位，`ncrust_settings.xml` 的 `wifi_quality=6` / `mobile_quality=6` 也都是 6=超清母带）。

### 5.2 "唯一双孤儿"的实测证明

```
$ python3 …  # tracks(live) × cacheIndex(57 keys)
live tracks: 50   cache keys: 57   unique cache songIds: 56
index songs WITHOUT any cached audio (reconcile 会丢): [503616]
cached songs NOT in index: [503597, 557924, 29049687, 29715735, 1483497971, 1483503076, 1483504359]
503616 in cache? False   in index? True
```

- **索引 → 缓存方向**：50 条里**只有 503616** 没有任何缓存片段；
- **缓存 → 索引方向**：7 首有缓存但不在索引里（索引只记"播放心跳"，缓存可能在更早的会话写入；这是既有现象，与本次清理**无关**，不要顺手清）；
- 另有 6 首真数据缺 `urls` 条目（`1854421609 / 1380176 / 1983686033 / 22259257 / 381962 / 1970006`），但它们**都有缓存片段** ⇒ "缺 urls"单独不构成注入判据，必须与"缺缓存"合取。

---

## 6. 清理方式评估与推荐

### 6.1 四种机制的评估

| 机制 | 存在吗 | 对 503616 是否有效 | 是否会伤真实数据 | 判定 |
|---|---|---|---|---|
| **A. 设置页「清除缓存」** | ✅ 有（`UserScreen.kt:300`） | ✅ 会清掉（`OfflineAudioCache.clear` 同时清 urls + tracks） | ❌ **会**：清掉 **2.3 GB / 50 首**真实离线数据 | **不推荐**（除非用户明确要清空全部） |
| **B. 打开「离线缓存管理」即对账** | ✅ 有（`OfflineCacheOverlay.kt:128` → `reconcileLibrary` → `retain`） | ✅ **精确**清掉，且只清它（§5.2 证明它是唯一无缓存的条目） | ✅ 不伤：`retain` 只丢"缓存里已无片段"的索引条目；`urls` 一个字不动（`OfflineAudioCache.kt:200-202` 明写单向对账） | ✅ **推荐** |
| **C. 下次启动自动清理** | ❌ **不存在**：`AppWarmup` / `MainActivity` 全文无 `Offline*` 调用（grep 实测）；`reconcileLibrary` 唯一调用点是 `OfflineCacheOverlay.kt:128` | — | — | 需要新增代码（且打开设置页就会触发，收益近似为 0） |
| **D. 数据版本标记 + 一次性迁移**（`offline_index_version`） | ❌ 不存在（全仓 grep `offline_index_version\|index_version\|INDEX_VERSION` 无命中） | 需要新写代码 | 需要设计"只删哪条"的规则，否则必然误伤 | **不推荐**：`OfflineTrackCodec` 已明确"**版本号不落盘**"（`OfflineTrackCodec.kt:89-91`），再引入一个版本 key 与既有契约冲突；且这是**设备专有的一次性状态问题**，不是所有用户都会遇到的通用迁移 |
| **E. 等 LRU 自然淘汰** | 机制上存在 | ❌ **实际不会**：上限 300 / 当前 50；503616 在插入序第 46/50 位（靠**队尾**），LRU 从**队头**淘汰（`OfflineLibrary.kt:84-92`）⇒ 需要约 250+ 次**新**条目 upsert 才轮到它；且它永远不会被"重播"拉回队尾（PLC110 上它没音频、没 URL，不可能被播放） | 不适用 | **不可依赖** |

### 6.2 推荐方案（不改代码，一次设备状态清理）

**推荐 B**，操作与验收如下（本探针**未执行**，留给 v2.5.6 收尾步骤）：

1. 前置快照（可复现）：
   ```bash
   adb -s 3B15CD00GB700000 exec-out su -c \
     "cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_offline.xml" > before.xml
   ```
2. 在设备上：**设置 → 存储/缓存 → 「离线缓存管理」**（打开即可，不需要点任何删除）；
3. 回读验收（期望值已由本探针给定，是**确定性预测**而非估计）：
   - `tracks`：50 → **49**，且 `503616` 消失、其余 49 条**逐字节不变**；
   - `urls`：**145 → 145**（一个都不动）；
   - 音频：`files/offline/audio` 文件数与 2.3 GB 占用**不变**（`retain` 不删 span）；
   - 管理页列表：正常显示 49 条（503616 本来也不会出现在列表里 —— 它在 reconcile 阶段就被丢掉了，**用户看不到、也没法手动选中删除**，这正是不改代码就必须走"打开即对账"的原因）。
4. 收尾在 v2.5.6 的 EVIDENCE 里如实记录（设备改动表），不要再留一条"未还原"。

**建议同时明确"不做"的事**：不要为了让这条记录出现在列表里可手动删除而改代码；不要加 `offline_index_version`；不要动 `urls`。

### 6.3 如果 owner 仍要求"代码层面兜底"

最小改动 = 在 `AppWarmup`（或 `MainActivity` 启动路径）里复用**同一个** `reconcileLibrary()`（已是纯对账 + 只在有变化时落盘，`OfflineLibrary.kt:265-270`），不新增字段、不新增 key、不碰 `urls`。**但**：这会扩大"索引条目静默消失"的触发面（当前只在用户主动打开管理页时发生），属于产品行为变更，需要 owner 明确拍板；且必须配套：`OfflineLibraryTest` 增一条"launch 对账不删有缓存的条目"用例（模拟真实数据的正向保护）。**本探针不推荐为了清理一条记录而做这个改动。**

---

## 7. 对任务书前提的验证或推翻

| 任务书前提（转述） | 判定 | 依据 |
|---|---|---|
| "PLC110 离线索引有注入记录必须清理" | ✅ **成立**，但规模是 **1 条**，不是"一批" | `EVIDENCE.md:109`（47→48）+ §4 的实测 |
| "清理时不能伤用户真实离线数据" | ✅ **成立且是硬约束** | 真实数据 = 50 条索引 / 145 条 URL / 2.3 GB / 529 个片段；`clear()` 会全部清掉 |
| 隐含"这是一个需要改代码的缺陷/迁移" | ❌ **推翻（b→a）** | 注入记录**没有音频、没有 URL**，是"半条记录"，功能上完全惰性：离线兜底 `recallOfflineCache` 需要 `OfflineUrlStore.recall` **且** `OfflineAudioCache.contains` 同时成立（`ui/viewmodel/PlayerViewModel.kt:1287-1289`），503616 两条都不满足 ⇒ 它**既不能让用户看到假的离线歌、也不可能被拿去起播**。既有对账路径已能精确清除 ⇒ **(a) 一次设备状态清理，无需代码改动**，不是 (c) |
| "需要单测" | ⚠️ **条件成立** | 机制已有单测（`OfflineLibraryTest.kt:141`）；**不改代码就没有新行为可测**。若 owner 选 §6.3 的代码兜底，则必须补测（"有缓存的条目不许被启动对账删掉"） |
| v2.5.5 `EVIDENCE.md:271` 的处置建议"如需清掉，走「清空离线缓存」即可" | ❌ **推翻（有害）** | 该建议写于本设备已有 2.3 GB 真实离线数据的现在 → 会造成用户数据损失。应以本报告的机制 B 取代 |

---

## 8. 未测得项与原因

| # | 未测得项 | 原因 |
|---|---|---|
| 1 | **未在设备上实际执行清理**（未打开「离线缓存管理」） | 任务明令"Do not delete anything yet — this is a probe"。§6.2 的"50→49 / 145→145"是**基于代码路径 + 57 条缓存 key 实测**的确定性预测，不是执行结果 |
| 2 | 未做全量 Gradle 构建 / 未跑 JVM 单测 | 任务明令"Do not run a full Gradle build"；单测覆盖情况由源码阅读得出（`OfflineLibraryTest.kt:141`、`OfflineTrackCodecTest.kt` 15 例、`OfflineKeysTest.kt` 14 例） |
| 3 | 529 个 `.v3.exo` 文件 vs 527 条 `ExoPlayerCacheFileMetadata` 的 **2 条差额未归因** | 未逐文件与 DB 行做 join（需解析 media3 v3 文件命名/元数据语义）；与本次清理无关 |
| 4 | **6 首真数据为何没有 `urls` 条目未逐一归因**（`1854421609/1380176/1983686033/22259257/381962/1970006`） | 只确认了写入点（`PlaybackService.kt:926` 的 `playUrl` 路径）不在所有起播路径上；未查 logcat 复现。**与本次清理无关**，仅作为"不能只用缺 urls 判注入"的反例 |
| 5 | **v2.5.6 任务书原文未找到** | 全仓 grep（`TASK.md` / 父目录 `TASK.md` / `docs/`）无 `v2.5.6` / `2.5.6` 命中；本报告对"任务书前提"的核对基于委派说明的转述 + v2.5.5 报告的遗留项（`EVIDENCE.md:271`） |
| 6 | 华为/荣耀控制中心媒体卡片 | 任务明令不碰 —— 本探针未读取、未改动任何相关内容 |
| 7 | 未验证"打开管理页"在**当前播放中**的行为差异（`currentSongId` 影响的是删除按钮而非对账） | 未执行 UI 操作；`reconcileLibrary` 的调用点（`OfflineCacheOverlay.kt:128`）与 `currentSongId`（`:109`）无耦合，代码上可判定无关 |

---

## 9. 对下一步实现的建议

1. **把本项归档为"设备状态收尾"，不占 v2.5.6 的代码改动额度**：在 v2.5.6 的收尾清单里加一条"PLC110 离线索引注入记录（songId 503616）用『离线缓存管理』打开即对账清除"，按 §6.2 的三步做快照 → 触发 → 回读，并把 before/after 两份 `ncrust_offline.xml` 与"49 条、无 503616、urls 仍 145"的回读输出归档到 `docs/verification/v2.5.6/verification/`。
2. **同步修订 v2.5.5 的遗留建议**：`EVIDENCE.md:271` / `CHANGELOG-v2.5.5.md:33-40` 里"走『清空离线缓存』即可"应改为"打开『离线缓存管理』即对账清除"，并在 v2.5.6 报告里说明修订原因（会误删真实数据）。
3. **不要新增 `offline_index_version`，不要新增启动期对账**：前者与 `OfflineTrackCodec` 的"版本号不落盘"契约冲突（`OfflineTrackCodec.kt:89-91`），后者把"索引静默缩水"从用户主动动作扩大到每次冷启（`reconcileLibrary` 会 `persist`，见 `OfflineLibrary.kt:265-270`），风险大于收益。
4. **如果要给这类"注入残留"留一条长期防线**，正确的位置不是迁移，而是**测试**：给 `OfflineLibraryIndex` 增一条 JVM 用例钉住"`retainSongIds` 只丢无缓存条目、且**一个真实条目都不许误伤**"（可复用 `OfflineLibraryTest.kt:141` 的风格，加入"有缓存的 id 必留"的正向断言）。这条用例**不需要任何产品代码改动**，纯防御。
5. **下次做同类真机注入测试时，注入前先落一份 `before.xml` 并写进验收清单**（本项之所以要靠三重证据反推，就是因为注入只记了"47→48"而没记"删哪条"）；注入的条目建议带一个显式可辨识的 `cacheKey`（例如 `song:<id>:seed`）或先记下 songId，省掉整条证据链。
