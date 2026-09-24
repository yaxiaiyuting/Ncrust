<div align="center">

# Ncrust

### 把网易云的曲库，装进一块直角玻璃

**Kanesumi Design · GPU 零重组动画 · 逐字歌词 · 无缝播放 · 8 级音质 · 大屏模式 · 车机适配 · 8 语言**

纯 Kotlin / Jetpack Compose · Media3 播放引擎 · eapi 加密直连 · 无中间服务器

**This is a GPLv3 fork of [GuitaristRin/Ncrust](https://github.com/GuitaristRin/Ncrust), maintained by [yaxiaiyuting](https://github.com/yaxiaiyuting).**

[![Version](https://img.shields.io/badge/version-1.9.0--gpl-brightgreen?style=flat-square)](https://github.com/yaxiaiyuting/Ncrust/releases)
[![APK](https://img.shields.io/badge/APK-9.8%20MB-blue?style=flat-square)](https://github.com/yaxiaiyuting/Ncrust/releases)
[![API](https://img.shields.io/badge/API-24%2B-green?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose-BOM%202024.12-blue?style=flat-square&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/license-GPLv3-blue?style=flat-square)](LICENSE)
[![Original](https://img.shields.io/badge/original%20code-MIT-yellow?style=flat-square)](LICENSE-MIT)

[**下载安装**](https://github.com/yaxiaiyuting/Ncrust/releases) · [**Wiki 文档**](https://github.com/GuitaristRin/Ncrust/wiki) · [**问题反馈**](https://github.com/GuitaristRin/Ncrust/issues) · [**Kanesumi 设计库**](https://github.com/GuitaristRin/Kanesumi-sec-a)

</div>

> **⚠️ 重要声明**
>
> 本项目仅供学习交流使用，请勿用于任何违法违规用途。
>
> 使用本工具产生的一切后果由用户自行承担。
>
> 请尊重版权，支持正版音乐。

---

## 为什么是 Ncrust

> 网易云官方客户端很全，但它不是为「直角、克制、信息优先」设计的。
> Ncrust 是一次从零开始的重写：**没有圆角，没有弹簧回弹，没有多余装饰。**

| | |
|---|---|
| 🟩 **直角美学** | 全应用遵循 Kanesumi Design：直角切割、纯色细线、封面贴屏边、浮层返回箭头 |
| ⚡ **GPU 零重组** | 播放器动画由单个 `progress` 经 `graphicsLayer` 驱动，展开 / 收起时**不重组** |
| 🎧 **真·无损** | eapi 直连拉取 FLAC / Hi-Res / 杜比全景声，逐级降级兜底，弱机自动跳过无解档位 |
| 🎤 **逐字歌词** | 网易云 yrc + AMLL TTML 双源，TTML → YRC → LRC 三级回退，逐字渐变三档质量 |
| 🔀 **5 种播放模式** | 顺序循环 / 单曲循环 / 乱序 / 顺序线性 / 相似无限（私人 FM 电台） |
| 🚗 **车机就绪** | Android Auto / Automotive 媒体源与浏览树，车机系统栏 inset 专项适配 |
| 🪶 **9.76 MB** | R8 全量混淆 + 资源 shrink；体积主要来自 FFmpeg 解码扩展的 4 个 ABI（换来 API 24–26 的真无损）。冷启动预热，进程被杀也能恢复队列 |
| 🌐 **8 语言** | 运行时切换，不依赖系统 locale |
| 🔐 **登录无忧** | 浏览器登录 + 二维码登录 + 手机扫码授权平板，全程无需手动粘贴 Cookie |

---

## ✨ 功能一览

### 🏠 发现

- **每日推荐**、**推荐歌单**、**新歌速递**，懒加载分页，缓存命中秒开
- **私人 FM**：首页电台入口，持续拉取私人 FM 流无限续播
- **剪贴板识别**：复制 `music.163.com` / `163cn.tv` 分享链接回到 App 自动打开（单曲只载入不自动播放）
- **音乐人推荐卡片**（v1.4.0）：首页推荐流按「收藏艺人 ∩ 风格锚点」**纯本地判定**插入一张艺人卡，点进艺人详情页；配置默认全空 —— 不配置就不显示、也不发任何请求

### 🔍 搜索

- 单曲 / 专辑 / 艺人三标签，500 ms 防抖，三态 Crossfade 过渡
- 本地搜索历史（每类 10 条、14 天过期）
- 专辑 / 艺人长按可批量「播放全部 / 下一首 / 添加到队尾」

### 📚 曲库

- **收藏单曲**：云端同步「我喜欢的音乐」，列表懒加载分页
- **收藏专辑**：云端订阅专辑网格
- **收藏歌单**：当前账号的用户歌单
- 单曲可收藏 / 插播 / 加队列，本地乐观更新后异步同步云端
- **歌单管理**（v1.3.0）：创建 / 改名 / 改简介 / 改隐私 / 删除 / 增删曲；入口在收藏页「＋」、播放器队列区 ⊕、任意歌曲长按菜单与歌单详情页顶部 ⋮；写操作串行 + 最小间隔 2 s，不自动重试

### 🎵 播放

- **8 级音质**：压缩 → 较好 → 更好 → 无损 → 高解析 → 高清环绕声 → 超清母带 → 杜比全景声
- **分网络偏好**：Wi-Fi 与移动数据独立设置，默认无损 / 较好
- **无缝播放（gapless）**：进入最后 60 秒预加载下一首，切歌即播
- **自动降档**：解码失败或音频输出故障时同曲降一档重试，避免「进度在走但没声音」
- **三区队列**：已播 / 当前 / 待播，触摸即拖重排、边缘自动滚动、清空队列
- **播放上报**：复刻官方 `webLog`，本地收听计入推荐与听歌指数
- **底部控制栏可收起**（v1.4.0）：窄屏全屏播放器下向上拖控制栏 → 控制栏滑出、歌词 / 队列面板长高到全屏，右下角悬浮播放键向下拖恢复；`Animatable` + `graphicsLayer` 平移，动画帧零重组。v1.4.2 起常驻把手（上拖收起 / 下拖恢复 / 点按切换），v1.5.0 起触摸区与无障碍语义达 48×24 dp
- **音质就地切换**（v1.8.0）：竖屏控制栏 / 宽屏控制条 / 横屏大屏共用同一个音质 chip，点开就是 8 档二级菜单，不再跳设置页；档位真变了才重新取链续播
- **离线缓存**（v1.6.0 Phase 1 → **v2.0.0 Phase 2**）：**已经播放过**的音频流按 media3 `SimpleCache` / `CacheDataSource` 语义自动落盘 `filesDir/offline/audio`（LRU，上限 64 MB–8 GB 可调，默认 512 MiB），断网时可回放；未播放过的歌不产生任何本地文件。**Phase 2 起可在设置 →「离线缓存」里看到缓存了哪些歌（标题 / 歌手 / 档位 / 占用）、单曲删除、调整上限并看到分项占用**。⚠️ 仍然**没有显式「下载」入口** —— 离线可播范围严格等于「这台设备上真正播过的歌」（合规定位见下方「离线缓存的范围」）

### 🎨 界面

- **全屏播放器**：三层图层架构，拖拽 25% 阈值吸附，展开 / 收起只做位移缩放
- **歌词**：LRC 行级 + yrc 逐字 + `tlyric` 行级翻译双语同屏；黄金分割定位、手动滚动 5 秒后恢复、点击行跳转；A-/A+ 字号五档（0.7x–1.5x）
- **逐字渐变**：`SweepTrack` 连续光标逐帧推进 + 窄离屏软边；渐变质量三档（自动 / 高级 / 兼容）+ 低内存自动降级；逐字动画三模式（渐变扫过 / 硬切 / 关闭），关掉即退回与 v1.4.1 一致的整行渲染
- **AMLL TTML 歌词源**（v1.9.0）：在 yrc 之外接入 [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db) 逐字数据，组成 **TTML → YRC → LRC 三级回退链**（总开关默认开、默认 TTML 优先，可切 YRC 优先）；取不到或解析失败一律静默回退，渲染层零改动 —— 实测覆盖率见下方「歌词数据来源」
- **歌词音译显示**（v1.9.3）：粤语拼音 / 日文罗马音 / 韩文罗马音等音译轨显示在**原文下方**（有译文时排在译文下面），设置项「显示音译」**默认关**；没有音译数据的歌不产生空行。数据来自 v1.9.2 就绪的音译轨（TTML `x-roman` + 网易云 `romalrc`），渲染层按时间戳逐行配对
- **播放时禁止熄屏**（v2.0.0）：**只**在「正在播放 + 停在播放器界面」时挂 `FLAG_KEEP_SCREEN_ON`（零权限、零依赖，不用 WakeLock）；暂停、切到其他页面、退到后台都立刻恢复系统熄屏策略。设置 → 播放可关
- **动态字号**（v2.0.0，实验性、**默认关**）：按每句**估算折行数**给这一句一个离散字号倍率（短句 ×1.15 / 三行以上 ×0.85），目标是让每句的视觉体量更均衡；倍率乘在 A-/A+ 的基准字号之上并被 48sp 上限夹住，译文 / 音译同乘。`SweepTrack` 渐变算法一个字节未改
- **大屏模式**（v1.7.0）：横屏桌面播放器 —— 左栏大封面 + 歌名 / 作者 + 就地音质选择器，右栏复用同一套逐字歌词面板；⤢ 按钮 / 系统返回键 / 转回竖屏三条退出路径
- **音频可视化**（v1.8.0 / v1.8.1）：大屏模式封面下方 28 根幅度柱，走 media3 `TeeAudioProcessor` 在应用自己的音频链上取数、**零新增权限**；v1.8.1 把采样速率提到 30 次/秒、重绘上限提到 60fps，并给柱高加时间常数平滑（起音 22 ms / 回落 130 ms）
- **应用内自动旋转**（v1.8.0）：开关开（默认）= 跟随传感器，且在播放器界面转横屏自动进大屏；关 = 手机锁竖屏，只能用 ⤢ 手动进大屏。只决定应用自己的方向，**既不读取也不修改**系统旋转设置
- **主题**：6 种主题色 × 3 种模式（跟随系统 / 深色 / 浅色），运行时切换
- **响应式**：窄屏限宽居中，宽屏（≥ 600dp）左侧 200dp 常驻 Sidebar
- **进程恢复**：被杀后恢复进度、歌曲信息、封面、歌词与完整队列

### 🔔 系统集成

- **媒体控制**：MediaLibraryService + MediaStyle 通知，锁屏 / 控制中心 / 蓝牙按键，封面主色调着色
- **媒体面板歌词**（v1.5.1 / v1.6.1 / v1.8.0）：控制中心与通知栏第一行显示当前歌词行、第二行显示「歌名 · 艺人」；API 24 上跨行时补一次通知重播，歌词随行刷新
- **车机**：Android Auto / Android Automotive（AAOS）媒体源与浏览树
- **后台播放**：`WAKE_LOCK` + 电池优化白名单引导，熄屏不被 ROM 清理
- **音频焦点**：ExoPlayer 自动处理

---

## 🎼 支持音质

| API 参数 | 说明 | 要求 |
|---|---|:--:|
| `standard` | 压缩（128 kbps） | 普通账号 |
| `higher` | 较好 | 普通账号 |
| `exhigh` | 更好（320 kbps） | 普通账号 |
| `lossless` | 无损（FLAC） | 黑胶 VIP |
| `hires` | 高解析 | 黑胶 VIP |
| `jyeffect` | 高清环绕声 | 黑胶 VIP |
| `jymaster` | 超清母带（FLAC，实测 5.8 Mbps） | 黑胶 SVIP |
| `dolby` | 杜比全景声 | 黑胶 VIP |

> 设备没有 MediaCodec FLAC 解码器（API < 27 或精简 ROM）时会自动跳过无损档位，避免无声；本 fork 已集成 FFmpeg 解码扩展，API 24–26 也能真无损。

---

## 📦 安装

1. 打开 [Releases](https://github.com/yaxiaiyuting/Ncrust/releases)，下载最新的 release APK（约 **9.8 MB**）
2. 允许「未知来源」安装
3. 打开 App，在用户页登录（见下）

> 体积说明（最新）：v1.9.3 release APK **实测 9,794,320 字节 ≈ 9.79 MB**，debug 30,057,644 字节。
> 体积说明：v1.8.1 release APK **实测 9,761,344 字节 ≈ 9.76 MB**，主要来自 FFmpeg 解码扩展的 4 个 ABI（上游 v1.0.4 时代不含 FFmpeg，只有 3.9 MB）。v1.9.0 构建后按实际产物更新。

### 🔐 登录

App 不提供手动粘贴 Cookie，登录方式：

- **浏览器登录（手机默认）** — 用户页 → 头像 → 应用内 WebView 打开网易云登录页，登录后自动提取凭证
- **扫码登录（平板 / 宽屏）** — 生成二维码，用手机网易云 App 扫码
- **手机扫码授权平板** — 平板显示二维码后，用已登录 Ncrust 的手机扫描，经局域网把凭证加密传给平板

登录成功后自动同步云端收藏。

---

## 🛠️ 从源码构建

### 环境要求

Android Studio Hedgehog+ · JDK 11 · Kotlin 1.9.24 · Gradle 9.3.1 · Android SDK 36（minSdk 24）

### ⚠️ 必须先克隆 Kanesumi

`settings.gradle.kts` 通过 `includeBuild("../Kanesumi-sec-a")` 直接依赖同级的 [Kanesumi](https://github.com/GuitaristRin/Kanesumi-sec-a) 源码仓库。**单独克隆 Ncrust 无法构建**，目录结构必须是：

```
projects/
├── Ncrust/
└── Kanesumi-sec-a/
```

### 构建命令

```bash
git clone https://github.com/GuitaristRin/Ncrust.git
git clone https://github.com/GuitaristRin/Kanesumi-sec-a.git

cd Ncrust
./gradlew assembleDebug            # Debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease          # Release APK（R8 minify，需 keystore.properties）

benchmark/run_benchmark.sh all     # Macrobenchmark：冷启动 / 滚动 / 播放器展开
```

---

## 🧭 技术架构

| 主题 | 说明 |
|---|---|
| **Kanesumi Design** | 直角、无圆角、无弹簧；共享组件与动画词汇（`Metro*` / `sokuou`）位于外部 Kanesumi 库，App 内**已无 `material3` 依赖** |
| **GPU 零重组** | 播放器动画由单个 `progress: Animatable<Float>` 驱动，视觉属性只在 `graphicsLayer {}` 内读取；逐字高亮同样只在 draw 阶段推进 |
| **三层图层** | 主页面 / 播放卡 / 导航栏为独立 composable 层，手势转场互不干扰 |
| **歌词引擎** | `LrcParser`（行级）/ `YrcParser` + `YrcAligner`（网易云逐字，LCS 保序对齐）/ `SweepTrack`（连续光标）/ `TtmlParser` + `TtmlScanner`（自研纯 Kotlin 扫描器，零依赖、JVM 可单测）/ `AmllTtmlClient`（AMLL 镜像拉取）/ `LyricSourceChain`（三级回退）/ `LyricRequestGate`（防快速切歌竞态）/ `LyricsCache`（200 条持久化） |
| **网络直连** | 自有 `EapiCrypto`（AES-128-ECB + MD5 签名）与 `WeapiCrypto`（双 AES-CBC + 原始 RSA），直连网易云，无中间服务器 |
| **ContentCache** | 内存网络快照 + Crossfade，消除「空屏 → spinner → 跳变」；LRU-32 详情缓存 |
| **离线缓存** | `cache/OfflineAudioCache`（media3 `SimpleCache` + `CacheDataSource`，`filesDir/offline/audio`，512 MiB LRU；自定义 key `ncrustkey=song:<id>:<level>`，否则 URL 每次轮换永远不命中）+ `OfflineUrlStore`（离线回放 URL 清单，300 条 LRU） |
| **持久化** | 全部 SharedPreferences + Gson，无 Room |
| **性能** | R8 全量混淆 + 资源 shrink（release 9.76 MB，v1.8.1 实测）、`AppWarmup` 冷启动预热、状态订阅下推至叶子组件、播放器折叠态子树 gating、大屏可视化 60fps 增量重绘 |

```
app/src/main/java/com/takahashirinta/ncrust/
├── MainActivity.kt        # 入口 + MainScreen 编排（导航 / 队列 / 播放模式）
├── auth/                  # Cookie + 局域网扫码配对
├── cache/                 # ContentCache（内存快照）+ OfflineAudioCache（media3 SimpleCache）
├── library/               # 云端收藏 + 搜索历史
├── lyric/                 # LRC / yrc / TTML 解析 + 逐字对齐 + 歌词源回退链 + 歌词缓存
├── network/               # Retrofit / eapi / weapi / 加密
├── player/                # PlaybackService / SongUrlFetcher / PlayReporter / PreloadSlot
├── reco/                  # 音乐人推荐锚点推导（ArtistReco）
├── warmup/  power/        # 冷启动预热、电池白名单
└── ui/                    # screen / player（含 AudioVisualizer）/ components / theme / i18n / navigation
```

> 更完整的架构说明见 [AGENTS.md](AGENTS.md) 与 [Wiki](https://github.com/GuitaristRin/Ncrust/wiki)。

---

## 📋 版本历史

| 版本 | 日期 | 亮点 |
|---|---|---|
| v0.1.0-beta | 2026-04-26 | 初始 MVP，核心播放流程跑通 |
| v1.0.0 | 2026-04-30 | 首个正式版：多屏幕适配、状态持久化、队列管理 |
| v1.0.1 ~ v1.0.3 | 2026-05-04 | 零重组修复、主题色系统、多语言框架、歌单页闪退与 WebView 登录修复 |
| v1.1.0 | 2026-05-05 | 收藏 / 队列 / 音质完善 |
| v1.1.4 | 2026-05-17 | 无缝播放元数据同步、URL 缓存去重、多语言扩充 |
| v1.2.0 | 2026-07-28 | Sokuou 动画系统、ContentCache + Crossfade、专辑收藏、批量入队 |
| v1.2.1 | 2026-07-29 | Kanesumi 化收官、冷启动预热、播放器重组归零、R8（25 MB → 4.1 MB） |
| v1.2.2 | 2026-08-04 | 全页面 Kanesumi 统一、底部导航与迷你条衔接、登录统一为浏览器方式 |
| v1.3.0 | 2026-09-10 | 平板 / 大屏 Sidebar 与宽屏两栏、唯一封面、弱网缓冲、AudioSink 降档兜底、歌词竞态与定位修复、队列拖拽重排、私人 FM 续播 |
| v1.3.1 | 2026-09-11 | 车机（Android Auto / AAOS）媒体源与浏览树、浅色模式与主题模式切换、扫码登录重构（平板扫码 + 手机扫码授权平板 + weapi 加密修正）、冷启动与歌词性能优化、播放卡与浅色修复 |
| v1.4.0 | 2026-09-22 | 底部控制栏可收起（`Animatable` + `graphicsLayer`，动画帧零重组）、暂停态拖动进度条立即生效、首页「播放全部」先播后补、音乐人推荐卡片（纯本地判定，配置默认全空） |
| v1.5.0 | 2026-09-22 | yrc 逐字歌词（`drawWithContent` 双层渲染、零重组）与行级翻译双语、音乐人推荐自动锚点收敛（去重 / 频次排序 / 截断 20）、离线下载调研与架构选型（D1 + D2，实现留后续版本） |
| v1.5.1 | 2026-09-22 | 歌词字号调节（设置页五档 + 界面 A-/A+）、媒体控制中心显示当前歌词行、逐字动画三模式（渐变 / 硬切 / 关闭，修 API 36 无效果）、控制栏把手排除系统手势区 |
| v1.5.2 | 2026-09-22 | 逐字渐变重做（`SweepTrack` 连续光标 + 逐帧推进 + 窄离屏软边）、渐变质量三档（自动 / 高级 / 兼容）、待播槽位不变量修「显示下一首、耳朵还是上一首」串台、冷启动不再等网络（最长 30 s → 本地阶段） |
| v1.6.0 | 2026-09-22 | 逐字覆盖率提升（可用歌 37 → 42 / 100，行级挂载 77.9% → 90.0%，`YrcAligner` LCS 对齐）、离线缓存 Phase 1（已播放音频落盘 + 断网回放）、Android 16 实时更新（Live Updates，代码路径就绪；实测机型不允许第三方提升通知，未验证） |
| v1.6.1 | 2026-09-23 | 媒体面板 / 通知栏 / 实时更新三处统一为「第一行当前歌词、第二行歌名 · 艺人」 |
| v1.7.0 | 2026-09-23 | 修「收起播放器只能拖下去不能拖上来」（手势吸附阈值 + 甩动判据 + `PlayerDragSnap`）、横屏大屏模式（左大封面 + 右歌词 + 就地音质）、M3 理念美化（层级色槽 / 对比度 / TalkBack 与 48dp 点击目标） |
| v1.8.0 | 2026-09-23 | 应用内自动旋转开关（竖转横自动进大屏）、大屏沉浸式（自动收起状态栏）、竖屏就地音质选择器、大屏音频可视化（`TeeAudioProcessor`，零新增权限）、API 24 状态栏歌词随行刷新 |
| v1.8.1 | 2026-09-23 | 大屏可视化提帧（数据 20 → 30 柱/秒、重绘 20 → 60fps、柱高时间常数平滑；PCL110 实测 ~20fps → 61fps / Janky 0.00%） |
| **v1.9.0** | **2026-09-23** | **AMLL TTML 逐字歌词源 + TTML → YRC → LRC 三级回退链（自研纯 Kotlin 扫描器，渲染层零改动）；实测 TTML 覆盖率 10.2%，逐字覆盖率 40.8% → 45.9%（+5.1pp）** |
| v1.9.1 | 2026-09-23 | 歌词镜像回退修正（非权威镜像 jsdelivr 的 404/403 不再被判成「这首歌没有 TTML」，改为继续试下一面）+ 「歌词源 … picked=」可观测性日志 |
| **v1.9.2** | **2026-09-23** | **译文 / 音译轨分轨合并：TTML 胜出时不再整轨丢弃网易云的翻译与音译（按文本 / 行序对齐、失败逐行丢弃、渲染层零改动）；修掉 `22704409` 丢翻译与 `1959528822` 丢音译；网易云 `romalrc` 音译轨接入缓存，并记译文 / 音译来源** |
| **v1.9.3** | **2026-09-24** | **歌词音译显示（罗马音 / 粤拼，可选开关默认关）：渲染层受控解禁，只加音译行渲染（原文 → 译文 → 音译），`SweepTrack` 核心算法与 v1.9.2 数据层一个字节未改；默认关时渲染路径与 v1.9.2 逐字节一致；S6 实测帧时间无劣化（p50 14.9 vs 15.2 ms），版本号改由 `tools/next-version.sh` 三源交叉校验确定** |
| **v2.0.0** | **2026-09-24** | **离线缓存 Phase 2（离线曲目管理页：列表 / 单曲删除 / 容量上限 + 占用分项，去掉图片缓存双计；离线优先取链；过期的 TTML 歌词离线兜底）；修三个用户报告的 bug：大屏模式在自动旋转关闭时**保持横屏**（推翻 v1.8.0「绝不长期锁横屏」契约）、乱序播放不再静默退回顺序播放（`play_mode` 落盘 + 轮末不再重播 + 游标同步 + 相邻不同艺人）、歌词加载期控制栏问题**未复现未能定位**（如实记录）；新增播放时禁止熄屏；新增实验性动态字号（默认关，`SweepTrack` 零改动）** |

| **v2.0.1** | **2026-09-24** | **hotfix：大屏模式下逐字歌词在「还没进入第一句」时被拉到最顶端、第一句看不见。根因 = 歌词面板固定 200dp 的顶部留白在大屏右栏（面板实高约 260dp）里占掉 77%，而前奏期间三条自动定位路径全部提前返回；修法 = 顶部留白按视口夹取（`min(200dp, 36% × 视口)`）+「没有当前行」也按第一句定位，滚动定位抽成纯函数 `LyricsPanelScroll` 并加 JVM 单测；`SweepTrack` 核心算法一个字节未改** |
| **v2.0.2** | **2026-09-25** | **媒体通知专项 hotfix（华为/荣耀）：① 「双通知栏」—— media3 `MediaSessionService` 默认实现会在应用自己那条之外**再自动 post 一条**媒体通知（id=1001 / channel=`default_channel_id`），两个 id 不同谁也覆盖不了谁；且那条正文取自 media3 会话 metadata，手动起播时是空的、预载时也无歌词。修法 = 覆盖**双参** `onUpdateNotification` 且不调 super（media3 官方留的口子）+ 升级时清掉旧版遗留的 id=1001。② 「通知栏歌词只有暂停/播放才刷新」—— 跨行重 post 被 v1.8.0 的 `SDK_INT < P` 闸门关掉，而「API 28+ 系统会自己重建」这个前提不成立（SystemUI 只在通知被 post 那一刻读一次会话 metadata，之后不轮询）；修法 = 删掉版本闸门，判定收敛成纯逻辑 `LyricNotifyGate` + 17 个 JVM 单测，限流从「丢弃」改为「延后重试」。华为 WGR-W09（HarmonyOS 4.2 / API 31）与荣耀 AGT-AN00（MagicOS 9 / API 35）真机验证：通知条数 2 → 1、下拉卡片第一行随歌词实时推进；AOSP API 34/24 模拟器回归通过；`SweepTrack` 零改动** |

> 日期取自各版本 git tag；v1.4.0 起的每行亮点均来自 `dist/RELEASE-NOTES-*.md` 真实发布说明。
> 补充：v1.4.1 / v1.4.2 是 v1.4.0 的补丁（暂停态 seek 歌词即时同步、控制栏把手），未在表内单列；v1.5.1 没有独立的 release notes 文件，其亮点取自该版本的提交记录。

---

## 🎙️ 歌词数据来源

歌词来自两个互相独立的公开数据源，**都不需要登录**，也都只读取公开数据：

| 来源 | 内容 | 说明 |
|---|---|---|
| 网易云官方歌词接口 | 行级 `lrc` + 行级翻译 `tlyric` + 逐字 `yrc` | `POST /api/song/lyric`（`lv` / `tv` / `yv` 开关）；匿名与登录态返回逐字节相同 |
| [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db) | 逐字 TTML（`ncm-lyrics/<songId>.ttml`） | **CC0-1.0**；按网易云歌曲 ID 直接拉取公开静态文件 |

- **不爬虫、不模拟登录、不绕过任何保护机制**：AMLL TTML DB 是社区维护的公开静态文件仓库，客户端只按歌曲 ID 发起普通 HTTP GET；4 个镜像实测内容逐字节一致、可互为备份，取不到就静默回退，不重试、不探测。
- **覆盖率如实说明**（v1.9.0 Phase 0 实测，样本 = 98 首真实收藏）：TTML 覆盖率 **10.2%**（10/98），yrc 覆盖率 40.8%（40/98），两者并集 **45.9%**（45/98）—— TTML 的净增量是 **+5.1 个百分点**，不是「大幅提升」。样本来自单一账号（偏华语流行 + 部分欧美摇滚 / 电子），ACG / vocaloid / 日系用户的实际 TTML 覆盖率会显著更高。
- **不做按歌名 / 歌手模糊匹配的兜底**：不同版本、不同音源的歌词时间轴不可混用，宁可没有逐字，也不给错位高亮。
- 第三方依赖许可证与 GPLv3 兼容性分析见 [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md)；AMLL TTML DB 为 CC0-1.0，本项目**不引入任何新的第三方运行时依赖**。

### TTML 的定位：**英文歌的逐字 + 翻译源**，不是覆盖率主力

TTML 在本项目里的角色是「给网易云没有逐字 / 没有译文的那部分歌补一份」，**不是**把逐字覆盖率拉高：

| 语种 | 网易云 YRC（逐字） | 逐字并集（YRC ∪ TTML） | 网易云 tlyric（译文） |
|---|---|---|---|
| 中文 | 16/30 = **53.3%** | 66.7% | 池内 232 首只有 4 首（2%） |
| 英文 | 24/30 = **80.0%** | 83.3% | 池内 274 首 248 首（91%） |
| 日文 | 1/20 = **5.0%** | 5.0% | 90% |
| 韩文 | **0/10 = 0%** | 0% | 100% |

- **日韩没有逐字**（TTML 在日韩基本 0 命中、网易云 YRC 也只有 0–5%），但译文与音译接近 100% ——
  对日韩用户，译文质量的天花板在网易云。**这一点只写在文档里，不做 UI 提示、不加弹窗、不加设置项。**
- **不做按语言分流**：实测数据不支持（语言维度相关的是覆盖率，不是「哪个源更好」）；也**不做语言检测**、
  **不做同名异版的模糊匹配**（会让逐字错位）。
- 中文歌几乎恒无 `tlyric`（池内 232 首里只有 4 首），所以「TTML 优先会丢译文」在中文歌上根本不会触发，
  风险集中在非中文语种。

### 分轨合并（v1.9.2）：主歌词 / 译文 / 音译**各自独立回退**

- 主歌词沿用 v1.9.0 的 `TTML → YRC → LRC` 回退链（谁的逐字好用用谁），本版一行未改；
- 第二相 TTML 胜出时译文轨与音译轨**不再被整体替换**：TTML 那一轨里能落到主轨时间轴上的行原样用，
  **缺的行**按**文本 / 行序**（归一化去空白 + LCS 保序）回退到网易云的 `tlyric` / `romalrc`，
  对不上的**逐行丢弃** —— 宁可这一行没有译文，也不按时间戳硬配（两源行时间中位差 +169 ms，
  单曲 −551 ms ~ +561 ms，不可混用）。
- **不新增依赖、不新增权限、不新增网络请求**：网易云的 `romalrc` 本来就在同一个 `/api/song/lyric`
  响应里（实测 `rv` 取值与它无关），本版只是把此前没解析的字段接上。
  > **v1.9.3 起音译轨已经从数据层走到 UI**：设置 → 播放 → 「显示音译」（键 `lyrics_romanization`，默认关）。
- **音译轨（`romalrc` / TTML `x-roman`）本版只到数据层**：v1.9.0 / v1.9.1 从未渲染过音译，
  要显示它必须给 `LyricsView` / `NcrustLyricsPanel` 加一个副文本槽，而本版硬约束是
  「渲染层 diff 为空」—— 渲染接线留给解禁渲染层的版本。
---

## 🔗 相关项目

| 项目 | 说明 |
|---|---|
| [Kanesumi-sec-a](https://github.com/GuitaristRin/Kanesumi-sec-a) | Kanesumi Design 组件与动画库（Apache-2.0），本项目 UI 依赖 |
| [163CMAnalyser](https://github.com/GuitaristRin/163CMAnalyser) | Rust CLI 无损下载工具 |
| [Netease_url](https://github.com/Suxiaoqinx/Netease_url) | Python 原版网易云解析（MIT） |

---

## 📄 许可证

**本 Fork 整体以 GPLv3 分发，并完整保留上游原始代码的 MIT 许可。**

Ncrust 原始代码 Copyright (c) 2026 Takahashi_Rinta，遵循 MIT 许可。本 Fork 的修改与新增部分，Copyright (c) 2026 yaxiaiyuting，遵循 GPLv3 许可。整体以 GPLv3 分发。

- 上游项目：[GuitaristRin/Ncrust](https://github.com/GuitaristRin/Ncrust)
- 原始 MIT 许可证全文（未作任何修改、原版权声明完整保留）：[LICENSE-MIT](LICENSE-MIT)
- 本 Fork 整体适用的 GPLv3 全文：[LICENSE](LICENSE)
- 第三方依赖许可证与 GPLv3 兼容性分析：[THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md)
- UI 依赖 Kanesumi（kanesumi-core / anim / controls / structure）为 Apache-2.0，与 GPLv3 兼容。

---

<div align="center">

## ⭐ Star History

<a href="https://www.star-history.com/?repos=GuitaristRin%2FNcrust&type=date&legend=top-left">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=GuitaristRin/Ncrust&type=date&theme=dark&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=GuitaristRin/Ncrust&type=date&theme=light&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
    <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=GuitaristRin/Ncrust&type=date&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
  </picture>
</a>

**如果 Ncrust 让你重新爱上听歌，请赐一颗 Star ⭐**

[问题反馈](https://github.com/GuitaristRin/Ncrust/issues) · [Wiki](https://github.com/GuitaristRin/Ncrust/wiki) · [Releases](https://github.com/yaxiaiyuting/Ncrust/releases)

</div>
