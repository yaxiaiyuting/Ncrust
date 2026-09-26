# Ncrust 持久化结构 × R8 字段名 全仓只读探针（v2.5.5）

- 仓库：`/home/duanjb666/deepseek/ncrust-gpl/Ncrust`，HEAD = `10e9df9`（v2.5.4 之后、v2.5.5 开工前的只读状态）
- 本探针**未修改 `app/` 下任何文件**，只新增本文件与 `probe-raw/r8-keys/` 下的原始证据
- mapping 来源：`app/build/outputs/mapping/release/mapping.txt`（68 MB / 550k+ 行，mtime 2026-09-26 15:02）
- **证据时效性（重要）**：`mapping.txt` 与 APK 都来自 **v2.5.4 release**（versionCode 45）。
  探针执行时工作区里另有**其他并行任务**的未提交改动（`MainActivity.kt` / `ui/player/PlayerCard.kt` / `ui/BottomOverlayInset.kt` / 新增 `ui/player/TrayLayout.kt` —— 控制中心卡片相关），
  本探针与它们无交集：这些文件**不包含本文分析的任何持久化 DTO**（`git status --porcelain` 可核验）。
  反过来说，这也正好说明 §4.2 的结论：**下一版构建的全局混淆方案一定会因为新代码而重新分配**，单字母 key 不是契约。
- mapping 与 APK 的对应关系自证：`aapt2 dump badging dist/Ncrust-v2.5.4-gpl-release.apk` → `versionCode='45' versionName='2.5.4-gpl'`，
  而 `app/build.gradle.kts` 的 HEAD 版本号同样是 `versionCode = 45 / versionName = "2.5.4-gpl"`
- 证据强度分级（全文按此标注）：
  - **【DEX】release APK 的 dex 反汇编**（`apkanalyzer dex code`）—— 最强：这是产物里真实存在的东西
  - **【MAP】mapping.txt 行号**（形如 `@406570`）—— 强：可逐条指认
  - **【SEED】seeds.txt / configuration.txt** —— 强：R8 实际生效的 keep 结果
  - **【SRC】源码 file:line** —— 中
  - **【真机】**引用本仓既有取证文档里的真机 XML —— 强，但不是我这次产生的
  - **【推断】**明确标注的推理，未实测

---

## §0 结论先行

### 0.1 必须修（3 个落点，全部跨版本存活，全部无 keep、无显式命名）

| # | 落盘位置 | DTO | R8 后字段名 | 用户可见后果 | 严重度 |
|---|---|---|---|---|---|
| **F1** | `ncrust_offline` / `tracks` | `cache.OfflineTrack` | `a`~`i` | 离线曲目索引**整表静默消失**（`songId` 是主键，读成 0 后 `upsert` 直接丢弃该条） | 高（已知项） |
| **F2** | `ncrust_playback_state` / `song_positions` | `player.PlaybackStateManager$PositionEntry` | `a`,`b` | 逐曲续播**静默失效**（两字段都读成 0）；极端情况下两字段**对调**，`a`(posMs) 拿到 epoch 毫秒 ⇒ 起播位置荒谬 + Toast「从 48631:12 续播」 | 高（**本次新发现**） |
| **F3** | `ncrust_library` / `saved_albums` | `library.AlbumInfo` | `a`~`e` | 订阅专辑列表字段全变 0/null ⇒ 显示空白；且 `LibraryScreen.kt:382` 用 `key = { it.albumId }`，`albumId` 全为 0 时 **LazyColumn 重复 key 会抛异常**（推断，见 §4.1） | 高（**本次新发现**） |

三者都满足「跨版本存活 + 依赖反射字段名 + 无 keep + 无 `@SerializedName`」，与历史上四次事故（`local.**` / `crosssource.**` / `playlist.**` / `library.SearchHistoryCodec`）**同型**。

### 0.2 已安全（`-keep class …** { *; }` 覆盖，字段名不变）

`network.**`（`SongItem`、`PlaylistApi$PlaylistCard`）、`lyric.**`（`CachedLyrics`）、`playlist.**`（`PlaylistCacheCodec` 的全部 DTO）、`local.**`、`crosssource.**` —— 见 §2 表与 `probe-raw/r8-keys/06-effective-keep-rules.txt`。
覆盖的落盘 key：`ncrust_home_cache/*`、`ncrust_playback_state/queue`、`ncrust_lyrics_cache/entries`、`ncrust_qq_playlists/*`、`ncrust_match_cache/*`、`ncrust_local_playlists/*`。

### 0.3 已安全（显式 `@SerializedName`，且**注解确实进了 release dex**）

`library.SearchHistoryCodec$EntryDto`（`search_history/{songs,albums,artists}`）与 `qq.QqFallbackCounters`（`ncrust_qq_probe/stats`）：
字段本身**被 R8 改名**成 `a..h` / `a..m`，但每个字段在 dex 里都带 `value = "稳定名"` 的 runtime 注解 ⇒ 落盘 key 稳定。
证据：`probe-raw/r8-keys/07-dex-annotated-dtos.txt`（【DEX】）。这条同时补上了 v2.5.4 `EVIDENCE.md` §6.1 #9「搜索历史新写入走稳定字段名未真机验证」的静态缺口 ——
**dex 层面已证实注解保留**，但仍未做「真机写入 → pull XML」的闭环（见 §4.3 未验证项）。

### 0.4 假阳性（看起来危险，实际安全，且给了机理/证据）

`ncrust_offline/urls`（map 的 key 是数据串 `song:<id>:<level>`）、所有 primitive/字符串设置项（`ncrust_settings` 全家、`ncrust_prefs`、`ncrust_device`、`ncrust_netease_vip`、`ncrust_qq_prefs`）、
枚举写 prefs（`theme_mode` / `accent_source` 写 `.name`，**常量字段确被改名**但 `Enum.<init>` 的字符串常量未变，【DEX】）、
`ContentCache`（纯内存，无落盘）、网络请求 payload（字面 key 的 `mapOf`/`JSONObject`/Retrofit `@Field`）、
Coil 磁盘缓存 / media3 `SimpleCache`（二进制 + SQLite，key 是数据）、`filesDir/background.png`（PNG 字节）、WebView 数据。
详见 §2 表下半部与 §4.4。

---

## §1 扫描方法（可复现）

### 1.1 五问判定法（对每个落点逐条回答）

1. 写盘/落网时，**字段名是从哪来的**？（`@SerializedName` / `@SerialName` / 手写 JSON key / Gson 反射字段名 / map 的 key 是数据）
2. 有没有依赖**反射字段名**（Gson 默认按字段名、`getDeclaredFields`、`field.name`）？
3. R8 之后字段名**实际**是什么？（mapping.txt 逐类提取）
4. 有没有 **keep 规则**覆盖？（`app/proguard-rules.pro` → `configuration.txt` 核验实际生效）
5. 数据**跨版本存活**吗？（SharedPreferences / `filesDir` 会；`cacheDir` 可能被系统清；内存不会）

判定口径：**「跨版本存活 ∧ 依赖反射字段名 ∧ 既无显式命名又无 keep」= 必须修**；三者缺一即降级为「已安全」或「需判断」。

### 1.2 实际跑过的命令

```bash
ROOT=/home/duanjb666/deepseek/ncrust-gpl/Ncrust
SRC=$ROOT/app/src/main/java/com/takahashirinta/ncrust
MAP=$ROOT/app/build/outputs/mapping/release

# ① 所有 Gson 调用点（落盘 + 落网）           → probe-raw/r8-keys/01-gson-callsites.txt
grep -rn "toJson\|fromJson" "$SRC" | grep -v import | sort

# ② 所有 SharedPreferences 文件 / 读点 / 写点   → probe-raw/r8-keys/02-prefs-inventory.txt
grep -rn 'PREFS\w* = "\|PREFS_NAME = "' "$SRC"
grep -rn "getSharedPreferences" "$SRC"
grep -rn "\.putString(\|\.putInt(\|\.putLong(\|\.putBoolean(\|\.putFloat(" "$SRC"

# ③ filesDir / cacheDir 的文件读写              → 见 §2 表末四行
grep -rn "filesDir\|cacheDir\|FileOutputStream\|writeText\|writeBytes" "$SRC"

# ④ 其它序列化机制（排除 kotlinx / Parcel / Room / DataStore）
grep -rn "kotlinx.serialization\|@SerialName\|ObjectOutputStream\|Parcelable\|java.util.Properties\|DataStore\|Room\b" "$SRC"

# ⑤ mapping.txt 逐类提取字段映射（awk：命中 '^<FQCN> ->' 进类，跳过 '#' 注释行，只输出字段行）
#    脚本见 docs/verification/v2.5.5/probe-raw/r8-keys/03-mapping-field-lines.txt 首部说明
awk -v pat="$FQCN ->" '/^[^ \t]/{if($0!~/^#/){if(index($0,pat)==1){p=1;print;next}else{p=0}}} p&&/^#/{next} p&&/^    /&&!/\(/&&/ -> /{print}' "$MAP/mapping.txt"

# ⑥ 带行号的 mapping 摘录（每条 R8 名字都能指到物理行） → 04-mapping-with-line-numbers.txt
grep -n -A 12 "^com.takahashirinta.ncrust.cache.OfflineTrack ->" "$MAP/mapping.txt"

# ⑦ keep 规则到底有没有命中：seeds.txt 计数 + R8 实际生效配置 → 05 / 06
grep -c "^com.takahashirinta.ncrust.network.SongItem" "$MAP/seeds.txt"
grep -n "takahashirinta" "$MAP/configuration.txt"

# ⑧ 【最强证据】release dex 里字段名与注解的真实形态 → 07 / 08 / 09 / 10
AA=/home/duanjb666/Android/sdk/cmdline-tools/12.0/bin/apkanalyzer
APK=/home/duanjb666/deepseek/ncrust-gpl/dist/Ncrust-v2.5.4-gpl-release.apk
$AA dex code --class 'F4.h' "$APK"      # F4.h = OfflineTrack 的混淆名（mapping @406570）
$AA dex code --class 'G4.k' "$APK"      # G4.k = SearchHistoryCodec$EntryDto（mapping @409687）
$AA dex code --class 'H4.F' "$APK"      # H4.F = PlaybackStateManager$PositionEntry（mapping @419670）
$AA dex code --class 'G4.a' "$APK"      # G4.a = library.AlbumInfo（mapping @409058）
$AA dex code --class 'I4.I' "$APK"      # I4.I = QqFallbackCounters（mapping @424065）
$AA dex code --class 'S4.s' "$APK"      # S4.s = ThemeMode（枚举字符串常量取证）

# ⑨ 既有真机取证（本仓文档，非本次产生）        → 11-in-repo-device-evidence.txt
sed -n '60,90p'   "$ROOT/docs/verification/v2.5.4/PROBE-SUMMARY.md"
sed -n '216,238p' "$ROOT/docs/verification/v2.5.4/EVIDENCE.md"
```

工具版本：`apkanalyzer`（cmdline-tools 12.0）、`aapt2`（build-tools 36.0.0）、`awk`/`grep`（GNU）。

### 1.3 覆盖范围与已知盲区

- 覆盖：`app/src/main/java/com/takahashirinta/ncrust/**` 全部 20 个使用 Gson 的文件（52 个 `toJson`/`fromJson` 行）、34 个文件里的 68 个 `getSharedPreferences` 调用点、29 个 prefs 文件名常量、全部 `filesDir`/`cacheDir` 文件读写、17 个 `JSONObject` 手写 key 文件。
- 不在范围：`Kanesumi-sec-a`（同级库，未读其持久化）、`ncrust-api`（Go，App 不调用）、`benchmark/`。
- 盲区（如实声明）：没有真机/模拟器；所有「真机形状」均引用本仓既有文档；`offline/audio` 的 media3 SQLite 索引未逐字节检查（只做机理判定）。

---

## §2 逐结构清单表

列义：**显式命名**＝`@SerializedName`/手写 key；**R8 后名字**＝mapping.txt（`@行号`）；**keep**＝`proguard-rules.pro` 的覆盖；**跨版本**＝升级后是否存活。

### 2.1 必须修（3 行）

| 文件（写点） | 落盘位置 | DTO | 显式命名 | R8 后实际名字（mapping 证据） | keep 规则 | 跨版本存活 | 判定 |
|---|---|---|---|---|---|---|---|
| [OfflineLibrary.kt:196](app/src/main/java/com/takahashirinta/ncrust/cache/OfflineLibrary.kt) `putString(KEY_TRACKS, …)` | `ncrust_offline` / `tracks` | `cache.OfflineTrack`（[OfflineLibrary.kt:36](app/src/main/java/com/takahashirinta/ncrust/cache/OfflineLibrary.kt)） | ❌ 无 | `OfflineTrack -> F4.h` @406570；`songId->a, name->b, artist->c, albumPicUrl->d, durationMs->e, level->f, cacheKey->g, approxBytes->h, completedAt->i` @406572-406580 | ❌ 无（`cache.**` 无规则；`configuration.txt` 里只有 network/network.model/lyric/playlist/local/crosssource/BuildConfig） | ✅ prefs | ❌ **必须修（F1）** |
| [PlaybackStateManager.kt:220](app/src/main/java/com/takahashirinta/ncrust/player/PlaybackStateManager.kt) `putString(KEY_SONG_POSITIONS, …)` | `ncrust_playback_state` / `song_positions` | `player.PlaybackStateManager$PositionEntry`（[:143](app/src/main/java/com/takahashirinta/ncrust/player/PlaybackStateManager.kt)） | ❌ 无 | `PositionEntry -> H4.F` @419670；`posMs->a` @419672、`savedAtMs->b` @419673 | ❌ 无（`player.**` 未 keep；仅 `PlaybackService { <init>(); }` @configuration.txt:1019） | ✅ prefs | ❌ **必须修（F2）** |
| [LibraryManager.kt:164](app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt) `putString(KEY_ALBUMS, …)` | `ncrust_library` / `saved_albums` | `library.AlbumInfo`（[LibraryManager.kt:430](app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt)） | ❌ 无 | `AlbumInfo -> G4.a` @409058；`albumId->a, name->b, picUrl->c, artist->d, songCount->e` @409060-409064 | ❌ 无（`library.**` 未 keep） | ✅ prefs | ❌ **必须修（F3）** |

> 【DEX】三者字段在 release dex 里**完全没有注解**，就是赤裸的 `a`/`b`/…：见 `probe-raw/r8-keys/08-dex-risky-dtos.txt`。

### 2.2 已安全 —— keep 覆盖（类名与字段名 identity）

| 文件（写点） | 落盘位置 | DTO | 显式命名 | R8 后实际名字 | keep 规则 | 跨版本 | 判定 |
|---|---|---|---|---|---|---|---|
| [HomeSnapshot.kt:79/81/83/85](app/src/main/java/com/takahashirinta/ncrust/cache/HomeSnapshot.kt) | `ncrust_home_cache` / `daily_songs`·`recommend_playlists`·`new_songs`·`toplists` | `network.SongItem`、`PlaylistApi$PlaylistCard` | 两者都有 `@SerializedName`（如 `al`/`ar`）**且**被 keep | `SongItem -> SongItem` @414683、`PlaylistApi$PlaylistCard -> …PlaylistCard`（identity，无字段行） | ✅ `network.**` + `network.model.**`（configuration.txt:124-125） | ✅ prefs | ✅ 安全 |
| [PlaybackStateManager.kt:260](app/src/main/java/com/takahashirinta/ncrust/player/PlaybackStateManager.kt) | `ncrust_playback_state` / `queue` | `List<SongItem>` | 同上 | 同上 | ✅ `network.**` | ✅ prefs | ✅ 安全 |
| [LyricsCache.kt:114](app/src/main/java/com/takahashirinta/ncrust/lyric/LyricsCache.kt) | `ncrust_lyrics_cache` / `entries` | `lyric.CachedLyrics`（[:36](app/src/main/java/com/takahashirinta/ncrust/lyric/LyricsCache.kt)） | ❌ 无注解 | `CachedLyrics -> CachedLyrics` @410568（identity；【DEX】字段名即 `lrc`/`tlyric`/`ttml`…） | ✅ `lyric.**`（configuration.txt:126） | ✅ prefs | ✅ 安全（注意：字段**增删**仍需 §4.3 的迁移纪律） |
| [QqPlaylistStore.kt:94/125](app/src/main/java/com/takahashirinta/ncrust/qq/QqPlaylistStore.kt) | `ncrust_qq_playlists` / `list:…`·`detail:…` | `PlaylistCacheCodec$PlaylistDto/$SongDto/$ListEnvelope/$DetailEnvelope` | ❌ 无注解 | 全部 identity，如 `PlaylistDto -> PlaylistDto` @421241 | ✅ `playlist.**`（configuration.txt:133） | ✅ prefs | ✅ 安全 |
| [LocalPlaylistStore.kt:89/107](app/src/main/java/com/takahashirinta/ncrust/local/LocalPlaylistStore.kt) | `ncrust_local_playlists` / `playlists`·`tracks:<key>` | `LocalPlaylistCodec$LocalPlaylistDto/$LocalTrackDto/$PlaylistEnvelopeDto` | ❌ 无注解 | 全部 identity，如 `LocalPlaylistDto -> LocalPlaylistDto` @409940 | ✅ `local.**`（configuration.txt:144） | ✅ prefs | ✅ 安全 |
| [MatchCacheStore.kt:190](app/src/main/java/com/takahashirinta/ncrust/crosssource/MatchCacheStore.kt) | `ncrust_match_cache` / 各 alias·match key + `schema_version` | `MatchCacheCodec$EnvelopeDto/$AliasDto` | ❌ 无注解 | identity，`EnvelopeDto -> EnvelopeDto` @408418 | ✅ `crosssource.**`（configuration.txt:202） | ✅ prefs | ✅ 安全 |
| [LibraryManager.kt:163](app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt) | `ncrust_library` / `saved_songs` | `List<SongItem>` | ✅ | identity | ✅ `network.**` | ✅ prefs | ✅ 安全 |
| [LibraryManager.kt:165](app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt) | `ncrust_library` / `liked_ids` | `List<Long>` | 不适用（标量列表） | 无字段名 | — | ✅ prefs | ✅ 安全 |

### 2.3 已安全 —— 显式 `@SerializedName`（字段被改名，但注解值固定在 dex 里）

| 文件（写点） | 落盘位置 | DTO | 显式命名 | R8 后实际名字 | keep 规则 | 跨版本 | 判定 |
|---|---|---|---|---|---|---|---|
| [SearchHistoryCodec.kt:140](app/src/main/java/com/takahashirinta/ncrust/library/SearchHistoryCodec.kt)（经 [SearchHistoryManager.kt:155](app/src/main/java/com/takahashirinta/ncrust/library/SearchHistoryManager.kt)） | `search_history` / `songs`·`albums`·`artists` | `library.SearchHistoryCodec$EntryDto`（[:125](app/src/main/java/com/takahashirinta/ncrust/library/SearchHistoryCodec.kt)） | ✅ 8 个字段全有 | `EntryDto -> G4.k` @409687；字段 `a..h`，但**每个字段带 `SerializedName` 注解**：`a`→`"id"`、`b`→`"title"` … 【DEX】 | `-keepclassmembers,allowobfuscation class * { @SerializedName <fields>; }`（proguard-rules.pro:21）——字段不删、名字可变、**注解保留** | ✅ prefs | ✅ 安全（写路径；读路径另认 3 种历史形状） |
| [QqProbeStore.kt:81](app/src/main/java/com/takahashirinta/ncrust/qq/QqProbeStore.kt) | `ncrust_qq_probe` / `stats` | `qq.QqFallbackCounters`（[QqFallbackStats.kt:57](app/src/main/java/com/takahashirinta/ncrust/qq/QqFallbackStats.kt)） | ✅ 13 个字段全有 | `QqFallbackCounters -> I4.I` @424065；字段 `a..m`，注解值 `parsedTotal…schemaVersion` 【DEX】 | 同上 | ✅ prefs | ✅ 安全（**但**：v2.5.4 之前落的 `{"a":30,…}` 老 blob 被读成全 0，见 §4.3） |

> `-keepclassmembers,allowobfuscation` 的**准确语义**（本次用 seeds.txt 核验）：字段被「保留不被 shrink」，但**允许改名**；
> `keepattributes *Annotation*`（proguard-rules.pro:16）保证 runtime 注解属性留在 dex 里。
> 【SEED】`seeds.txt` 里 `SearchHistoryCodec$EntryDto` 命中 **8** 行、`QqFallbackCounters` 命中 **13** 行 ——
> 恰好等于两者带 `@SerializedName` 的字段数（8 / 13），说明「按注解匹配」的规则对**每一个**字段都生效了；而三个风险类均为 **0**（`05-seeds-kept-members.txt`）。

### 2.4 假阳性 / 不需要修（含不落盘与二进制）

| 文件 | 落盘位置 | 结构 | 为什么安全 | 跨版本 | 判定 |
|---|---|---|---|---|---|
| [OfflineUrlStore.kt:127](app/src/main/java/com/takahashirinta/ncrust/cache/OfflineUrlStore.kt) | `ncrust_offline` / `urls` | `LinkedHashMap<String,String>` | 序列化的是 **map 本身**，key 是**数据串** `song:<songId>:<level>`（[OfflineKeys.kt:34](app/src/main/java/com/takahashirinta/ncrust/cache/OfflineKeys.kt)），**不含任何 Java 字段名**；Gson 对 String key 原样写出 | ✅ prefs | ✅ 假阳性（§3.3 给了对照表） |
| `auth/CookieManager.kt:15` | `ncrust_prefs` / `user_cookie` | `String` | 标量 | ✅ | ✅ |
| `network/ClientIdentity.kt:49` | `ncrust_device` / `device_id` | `String` | 标量 | ✅ | ✅ |
| `auth/NeteaseVipStore.kt:103-105` | `ncrust_netease_vip` / 3 个 int/long | 标量 | 标量 | ✅ | ✅ |
| `qq/QqAuthStore.kt`、`qq/QqIdentity.kt` | `ncrust_qq_prefs` / cookie·nick·uid·vip·device | 标量 | 标量 | ✅ | ✅ |
| `lyric/LyricsDisplayPrefs.kt`、`ui/theme/*`、`ui/i18n/LanguageManager.kt`、`player/QualityLadder.kt`、`reco/ArtistReco.kt`、`RotationSetting.kt`、`KeepScreenOnSetting.kt`、`ui/player/AudioVisualizer.kt`、`player/LiveUpdateNotifier.kt`、`cache/OfflineAudioCache.kt:89` | `ncrust_settings` / 20+ 个 key | int/bool/float/String、CSV | 标量或 CSV（`artist_reco_anchor_ids` 是 `joinToString(",")`） | ✅ | ✅ |
| [ThemeManager.kt:52](app/src/main/java/com/takahashirinta/ncrust/ui/theme/ThemeManager.kt)、[AccentSource.kt:42](app/src/main/java/com/takahashirinta/ncrust/ui/theme/AccentSource.kt) | `ncrust_settings` / `theme_mode`·`accent_source` | 枚举 `.name` 字符串 | **枚举常量字段确被 R8 改名**（`SYSTEM->U` @499707+），但 `Enum.<init>` 收到的字符串常量 `"SYSTEM"` 原样留在 dex ⇒ `.name`/`valueOf` 稳定【DEX】 | ✅ | ✅ 假阳性（最容易被误报的一条） |
| [PlaybackStateManager.kt:116](app/src/main/java/com/takahashirinta/ncrust/player/PlaybackStateManager.kt) | 无 | `SavedState` | 只由 prefs 标量在 `getState()` 里手工拼装，**从不经过 Gson** | — | ✅ |
| `cache/ContentCache.kt` | 无 | 内存快照 | 纯内存，无 `getSharedPreferences`/`filesDir` | ❌ 不存活 | ✅ |
| `OfflineAudioCache.kt:99` | `filesDir/offline/audio` + media3 自己的 SQLite（`StandaloneDatabaseProvider`） | `SimpleCache` 分片 + 索引 DB | 二进制分片与 SQLite 行；key 是 `OfflineKeys` 的数据串 | ✅ filesDir | ✅ |
| `ui/theme/BackgroundImageManager.kt:106` | `filesDir/background.png` + prefs 布尔 | PNG 字节 | 文件名是常量，内容是二进制 | ✅ filesDir | ✅ |
| `NcrustApplication.kt:54` | `cacheDir/image_cache`（Coil） | 磁盘图片缓存 | Coil 自己的格式，key 是 URL 摘要 | ⚠️ 系统可清 | ✅ |
| `ui/viewmodel/SongViewModel.kt:29`、`PlaylistApi`/`PlaylistEditApi`/`SongUrlFetcher`/`NeteaseAvailabilityApi` 的 payload、`qq/**` 的 `JSONObject` | 网络（落网） | `mapOf`/`JSONObject` 字面 key；Retrofit `@Field("…")` | 字段名是**源码里的字符串字面量**，R8 不碰 | 不适用 | ✅ 落网安全 |
| `ui/screen/UserScreen.kt:377` | 无（logcat，仅 debug 包） | `Gson().toJson(QqFallbackCounters)` | 只是把同一份注解 DTO 打进 log | — | ✅ |

---

## §3 重点：`cache.**`（`ncrust_offline/tracks` + `ncrust_offline/urls`）

### 3.1 `tracks` 的完整字段表（稳定名 / 当前 R8 名 / 真机观测）

来源：`cache/OfflineLibrary.kt:36-59` 与 mapping.txt @406570-406580；真机形状引用 `docs/verification/v2.5.4/PROBE-SUMMARY.md:85`。

| 声明顺序 | 字段（稳定名，建议） | 类型 | 当前 R8 名（mapping 行号） | release dex 里的样子 | 真机 `tracks` 里是否出现 | 说明 |
|---|---|---|---|---|---|---|
| 1 | `songId` | `Long` | `a` @406572 | `.field public final a:J`，无注解 | ✅ `"a":557902` | **主键**：读成 0 时 `upsert` 直接丢弃整条（`OfflineLibrary.kt:76`） |
| 2 | `name` | `String?` | `b` @406573 | `b:Ljava/lang/String;` | ✅ `"b":"…"` | 歌名 |
| 3 | `artist` | `String?` | `c` @406574 | `c:Ljava/lang/String;` | ❌ 该条为 null（Gson 省略 null key）【推断】 | 歌手 |
| 4 | `albumPicUrl` | `String?` | `d` @406575 | `d:Ljava/lang/String;` | ❌ 同上【推断】 | 封面 |
| 5 | `durationMs` | `Long?` | `e` @406576 | `e:Ljava/lang/Long;` | ✅ `"e":287533` | 时长 |
| 6 | `level` | `String?` | `f` @406577 | `f:Ljava/lang/String;` | ✅ `"f":"jymaster"` | 实际档位 |
| 7 | `cacheKey` | `String?` | `g` @406578 | `g:Ljava/lang/String;` | ✅ `"g":"song:557902:jymaster"` | 删曲目时按它删 span |
| 8 | `approxBytes` | `Long?` | `h` @406579 | `h:Ljava/lang/Long;` | ❌ 代码**从不写**（默认 null） | 只由 `totalBytes()` 读 |
| 9 | `completedAt` | `Long?` | `i` @406580 | `i:Ljava/lang/Long;` | ✅ `"i":…` | 首次可离线播放时刻 |

**与 v2.5.4 真机观测的逐键对照**：观测到的键集合是 `{a,b,e,f,g,i}`；按 mapping，缺的三个是 `c`(artist)、`d`(albumPicUrl)、`h`(approxBytes)。
`h` 的缺失有代码依据（`approxBytes` 默认 null、无写入点）；`c`/`d` 的缺失说明**该条记录写入时 artist/albumPicUrl 就是 null**（Gson 默认跳过 null 字段）—— 这是【推断】，与 mapping 无矛盾，但没有该设备的原始 XML 可逐字复核。
**v2.5.4 当时那句「看起来跳过 h」是对的，但不完整**：同一条记录里 `c`/`d` 也一起被省掉了。

### 3.2 读取路径为什么是「整表消失」而不是「字段为 null」

`OfflineLibraryIndex.fromJson`（`OfflineLibrary.kt:150-159`）逐条 `upsert`，而 `upsert` 第一行就是 `if (track.songId <= 0L) return false`。
所以字段字母一旦位移：`songId` 读不到 ⇒ Gson 留 `0L`（`Long` 是 primitive 参数）⇒ **该条被静默丢弃**，不是留个 null 字段。
连带影响（已读代码确认，非推断）：
- `OfflineUrlStore` 的 `urls` **不受影响**（对账是单向的：`reconcileLibrary` 只按「音频 span 是否还在」丢索引条目，不动 URL 清单，见 `OfflineAudioCache.kt:207-210`）；
- 音频字节也还在（`filesDir/offline/audio`）；
- ⇒ 用户可见的是「离线列表空了 / 缓存占用统计变小」，**但离线兜底仍能播**（兜底只认 `urls` + `SimpleCache.contains`）。
  这正是这类 bug 难被发现的原因：不崩、歌还能放，只是那份清单无声消失。

### 3.3 `urls` 为什么安全（无需修）

`OfflineUrlIndex.toJson()` = `Gson().toJson(LinkedHashMap<String,String>)`（`OfflineUrlStore.kt:81`）。落盘形状是**纯 map**：

```json
{"song:557902:jymaster":"https://m804.music.126.net/…?ncrustkey=song:557902:jymaster&…"}
```

- key 是 `OfflineKeys.key(songId, level)` 生成的数据串（`OfflineKeys.kt:34`），**不含 Java 字段名**；
- value 是 URL 字符串；
- 【DEX】`F4.i`（`OfflineUrlIndex`）只有两个字段 `a:I`、`b:Ljava/util/LinkedHashMap;`（容量与 map 本身），**这两个字段不参与序列化**（序列化的是 map 的值）。
⇒ 判定：**假阳性**，R8 改字段名对它没有任何影响。

---

## §4 其他发现

### 4.1 本次新发现的两个落点（此前所有文档都没提过）

- **F2 `player.PlaybackStateManager$PositionEntry`** → `ncrust_playback_state/song_positions`。
  `flushPositions` 用 `gson.toJson(Map<Long, PositionEntry>)`（`PlaybackStateManager.kt:218`），落盘是 `{"<songId>":{"a":posMs,"b":savedAtMs}}`。
  读取在 `ensurePositionsLoaded`（[:154-157](app/src/main/java/com/takahashirinta/ncrust/player/PlaybackStateManager.kt)）。
  后果分两支：① 字母变了 ⇒ 两字段都读成 0 ⇒ `getSongPosition` 返回 0 ⇒ **逐曲续播静默失效**（用户感知为"断点续播没了"）；
  ② 名字恰好对调 ⇒ `posMs` 拿到 epoch 毫秒 ⇒ 起播位置荒谬，并且 `PlayerViewModel.kt:1153-1158` 会弹「从 48631:12 续播」的 Toast。
  两支都不崩，但都是用户可见的错误行为。**建议与 F1 同批修**。
- **F3 `library.AlbumInfo`** → `ncrust_library/saved_albums`。
  它是 `library/LibraryManager.kt:430` 的顶层 data class（**不在被 keep 的 `network.**` 里**，这是最容易误判的一点 —— 同文件里的 `SongItem` 是 `network.**` 所以安全，`AlbumInfo` 不是）。
  消费点 `LibraryScreen.kt:382`：`items(savedAlbums, key = { it.albumId })`。若 `albumId` 全解出 0，LazyColumn 的 key 重复 ——
  Compose 会抛 `IllegalArgumentException: Key "0" was already used`。这一支是【推断】（我没有真机构造过该状态），但它与 v2.2.0 的 `playlist.**` 事故是同一类崩溃路径。
  即便不崩，`name`/`picUrl`/`artist` 在 Kotlin 侧是非空 `String` 却会被 Gson 走 Unsafe 填成 null ⇒ 界面空白 / 后续 NPE。

### 4.2 混淆字母表的稳定性：**不能当作契约**（本次给出的量化观察）

| 观察 | 数据 | 出处 |
|---|---|---|
| 同一个类，**类名混淆字母在两次构建间变了** | `SearchHistoryManager$HistoryItem`：v2.5.3 = `G4.k` → v2.5.4 = `G4.m` | v2.5.4 `EVIDENCE.md:208/220`（【真机/文档】）+ 本次 mapping @409718 |
| 同一个类，**字段字母在两次构建间没变**（前 5 个） | `id->a … timestamp->e` 两次一致；新加的 3 个字段追加为 `f/g/h` | 同上 |
| `OfflineTrack` 的 `a`~`i` 在 v2.5.3 → v2.5.4 **逐字未变** | 因此 v2.5.4 升级没有丢老离线索引 | v2.5.4 `EVIDENCE.md:230-238` |
| **字段字母由类内声明顺序决定**（本构建 9/9、13/13、8/8 全部按声明顺序） | `OfflineTrack` a..i、`QqFallbackCounters` a..m、`EntryDto` a..h | 本次 mapping @406572+、@424065+、@409687+ |

结论（含【推断】）：字母**目前恰好稳定**，但它的决定因素是「类内字段集合与顺序」+「R8 全局方案」。
在类中间插一个字段、删一个字段、或 R8 版本/规则变化，都会让后续字母位移；类名字母已经实测漂移过一次。
所以正确的说法不是「现在没坏」，而是「**契约掌握在构建工具手里，而构建工具不承诺任何跨版本兼容**」。
这也解释了为什么 v2.5.4 选择「不加 `-keep class …library.**`」（加 keep 会同时改掉 `cache.**` 那套已落盘的单字母 key，用一个静默丢失换另一个，见 `EVIDENCE.md:235-238`）。

### 4.3 需要人工判断 / 未验证

1. **修 F1/F3 必须带读侧兼容，否则等于制造一次真实的断裂**。现场教训就在本仓：v2.5.4 给 `QqFallbackCounters` 加 `@SerializedName` 后，
   老 blob `{"a":30,"b":0,…,"k":…,"m":1}` 被新版本**读成全 0**（`EVIDENCE.md:82-86`，作者自己标注为「这正是字段名是跨版本契约的现场演示」）。
   所以 F1 的修法必须照抄 `SearchHistoryCodec` 的三形状读：**新形状（稳定名）→ 已知旧单字母（`a`~`i`，顺序即上表）→ 未知 key 时按声明顺序兜底**。
2. **不要用 `-keep class com.takahashirinta.ncrust.cache.** { *; }` 来修 F1**。它会改全局混淆映射，让**已经落盘的 `a`~`i`** 在新版本里变成别的字母 ⇒ 制造断裂。
   正确姿势是「显式 `@SerializedName` + 读侧兼容」（`library.SearchHistoryCodec` 已跑通的模式）。
3. **`search_history` 的「新写入走稳定名」仍缺真机闭环**：v2.5.4 自己在 `EVIDENCE.md` §6.1 #9 标注未验证。
   本次用 dex 反汇编补上了「注解确实进了 release dex」（【DEX】，比单测强），但没有「真机写入 → pull XML → 读回」。
4. **`OfflineTrack` 的 `c`/`d` 缺失是推断**（§3.1）：结论不依赖它，但若要写进发布说明，建议补一次真机 XML dump。
5. **两条 `Map` 型落盘的 key 语义要写进契约**：`song_positions` 的 map key 是 `songId.toString()`（`Long` 键 → JSON 字符串键，数据）、`entries` 的 key 也是 `songId.toString()`、`ncrust_qq_playlists` 的 key 是 `list:<source>:<ownerId>`。
   这些 key 都是数据、R8 不碰，但**改 key 格式同样是跨版本断裂**（属另一类问题，本次不展开）。

### 4.4 假阳性汇总（一句话理由）

- `ncrust_offline/urls`：序列化的是 map，key 是数据串。
- `theme_mode` / `accent_source`（枚举 `.name`）：常量**字段**被改名，但 `Enum.<init>` 的字符串常量在 dex 里原样存在【DEX】。
- `ContentCache`：纯内存，压根没落盘（其 KDoc 也这么写）。
- 所有网络 payload：`mapOf`/`JSONObject` 字面 key + Retrofit `@Field("…")`，键名是字符串字面量。
- Coil / media3 `SimpleCache` / PNG：二进制或 SQLite，键是数据。
- `SavedState`：手工从 prefs 标量拼装，从不经 Gson。

---

## §5 防复发机制建议（含可行性评估）

> 判定原则：**单测抓「源码契约」，产物守门抓「构建结果」**。两者缺一不可 ——
> 单测跑在 JVM 上、没有 R8，永远抓不到「keep 规则被删」；mapping 守门跑在构建后，抓不到「注解值写错」的语义错误。

### 建议 A（推荐，成本最低，已有先例）：每个持久化 codec 配一条 golden key 单测

- 做法：断言**精确 key 集合**而不是 `contains`。先例已是仓库风格 —— `SearchHistoryCodecTest.kt:62-65` 同时断言「有 `"id":`」与「没有 `"a":`」。
  给 F1/F2/F3 补：
  ```kotlin
  // OfflineLibraryCodecTest
  val json = OfflineLibraryIndex.fromJson(legacy).toJson()      // 或直接构造
  assertEquals(setOf("songId","name","artist","albumPicUrl","durationMs",
                     "level","cacheKey","approxBytes","completedAt"),
               JsonParser.parseString(json).asJsonArray[0].asJsonObject.keySet())
  ```
- 工作量：约 0.5 天（3 个落点 × 1 条用例 + 旧形状兼容用例）。
- 能抓住：字段改名、加字段忘注解、注解值被改、读侧兼容分支失效。
- 抓不住：keep 规则在 release 阶段失效（JVM 无 R8）。
- 可行性：**高**（现有依赖 `gson` + 已有 4 个同类测试文件，无需新工具）。

### 建议 B（推荐，唯一能覆盖 keep 规则）：release 产物守门（mapping.txt 断言）

- 做法：`tools/verify-r8-keys.sh`（放仓库外 `tools/`，与现有探针脚本同风格）或 `app/build.gradle.kts` 里加一个 `verifyReleaseMapping` task，在 `assembleRelease` 之后跑：
  ```bash
  # 判据 1：显式命名 DTO —— 允许字段被改名，但每个字段必须在 dex 里带注解
  #   （mapping.txt 不记录注解，故这里用「白名单类」表示已人工确认过注解）
  # 判据 2：keep 覆盖的包 —— 类头必须是 identity，且不得出现字段改名行
  awk '...' mapping.txt   # 对 network./lyric./playlist./local./crosssource. 前缀的类，
                          # 若出现 "    <type> <field> -> <short>" 形式的字段行 ⇒ 失败
  # 判据 3：未命名又未 keep 的持久化 DTO 黑名单 —— 一旦出现就失败
  #   （当前黑名单：cache.OfflineTrack / player.PlaybackStateManager$PositionEntry / library.AlbumInfo）
  ```
  更狠一点可以做**差分守门**：把上一版 tag 的 mapping.txt 也拉出来，对同一类做字段字母 diff，**只允许追加**、不允许已有字段换字母（这是真正的「跨版本契约」判据）。
- 工作量：0.5 天（脚本）/ 1 天（带差分）。
- 能抓住：keep 规则缺失或被误删、新 DTO 未命名、R8 行为变化、字母位移。
- 抓不住：注解值写错（`@SerializedName("nmae")`），需要建议 A 兜。
- 可行性：**高**（mapping.txt 每次 release 都产出、无需额外工具；`configuration.txt` 还能顺带核验 keep 规则是否真生效）。

### 建议 C（中等成本）：单一清单驱动的「持久化 DTO 注册表」测试

- 做法：新增一个 `PersistedStructures.kt`（**放测试源集，不动 main**）声明「DTO 类 + 落盘位置 + 稳定名清单」，
  再用一条参数化测试对每个 DTO 做两件事：① 反射断言每个字段「有 `@SerializedName` 或所属包在 keep 白名单」；② 序列化后断言 key 集合等于注册表里的稳定名。
  这样新增落盘结构时，**忘了登记就会红**。
- 工作量：1~1.5 天。
- 能抓住：新增 DTO 忘记命名/登记；把「哪些结构是契约」这件事从注释变成可执行清单。
- 可行性：**中高**（`javaClass.declaredFields` + 已有 Gson 依赖即可；注意 `internal` 可见性需要测试与 main 同模块 —— 现有 `app/src/test` 已能访问 `internal`，先例是 `OfflineLibraryTest`）。

### 建议 D（高成本，不建议本版做）：自定义 Android Lint 规则

- 做法：新建 `lintChecks` 模块 + UAST `Detector`，拦截 `Gson#toJson(Object)` / `Gson#fromJson(..., Class)` 的实参静态类型，
  若该类型是 `data class`、字段存在无注解项、且包不在 keep 白名单 ⇒ 报 error。
- 难点/成本：2~3 天（新模块 + `lintChecks` 依赖 + 与现有 `app/lint-baseline.xml` 基线流程共存）；泛型/集合/`TypeToken` 间接引用会产生误报，需要白名单维护。
- 结论：**可行性中，性价比低**。建议先落 A + B；若 v2.6 之后落盘结构数量继续增长，再考虑 D。

### 建议 E（顺带，零成本）：把「为什么不能加 keep」写进 `proguard-rules.pro` 的注释

- `app/proguard-rules.pro` 目前对 `cache.**` 一字未提。建议加一段注释（**本探针不改 app/，故只建议**）：
  指向本文件 §3/§4.2，并写明「修 `cache.**` 要带读侧兼容，禁止用 `-keep` 解决，因为它会改全局映射、反而断掉已落盘的单字母 key」。
  成本：0；价值：阻止下一个人用最省事但最有害的方式"修"它。

### 建议优先级

| 顺序 | 项 | 理由 |
|---|---|---|
| 1 | 修 F1 + F2 + F3（显式命名 + 三形状读） | 三个都是跨版本存活的真实风险；F1 已知，F2/F3 本次新发现 |
| 2 | 建议 A（golden key 单测） | 半天，直接锁住上面三个的修复成果 |
| 3 | 建议 B（mapping 守门脚本） | 唯一能覆盖 keep 失效；可与 release 流程绑定 |
| 4 | 建议 E（注释） | 零成本，防"用 keep 修" |
| 5 | 建议 C / D | 结构性投入，视后续新增落盘结构的速度决定 |

---

## 附录：原始证据文件

全部位于 `docs/verification/v2.5.5/probe-raw/r8-keys/`（本次新建，未改动 `app/`）：

| 文件 | 内容 |
|---|---|
| `01-gson-callsites.txt` | 全部 20 个 Gson 调用点（含行号） |
| `02-prefs-inventory.txt` | 全部 prefs 文件名常量 + `getSharedPreferences` 读点 + 写入点（值表达式） |
| `03-mapping-field-lines.txt` | 22 个类的字段映射（类 → 混淆名 + 字段 → 短名） |
| `04-mapping-with-line-numbers.txt` | 9 个关键类的 `grep -n` 摘录，行号可逐条指认 |
| `05-seeds-kept-members.txt` | keep 命中的 seeds 计数（keep 类 ≠ 0，三个风险类 = 0）+ `SongItem` 被 seed 的成员示例 |
| `06-effective-keep-rules.txt` | `configuration.txt` 里实际生效的 10 条 ncrust keep 规则 |
| `07-dex-annotated-dtos.txt` | 【DEX】`EntryDto`/`QqFallbackCounters`：混淆字段名 + 注解 `value="稳定名"` 同框 |
| `08-dex-risky-dtos.txt` | 【DEX】`OfflineTrack`/`PositionEntry`/`AlbumInfo`/`OfflineUrlIndex` 的裸字段 |
| `09-dex-enum-name-strings.txt` | 【DEX】`ThemeMode.<clinit>`：常量字段改名但字符串常量原样 |
| `10-dex-kept-dtos.txt` | 【DEX】`SongItem`/`CachedLyrics`：keep 后字段名原样 |
| `11-in-repo-device-evidence.txt` | 引用 v2.5.4 / v2.3.0 的真机形状与两次构建的 mapping 对照 |
