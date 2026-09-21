# CLAUDE.md

This file provides guidance to Claude Code when working in this repository. It is a mirror of **`AGENTS.md`** - that file is the canonical agent guide and the single source of truth. **When the two disagree, `AGENTS.md` wins** (or just re-copy it). When in doubt, the source wins - update `AGENTS.md` first, then sync this file.

## Build & Test Commands

```bash
./gradlew assembleDebug            # debug APK  -> app/build/outputs/apk/debug/
./gradlew assembleRelease          # release APK (R8 minified), needs keystore.properties
./gradlew test                     # JVM unit tests
./gradlew connectedAndroidTest     # instrumented tests (device/emulator required)

# Macrobenchmark (performance; drives a signed release build, not connectedCheck)
benchmark/run_benchmark.sh [startup|scroll|expand|all]     # default: all

# Standalone Go backend — NOT used by the app (see ncrust-api/ below)
cd ncrust-api && go build -o ncrust-api .
```

Toolchain:

| | |
|---|---|
| compileSdk / targetSdk | 36 (Android 15) |
| minSdk | 24 |
| Java / jvmTarget | 11 |
| Kotlin | 1.9.24 |
| Compose BOM | 2024.12.01 (compiler ext 1.5.14) |
| Android Gradle Plugin | 8.5.0 |
| Gradle | wrapper 9.3.1 |

**Composite build:** `settings.gradle.kts` does `includeBuild("../Kanesumi-sec-a")` with explicit `dependencySubstitution`, so the app builds against the sibling `Kanesumi-sec-a` checkout (not a published artifact). Cloning Ncrust alone is not enough — `Kanesumi-sec-a` must sit next to it. Delete the `includeBuild` block once Kanesumi is on Maven Central; the coordinates in `app/build.gradle.kts` then work unchanged.

## Repository Layout

```
Ncrust/
├── app/                  # the Android application (Kotlin + Jetpack Compose)
├── benchmark/            # macrobenchmark module (3 benchmarks + run_benchmark.sh)
├── ncrust-api/           # standalone Go reverse proxy — orphaned, not wired into the app
├── build.gradle.kts      # top-level plugin declarations
├── settings.gradle.kts   # :app, :benchmark + Kanesumi composite build
├── gradle.properties     # jvmargs, AndroidX, suppressUnsupportedCompileSdk=36
├── keystore.properties   # gitignored, release signing (storeFile/storePassword/keyAlias/keyPassword)
└── local.properties      # gitignored, sdk.dir
```

- **`app/`** — everything documented below.
- **`benchmark/`** — `StartupBenchmark` (cold start, 8 iters), `HomeScrollBenchmark` (frame timing, 6 iters), `ExpandPlayerBenchmark` (player expand/collapse, 6 iters, HOT start). Results land in `benchmark/output/<device>/` (gitignored) as `*-benchmarkData.json` + Perfetto traces. `benchmark/cookie.secret` (gitignored) injects a login cookie for realistic runs; `cookie.secret.example` documents it.
- **`ncrust-api/`** — a self-contained Go 1.21 service (stdlib only) that reverse-proxies NetEase with eapi encryption and TTL caching. It includes a checked-in 9.75 MB binary. **The Android app never calls it** — the app talks directly to `music.163.com` / `interface*.music.163.com`. Treat it as an unused side artifact / future scaffold; do not assume it is part of the runtime.

## Versioning & Release

Single source of truth: `app/build.gradle.kts` → `defaultConfig.versionName` / `versionCode`.

- `AboutScreen.kt` reads `BuildConfig.VERSION_NAME` — **never hardcode a version constant**. This needs `buildFeatures.buildConfig = true`.
- Release flow: bump `versionCode` + `versionName` → commit `build: 升级至 vX.Y.Z ...` → `./gradlew assembleRelease` → `gh release create vX.Y.Z --draft <apk>` → user smoke-tests and publishes manually.
- Current: `versionName = "1.3.1"`, `versionCode = 6`. Latest release: `v1.3.1` (2026-09-11).

## Commit Convention

All development history lives in `git log`. **Do not create a parallel `*_log.md` file.**

Conventional Commits, lowercase type prefix, Chinese subject:

| Prefix | When to use |
|---|---|
| `feat:` | User-visible new capability (features, UI additions, new APIs) |
| `fix:` | Bug fix; no new behaviour beyond restoring correctness |
| `chore:` | Housekeeping (delete unused files, rename dirs, gitignore) |
| `docs:` | Docs-only changes (README, AGENTS.md, in-code comments) |
| `build:` | Build system / dependencies / version bumps |
| `refactor:` | Code-shape change without behavioural change |
| `perf:` | Performance-only optimisation |
| `style:` | Formatting, whitespace, comment tweaks |

- Subject: prefix + one-sentence Chinese summary. Body (blank line, then paragraphs) explains **why** — commits should be readable a year later without opening a PR. Reference issues with `Fixes #N` / `#N`.
- **One logical section = one commit, committed proactively without waiting to be asked.** Don't batch unrelated changes (e.g. "extract txt + fix color + update docs") into one commit. Split by logical unit, not by file count: changes that must compile together are one section; independently revertable changes are separate.
- Multiple related fixes may share a commit only when they are the same logical change; pick the dominant type by user impact.

## What This App Is

Ncrust is a third-party NetEase Cloud Music (网易云音乐) Android client built around three design priorities:

1. **Kanesumi Design** — right-angle cuts, no curves, no rounded corners, information-first.
2. **GPU zero-recomposition** — animations driven by a single `progress: Float` through `graphicsLayer`, not state-driven recomposition.
3. **Three-layer graphics architecture** — main page / player card / navigation bar are independent composable layers, enabling gesture transitions without interference.

Feature surface at v1.3.1: home discovery (daily songs / recommended playlists / new songs / private FM), three-type search with 500 ms debounce + history, cloud-synced library (liked songs / subscribed albums / user playlists), full-screen player with gapless playback and an 8-level quality ladder, 5 playback modes, bilingual lyrics, system media controls + Android Auto / Automotive, WebView + QR login, runtime theming (6 colors × 3 modes), and 8 runtime languages.

## Terminology: Kanesumi Design

The design language is **Kanesumi Design** (canon: Ether monorepo root `KANESUMI_DESIGN.md`), historically "Metro Design". Notes for agents:

- `Metro*` identifiers (`MetroText`, `MetroTheme`, `MetroIndication`, easing constants `MetroDefault`/`MetroCubic`…) are code/component names — **keep, do not rename**.
- New docs/comments: write 「Kanesumi Design / Kanesumi 风格」, not 「Metro Design / Metro 风格」. The easing family is 「UWP 缓动」 (`UwpEasing`), from the Metro era.
- The `Metro*` UI components now live in the external **Kanesumi** library (see below), not in this repo.

## Architecture

### Entry Point & Orchestration

`MainActivity.kt` is a lean entry point (the activity class is ~130 LOC) plus the `MainScreen()` composable in the same file. `MainActivity`:

- applies the orientation policy (phones `< 600dp` locked portrait; large screens unlocked) via `smallestScreenWidthDp`, re-run in `onConfigurationChanged`;
- requests `POST_NOTIFICATIONS` on API 33+;
- calls `RetrofitClient.init(this)` and `AppWarmup.start(this)`;
- builds the theme stack: `LocalStrings` + `metroViewConfiguration` → `NcrustTheme` → `MetroTheme` → `MainScreen` + `SplashScreen` + first-run battery prompt.

`MainScreen()` owns all app-level orchestration: bottom navigation / wide-screen sidebar, the `progress: Animatable<Float>` that drives the player card, the playback queue, the 5 playback modes, the global song menu sheet, clipboard link handling, and the About / WebView-login overlays.

Also at file scope: `formatDuration(ms)`, and `object QueueModes { CYCLE=0, SINGLE=1, SHUFFLE=2, LINE=3, INFINITY=4 }`.

### Package Layout

Under `com.takahashirinta.ncrust/`:

| Package | Purpose |
|---|---|
| `network/` | Retrofit interface (`NcmApi`), eapi/weapi endpoints (`PlaylistApi`), `RetrofitClient` gateway, `CoverUrls`, response models |
| `network/crypto/` | `EapiCrypto` (AES-128-ECB + MD5 sign), `WeapiCrypto` (double AES-CBC + RSA) |
| `player/` | `PlaybackService` (MediaLibraryService + ExoPlayer), `PlaybackStateManager`, `SongUrlFetcher`, `PlayReporter` |
| `auth/` | `CookieManager` (SharedPreferences), `QrPair` / `QrPairClient` / `QrPairServer` (LAN QR handoff) |
| `library/` | `LibraryManager` (cloud-synced liked songs + subscribed albums), `SearchHistoryManager` |
| `cache/` | `ContentCache` — in-memory network snapshot (home + LRU detail caches + user profile) |
| `lyric/` | `LrcParser` (`[MM:SS.mm]` → `LrcLine.timeMs`), `LyricsCache` (200-entry persistent cache) |
| `warmup/` | `AppWarmup` — cold-start preload singleton |
| `power/` | `BackgroundActivity` — battery-optimisation whitelist intents |
| `ui/navigation/` | `NavRoutes` route constants and `MainNavGraph` |
| `ui/player/` | `PlayerCardOverlay`, `PlayerCard`, `FullPlayerControls`, `LyricsView`, `QueueView`, `SlimProgressBar` |
| `ui/screen/` | One file per screen (Home, Search, Library, User, Album/Artist/Playlist/Song detail, About, Splash) |
| `ui/viewmodel/` | `PlayerViewModel`, `SearchViewModel`, `SongViewModel` |
| `ui/components/` | Reusable composables (`SongCard`, `DetailScaffold`, `SongMenuSheet`, `PlayAllDialog`, `QrLoginDialog`, `QrScannerScreen`, `ArtistSearchItem`, `BackgroundActivityDialog`) |
| `ui/theme/` | `NcrustColors`, `NcrustTypography`, `ThemeManager`, `ThemeColorSelector`, `MarkdownText` |
| `ui/i18n/` | Runtime i18n: `Strings` data class, per-language files, `LanguageManager` |
| `ui/ResponsiveContent.kt` | 360dp (phone) / configurable (wide) max-width centre container |
| `ui/BottomOverlayInset.kt` | `BottomOverlayInsetDp` constant (144dp narrow / 64dp wide) |

### Kanesumi Library (external)

The app's shared UI components and animation vocabulary have **migrated out of this repo** into `Kanesumi-sec-a` (Apache-2.0), consumed via composite build:

- `kanesumi-core` — `MetroTheme`/`MetroColors`/`MetroTypography`, `MetroText`/`MetroIcon`, `MetroInsets`, `MetroIndication`.
- `kanesumi-anim` — `sokuou` package: `UwpEasing`, `SokuouPresets`/`SokuouTweens`, `sokuouSpring`, `MetroFling`/`MetroScroll`, `metroViewConfiguration`.
- `kanesumi-controls` — `MetroSurface`, `MetroButton`, `MetroTabRow`, `MetroSwitch`, `MetroIconButton`, `MetroDropdownMenu`, `MetroBottomSheet`, `MetroLyricsPanel`, `MetroDialog`, …
- `kanesumi-structure` — `MetroShell`, `MetroAppBar`, `MetroDetailScaffold`, `MetroTopScrim`, `MetroBottomNav`, `MetroSidebar`.

**There is no `androidx.compose.material3` dependency in the app** and no `material3` import anywhere. The only Material artifact left is `androidx.compose.material:material-icons-extended` (icons only). The former in-repo `ui/anim/sokuou/` directory no longer exists.

When editing UI, prefer Kanesumi components/presets. **Do not batch-refactor existing hand-tuned player-card animations** — they are load-bearing.

### Player Card Component Tree

- **`PlayerCardOverlay`** — positions the card via `graphicsLayer { translationY = collapsedOffsetY + (0 - collapsedOffsetY) * progress.value }`. Thin wrapper, no animation logic.
- **`PlayerCard`** — gesture handling (vertical drag, 25% snap threshold), cover-art animation, mini bar, lyrics/queue toggle, wide-screen two-column layout. Owns `lyricAnimProgress` (0 = large cover → 1 = small cover), `queueSlideProgress` (0 = lyrics → 1 = queue), and `wideSplit`. `StableCover` holds the previous cover across song changes (`COVER_HOLD_MS = 400L`). All animation values are read inside `graphicsLayer`.
- **`FullPlayerControls`** — transport buttons, seekable progress bar, position/duration/quality leaf composables (each collects its own StateFlow), lyrics/queue/library toggles, `compact`/`landscape` variants.
- **`LyricsView`** — wraps Kanesumi `MetroLyricsPanel`. Golden-section positioning, 5 s manual-scroll pause, tap-to-seek, bilingual `tlyric` merge.
- **`QueueView`** — three-zone queue (past / current / upcoming), drag-reorder limited to the upcoming zone, INFINITY placeholder, auto-centre on open.
- **`SlimProgressBar`** — Canvas-drawn 40dp-touch progress bar with a lazily-started buffering pulse.

`progress: Animatable<Float>` (0 = mini, 1 = full) is owned by `MainScreen` and passed through `PlayerCardOverlay` → `PlayerCard`. Card expand/collapse: 400 ms `CubicBezierEasing(0.2, 0, 0, 1)` / 260 ms `FastOutSlowInEasing`.

### Animation Pattern

The player card uses a single `progress: Float` driven by `Animatable`. All visual properties (card size, position, opacity) are computed inside `graphicsLayer { }` — **never `animateFloatAsState`**. This is the core GPU zero-recomposition pattern. Easing is `tween + CubicBezierEasing` (controlled deceleration, no spring/bounce). Cover art always fills the full screen width with no clipping; transitions use centre-based `TransformOrigin(0.5f, 0.5f)` with computed translations.

For anything that is *not* the player card, use the Kanesumi `sokuou` presets (`SokuouTweens.CoverFade`, `SokuouPresets.*`, `mapRange`/`mapRangeClamped`) instead of ad-hoc `tween(...)`.

### Network Layer

Two API styles coexist:

- **REST via Retrofit** (`NcmApi`, base `https://music.163.com/`): search (`api/cloudsearch/pc`, types 1/10/100), song detail (`api/v3/song/detail`), lyrics (`api/song/lyric`), album detail (`api/v1/album/{id}`), artist albums (`api/artist/albums/{id}`).
- **eapi via manual OkHttp POST** (`PlaylistApi` + `RetrofitClient.eapiPost`): playlists, recommendations, daily songs, personal FM, similar songs, song URL, album sub/unsub, like. **weapi** (`RetrofitClient.weapiPost`) is used for QR login (`/api/login/qrcode/unikey`, `/api/login/qrcode/client/login`) and the similar-song fallback.

### Playlist 写操作（v1.3.0 · B1）

歌单管理（创建 / 删除 / 增删曲 / 改名 / 改描述 / 改隐私）走 `PlaylistEditApi` + `PlaylistWriteGate`，
端点与行为全部为 2026-09 登录态实测，不是文档推断：

| 操作 | 端点 | 参数 | 备注 |
|---|---|---|---|
| 创建 | `/eapi/playlist/create` | `name`(必填) `privacy`(0/10) | 顶层 `id` 与 `playlist.id` 相等 |
| 加/删曲 | `/eapi/playlist/manipulate/tracks` | `op`(add/del) `pid` `trackIds` `imme` | 返回 `count` = 操作后曲目数 |
| 删除 | `/eapi/playlist/delete` | **`pid`** | 非本人 → 401 无权限操作歌单 |
| 批量删除 | `/eapi/playlist/remove` | **`ids`**=JSON 串 | 对非本人 pid 返回 200 却无动作，不可用于判权限 |
| 改名 / 描述 / 隐私 | `/eapi/playlist/update/name`、`/desc/update`、`/update/privacy` | `id` + 对应字段 | 三个独立端点，`/playlist/update` **不存在**（404） |

- **鉴权**：写操作的 HTTP 状态码恒为 200，成败只看 `body.code`。需要 cookie 追加身份
  （`ClientIdentity.extraCookieFor`）**或** `csrf_token`，缺一即 403 `illegal request!`
  （实测只带原 cookie 必 403）；**不需要** `eapiPostOfficial` 那套 body header。
  `csrf_token` 的值服务端不校验，但照带 `__csrf` 最稳。
- **`op` 白名单是硬约束**：实测任何非 `add` 的值（含空串、未知值）都会被服务端**当成删除执行**。
  所以请求体只能由 `addSongsPayload`/`removeSongsPayload` 构造，绝不透传外部输入。
- **502 = 幂等成功**：`歌单内歌曲重复`/`歌单歌曲重复` 两种文案都出现过；部分重复时返回 200 且新歌入库。
- **写入判定不能用 detail**：`/eapi/v6/playlist/detail` 有陈旧缓存 —— 歌单**删除后 20 分钟仍返回
  code 200 + 完整内容**，写入后 `trackCount` 也滞后数秒。判定「是否生效」只能用响应体的 `count`
  或 `/eapi/user/playlist`。另外 detail 的 `n` 上限 1000、`s`/`offset` 全部无效，**没有可翻页的
  曲目列表端点**，>1000 首的歌单拿不全（判重只能放弃，交给 502 兜底）。
- **405 限流按身份维度**：`操作过于频繁，请稍后再试`；实测同一时刻 pc 身份持续 405，换成
  os=android/新 deviceId 立刻通过（eapi/weapi/明文 api 三条路报错一致）。客户端只做「串行 +
  最小间隔 2s」（`PlaylistWriteGate`）并在 405 时提示等待，**不做自动重试**。
- **归属判定**：收藏的歌单里 `userId`/`creator.userId` 都是原作者，判自建必须
  `creator.userId == 我 && !subscribed`（`PlaylistInfo.isOwnedBy`）。

`RetrofitClient` hosts:

| Constant | Value |
|---|---|
| `BASE_URL` | `https://music.163.com` |
| `API_URL` | `https://interface.music.163.com` (default eapi host) |
| `INTERFACE_URL` | `https://interface3.music.163.com` (eapi when `useInterface = true`, i.e. song URL fetch) |

- `init(context)` loads the cookie from `CookieManager`; `updateCookie(...)` replaces it (login/logout).
- `eapiPost(path, payload, useInterface)` encrypts via `EapiCrypto`, sends form `params=<hex>`, PC UA + Referer + Cookie.
- `eapiPostOfficial(path, payload)` mimics the official iPhone client (header with `os=iphone`, `appver=8.9.60`, deviceId/osver from cookie, random `requestId`); used for `likeSong`. Encrypted write responses are decrypted with `EapiCrypto.decryptResponse`.
- `weapiPost(path, payloadJson, extraCookies)` produces `params` + `encSecKey` via `WeapiCrypto`.
- `get(path)` plain GET; `postWeblog(url, logsJson)` form POST for playback reporting.
- A cookie interceptor injects `Cookie`/`User-Agent`/`Referer`; `HttpLoggingInterceptor` is `BuildConfig.DEBUG`-only.

`EapiCrypto`: AES key `e82ckenh8dichen8`, `/eapi/` → `/api/` path rewrite, signature `MD5("nobody" + path + "use" + json + "md5forencrypt")`, payload `path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest`, AES-128-ECB/PKCS5 → hex.

`WeapiCrypto`: random 16-char secret, double AES-128-CBC/PKCS5 with preset key `0CoJUm6Qyw8W8jud` then the secret, and `encSecKey` = raw RSA of the reversed secret (256 hex, no PKCS#1 padding, not base64).

### ✅ 详情页播放器死带（v1.3.0 已修 · B-1，此节保留根因与实测数据）

折叠态播放器卡片的 `Box(Modifier.fillMaxSize())` + `graphicsLayer { translationY = collapsedOffsetY }`
（[PlayerCard.kt:404-422](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)、
[PlayerCardOverlay.kt:37-43](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCardOverlay.kt)）
会在屏幕 **y ≳ collapsedOffsetY**（本机 S6/G9209 实测 ≈1872px）以下形成一条**死带**：肉眼看不见，
**但仍参与命中测试，且先于下层详情页拿到事件**（Alpha=0 在 Compose 里不会退出命中测试）。

实测证据（同一页面、同一 ⋮ 按钮）：

| 触摸 y | 按钮节点收到事件 | 页面级 Initial pass | 结果 |
|---|---|---|---|
| 1850（带上沿之上） | ✅ pressed → CLICK | — | 菜单弹出 |
| 1938（带内） | ❌ 一条都没有 | ❌ 也没有 | 完全无响应 |

**规则**：详情页（歌单/专辑/歌手）的交互元素不得落在该带内。需要底部操作时用**顶部 scrim**
（y≈208–400，实测可用）或**列表行入口**承载。

**已踩坑**：

- 歌单详情页「编辑歌单」⋮ 原本放在 `DetailHeader.headerActions`（y≈1842–2016）→ 点不动
  （2/6 命中，全在带上沿）；现已移到 `DetailScaffold.onTopEndAction`（TopScrim 右上）。
- AlbumDetail 的收藏按钮（y≈1616–1808）侥幸落在带上沿之上，**能点但不可靠** —— 换首歌单、
  换封面高度就可能落进带内。
- 同一原因：`MetroBottomSheet`（如歌单操作菜单）画在本页 `LazyColumn` 之下会被盖住，
  且自身也落在带里 —— 详情页不要用底部菜单承载操作，改用 `MetroDialog`。

**根因与修复（v1.3.0 · B-1）**：那一段里其实一个子节点都没有 —— mini bar 与 Column 的内容都被
statusBarsPadding() 下推到 2016，吃事件的是卡片根 Box 自己的两个 pointerInput（上拉手势 / 展开态吞事件），
命中区就是整个 fillMaxSize 根 Box。修法两步：① 折叠态不挂载展开态子树（mini bar 永远挂载）；
② 折叠态给根 Box 一个 statusBar 高的 top padding 收窄这两个 pointerInput，再用等量 offset 把子节点
放回原位（视觉与布局零变化）。实测（API 24 模拟器，同一坐标 y=1188 同长度上滑）：修复前把整卡拉起，
修复后事件还给下层；mini bar 上滑展开、点按展开均正常。「清空队列没有真正移除 overlay」已由 commit A 修掉。

### Playlist 管理（v1.3.0 · B2–B5）

歌单的创建/编辑/删除/增删曲全部走 `PlaylistEditApi`，UI 入口固定四处：

| 入口 | 位置 | 行为 |
|---|---|---|
| 保存当前队列为歌单 | 全屏播放器队列区 ⊕（`onSavePlaylist`，此前是空壳） | 快照队列 → 命名对话框 → 创建 → 批量加歌 |
| 新建空歌单 | 收藏页歌单 tab 网格第一格「＋」 | 复用同一对话框，不加歌 |
| 加入歌单 | 任意歌曲长按菜单第一项（由 `MainActivity.showSongMenu` 统一追加，全 Screen 覆盖） | `AddToPlaylistSheet` 只列**本人自建**歌单 + 顶部「新建歌单」 |
| 编辑 / 删除 / 改隐私 / 移除曲目 | 歌单详情页**顶部 scrim 右上「⋮」**（`DetailScaffold.onTopEndAction`）与曲目长按菜单 | ⋮ 直接开 `EditPlaylistDialog`（名称+简介+隐私，**只发改动过的字段**）+ 底部「删除歌单」（二次确认）；移除曲目在曲目长按菜单，均仅自建歌单可见 |

- **归属判定**：`PlaylistInfo.isOwnedBy(uid)` = `creator.userId == uid && !subscribed`。收藏的歌单
  里 `userId`/`creator.userId` 都是原作者，用 `userId == 我` 会把别人的歌单显示成可编辑。
- **判重不做预判**：`>1000` 首的歌单没有可靠判重手段（见上），重复添加由服务端 502 兜底，
  客户端把它当幂等成功、提示「已加入歌单」——与真的加进去在用户视角无区别，还省一次往返。
- **缓存**：任何写操作成功后调用 `ContentCache.invalidatePlaylist(id)`。服务端 detail 本身有陈旧
  缓存，本地这份不丢就会出现「改名成功、进详情还是旧名」。
- **收藏页的 playlist tab 过滤 `specialType != 0`**，所以「我喜欢的音乐」不在网格里（它是单曲 tab
  的数据源，见 `PlaylistApi.getLikedPlaylistId`）。

### Playback

`PlaybackService` is a **`MediaLibraryService`** (media3), not a plain `MediaSessionService`. It owns:

- an `ExoPlayer` with audio focus, `handleAudioBecomingNoisy`, `WAKE_MODE_NETWORK`, and `DefaultLoadControl(30_000, 60_000, 2_500, 15_000)`; **no on-disk media cache**;
- a media3 `MediaLibrarySession` (Android Auto / Automotive browse tree: root → 每日推荐 / 私人 FM / 我喜欢的音乐, resolving `song:<id>` through `SongUrlFetcher`) and a legacy `MediaSessionCompat` (notification / lock-screen);
- a foreground MediaStyle notification (channel `ncrust_playback`, dominant-colour colorization, artwork bitmap);
- a **2 Hz** progress ticker (`delay(500)`);
- error reporting: both `onPlayerError` and `onAudioSinkError` invoke the static `onPlaybackError(songId)`.

`PlayerViewModel` holds all playback state as `MutableStateFlow` (isPlaying, position, duration, progress, buffering, current song metadata, lyrics, `currentQualityIndex`, `needsPreload`). `PlaybackStateManager` persists the last song + queue to SharedPreferences via Gson so playback survives process kill.

**Song URL resolution** (`SongUrlFetcher`) starts at the selected quality and falls back down the ladder. Exact ladders:

```
dolby    -> dolby, hires, lossless, exhigh, higher, standard
jymaster -> jymaster, hires, lossless, exhigh, higher, standard
jyeffect -> jyeffect, lossless, exhigh, higher, standard
hires    -> hires, lossless, exhigh, higher, standard
lossless -> lossless, exhigh, higher, standard
exhigh   -> exhigh, higher, standard
higher   -> higher, standard
standard -> standard
```

- **Device FLAC gate**: skips `lossless`/`hires`/`jyeffect`/`jymaster` when no MediaCodec `audio/flac` decoder exists (API < 27 or stripped ROM), so ExoPlayer never gets a FLAC stream it can only render as silence.
- **`sky`（沉浸环绕声）：已探明，不实现为独立档位（v1.2.1 TODO 关闭）**。服务端**没有**独立的沉浸声音频文件：账号态 36 首 `maxBrLevel=sky` 曲目的 privilege 里 `maxbr`/`pl` 上限就是 320000，`song.sq`/`song.hr` 均为 null —— 即这些曲子的最高源就是 `exhigh` 320k mp3。实测 `level=sky` 与 `exhigh` 取回的是**同一个文件**（CDN 回源比对文件名/大小/哈希，6/6 一致）；即使请求 `level=sky` 时换成 `encodeType=mp4`、换 appver（3.0.6/8.9.60/9.1.20/9.3.40）或换成 pc/android/iphone 身份，结果都不变；在有母带源的曲目上请求 `level=sky` 会返回该曲最高可用源（jyeffect/jymaster FLAC），说明 sky 请求的语义就是「取该曲最高源」。官方客户端听感上的「环绕感」来自其客户端 DSP 渲染，Ncrust 无法复现，因此加进档位表只会把一个 320k mp3 标成沉浸环绕声、误导用户 —— 故 v1.3.0 明确不实现。复现方式见仓库外 `tools/probe-quality.py` 与本轮 level×encodeType×身份矩阵。
  **客户端侧配套（v1.3.0 · B4）**：档位上限为 `sky` 的曲子，用户在设置里选 hires/无损时，`QualityAssessment` 会把 `sky` 归一化成 `exhigh` 再比较上限（`CAPABILITY_ALIASES`），角标显示「该曲无此档位」；选 exhigh 则不加角标。归一化只改判定、不改档位表（设置页仍是 8 档，不含 sky）。
- **Auto quality downgrade**: `PlayerViewModel.handlePlaybackError` retries the same song one rung lower on `qualityRetryLadder` (reverse order, deduped 3 s per `songId@level`), skipping to the next song only when even `standard` fails. `AudioSink` failures are covered too. The badge is decided by `QualityAssessment` from the **actual file params** (br/type) plus the song cap: a `sky`-capped song requested at hires/无损 shows 「该曲无此档位」, not 「已降级」/「无权限」.
- **Level-aware preload cache**: `PreloadCacheEntry` records `requestedLevel`; a downgrade retry never replays an already-failed higher-tier URL. TTL = 5 min. Gapless preload window = **60 s** (`PRELOAD_THRESHOLD_MS`), despite some older comments saying 20 s.
- A song that yields no playable URL at any tier is skipped — **never** fall back to `.../song/media/outer/url?id=X.mp3` (it 302→404s HTML and buffers forever).

Quality preferences (indices into the 8-level ladder) live in `ncrust_settings` (migrated once by `QualityLadder.migrate` at app start):

| Index | Display (zh) | API level |
|---|---|---|
| 0 | 压缩 | `standard` |
| 1 | 较好 | `higher` |
| 2 | 更好 | `exhigh` |
| 3 | 无损 | `lossless` |
| 4 | 高解析 | `hires` |
| 5 | 高清环绕声 | `jyeffect` |
| 6 | 超清母带 | `jymaster` |
| 7 | 杜比全景声 | `dolby` |

Defaults: Wi-Fi = 3 (lossless), Mobile = 1 (higher). The selected label shows as a badge in `FullPlayerControls`.

#### Playback reporting (webLog)

`PlayReporter` replays the official web player's `webLog` so local listening feeds NetEase recommendations. On natural end **or** progress ≥ 80%, it POSTs a form `logs=JSON([{action:"play", json:{type:"song", wifi:0|1, download:0, id, time, end:"playend", mainsite:"1", mainsiteWeb:"1"}}])` to `https://clientlogusf.music.163.com/api/feedback/weblog?csrf_token=<csrf>`. **No eapi/weapi encryption.** Fire-and-forget on a `ncrust-weblog` thread, deduped per song, never blocks playback.

### Queue Management

All queue operations live in `MainScreen` (not `PlayerViewModel`): `replaceQueueAndPlay`, `playSongItem`, `insertNext`, `appendToQueue`, `insertAllNext`, `appendAllToQueue`, `removeFromQueue`, `moveInQueue`, `playFromQueue`, `playNext`, `playPrevious`, `generateShuffledIndices`, `launchInfinity`, `startFm`.

**Critical invariant:** `playbackQueue[currentQueueIndex]` must always equal the currently playing song. When deduping a queue mutation, record the current song's id first, filter, then re-locate the index — a naive `.filter` can drop an earlier duplicate and point `currentQueueIndex` at the wrong item. Every mutation calls `PlaybackStateManager.saveQueue(...)`, and any mutation in SHUFFLE mode regenerates the shuffled-index list.

5 playback modes (`QueueModes`): `CYCLE` (0), `SINGLE` (1), `SHUFFLE` (2), `LINE` (3, stops at tail), `INFINITY` (4, FM radio / similar-song continuation). `launchInfinity()` appends at the tail from `getPersonalFm` (FM mode) or `getSimilarSongs(seed)`, falling back to `getDailyRecommendSongs`, deduped and in-flight guarded.

### Content Cache & Load Transitions

`ContentCache` is an in-memory snapshot (not persistence — that is `LibraryManager`). It holds the three home lists + `homeWarmedUpAt` (15 s freshness window), LRU-32 caches for album / playlist-songs / artist-albums, and the user profile. It is cleared on `onTrimMemory` / `onLowMemory` / manual cache clear.

Load pattern for any network screen:

1. Read `ContentCache.getX(id)` as initial state.
2. If present, render immediately; do not show a loader.
3. Regardless, kick off a background refresh and write back on success.
4. Let `LazyColumn` diff by key.
5. Wrap loading↔content in `Crossfade(animationSpec = SokuouTweens.CoverFade)` — never a hard `if (isLoading) return Loader()`.

`DetailScaffold` takes `hasCachedContent: Boolean`; when `true` it suppresses the loader even if `isLoading`. Detail screens pass `hasCachedContent = (data != null)`.

### State Management

No DI framework — singletons as service locators: `RetrofitClient`, `CookieManager`, `LibraryManager`, `PlaybackStateManager`, `ContentCache`, `ThemeManager`, `LanguageManager` (via `LocalStrings`). No Room; all persistence is SharedPreferences + Gson.

SharedPreferences files:

| File | Owner(s) | Contents |
|---|---|---|
| `ncrust_prefs` | `CookieManager` | `user_cookie` |
| `ncrust_settings` | `ThemeManager`, `LanguageManager`, `PlayerViewModel`, `UserScreen`, `MainActivity` | theme index/mode, language, quality, gapless, lyrics translation, `battery_prompt_done` |
| `ncrust_library` | `LibraryManager` | `saved_songs`, `saved_albums`, `liked_ids` |
| `ncrust_playback_state` | `PlaybackStateManager` | last song + `queue` / `queue_index` |
| `ncrust_lyrics_cache` | `LyricsCache` | `entries` (≤ 200) |
| `search_history` | `SearchHistoryManager` | `songs`, `albums`, `artists` (≤ 10 each, 14-day TTL) |

### Auth & Login

`CookieManager` stores the raw cookie in `ncrust_prefs`. Login paths:

1. **WebView login** (phone default, and "通用登录" on tablet) — `MainActivity` shows a WebView at `https://music.163.com/#/login`, reads `MUSIC_U=` on `onPageFinished`, saves + `RetrofitClient.updateCookie`, then triggers `LibraryManager.refreshFromCloud`.
2. **QR login** (wide screens `>= 600dp`) — `QrLoginDialog` requests a `unikey` via weapi, renders the QR with ZXing, starts a `QrPairServer`, and polls `client/login` every 2 s (max 150 ticks): 800 expired / 802 scanned / 803 success.
3. **Phone-scanning-tablet handoff** — `QrAuthorizeScreen` (CameraX + ZXing) scans the tablet's QR, extracts the unikey, and sends the phone's cookie over the LAN. Protocol in `QrPair`: UDP broadcast on port `47821` (prefix `NCRUSTPAIR1?` / `NCRUSTPAIR1!`) to discover the tablet, then a TCP connection carrying a length-prefixed AES-128-CBC payload (key = `SHA-256(unikey)[0:16]`, random IV prepended). `cleartextTraffic` is enabled for this.

**Manual cookie paste no longer exists** — it was removed in `a16e381`. Do not reintroduce it in docs.

### i18n System

Runtime-based (not Android resource strings), under `ui/i18n/`:

- `Strings.kt` — one data class with every UI string (≈165 properties; some are lambdas such as `trackCount: (Int) -> String`).
- One file per locale defining a top-level `Strings(...)` value.
- `LanguageManager.kt` — `languagePresets`, `LocalStrings` (default `zhCN`), SharedPreferences helpers.

**8 locales:** `zh-CN` 简体中文, `zh-TW` 繁體中文, `en-US` English, `ja-JP` 日本語, `ja-MY` 万葉仮名, `ko-KP` 조선어, `de-DE` Deutsch, `ru-RU` Русский. (The legacy `en-UK` code maps to `en`.)

To add a locale: create `xx_XX.kt` with a `Strings(...)` and add a `LanguagePreset` to `languagePresets`. To add a string: add the property to `Strings.kt` and to **every** locale file. Composables read `val s = LocalStrings.current` — never hardcode UI strings.

### Theming & Responsive Layout

- **6 theme colors** (`themeColorPresets`): 云杉 `#1DB954`, 钴蓝 `#3B82F6`, 绯红 `#EF4444`, 琥珀 `#F59E0B`, 堇紫 `#8B5CF6`, 素白 `#FFFFFF`.
- **3 theme modes** (`ThemeMode`): `SYSTEM`, `DARK`, `LIGHT`. Dark base is OLED black `#000000`; light base is warm off-white `#F6F2E9`.
- `NcrustTheme(primaryColor, isDark)` derives `NcrustColors`; `toMetroColors(isDark)` bridges them into Kanesumi `MetroColors` so `Ncrust*` and `Metro*` components stay visually consistent.
- `ResponsiveContent`: `< 600dp` → centred `widthIn(max = 360.dp)`; `>= 600dp` with `maxWidth` → centred at that width; without → fills with 24dp horizontal padding.
- Wide screens (`windowWidthDp >= 600`) replace the bottom nav with a 200dp `MetroSidebar`; the content column gets `padding(start = 200.dp)`.
- `BottomOverlayInsetDp` = **144dp** narrow (80 nav + 56 mini + 8 buffer) / **64dp** wide (56 mini + 8). All scrollable content must use it as `contentPadding`; **never** append a manual end-of-list `Spacer`.

## Compose 触摸陷阱（实测汇编 · v1.5.0 整理）

> 这一节是本 fork 踩过的**全部**触摸/命中测试坑，每条都给：症状 → 根因 → 修法 → 触发版本 → 相关文件。
> 「触发版本」是 `git log -S` 反查出来的**实际引入/修复版本**，不是回忆；与口头描述不符的地方以本节为准。
> 背景：本应用的播放器卡片是 `fillMaxSize` + `graphicsLayer` 平移到屏幕底部的独立图层，
> 详情页/导航栏都在它下面 —— 这是全部坑的共同温床。

### 1. `alpha = 0` 不会退出命中测试

| | |
|---|---|
| **症状** | 肉眼什么都看不见的地方照样吃事件：折叠态在底部导航栏附近乱按会点到**不可见的**歌词行/队列行；详情页底部按钮「点了没反应」 |
| **根因** | Compose 的命中测试只看布局与 `pointerInput`，**不看 `alpha`**。`graphicsLayer { alpha = 0f }` 只影响绘制，节点仍然是可命中的；`Modifier.alpha()` 同理 |
| **修法** | 隐藏必须走「不挂载」或「收窄命中区」，不能只改透明度。播放器里 mini bar 是**故意**保留 alpha 渐隐的（它在 Composition 中常驻、透明度只在绘制阶段控制，动画期零重组），代价是它始终可命中 —— 所以它必须永远可见可用，不能当「隐藏层」用 |
| **触发版本** | 自播放器卡片分层架构（v1.0.x 上游）起存在；v1.3.0（`5d93ae3`）修掉展开态子树的这一份 |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（mini bar 叠加层、`graphicsLayer { alpha = ... }`） |

### 2. `fillMaxSize` + `translationY` 形成「屏幕坐标死带」

| | |
|---|---|
| **症状** | 有歌在播时，详情页 **y ≳ collapsedOffsetY** 一带的交互元素完全收不到事件（S6/G9209 实测 y≈1938 的歌单详情 ⋮ 点不动；API 24 模拟器同一坐标上滑也不生效） |
| **根因** | 卡片是 `Box(Modifier.fillMaxSize())`，用 `graphicsLayer { translationY = collapsedOffsetY }` 整体下移。**`graphicsLayer` 的平移同时作用于绘制与命中测试** —— 卡片的逻辑布局仍在 (0,0)-(W,H)，但命中区跟着视觉一起被推到屏幕下半部，整块压在详情页之上且先于下层拿到事件 |
| **修法** | ① 折叠态不再挂载展开态子树；② 折叠态给根 Box 加 `statusBar` 高的 top padding 收窄两个 `pointerInput` 的命中区，再用等量 `offset(y = -inset)` 把子节点放回原位（视觉与子节点布局零变化）。见第 6 条 |
| **触发版本** | 随分层架构引入；v1.3.0（`5d93ae3`）修复。S6 实测 `collapsedOffsetY = 1920px`、mini bar 内容上沿 `2016px`，死带正好是中间 24dp |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)、[PlayerCardOverlay.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCardOverlay.kt) |

### 3. `graphicsLayer` 平移参与命中测试，且 `onGloballyPositioned` 在动画期间滞后

| | |
|---|---|
| **症状** | 展开态下关掉歌词后，**底部播放控制栏与底部导航栏一起失效**（歌词开着反而正常）；动画进行中判断「点是否落在卡片可见区」会得到旧值 |
| **根因** | 两个独立问题叠在一起：① 根节点在 `progress > 0.99f` 时吞掉所有事件，唯一的豁免区 `isOverPanel` 却挂在 `isPanelInteractive` 上、进而挂在 `lyricsEnabled` 上 —— 关歌词 ⇒ 豁免区整个消失 ⇒ 全吞；② `graphicsLayer` 的平移**不重新触发布局**，`onGloballyPositioned` 写下的坐标在动画期间是滞后的，直接读它算可见区会在动画中判错 |
| **修法** | 把「豁免区」按**语义**拆成两个函数，绝不混用：`isOverPanel`（面板语义，只给拖拽检测器用，依赖 `isPanelInteractive` 是对的）与 `isOverCardVisibleArea`（几何语义，只给「展开态吞事件」的消费者用）。几何计算用 `cardRootOrigin.y * (1f - progress.value)` **插值**，不直接读 `cardRootOrigin.y` |
| **触发版本** | 自展开态吞事件引入；v1.1.1（`709c20b`）修复 |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（`isOverPanel` / `isOverCardVisibleArea` / `isOverCollapsibleControls` 三个语义注释块） |

### 4. 隐藏层用「条件挂载」而不是 `alpha`（B-1 的取舍）

| | |
|---|---|
| **症状** | 同第 1 条。折叠态在屏幕下半部乱按会点到看不见的歌词行/队列行 |
| **根因** | 折叠态只把展开态子树用 `alpha≈0` 隐藏，子树仍然参与命中测试 |
| **修法** | `progress < 0.01f` 时**整棵展开态子树不挂载**（顶部标题栏 / 歌词·队列双面板 / 底部播放控件 / 大封面信息），mini bar 永远挂载。**代价（有意接受）**：首次展开要付一次 composition + layout + draw，压不进单帧时会看到一次轻微掉帧（冷启动由 Splash + AppWarmup 兜底） |
| **触发版本** | v1.3.0（`5d93ae3`）。**任务书把这条记在 v1.1.1，实际是 v1.3.0** |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)、[PlayerCardOverlay.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCardOverlay.kt) |

### 5. 折叠态「隐藏子树的命中区」比可视区大

| | |
|---|---|
| **症状** | 明明已经没有可视子节点的那一段（死带），事件还是被卡片吃掉 |
| **根因** | 那一段里**一个子节点都没有** —— mini bar 与 Column 的内容都被 `statusBarsPadding()` 下推到 2016，而吃事件的是卡片**根 Box 自己**的那两个 `pointerInput`（上拉手势 / 展开态吞事件），命中区就是整个 `fillMaxSize` 根 Box。所以「不挂载子树」单独用**修不掉** |
| **修法** | 见第 6 条（命中区让位）。判据是「有没有子节点」不够，必须看「有没有 `pointerInput` 挂在被平移过的根节点上」 |
| **触发版本** | 随分层架构引入；v1.3.0（`5d93ae3`）修复 |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（根 Box 的 `collapsedHitGate` 与两个 `pointerInput`） |

### 6. `padding` + 等量 `offset` 收窄命中区（视觉零变化）

| | |
|---|---|
| **症状** | 需要一个「只改命中区、不改任何视觉与布局」的手段 |
| **根因** | —（这是修法本身） |
| **修法** | 折叠态给根 Box 加 `Modifier.padding(top = hitGateInsetDp)`（只收窄挂在**同一节点**上的 `pointerInput` 命中区），再用 `Modifier.offset(y = -hitGateInsetDp)` 把子节点放回原位。子节点的最终位置、卡片背景的 `graphicsLayer` 平移都不受影响 ⇒ **视觉与子节点布局零变化**。展开态与动画中段不加 padding，「整屏吞事件」的行为原样保留 |
| **触发版本** | v1.3.0（`5d93ae3`）。API 24 模拟器实测：同一坐标 y=1188 同长度上滑，修复前把整卡拉起、修复后事件还给下层；mini bar 上滑展开与点按展开均无回归 |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（`collapsedHitGate` / `hitGateInsetDp`） |

### 7. 小控件要把触摸区扩到 ≥48×24dp，且必须能被无障碍服务看见

| | |
|---|---|
| **症状** | 全屏播放器底部的控制栏把手（视觉 40×3dp 的小横条）用户「找不到 / 划不动」；TalkBack 用户把控制栏收起后**再也拿不回来** |
| **根因** | 视觉元素做得再小，命中区也不能跟着变小；此外只画一个 `Box` 而不加 `clickable`/语义，等于对无障碍服务不存在 |
| **修法** | 视觉保持 40×3dp，**命中区只增不减**：外层全宽 × 24dp 拖拽带（保持既有手感，宽度故意不缩到 48dp —— 1440px 宽的 S6 上 48dp 只有 192px，v1.4.2 的用户反馈正是「划下去就划不上来」）+ 内层显式 48×24dp 点按命中盒；并加 `semantics { contentDescription = ... }`。触摸契约写进注释，防止后续被「优化」掉 |
| **触发版本** | 把手本身 v1.4.2（`094675e`）加入，v1.4.2（`b513359`）修「恢复手势被整卡拖拽抢走」；命中区显式化 + 无障碍语义 v1.5.0 · C2（`fd15b89`） |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（控制栏把手 Box） |

### 一句话总结（给改播放器的自己）

`graphicsLayer` 的 `translationY`/`scale`/`alpha` 里，**只有 `alpha` 不影响命中测试**；
`translationY` 会把命中区一起搬走，`fillMaxSize` 的根节点即使一个子节点都没有也照样吃事件。
凡是「看不见的地方还能点」或「看得见的地方点不到」，先问三件事：
**这个节点挂载了吗？它的 `pointerInput` 在哪一层？它的命中区被 `graphicsLayer` 搬到哪去了？**

## v1.4.0 新增（本 fork）

| 项 | 说明 |
|---|---|
| 底部控制栏可收起 | 窄屏全屏播放器下向上拖控制栏 → 控制栏滑出、面板长高到全屏；右下角悬浮播放键向下拖恢复。controlsCollapse(Animatable) + graphicsLayer 平移 + Modifier.collapsibleHeight（layout 阶段读 Animatable，零重组）；控制栏区域的整卡拖拽由 isOverCollapsibleControls 让路。宽屏不启用。 |
| 暂停态 seek 立即生效 | 进度 ticker 只在 isPlaying 时广播，暂停态 seek 后 UI 收不到新位置。修法：PlaybackService 两条 seek 路径后 publishProgressNow() + PlayerViewModel.seekTo 乐观更新；showBuffering 不再把 isSeeking 当缓冲（点进度条不再有脉冲动画）。 |
| 播放全部先播后补 | 首页/库页的 ▶ 直接播放：命中 ContentCache / 本地收藏单曲立即开播，未命中先 Toast 再拉取；收藏单曲剩余详情后台补齐后追加队尾。移除全局 pendingPlayAllSongs 二次确认（详情页各自的 PlayAllDialog 保留）。 |
| 音乐人推荐卡片 | 首页推荐流按「收藏艺人 ∩ 风格锚点」本地判定插入一张艺人卡（点击进 ArtistDetailScreen）。配置在 ncrust_settings：artist_reco_enabled / artist_reco_target_id / artist_reco_anchor_ids(CSV)，默认全空 → 其他用户不显示也不发请求。自动锚点：目标艺人热门曲 → simiSong → 同风格艺人（7 天 TTL）。**v1.5.0 起**自动锚点按频次排序 + 截断到 20 个 + 空结果不冲缓存，见下节。 |
| 相似艺人端点纠正 | 任务里写的 /eapi/simi/artist 不存在（404）；真实端点是 /eapi/discovery/simiArtist，参数名 artistid，匿名 301、需登录，返回 artists[]（≤20，平均约 300ms）。B0 实测目标艺人 122618229 自己的相似列表为空、在 top20 收藏艺人的相似列表里 0 次命中 → 原方案不可行，改走锚点降级方案。 |
| 艺人端点可用性 | GET /api/artist/{id} 与 GET /api/artist/albums/{id} 可用；/eapi/v1/artist/detail、/eapi/artist/albums（PlaylistApi 里那两个旧函数）已 400 失效（当前无人调用，ArtistDetailScreen 走 Retrofit 的 REST 路径）。 |

## v1.5.0 新增（本 fork）

### 音乐人推荐：自动锚点推导的实测与收敛（任务 A）

**S6 真机实测（SM-G9209 / Android 7.0 / 登录态，2026-09-22）**——本节所有结论都来自这次实测，不是推断。
方法：root 写入 `artist_reco_enabled=true` + `artist_reco_target_id=122618229`、清空
`artist_reco_anchor_ids`，冷启一次后读回 `ncrust_settings.xml`。

| 项 | 实测值 |
|---|---|
| 目标艺人 | `122618229` = **SKULLCHAIN颅链**（musicSize 13，热门曲 Home تۋعان جەر / CALL THE FATALITY / JAR JAR PHONK） |
| 推导结果（9 个，落盘顺序） | `60952064,53432819,1132066,12003027,29878,93805,99989,13518632,99999` |
| 反解艺人名 | PFJ_5 / 333xd / Fayzz / 奈热乐队 / Boris Brejcha / HIM / Sum 41 / Charix / System of a Down |
| 推导耗时 | 冷启后 12 s 内完成并落盘（`artist_reco_auto_anchor_at` 刷新） |
| 目标艺人是否被误收进锚点 | 否（`aid != target` 生效） |
| 卡片是否触发 | **是** —— 该账号收藏单曲里有 `PFJ_5(60952064)`，`shouldShow` 命中，首页「音乐人推荐」渲染出 **SKULLCHAIN颅链**（截图复核） |

结论：**推导合理**（全部是真实艺人，风格上扎堆在摇滚/金属/电子，且确实在真实收藏上命中），
因此保留自动推导并做三点收敛，而不是降级成「默认关闭自动推导、只留手动锚点」：

1. **去重**：以艺人 id 为 key 收进 `LinkedHashMap`，同一艺人无论命中多少次只留一个；
2. **排序 + 截断**：按「在几首种子曲的相似列表里出现过」降序（`sortedByDescending` 是**稳定**排序，
   同频次保持首次出现顺序 → 同一份服务端数据每次结果完全一致），再截断到 `AUTO_MAX_ANCHORS = 20`。
   原实现没有上限，3 种子曲 × 20 相似曲最多能收 60 个；锚点集就是命中判定集，越大越容易误命中。
   S6 实测只有 9 个，**低于上限 → 对现有账号是零行为变化**；
3. **缓存**：7 天 TTL 不变，另加两点 —— ① `Mutex` 单飞，快速连续切页只推导一次（此前 TTL 写回前
   每个并发调用都会各跑一遍网络）；② **推导结果为空时保留旧缓存**，不再把好数据冲成空、让卡片
   静默消失到 TTL 结束。

顺带补了可观测性：推导成功打 `Log.i(ArtistReco)`（此前只有失败才 `Log.e`，线上无法判断卡片为何不出现）。
纯逻辑抽成 `ArtistReco.rankAnchors(hits, target, max)`，由 JVM 单测
[ArtistRecoTest.kt](app/src/test/java/com/takahashirinta/ncrust/reco/ArtistRecoTest.kt) 覆盖去重/排序/稳定性/截断/过滤。

**S6 上的复现方式**（S6 已 root，Magisk）：改 prefs 前先 `su -c cp` 备份 `ncrust_settings.xml`，
`am force-stop` 之后再写入，冷启后读回；登录态（`ncrust_prefs.xml` 的 `user_cookie`）全程不动。

### 歌词增强：逐字（yrc）+ 翻译（任务 B）

#### 实测结论（2026-09，真实响应，非文档推断）

| 项 | 结论 |
|---|---|
| 端点 | `POST /api/song/lyric`（REST，应用现有路径）。**`/eapi/song/lyric` 也能用，但必须做 `/eapi/`→`/api/` 的签名路径重写**；`/eapi/song/lyric/v1` 会混入富文本 JSON 格式，不要用 |
| 逐字字段 | `yrc`，格式 `[行起始ms,行时长ms](词起始ms,词时长ms,0)词文本...`。**词起始是绝对毫秒**（3161/3162 行首段 start == 行首；段起始单调不减 0 违例）；括号里第三个数字 **25080/25080 恒为 0**，无语义。⚠️ `行首+行时长 == 末段 start+末段 dur` 只有 **2931/3162 = 92.7%** 成立，其余是行尾留白（+30~480ms）与 ±1ms 舍入 —— 所以**行尾要按末段 `start+dur` 算**，别把 `行首+行时长` 当行尾 |
| `yrc.version` | **是这首歌词的修订号，不是格式版本号**：实测取值 2~113 无规律，不要拿它做格式判断 |
| `ytlrc` 对齐性 | 它每行时间戳**精确落在某个 yrc 行首上**（20 首合计 1060/1060），而 `tlyric` 几乎从不对齐（0–3/33–75）⇒ 两者是两份独立资产，**不可互换**；本仓库用 `tlyric`（因为展示行来自 lrc，不是 yrc） |
| 参数语义 | 是**开关**：传 `lv`→lrc、`kv`→klyric、`tv`→tlyric、`rv`→romalrc、**`yv`→yrc + ytlrc + yromalrc 三个一起**。单独传 `ytv`/`yrv` 什么都不返回；不传 `yv` 时响应里**连 `yrc` 这个 key 都不存在** |
| `ytlrc` 不是逐字 | 它是**行级** LRC（`[MM:SS.mmm]整行译文`），实测 23 首有 ytlrc 的歌 100% 命中行级文法、0 行命中 yrc 文法。所以唯一的逐字数据源就是 `yrc` |
| 匿名可用 | 完全不带 Cookie 与登录态返回**逐字节相同**（code 200）。歌词接口不需要登录 |
| 覆盖率 | yrc 与发行年份**不严格相关**：2024-2025 首发新歌（周深《小美满》、Billie Eilish《CHIHIRO》、Taylor Swift《Fortnight》等）大多**没有** yrc；而《屋顶》《修炼爱情》《演员》《浮夸》《孤勇者》《后来的我们》等有 |
| 「确无歌词」 | code 仍是 200，靠 `uncollected:true` / `pureMusic:true` 与 `lrc.lyric="[00:00.00]暂无歌词"` 判别，此时响应里没有 yrc/tlyric 等 key |

#### 对齐：**不能按时间戳匹配**（踩坑记录）

直觉做法「按时间戳把 yrc 行挂到 lrc 行上」**实测不成立**：《屋顶》55 行里只有 3 行时间戳完全相同，其余相差 20–310 ms（yrc 是逐字轨，本来就更精确）。

但实测同时确认了更强的事实 —— **yrc 与 lrc 的「行」是一一对应的**：

| 歌曲 | lrc 行数 | yrc 行数 | 按文本命中 | 时间差 |
|---|---|---|---|---|
| 屋顶 `5257138` | 55 | 55 | 55/55 | +20…+310 ms |
| 遇见 `287035` | 28 | 28 | 11/28（其余只差空格） | −269…+530 ms |

所以 [YrcParser](app/src/main/java/com/takahashirinta/ncrust/lyric/YrcParser.kt) 按 **行序**对齐：行数必须相等 + 时间漂移抽样达标（`MAX_LINE_DRIFT_MS=2000`、`MIN_ALIGN_RATIO=0.8`），否则整首放弃逐字。

文本也要重新映射：yrc 拼出来的是「词的原始拼接」，LRC 那份是「补回空格的美化版」
（`听见冬天的离开` vs `听见 冬天的离开`）。**展示文本仍用 LRC 的**（与 v1.4.1 完全一致），词的字符区间用
「游标 + indexOf」映射到 LRC 文本上；任何一个词定位失败就放弃该行的逐字 —— 宁可没有逐字，也不给错位高亮。

#### 渲染：`drawWithContent` 两层（零重组）

`MetroLyricsPanel`（Kanesumi）只接受纯文本、无法从外部注入单行渲染，所以逐字高亮需要面板本身参与。
本仓库**把面板整体搬了进来**：`ui/player/NcrustLyricsPanel.kt`（派生自 Kanesumi `MetroLyricsPanel`，Apache-2.0）。
为什么是复制而不是改 Kanesumi：Kanesumi 走组合构建、不在本仓库版本控制内，改它会让 v1.5.0 的发布产物**不可复现**
（`git clone Ncrust` 不再足以构建）。除单行渲染外，滚动 / 定位 / 缩放 / 渐入 / a11y 行为与原版逐行一致。

单行渲染 `LyricLineBody`：底色用 `BasicText` 正常画一遍（未唱部分弱化到 45% α），再在 `drawWithContent` 里
**在 draw 阶段**读播放位置，用 `TextLayoutResult.getPathForRange(0, 已唱字符数)` 取出已唱字符的版面路径，
`clipPath` 之后把同一份 layout 用高亮色重画一遍。三个好处：

1. **零重组** —— 逐字推进只让这一行重绘，不触发任何重组，与面板既有的 `graphicsLayer` 缩放动画、
   以及播放器整体的 GPU 零重组原则一致；
2. **不自己排版** —— 换行 / 断字 / CJK 避头尾全部交给 Compose，逐字高亮天然跟着版面走；
3. **一次画完** —— `getPathForRange` 把跨行的一段字符合成一条 Path，开销不随词数增长。

只有**当前行**做逐字高亮：已唱完的行会切成 `pastLineColor`，若在那些行上再叠高亮色会把整行重新点亮成
primary，破坏过去行的弱化。

#### 与 v1.4.1 的 2Hz 采样 / 漂移外推如何共存（B3）

`LyricsView` 的「按需唤醒」循环（v1.4.1 引入，用于跨行精确对齐 + 暂停态 seek 立即同步）原本只按
**行时间戳**唤醒。逐字高亮要求精度到「词」，但**不能改成逐帧轮询** —— 现在把唤醒表从「行边界」细化成
「行边界 ∪ 词边界」（`TreeSet` 去重升序），仍然是按需唤醒：静态时零状态写入、零帧调度，只在真正跨行/跨词
的那一刻动一次（词密度约每秒 3–8 个，远低于 60 fps）。2Hz 采样到达时外推锚点被重置回真实值，
所以细粒度外推不会累积误差。**v1.4.1 的漂移对齐逻辑一行未动。**

#### 开关与降级

- 设置项 `lyrics_word_by_word`（默认**开**），8 语言文案 `lyricsWordByWordLabel` 已补齐；
- 关掉、或该曲没有 yrc、或某一行对齐失败 → 行内渲染退回与 v1.4.1 **逐字节一致**的整行 `MetroText`；
- `YrcParser.attachWords` **不增删任何一行、不改任何一行的文本与时间戳**，这是「关掉开关等于没这功能」的保证；
- `LyricsCache.CachedLyrics` 新增 `yrc`，声明为可空并在读取处 `orEmpty()` —— 老缓存（无该字段）命中时
  退化成「没有逐字数据」，不会因 Gson 走 Unsafe 反序列化而 NPE。

单测 [YrcParserTest.kt](app/src/test/java/com/takahashirinta/ncrust/lyric/YrcParserTest.kt) 用真实样本覆盖
绝对毫秒语义、信息行 trim、按行序对齐、少空格时的字符区间映射、定位失败放弃、行数不等放弃、乱码容错。
### 离线下载：实测调研与架构选型（任务 D，v1.5.0 只做 D1+D2）

> **结论先行：v1.5.0 不实现下载功能，只交付调研与选型。** 原因见文末「为什么不在 v1.5.0 落地」。
> 本节所有数据都是 2026-09 真实登录态实测 + 逐行源码审计，不是文档推断，可直接作为 v1.5.1 的施工依据。

#### D1-1 现有下载能力：**零**

| 检索 | 命中 |
|---|---|
| `下载` / `离线` / `(?i)offline` | **0** |
| `(?i)download` | **1** —— [PlayReporter.kt](app/src/main/java/com/takahashirinta/ncrust/player/PlayReporter.kt) 的 webLog 上报字段 `.put("download", 0)`，与下载无关 |

**没有磁盘音频缓存（已用代码证实，不是猜）**：`PlaybackService.kt` 全文 918 行的 media3 import 里没有
`androidx.media3.datasource.cache.*`（无 `Cache`/`SimpleCache`/`CacheDataSource`）、也没有 `androidx.media3.exoplayer.offline.*`；
ExoPlayer 构造时只设 `DefaultRenderersFactory`（FFmpeg）/`DefaultExtractorsFactory`/`setLoadControl`/`setWakeMode`，**没挂任何缓存数据源**。
全仓库 grep `SimpleCache|CacheDataSource|DownloadManager|DownloadRequest|androidx.work|androidx.room` → **0 命中**。

真正的磁盘占用只有三处，都不含音频：封面图磁盘缓存（`cacheDir/image_cache`，100 MB）、6 个 SharedPreferences、自定义背景图（`filesDir/`）。

→ **在线播放完全依赖每次现场取链的临时 URL，离线零可用性。**

#### D1-2 `/eapi/song/enhance/download/url/v1` 实测结构

⚠️ **先记一条会让全部 eapi 请求 404 的坑**：签名/明文里的路径必须是 `/api/...`，而 HTTP URL 是 `/eapi/...`
（[EapiCrypto.kt](app/src/main/java/com/takahashirinta/ncrust/network/crypto/EapiCrypto.kt) 的 `urlPath = parsedUrl.path.replace("/eapi/", "/api/")`）。
照抄「两处路径相同」的直觉写法会拿到 `{"code":404,"message":"接口未找到！"}`。

请求：`POST /eapi/song/enhance/download/url/v1`，body `{"id": <long>, "level": "<档位>"}`。

| 参数 | 必填 | 实测 |
|---|---|---|
| `id` | ✅ | **单值**。传 `ids`（复数数组串）→ `{"msg":"参数错误","code":400}` |
| `level` | ✅ | 缺 → 400；用 `br` 代替也 400 |
| `encodeType` | ❌ | **被完全忽略**：`mp3`/`flac`/`aac`/`mp4` 四种取值响应逐字节相同；响应里的 `encodeType` 恒为 `"mp3"`，**不能拿来判格式** |
| `header` | ❌ | 带上与不带响应逐字节相同 |

响应只有两个顶层 key：`{"code":..., "data":{...}}`。**`data` 是对象**（不同于 player 端点的数组），
且**恒为 36 个字段**（成功/`-105`/`-110` 都一样，失败时全部置 0/null，字段不消失）—— 解析器可以无条件按 36 字段读。

关键字段：`url`（**http 不是 https**）、`br`、`size`（实测与实际下载字节数一致）、`md5`（实测与实际内容一致）、
`code`、`type`（**权威容器字段**）、`level`（**实际授予档位**）、`time`、`fee`、`payed`、`freeTrialInfo`（**download 端点恒 null**）、
**`expi` = 1200**（URL 有效期 20 分钟）、`sr`、`gain`、`peak`、`freeTrialPrivilege`、`musicId`。

**两条硬结论**：

1. **超出该曲上限的档位被静默封顶**，不报错也不给降级标记 —— 响应 `level` 回落到实际授予档位。
   下载必须持久化 `data.level`（实际值），不能存用户请求的档位。`level=sky` 恒失败（`-110`），与既有结论一致。
2. **`download` 端点「要么给完整文件、要么什么都不给」**：未登录 + VIP 曲给 `data.code=-105` + `url=null`，
   **永不返回 45 秒试听片段**（`player/url/v1` 对同一首会给 45 s 试听）。这正是离线下载需要的语义 ——
   否则会存下一个 45 秒的坏文件。

与现有 `player/url/v1` 的差异：**字段集合完全相同（36/36，双向差集为空）**，差异只在请求/响应形状与权限语义 ——

| 维度 | `download/url/v1` | `player/url/v1` |
|---|---|---|
| 请求体 | `{"id": <long>}` 单值 | `{"ids": "[...]"}` 数组串，可批量 |
| `data` 形状 | **对象** | **数组** |
| 完全无 cookie | 顶层 `{"data":null,"code":301}`（一律拒绝） | 免费曲仍 200 + url |
| 有身份无 `MUSIC_U` + VIP 曲 | `data.code=-105`，`url=null`，**无试听** | 200 + 45 s 试听片段 |
| 无版权曲（`st=-200`） | `data.code=**-110**` | `data.code=**404**`（**码不同，别混用判定**） |

#### D1-3 断点续传：**完全可行，且 URL 轮换不影响续传**（实测）

| 实验 | 结果 |
|---|---|
| `Range: bytes=0-1023` / `bytes=1000000-1001023` / `bytes=-1024` / `bytes=0-` | **全部 206** + 正确 `Content-Range`；但**没有 `Accept-Ranges` 头**（不能靠探测它，直接发 Range） |
| `md5` / `size` 是否可信 | 全量下载 5,217,010 字节后比对：`size` 一致、`md5` 一致 ⇒ **服务端白送完整性校验** |
| 同 URL 分段拼接 | `cat part1 part2` 的 md5 与 `data.md5` 一致 |
| **跨 URL 续传**（下到一半换新 URL 继续） | 用 URL_A 下 `0-1999999`、URL_B 下 `2000000-`，拼接后 **size 与 md5 都一致** ⇒ **URL 过期只需重新取链，不用重下** |
| URL 是否稳定 | 每次都变（host 在 `m704`/`m804` 间轮换、路径时间戳每次不同）⇒ **绝不能把 URL 当持久标识** |
| URL 过期机制 | 签名在**路径**里（14 位时间戳恒 = 签发时刻+1500 s；篡改任一字节即 403）；**query 参数是装饰**（删掉/篡改仍 206） |
| CDN 是否自鉴权 | 是（不带 Cookie/Referer 直接 curl 也能下）；**https 同样可用**（建议升级，避免明文） |

→ 施工要点：持久化 `(songId, requestedLevel, actualLevel, type, br, size, md5)`，**不持久化 URL**；
每次开始/恢复都重新取一次链，再按已下载偏移发 `Range`；下完比对 `size` + `md5`。

#### D1-4 两条容易被忽略的既有约束

- **共享 OkHttp 客户端的 read timeout 只有 30 s**（[RetrofitClient.kt](app/src/main/java/com/takahashirinta/ncrust/network/RetrofitClient.kt)）——
  32 MB 无损在弱网下必然超时。下载**必须自建 OkHttpClient**（长 read timeout），不要复用。
- **离线音频应放 `filesDir/offline` 而不是 `cacheDir`**：「清除缓存」只删 `cacheDir/{WebView,http}`（不会误删），
  但「缓存占用」统计的是**整个 cacheDir**（会把离线音频算成缓存 → 用户以为可清、实际清不掉）。
- 下载**不应套用 `SongUrlFetcher` 的 FLAC 设备门控**：下载只是写字节、不经 ExoPlayer，API<27 设备也能下无损。

#### D2 架构选型：**WorkManager + Room**（官方推荐，断点续传友好，Android 7.0 兼容）

实测事实支撑这个选择：

| 判断项 | 依据 | 结论 |
|---|---|---|
| 后端是否支持离线 | Range 206 + md5/size 校验 + 跨 URL 续传通过 | ✅ |
| 是否需要新存储 | `SongItem` 只有 5 个展示字段（无 size/md5/level），SharedPreferences 整表 Gson 不适合大规模清单 | ✅ 需要 Room |
| 是否需要后台调度 | 无任何既有 Work/Job 调度基础设施 | ✅ 需要 WorkManager |
| 许可证 | 实查 Google Maven POM：`room-runtime` 与 `work-runtime-ktx` 均为 **Apache-2.0**；Apache 官方 GPL 兼容性页原文「Apache 2 software can therefore be included in GPLv3 projects」⇒ **单向兼容** | ✅ 可引入 |

**唯一未验证的工程风险（必须在 v1.5.1 第一步解决）**：本仓库是 Kotlin **1.9.24** / AGP **8.5.0** / jvmTarget 11，
而 Room 2.7+/2.8+ 与 WorkManager 2.10+ 通常要求更新的 AGP/Kotlin；引入时很可能需要**降级选版**
（如 Room 2.6.x + WorkManager 2.9.x）或同步升级 AGP/Kotlin。**没有跑过真实构建之前不要合并依赖改动。**
另外 Room 需要注解处理器（KSP 或 kapt），本仓库当前两者都未配置。

表结构（草案）：

```
download_task(
  songId INTEGER PK, name TEXT, artist TEXT, album TEXT, coverUrl TEXT,
  requestedLevel TEXT, actualLevel TEXT, type TEXT, br INTEGER, size INTEGER, md5 TEXT,
  filePath TEXT, downloadedBytes INTEGER, status INTEGER,   -- 0 排队/1 下载中/2 完成/3 失败/4 已删除
  error TEXT, createdAt INTEGER, updatedAt INTEGER
)
```

落地顺序（对应任务书的 4 个 commit）：

1. `feat(download): 下载管理器核心` —— `DownloadApi`（复用 `EapiCrypto` + download 端点）、`DownloadStore`（Room）、
   `DownloadWorker`（WorkManager，Wi-Fi 约束 + 前台通知 + Range 续传 + md5 校验）、自建 OkHttpClient；
2. `feat(download): 批量下载入口` —— 在 `MainActivity` 的 `[加入歌单] + menuSongActions + [转到歌手/专辑]` 拼接处插全局「下载」，
   一处改动覆盖全部 7 个页面；歌单/专辑详情页的批量下载要先**把 `DetailScaffold.onTopEndAction` 泛化成多动作**
   （现在只支持一个右上角动作，歌单页已被「编辑歌单 ⋮」占用），且**不能**放 `DetailHeader.headerActions`（在播放器死带里）；
3. `feat(download): 下载队列页` —— 复用 `DetailScaffold`；
4. `feat(download): 存储管理页` —— 已下载列表 + 占用统计 + 删除，与设置页「缓存占用」口径分开。

播放侧接入（**这才是「支持离线播放」的真正开关**）：`PlaybackService` 收到播放请求时先查 Room 有没有已完成的本地文件，
有就播 `fileUri`，没有才走 `SongUrlFetcher`。当前完全没有这条路径。

#### 为什么不在 v1.5.0 落地

1. 引入 Room + WorkManager 需要先解决 Kotlin 1.9.24 / AGP 8.5.0 的版本兼容并新配注解处理器 ——
   这是**唯一未验证的工程风险**，而任务书的红线是「引入新依赖但无法确认兼容性时停下」；
2. 任务书明确允许：「如果上下文不足：只做 D1 + D2 调研报告，实现留 v1.5.1」；
3. 与其塞一个未在真机验证过的下载器进 v1.5.0，不如把已实测的协议结论与表结构沉淀下来，
   让 v1.5.1 从「已知可行」起步。

## Key Constraints & Pitfalls

- **Kanesumi Design**: no rounded corners in the player; no spring/bounce; cover always fills the full screen width (`fillMaxWidth().aspectRatio(1f)`, scale 1.0 in large mode).
- **GPU zero-recomposition**: read animation values only inside `graphicsLayer`; never `animateFloatAsState` for the player card. High-frequency StateFlows are subscribed in leaf composables / `draw` scope, not in the parent.
- **PlayerCard collapse gating**: below `progress = 0.01`, the whole expanded subtree (LyricsView / QueueView / FullPlayerControls / 大封面信息) is **not mounted**; mini bar 永远挂载。代价是首次展开付一次 composition+layout+draw（v1.3.0 · B-1 的取舍，换掉详情页死带）。
- **Borderless list style**: `SongCard` LIST/COMPACT rows have `0dp` left padding (72dp cover flush to the edge); only the right keeps 16dp. `DetailScaffold` has no `TopAppBar` Surface — a floating back arrow (`MetroTopScrim`) overlays the content. Grid tiles use `spacedBy(2.dp)`. Home/Library use a 34sp page header instead of an app bar.
- **Bottom overlay inset**: the mini bar (56dp) sits above the M3-era 80dp nav bar, drawn as a sibling overlay, so `Scaffold.innerPadding.bottom` does **not** reserve space for them. Use `BottomOverlayInsetDp` as `contentPadding`.
- **System-bar compensation**: `collapsedOffsetY = contentHeightPx - sysNavPx - navBarHeightPx(56/0) - miniBarHeightPx(56) - sysStatusPx`. `sysStatusPx` cancels the `.statusBarsPadding()` applied inside `PlayerCard` to the mini-bar overlay. **Automotive (AAOS) caveat**: CarSystemUI does not deliver WindowInsets, so on `UI_MODE_TYPE_CAR` the content height comes from the measured root height (`rootHeightPx`), not `screenHeightDp`; phone/tablet keep `screenHeightDp` so car changes don't leak. Touch any of these values carefully.
- **Search debounce**: 500 ms in `SearchViewModel` — do not remove.
- **No explicit coroutines dependency**: coroutines ship with the Kotlin stdlib configuration here.
- **`ContentCache` is not persisted**; `LibraryManager` is the persistence layer.
- **Stale comments**: some comments say "last 20 s" for the gapless preload window (actual 60 s) and "4 Hz" for progress ticks (actual 2 Hz). Trust the code.
- **`SongDetailScreen` / `NavRoutes.song(...)` are registered but unreachable** — clipboard song links load into the player instead.
- **`ncrust-api/` is not part of the app** — see Repository Layout.
