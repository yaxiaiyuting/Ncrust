# probe-cover：封面渲染现状探测（v2.5.0「圆角 + 描边」改造前置）

- 探测对象：`/home/duanjb666/deepseek/ncrust-gpl/Ncrust`，HEAD `2b04201`，`versionCode = 40` / `versionName = "2.4.0-gpl"`
- 探测性质：**只读事实调查**。本次未修改任何源码文件，只新增本文与原始证据文件。
- 原始证据：`docs/verification/v2.5.0/probe-raw/cover-greps.txt`（15 组 grep 的未删改输出 + 计数）
- 所有路径相对于仓库根；行号均为本次读到的实际行号。
- ⚠️ **并发提示**：本次探测期间（19:23），另一条 v2.5.0 工作线**同时**新增了两个未跟踪文件
  `ui/theme/AppShapes.kt`（v2.5.0 · A 圆角规范）与 `ui/theme/AppMotion.kt`（动效规范）。
  §7 / §8 已按该快照更新，但**该文件仍在变动中**（`git status` 显示 untracked；`AppShapesSingleSourceTest` 尚未创建）。
  引用前请重新读取一次。相关探测文档：`probe-theme.md`、`probe-motion.md`（同一 v2.5.0 目录，非本文作者所写）。

---

## 结论

1. 全应用共 **21 个封面/艺人图渲染点**（22 次 `Image` 绘制，其中播放页 1 个 model 点内部画 2 层），另有 6 个非封面图片点（头像 / Markdown 正文图 / 自定义背景 / 两个二维码 / 应用图标）。
2. 这 21 个封面点里 **带 `clip` 的 0 个、带 `border` 的 0 个、带 `shadow` 的 0 个**。当前封面一律**直角、无描边、无裁切**，只有 3 处盖了一个圆形的 `PlayAllButton` 叠加层（首页/库页歌单格、库页专辑格）。
3. 全应用 `RoundedCornerShape` 出现 **0 次**、`Modifier.shadow` **0 次**、`CornerSize` **0 次**、`cornerRadius` **0 次**；`clip(` 全应用只有 1 处、且属于播放按钮（`SongCard.kt:284` `PlayAllButton`），**不是封面**。现有 `border(` 共 11 处，全部是对话框/设置行的直角描边。
4. 「图片不裁圆角」不是疏忽，是**写进注释的设计正典**：`OfflineCacheOverlay.kt:327`「Kanesumi 铁律：图片不裁圆角」、`LibraryPlaylistsTab.kt:476-478`（保留未使用的 `squareShape = RectangleShape`，注释「本页所有封面都是直角」）、`ArtistRecoCard.kt:39`、`SongMenuSheet.kt:48`、`LibraryScreen.kt:396`、`NcrustColors.kt:19`。因此 v2.5.0 的圆角化是**改正典**，这几条注释必须同步改，否则代码与自己的注释矛盾。
5. 全屏播放器封面（`PlayerCard.kt:1424` → `StableCover`）用 `CoverUrls.large()`，窄屏 `Modifier.fillMaxWidth().aspectRatio(1f)`（= 整屏宽正方形）、宽屏 `Modifier.size(coverSizeDp)`，`ContentScale.Crop`，**无任何裁剪/描边**；`StableCover` 内部是一个 `Box` + 两层 `Image`（旧图垫底 + 当前 painter），loading 超时 400ms 才落纯色占位。
6. **关键结构约束**：这个唯一封面叠加层同时充当**折叠态 mini bar 封面**（56dp，`miniCoverHalfPx = 28.dp`）。而且它的视觉位置完全由 `graphicsLayer { translationX/Y/scaleX/scaleY }` 从**布局原点 (0,0)** 搬过去（根 `Box` 在 `PlayerCard.kt:509`，无 `contentAlignment`）。所以**在外面套 `Modifier.clip(...)` 会裁在屏幕左上角而不是封面所在处**，必须把圆角/描边做进 `graphicsLayer` 的**层内坐标**（见 §3.4）。
7. `CoverUrls` 只有两个档位、没有 `thumb`/`detail`：`small()` = 640px、`large()` = 1080px，重写规则 `?param=<N>y<N>`（注意是字母 `y` 不是 `x`），已带 `?param=`/`&param=` 的 URL **原样返回、绝不降级**。
8. 通知栏封面：`PlaybackService` 用 Coil 请求 `1024×1024`，再 `downscaleArtwork()` 夹到 `ARTWORK_MAX_PX = 512`，`setLargeIcon(currentArtworkBitmap)`（`PlaybackService.kt:1647`），并同图以 JPEG 塞进 media3 `artworkData`（`:1405-1409`）。**应用侧不做任何圆角**；最终形状由系统通知栏决定（SystemUI 行为本身未在本次探测中验证，见 §9）。
9. `CustomBackgroundLayer.kt:72` 与 `WaveformRing.kt` 都**不是封面派生绘制**：前者是用户导入的自定义背景图（带 35%/45% 半透明遮罩），后者是 PCM 音频波形（取 `LocalMetroColors.current.primary` 上色，`AudioVisualizer.kt:192`），与专辑封面无关。
10. 改造点共 **21 个封面点**：播放页大封面（1 处）+ 详情页头图（2 处）按 16dp + 1dp；列表/菜单/网格小封面（18 处）按 8dp + 1dp；通知栏封面保持不动。
11. ⚠️ **并发新增的 `ui/theme/AppShapes.kt` 就是本次改造该用的形状落点**：`AppShapes.large`(16dp，文档写明「**封面**」)、`AppShapes.small`(8dp，列表项)、`AppShapes.medium`(12dp，「歌单/专辑/艺人卡片、**封面缩略图容器**」)、`pill`(50%)。**不要把 `RoundedCornerShape(...)` 写进任何封面调用点** —— 该文件的规划测试 `AppShapesSingleSourceTest`（尚未创建）会扫源码树，在 `AppShapes.kt` 之外出现 `RoundedCornerShape(`/`CircleShape` 即判红。
12. ⚠️ **规则口径有一处待裁决的歧义**：任务书给本次的规则是「列表/菜单小封面 = 8dp」，而 `AppShapes.kt` 文档把 8dp 定义为「**列表项**」、12dp 定义为「卡片 / **封面缩略图容器**」。72dp 的列表行封面（§8.2）算「列表项」还是「封面缩略图容器」，两者分别指向 8dp 与 12dp —— **需产品确认，本文按任务书口径写 8dp**。

---

## 1. 全部封面/图片渲染点

命令：`grep -rn "AsyncImage\|rememberAsyncImagePainter\|painterResource\|Image(" app/src/main/java --include=*.kt`
（原始输出见证据文件 `[G1]`；下表逐点读过上下文后补齐）

### 1.1 封面 / 艺人图（21 个点）

| # | file:line | 所在 composable | `model` | `Modifier` 链 | 尺寸 | `contentScale` | clip/border/shadow/overlay |
|---|---|---|---|---|---|---|---|
| 1 | `ui/components/SongCard.kt:103` | `SongCard`（LIST 分支，声明 @ :44） | `CoverUrls.small(song.album?.picUrl)` | `Modifier.size(actualCoverSize)` | 默认 72dp（`coverSize` 未指定时回落，`:85-88`）；搜索页经 `SongSearchItem` 传 56dp（`SearchScreen.kt:756`） | `Crop` | 无 clip/border/shadow；`placeholder = ColorPainter(surfaceVariant)`（`:107`） |
| 2 | `ui/components/SongCard.kt:198` | `SongCard`（GRID 分支） | `CoverUrls.small(song.album?.picUrl)` | `Modifier.fillMaxWidth().aspectRatio(1f)` | 由父格宽决定（正方形） | `Crop` | 无；父 `Column` 有按压缩放 `graphicsLayer`（`:192-196`）；`placeholder = ColorPainter`（`:201`） |
| 3 | `ui/components/DetailScaffold.kt:240` | `DetailHeader`（宽屏分支，声明 @ :219） | `CoverUrls.large(coverUrl)` | `Modifier.size(220.dp)` | 220dp | `Crop` | 无；`placeholder = ColorPainter`（`:243`） |
| 4 | `ui/components/DetailScaffold.kt:266` | `DetailHeader`（窄屏分支） | `CoverUrls.large(coverUrl)` | `Modifier.fillMaxWidth().aspectRatio(1f)` | 整屏宽正方形 | `Crop` | 无；`placeholder = ColorPainter`（`:270`） |
| 5 | `ui/components/ArtistSearchItem.kt:52` | `ArtistSearchItem`（@ :29） | `CoverUrls.small(artist.picUrl)` | `Modifier.fillMaxSize().background(surfaceVariant)` | 父 `Box` 72dp（`:51`） | `Crop` | 无 clip/border；**`background` 直接挂在图片自己的 modifier 上**（`:57`）；无 placeholder painter |
| 6 | `ui/components/SongMenuSheet.kt:49` | `SongMenuSheet`（@ :32） | `CoverUrls.small(song.album?.picUrl)` | `Modifier.size(112.dp)` | 112dp | `Crop` | 无。源码注释 `:48`「直角封面，贴屏左边缘，112dp = 2x 迷你播放栏封面高」 |
| 7 | `ui/components/AddToPlaylistSheet.kt:187` | `AddToPlaylistSheet`（@ :47） | `CoverUrls.small(playlist.coverImgUrl)` | `Modifier.size(48.dp)` | 48dp | `Crop` | 无 |
| 8 | `ui/components/ArtistRecoCard.kt:68` | `ArtistRecoCard`（@ :42） | `rememberAsyncImagePainter(CoverUrls.large(pic))` | `Modifier.fillMaxSize()` | 父 `Box` 72dp（`:61-64`，带 `background(surfaceVariant)`） | `Crop` | 无。源码注释 `:39`「直角、无圆角、封面 72dp」 |
| 9 | `ui/screen/AlbumSearchItem.kt:52` | `AlbumSearchItem`（@ :28） | `CoverUrls.small(album.picUrl)` | `Modifier.size(72.dp)` | 72dp | `Crop` | 无 |
| 10 | `ui/screen/HomeScreen.kt:531` | `DailySongTile`（@ :524） | `CoverUrls.small(song.album?.picUrl)` | `Modifier.fillMaxWidth().aspectRatio(1f)` | 父 `Column` 宽 160dp（`:528`） | `Crop` | 无 |
| 11 | `ui/screen/HomeScreen.kt:570` | `SongGridTile`（@ :563） | `CoverUrls.small(song.album?.picUrl)` | `Modifier.fillMaxWidth().aspectRatio(1f)` | 自适应格宽 | `Crop` | 无 |
| 12 | `ui/screen/HomeScreen.kt:601` | `PlaylistTile`（@ :598） | `CoverUrls.small(playlist.coverUrl)` | `Modifier.fillMaxSize()` | 父 `Box` 宽 160dp 正方形（`:600`） | `Crop` | **有 overlay**：`PlayAllButton`（圆形，34dp，`align(BottomEnd).padding(6.dp)`，`:607-611`）。无 clip/border |
| 13 | `ui/screen/OfflineCacheOverlay.kt:335` | `OfflineTrackRow`（@ :310） | **`url`（`track.albumPicUrl`，未过 CoverUrls）**（`:334-336`） | `Modifier.fillMaxSize()` | 父 `Box` 48dp（`:329-332`，带 `background(coverPlaceholder(songId))`） | `Crop` | 无。**源码注释 `:327`「Kanesumi 铁律：图片不裁圆角」** |
| 14 | `ui/screen/SearchScreen.kt:624` | `ToplistRow`（@ :616） | `CoverUrls.small(playlist.coverUrl)` | `Modifier.size(48.dp)` | 48dp | `Crop` | 无 |
| 15 | `ui/screen/SearchScreen.kt:700` | `SearchHistoryItemCard`（@ :683） | `CoverUrls.small(item.coverUrl)` | `Modifier.size(64.dp)` | 64dp | `Crop` | 无 |
| 16 | `ui/screen/LibraryScreen.kt:444` | `PlaylistGridItem`（@ :435） | `CoverUrls.small(playlist.coverImgUrl)` | `Modifier.fillMaxSize()` | 父 `Box` 正方形（`:443`） | `Crop` | **有 overlay**：`PlayAllButton`（`align(BottomEnd).padding(6.dp)`，`:450-453`） |
| 17 | `ui/screen/LibraryScreen.kt:472` | `LibraryAlbumGridItem`（@ :463） | `CoverUrls.small(album.picUrl)` | `Modifier.fillMaxSize()` | 父 `Box` 正方形（`:471`） | `Crop` | **有 overlay**：`PlayAllButton`（`:478-481`） |
| 18 | `ui/screen/LibraryPlaylistsTab.kt:351` | `QqPlaylistInlineRow`（@ :333） | **`cover`（`playlist.coverUrl`，未过 CoverUrls）**（`:349-352`） | `Modifier.fillMaxSize()` | 父 `Box` 48dp（`:344-347`，带 `background(surfaceVariant)`） | `Crop` | 无。同文件 `:476-478` 保留未使用的 `squareShape = RectangleShape`，注释「本页所有封面都是直角」 |
| 19 | `ui/screen/ArtistDetailScreen.kt:364` | `AggregatedAlbumGridItem`（@ :356） | `CoverUrls.small(album.picUrl)` | `Modifier.fillMaxWidth().aspectRatio(1f)` | 格宽正方形 | `Crop` | 无 |
| 20 | `ui/screen/ArtistDetailScreen.kt:416` | `ArtistAlbumGridItem`（@ :413） | `CoverUrls.small(album.picUrl)` | `Modifier.fillMaxWidth().aspectRatio(1f)` | 格宽正方形 | `Crop` | 无。整条声明写在一行上（`:416`） |
| 21 | `ui/player/PlayerCard.kt:1424` | `PlayerCard`（@ :90）→ `StableCover`（@ :1513） | `CoverUrls.large(s.album?.picUrl)` | 见 §3.2 | 窄屏整屏宽 / 宽屏 `coverSizeDp`；mini 态 56dp | `Crop` | 无 clip/border；内部有占位 `background(placeholderColor)`（`:1555`）。**唯一同时服务全屏与 mini bar 的封面** |

**汇总：21 个封面点里带 `clip` 的 0 个、带 `border` 的 0 个、带 `shadow` 的 0 个；3 个有叠加层（#12/#16/#17，均为圆形播放键）；6 个容器带 `background` 占位色（#5/#8/#13/#18 加上 #21 内部占位、以及 `UserScreen` 头像）。**

### 1.2 非封面图片点（6 个，改造范围外，列出以免误改）

| file:line | 所在 composable | 说明 |
|---|---|---|
| `ui/screen/UserScreen.kt:843` | `ProfileBlock`（@ :820） | 用户头像，`model = profile.avatarUrl`（**未过 CoverUrls**，`:844`），`Modifier.fillMaxSize()`，父 `Box` 96dp + `background(surfaceVariant)`（`:836-839`），`Crop`；无 clip（**不是圆形**，缺头像时回落 `Icons.Default.Person`） |
| `ui/theme/MarkdownText.kt:54` | `MarkdownText`（@ :43） | Markdown 正文图片 `![alt](url)`，`Modifier.fillMaxWidth().padding(vertical = 8.dp)`，`ContentScale.Fit` + `alignment = Alignment.Center` |
| `ui/CustomBackgroundLayer.kt:72` | `CustomBackgroundLayer`（@ :46） | 用户导入的自定义背景图（`ImageRequest` + `allowRgb565(true)` + `crossfade(false)`，`:73-79`），`Modifier.fillMaxSize()`，`Crop`；上面盖 `Box.fillMaxSize().background(白 35% / 黑 45%)` 遮罩（`:86-93`） |
| `ui/components/QrLoginDialog.kt:154` | 二维码显示 | `Image(bitmap = bmp.asImageBitmap(), ...)`，登录二维码，非封面 |
| `ui/components/QqQrLoginDialog.kt:192` | 二维码显示 | 同上（QQ 登录） |
| `ui/screen/AboutScreen.kt:81` | 关于页 | `painterResource(id = R.drawable.ic_launcher)`，应用图标 |

---

## 2. `CoverUrls` helper

文件：`app/src/main/java/com/takahashirinta/ncrust/network/CoverUrls.kt`（全文 28 行，`object CoverUrls`）

| 成员 | 行号 | 语义 | 实际像素 |
|---|---|---|---|
| `fun small(url: String?)` | `:16` | 「列表/网格/tile 封面」（注释：160~320dp × 2.6x ≈ 420~840px，640 足够） | `applyParam(url, 640)` |
| `fun large(url: String?)` | `:19` | 「全屏播放器/详情页头部封面：1080 宽屏」 | `applyParam(url, 1080)` |
| `private fun applyParam(url, px)` | `:21-27` | URL 重写 | 见下 |

**重写规则（逐字，`CoverUrls.kt:21-27`）：**

1. `url == null` → 返回 `null`（`:22`）
2. `!url.startsWith("http")` → **原样返回**（`:23`，本地/`content://` 等不走图床）
3. `url.contains("?param=") || url.contains("&param=")` → **原样返回，绝不降级**（`:25`；注释 `:8-9` 说明部分 API 已返回官方最佳尺寸）
4. 否则按是否已有 `?` 拼接：`"$url&param=${px}y${px}"` 或 `"$url?param=${px}y${px}"`（`:26`）

> ⚠️ 精确性提醒：拼接格式是 **`param=WyH`（字母 `y` 作分隔）**，例如 `?param=640y640` / `?param=1080y1080`，不是 `WxH`。
> ⚠️ **不存在** `CoverUrls.thumb(...)` / `CoverUrls.detail(...)`：全仓库 `CoverUrls.` 引用只有 `small` 与 `large` 两种（证据 `[G4]`）。

**一致性约束（注释 `:11`）**：显示端与 `AppWarmup` 预取必须走同一函数，URL 才能命中同一份磁盘缓存。`AppWarmup.kt:170` 用 `CoverUrls.small` 预取首页封面，`AppWarmup.kt:181-186` 以 `COVER_PX` 为尺寸 execute（**预取，不是渲染点**）。

---

## 3. 全屏播放器封面

文件：`app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt`

### 3.1 渲染调用点

- `PlayerCard.kt:1420-1422` 注释：「唯一封面叠加层：全屏 ↔ miniBar 共用这一个 cover，随 progress 平滑位移/缩放。窄屏大图=整屏宽居中；宽屏大图=左栏封面区实测中心与尺寸。」
- `PlayerCard.kt:1424-1455`：`StableCover(model = CoverUrls.large(s.album?.picUrl), contentDescription = null, placeholderColor = LocalMetroColors.current.surfaceVariant, modifier = ..., contentScale = ContentScale.Crop)`
- `contentDescription = null`（`:1426`，播放页封面不进无障碍树）

### 3.2 尺寸与定位

```
modifier = Modifier
    .then(
        if (usesSideCover) Modifier.size(coverSizeDp)          // :1430
        else Modifier.fillMaxWidth().aspectRatio(1f)            // :1431  ← 窄屏：整屏宽正方形
    )
    .graphicsLayer {
        val p = progress.value
        val normalizedP = ((p - 0.2f) / 0.8f).coerceIn(0f, 1f)
        val lyricAnimValue = if (usesSideCover) 0f else lyricAnimProgress.value
        val targetCenterX = largeCoverCenterX + lyricAnimValue * (miniCoverCenterX - largeCoverCenterX)
        val targetCenterY = largeCoverCenterY + lyricAnimValue * (miniCoverCenterY - largeCoverCenterY)
        val targetScale   = miniScale + (1f - lyricAnimValue) * (1f - miniScale)
        val currentCenterX = miniCoverCenterX + normalizedP * (targetCenterX - miniCoverCenterX)
        val currentCenterY = miniCoverCenterY + normalizedP * (targetCenterY - miniCoverCenterY)
        val currentBaseScale = miniScale + normalizedP * (targetScale - miniScale)
        scaleX = currentBaseScale; scaleY = currentBaseScale
        translationX = currentCenterX - boundsCenter
        translationY = currentCenterY - boundsCenter
        transformOrigin = TransformOrigin(0.5f, 0.5f)
    }
```
（`PlayerCard.kt:1428-1453`，`contentScale = ContentScale.Crop` 在 `:1454`）

相关量（`PlayerCard.kt:230-244`）：

| 量 | 行号 | 定义 |
|---|---|---|
| `coverSizePx` | `:230-234` | `usesSideCover` → 实测 `wideCoverSizePx`，兜底 `PlayerLayout.coverFallbackSizePx(...)`；窄屏 → `screenWidthPx` |
| `coverSizeDp` | `:235` | `coverSizePx.toDp()` |
| `miniCoverHalfPx` | `:236` | `28.dp.toPx()` ⇒ **mini bar 封面 = 56dp** |
| `miniScale` | `:237` | `miniCoverHalfPx * 2f / coverSizePx` |
| `miniCoverCenterX/Y` | `:240-241` | 左上角贴状态栏下方：`(28dp, statusBar + 28dp)` |
| `largeCoverCenterX/Y` | `:242-243` | 宽屏取 `wideCoverCenter`；窄屏 `(screenWidthPx/2, screenHeightPx*0.3f + 24dp)` |
| `boundsCenter` | `:244` | `coverSizePx / 2f` |
| `usesSideCover` | `:191` | `isWidePlayer \|\| bigScreenActive` |
| `wideCoverCenter` 的实测来源 | `:1059-1069` | 左栏一个**空的** `Box`（注释 `:1056-1058`：这里不放第二个封面）通过 `onGloballyPositioned` 回报 center / `minOf(w,h)` |

`PlayerCard.kt:509` 是卡片根 `Box`（**无 `contentAlignment`** ⇒ 默认 `TopStart`）。因此 `StableCover` 的 `Box` 布局在卡片 (0,0)，纯靠上面的 `translationX/Y` 搬到视觉位置。

### 3.3 `StableCover`（`PlayerCard.kt:1512-1566`）与 loading/error 处理

| 行号 | 内容 |
|---|---|
| `:1503` | `private const val COVER_HOLD_MS = 400L`（新封面超时阈值） |
| `:1513-1519` | 签名：`StableCover(model: Any?, contentDescription: String?, placeholderColor: Color, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop)` |
| `:1521-1524` | `rememberAsyncImagePainter(model = model, imageLoader = Coil.imageLoader(context))` |
| `:1525` | `val state = painter.state` |
| `:1527` | `var lastBitmap by remember { mutableStateOf<Bitmap?>(null) }`（跨切歌保留的上一张成功封面） |
| `:1529` | `var timedOut by remember { mutableStateOf(false) }` |
| `:1531-1537` | `LaunchedEffect(state)`：`AsyncImagePainter.State.Success` → 取 `(s.result.drawable as? BitmapDrawable)?.bitmap` 存 `lastBitmap`，并 `timedOut = false` |
| `:1538-1542` | `LaunchedEffect(model)`：`timedOut = false` → `delay(400)` → 若 `painter.state !is Success` 则 `timedOut = true` |
| `:1544` | `Box(modifier = modifier)` ← **传入的 modifier（含 `graphicsLayer`）挂在这里** |
| `:1546-1553` | 底层：`bg = if (timedOut) null else lastBitmap`；非空 → `Image(painter = BitmapPainter(bg.asImageBitmap()), contentDescription = null, modifier = Modifier.matchParentSize(), contentScale)` |
| `:1554-1556` | 底层兜底：`Box(Modifier.matchParentSize().background(placeholderColor))` |
| `:1559-1564` | 上层：`Image(painter = painter, contentDescription, Modifier.matchParentSize(), contentScale)` —— 注释 `:1557-1558` 说明必须真正绘制它，Coil 才会在 `onRemembered` 发起请求；loading 时它不画东西，露出底层 |

**loading/error 语义**：没有 `error` painter、没有 `AsyncImagePainter.State.Error` 分支：
- loading（< 400ms）→ 显示**上一首的封面**（视觉无缝）
- loading（≥ 400ms）或 error → 显示 `placeholderColor` 纯色块（`LocalMetroColors.current.surfaceVariant`，`:1427`）
- error 与「慢加载超时」**共用同一条退化路径**，无法从 UI 区分（未确认是否存在日志区分；`StableCover` 内无日志）

### 3.4 是否裁剪/描边：**没有**，且「外面套 clip」在本结构下是错的

- 现状：`PlayerCard.kt:1428-1455` 的 modifier 链里**没有** `clip` / `border` / `shadow`；`StableCover` 内部也没有（`:1544-1565`）。
- 若在 `:1428` 的链首加 `Modifier.clip(RoundedCornerShape(16.dp))`：该 `clip` 属于 `graphicsLayer` **之外**的节点，裁剪发生在**布局坐标**（卡片左上角 (0,0) 起的 `coverSizePx` 区域），而封面视觉位置被 `translationX/Y` 搬到了屏幕中心/左栏中心 ⇒ **会把可见封面裁掉或完全裁没**。折叠态更明显：`currentCenterY` 落在状态栏附近的 56dp 小图，与布局区域几乎不重叠。
- 正确做法（两种，均作用于**层内坐标**，因此圆角随封面一起缩放）：
  1. 在既有 `graphicsLayer { }` 块内追加 `shape = RoundedCornerShape(16.dp)` + `clip = true`（`GraphicsLayerScope` 提供 `shape` / `clip`，**:1433 那个块可直接加两行，不动其它动画量**）；
  2. 或在 `StableCover` 的 `Box`（`:1544`）与两层 `Image`（`:1551` / `:1562`）上加 `Modifier.matchParentSize().clip(shape)`，并把 `shape` 作为新参数从 `PlayerCard` 传入（更易复用，且不依赖 `GraphicsLayerScope.shape` 语义）。
- 描边的额外坑：**1dp 描边若画在层内，会随 `scaleX/scaleY` 一起缩放** —— 全屏态 1dp 正常，mini 态（`miniScale ≈ 56dp / coverSizePx`）会被缩到亚像素（窄屏 1080px 宽时约 0.05dp ⇒ 不可见）。同理 16dp 圆角在 mini 态约 0.83dp，视觉上等同直角。若产品要求 mini bar 也有可见的 1dp 描边/8dp 圆角，必须**另画一层不随缩放变化的 overlay**（放在 `graphicsLayer` 之外、用与 `miniCoverCenter*` 同步的固定尺寸），而不能复用层内写法。**此取舍需要产品决策，本次未选定。**

### 3.5 mini bar 封面

- **没有独立的 mini bar 封面渲染点**：`PlayerCard.kt:1420` 注释与 `:225` 注释都明确「全屏 ↔ miniBar 始终是同一个 cover」、「唯一封面 overlay」。
- mini 态只是同一个 `StableCover` 的 `graphicsLayer` 结果：`scaleX/Y = miniScale`、中心 = `(28dp, statusBar + 28dp)`（`:236-241`、`:1444-1449`），视觉边长 56dp。
- mini bar 内容（标题/按钮）在 `PlayerCard.kt:1318` 起的 `Box`，与封面是**兄弟节点**，不共享 modifier。
- 触发条件 `miniBarEnabled`（`:247`）= `progress.value < 0.01f`；mini bar **永远挂载**（不随折叠卸载，`:264` 注释）。

---

## 4. 通知栏封面（`player/PlaybackService.kt`）

| 环节 | 行号 | 事实 |
|---|---|---|
| 常量 | `:136` | `private val ARTWORK_MAX_PX = 512` |
| 常量 | `:133` | `ARTWORK_IDLE_RELEASE_MS = 45_000L`（暂停 45s 后释放位图） |
| 取色采样边长 | `:138-139` | `PALETTE_SAMPLE_PX = 112` |
| 大图加载 | `:1141-1177` `loadArtwork(url)` | Coil `ImageRequest.Builder(...).data(CoverUrls.large(url)).size(1024, 1024)`（`:1149-1154`） |
| 预载 | `:1119-1139` `preloadArtwork(url)` | 同上 `CoverUrls.large(url)` + `.size(1024, 1024)`（`:1124-1127`） |
| 降采样 | `:1041-1048` `downscaleArtwork(source)` | 长边 ≤ `ARTWORK_MAX_PX`(512) 原样返回；否则按比例 `Bitmap.createScaledBitmap` ⇒ **通知栏位图长边 ≤ 512px** |
| 当前位图 | `:1162` | `currentArtworkBitmap = bitmap`（ARGB_8888，`:1161`） |
| **通知大图标** | `:1646-1648` | `if (currentArtworkBitmap != null) { builder.setLargeIcon(currentArtworkBitmap) }` |
| 通知着色 | `:1643-1644` | `.setColor(currentDominantColor)` + `.setColorized(true)`（来自封面取的 dominant 色，`:1104`、`:1113`） |
| 通知样式 | `:1635-1642` | `androidx.media.app.NotificationCompat.MediaStyle()` + `setMediaSession(mediaSession?.sessionCompatToken)` + `setShowActionsInCompactView(0, 1, 2)` |
| 会话元数据位图 | `:1405-1409` | `meta.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)`（JPEG，见 `:1413-1424`；注释给的理由是 Binder 1MB 上限） |
| 会话元数据 URI | `:823`、`:968`、`:1016` | 三处 `setArtworkUri(Uri.parse(CoverUrls.large(...)))`（MediaItem 首建 / metadata 刷新 / 另一条构造路径） |
| media3 自发的第二条通知 | `:580`、`:1659-1698`、`:1714` | v2.0.2 起 `onUpdateNotification` 不再调 super，并主动 `cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)` —— 通知栏只剩本类 `id = 1` 这一条 |

**是否圆角：应用侧完全不做圆角。** 全仓库没有任何 `RoundedBitmapDrawable` / `createRoundedBitmap` / `drawRoundRect` / `RoundedCornerShape`（证据 `[G2]`、`[G7]`）。最终形状由系统通知栏/锁屏（SystemUI）绘制决定 —— **SystemUI 在该 ROM 上的具体表现本次未验证，见 §9**。

---

## 5. 其他封面数据与封面派生绘制

### 5.1 数据层只存 URL，不渲染

| file:line | 说明 |
|---|---|
| `cache/OfflineLibrary.kt:44` | `val albumPicUrl: String? = null`（离线曲目索引字段；注释 `:43`「列表里只用来画一个色块，取不到就回落纯色块」） |
| `cache/OfflineLibrary.kt:210`、`:224` | `record(... albumPicUrl: String?, ...)` 写入路径 |
| `cache/ContentCache.kt` | **无** `picUrl` / `coverUrl` / `coverImgUrl` / `avatarUrl` 字段（grep 0 命中）——只持有模型对象，封面 URL 在模型里 |
| `library/LibraryManager.kt:344`、`:433` | `picUrl = it.picUrl` 映射与 `val picUrl: String`（收藏专辑模型；非渲染点） |
| 离线列表消费点 | `ui/screen/OfflineCacheOverlay.kt:334-336`（唯一消费 `albumPicUrl` 的渲染点，见 §1.1 #13） |

### 5.2 `ui/CustomBackgroundLayer.kt` —— **不是封面派生**

- 文件头注释 `:8-9`：「v1.2.0 · B3 自定义背景图 …… 铺在根 Box 最底层的背景图层 + 半透明遮罩」。
- 数据源是**用户导入的文件**：`BackgroundImageManager.backgroundFile(context)`（`:57`），不是 `albumPicUrl`。
- `:72-83` `AsyncImage(model = ImageRequest(...).data(file).allowRgb565(true).crossfade(false), contentScale = Crop, modifier = Modifier.fillMaxSize())`。
- `:86-93` 半透明遮罩：浅色主题 `Color.White.copy(alpha = 0.35f)`、深色主题 `Color.Black.copy(alpha = 0.45f)`。
- 结论：与专辑封面无关，**不在 v2.5.0 封面圆角改造范围**。

### 5.3 `ui/player/WaveformRing.kt` —— **不是封面派生**

- `WaveformRing`（`ui/player/WaveformRing.kt:39`）是纯数值环形缓冲（`FloatArray`、`targets`/`bars`、指数逼近时间常数），**不接触任何 Bitmap / URL / 封面**（grep `Bitmap|cover|Cover|album|palette|Palette` 在该文件 0 命中）。
- 消费方 `ui/player/AudioVisualizer.kt:103`：`WaveformRing(capacity = CAPACITY, barCount = BAR_COUNT)`；颜色 `barColor = LocalMetroColors.current.primary`（`AudioVisualizer.kt:192`），绘制在 `:243`。**取的是主题色，不是封面取色。**
- 结论：与封面无关，**不在改造范围**。

### 5.4 封面取色（会随圆角改造被间接触及，但不是渲染点）

| file:line | 说明 |
|---|---|
| `PlaybackService.kt:1080-1117` | `applyCoverAccent(bitmap, url, gen)`：112×112 采样 + `Palette` 取色，`paletteUrl` 去重；产出 `paletteDominant`（通知着色）与 `paletteRgb`（主题 accent） |
| `ui/screen/HomeScreen.kt:711-730` | `extractAvatarAccent(context, avatarUrl)`：`Coil.imageLoader` + `.data(CoverUrls.large(avatarUrl)).size(128, 128).allowHardware(false)` + `Palette`（取色，不渲染） |

---

## 6. 现有 `clip` / `border` / `shadow` / `RoundedCornerShape` 全量清单

命令：`grep -rn "\.clip(\|\.border(\|Modifier.shadow\|RoundedCornerShape" app/src/main/java --include=*.kt`（证据 `[G2]`）

**共 12 行命中：`border(` 11 处 + `clip(` 1 处；`Modifier.shadow` 0 处、`RoundedCornerShape` 0 处。**

| file:line | 形式 | 归属 | 与封面有关? |
|---|---|---|---|
| `ui/components/QrLoginDialog.kt:192` | `.border(1.dp, LocalMetroColors.current.primary)` | 二维码框（选中态） | 否 |
| `ui/components/QrLoginDialog.kt:207` | `.border(1.dp, LocalMetroColors.current.divider)` | 二维码框 | 否 |
| `ui/components/QrScannerScreen.kt:230` | `.border(1.dp, LocalMetroColors.current.primary)` | 扫码取景框 | 否 |
| `ui/components/BackgroundActivityDialog.kt:70` | `.border(1.dp, onSurfaceVariant.copy(alpha = 0.4f))` | 对话框（注释 `:23`「直角、信息优先、无圆角」） | 否 |
| **`ui/components/SongCard.kt:284`** | **`.clip(CircleShape)`** | **`PlayAllButton`（@ :274）**——注释 `:279`「圆形外框(用户决策)」；`clip` 先于 `background`（`:285`） | 否（是按钮，不是封面） |
| `ui/screen/UserScreen.kt:625` | `.border(1.dp, LocalMetroColors.current.divider)` | 设置行 | 否 |
| `ui/screen/UserScreen.kt:996` | `.border(...)`（多行） | 主题模式选择器 | 否 |
| `ui/screen/UserScreen.kt:1254` | `Modifier.border(1.dp, onSurfaceVariant.copy(alpha = 0.4f))` | 对话框按钮 | 否 |
| `ui/screen/UserScreen.kt:1285` | `Modifier.border(1.dp, borderColor)` | 全宽对话框按钮 | 否 |
| `ui/screen/UserScreen.kt:1322` | `.border(1.dp, onSurfaceVariant.copy(alpha = 0.4f))` | 语言下拉 | 否 |
| `ui/theme/ThemeColorSelector.kt:55` | `Modifier.border(1.dp, Color.Gray.copy(alpha = 0.5f))` | 主题色圆点 | 否 |
| `ui/theme/AccentSourceSelector.kt:42` | `.border(...)`（多行） | accent 来源选择 | 否 |

**结论：现有形状词汇表里，封面这一类的「裁切/描边」是空的 —— 预期被证实，不是假设。** 唯一非直角形状是 `PlayAllButton` 的 `CircleShape`。
补充：`elevation` 全仓库仅 1 处命中（非 `Modifier.shadow`，见 `[G12]` 计数），`import androidx.compose.foundation.border` 有 5 个文件在用（`[G14]`）——说明 `border` 是既有可用 API，改造无需引入新依赖。

---

## 7. 现有圆角 / 描边常量

命令：`grep -rn "CornerSize\|cornerRadius\|radius\|shape =\|Shape" app/src/main/java --include=*.kt`（证据 `[G3]`）

**全部命中只有 4 行，没有任何可供复用的圆角常量：**

| file:line | 内容 | 说明 |
|---|---|---|
| `ui/components/SongCard.kt:8` | `import androidx.compose.foundation.shape.CircleShape` | 唯一被 import 的 `Shape` |
| `ui/components/SongCard.kt:284` | `.clip(CircleShape)` | 唯一 `clip` 使用（`PlayAllButton`） |
| `ui/screen/LibraryPlaylistsTab.kt:27` | `import androidx.compose.ui.graphics.RectangleShape` | |
| `ui/screen/LibraryPlaylistsTab.kt:476-478` | `/** 未使用但保留的直角裁切常量（Kanesumi Design：本页所有封面都是直角）。 */`<br>`@Suppress("unused")`<br>`private val squareShape = RectangleShape` | **`squareShape` 零引用（grep 仅命中声明行本身）**；这是「封面必须直角」留下的化石 |

- `CornerSize`：**0 命中**；`cornerRadius`：**0 命中**；`RoundedCornerShape`：**0 命中**；`shape =`：**0 命中**。
- `import androidx.compose.foundation.shape.*` 全仓库只有 1 行（`SongCard.kt:8` 的 `CircleShape`，见 `[G15]`）。
- 依赖核对：`app/build.gradle.kts` 中 `material3` 声明 **0 处**；`androidx.compose.material3` import **0 行**（证据 `[G12]`）。可用的形状 API 是 `androidx.compose.foundation.shape.RoundedCornerShape`（foundation，已是既有依赖），描边用 `androidx.compose.foundation.border(width, color, shape)`（已有 5 个文件在用）。
- **⇒ v2.5.0 前本仓库确实没有任何可复用的圆角常量**（`squareShape` 是唯一一个，且是直角、零引用）。

### 7.1 ⚠️ 并发新增：`ui/theme/AppShapes.kt`（v2.5.0 · A 全局圆角规范）

**该文件在本次探测过程中（19:23:38）由另一条工作线创建，`git status` 仍为 untracked，`AppShapesSingleSourceTest` 尚未创建。** 它是 v2.5.0 圆角改造的**唯一形状落点**，本次改造点清单应按它的 token 书写，而不是散落 `RoundedCornerShape(N.dp)`。

| token | file:line | 值 | 该文件文档指定的用途 |
|---|---|---|---|
| `AppShapes.extraSmall` | `ui/theme/AppShapes.kt:67` | `RoundedCornerShape(4.dp)` | 角标、药丸内层、极小控件 |
| `AppShapes.small` | `ui/theme/AppShapes.kt:75` | `RoundedCornerShape(8.dp)` | **列表项**（注释引任务书 §3.1「列表项：small（8dp）」） |
| `AppShapes.medium` | `ui/theme/AppShapes.kt:82` | `RoundedCornerShape(12.dp)` | **卡片**；注释 `:80` 明确含「歌单/专辑/艺人卡片、**封面缩略图容器**」 |
| `AppShapes.large` | `ui/theme/AppShapes.kt:90` | `RoundedCornerShape(16.dp)` | **封面**与歌词面板（注释引任务书 §3.1「封面：large（16dp）+ 边框」） |
| `AppShapes.extraLarge` | `ui/theme/AppShapes.kt:99` | `RoundedCornerShape(28.dp)` | 弹窗（app 自有内容块） |
| `AppShapes.pill` | `ui/theme/AppShapes.kt:107` | `RoundedCornerShape(percent = 50)` | 按钮 / 搜索框 / 音源标签 |

该文件的另外三条硬约束（`AppShapes.kt:28-59`）：

1. 本对象是**唯一落点**；规划中的 `AppShapesSingleSourceTest` 会扫源码树，在 `AppShapes.kt` 之外出现 `RoundedCornerShape(` / `CircleShape` 即让测试变红（`:30-33`）。
2. 不改 Kanesumi 库 —— app 侧对 `Metro*` 组件只能通过调用点 `Modifier.clip(AppShapes.x)` 施加圆角，库内背板保持直角（`:52-59`）。
3. 不提供 `LocalAppShapes` / CompositionLocal（`:45-50`）。

**⚠️ 与现状的两处冲突（需在改造时一并处理）：**

| 冲突 | 证据 | 影响 |
|---|---|---|
| 规划测试要禁 `CircleShape`，但现网已有 1 处 | `ui/components/SongCard.kt:8`（import）+ `:284`（`PlayAllButton` 的 `.clip(CircleShape)`） | 该测试若不做白名单，**创建当天就是红的**。要么把 `PlayAllButton` 改用 `AppShapes.pill`（`RoundedCornerShape(percent = 50)`，视觉等价），要么在测试里豁免。**这是 v2.5.0 必须先裁决的一项。** |
| 8dp 与 12dp 的归属歧义 | 任务书给本次的规则：列表/菜单小封面 = 8dp；`AppShapes.kt:70-82`：8dp = 列表项、12dp = 卡片/**封面缩略图容器** | §8.2 的 18 个点里有 12 个是「列表行内 48~112dp 的封面」，按前者 8dp、按后者 12dp。**需产品裁决**；本文按任务书口径（8dp）书写 |


---

## 8. 改造点清单（v2.5.0：圆角 + 描边）

规则集（来自任务书）：**播放页大封面 = 16dp radius + 1dp border；列表/菜单小封面 = 8dp radius + 1dp border；通知栏封面 = 保持不动。**
形状取值一律走 **`AppShapes`**（§7.1），**禁止在调用点写 `RoundedCornerShape(...)`**（否则撞 `AppShapesSingleSourceTest`）：大封面用 `AppShapes.large`（16dp），列表/菜单小封面用 `AppShapes.small`（8dp）。
描边建议：`Modifier.border(1.dp, LocalMetroColors.current.divider, AppShapes.x)`；`divider` 是既有描边色（`QrLoginDialog.kt:207`、`UserScreen.kt:625` 在用），**属提案而非现状，颜色与对比度需真机确认（§9）**。
⚠️ `border` 需要 `import androidx.compose.foundation.border`（已有 5 个文件在用），`clip` 需要 `import androidx.compose.ui.draw.clip`；两者都要在 21 个点所在文件里新增 import。

### 8.1 播放页 / 详情页大封面 —— `AppShapes.large`(16dp) + 1dp（3 处）

| file:line | composable | 当前尺寸 | 建议处理 |
|---|---|---|---|
| `ui/player/PlayerCard.kt:1424-1455` | `PlayerCard` → `StableCover`(@ :1513) | 窄屏整屏宽 × 1:1；宽屏 `coverSizeDp`；mini 态 56dp | **`AppShapes.large`(16dp) + 1dp。必须做进层内**（`graphicsLayer` 块 `:1433` 加 `shape`/`clip`，或在 `StableCover` 的 `Box`/两层 `Image` 上加 `clip`+`border` 并新增 `shape` 参数）。**不要**在 `:1428` 链首套 `Modifier.clip`。另需决策：mini bar（同一节点，56dp）是否要独立描边 —— 若要与大图一致，层内写法在 mini 态会缩到亚像素，需另画固定尺寸 overlay |
| `ui/components/DetailScaffold.kt:266` | `DetailHeader`（窄屏） | `fillMaxWidth().aspectRatio(1f)`（整屏宽） | `AppShapes.large` + 1dp（图片自身 modifier 加 `clip`+`border`）。⚠️ 判断项：规则集只写了「播放页大封面」，详情页头图是否同规格**需产品确认**；未确认则此两行按 16dp 提案 |
| `ui/components/DetailScaffold.kt:240` | `DetailHeader`（宽屏 ≥600dp） | `Modifier.size(220.dp)` | `AppShapes.large` + 1dp（同上一行的判断项） |

### 8.2 列表 / 菜单 / 网格小封面 —— `AppShapes.small`(8dp) + 1dp（18 处）

> ⚠️ 本节 18 个点的圆角归属存在口径歧义（8dp「列表项」vs 12dp「封面缩略图容器」，见 §7.1）。下表按**任务书口径 8dp / `AppShapes.small`** 书写；若最终裁定「封面缩略图」走 12dp，则本表 `AppShapes.small` → `AppShapes.medium` 全局替换，网格类（#2/#10/#11/#16/#17/#19/#20）优先适用 12dp。

| file:line | composable | 当前尺寸 | 建议处理 |
|---|---|---|---|
| `ui/components/SongCard.kt:103` | `SongCard`(LIST) | 72dp（默认）/ 56dp（`SearchScreen.kt:756`） | `AppShapes.small` + 1dp；尺寸三处不同但同一行，建议按实际 `actualCoverSize` 统一 8dp |
| `ui/components/SongCard.kt:198` | `SongCard`(GRID) | 格宽正方形（常 ≥160dp） | `AppShapes.small` + 1dp；⚠️ 判断项：格宽已接近大图，是否升到 16dp 需确认。另注意父 `Column` 有 `graphicsLayer` 缩放（`:192-196`），`clip` 必须与缩放同层或更内层 |
| `ui/components/ArtistSearchItem.kt:52` | `ArtistSearchItem` | 72dp | `AppShapes.small` + 1dp；注意 `background` 现在直接挂在图片 modifier 上（`:57`），需决定描边是否盖住占位底色 |
| `ui/components/SongMenuSheet.kt:49` | `SongMenuSheet` | 112dp | `AppShapes.small` + 1dp；**必须同步改 `:48` 注释「直角封面」** |
| `ui/components/AddToPlaylistSheet.kt:187` | `AddToPlaylistSheet` | 48dp | `AppShapes.small` + 1dp |
| `ui/components/ArtistRecoCard.kt:68` | `ArtistRecoCard` | 72dp | `AppShapes.small` + 1dp；**同步改 `:39` 注释「直角、无圆角」** |
| `ui/screen/AlbumSearchItem.kt:52` | `AlbumSearchItem` | 72dp | `AppShapes.small` + 1dp |
| `ui/screen/HomeScreen.kt:531` | `DailySongTile` | 160dp 宽正方形 | `AppShapes.small` + 1dp |
| `ui/screen/HomeScreen.kt:570` | `SongGridTile` | 自适应格宽正方形 | `AppShapes.small` + 1dp |
| `ui/screen/HomeScreen.kt:601` | `PlaylistTile` | 160dp 正方形（父 `Box`） | 圆角（`AppShapes.small`）加在图片上；**overlay `PlayAllButton`（`:607-611`，圆形 34dp，`padding(6.dp)`）与 8dp 圆角在右下角有轻微几何重叠，需目视确认**（按钮是圆的，视觉冲突概率低） |
| `ui/screen/OfflineCacheOverlay.kt:335` | `OfflineTrackRow` | 48dp | `AppShapes.small` + 1dp；容器 `Box` 有 `coverPlaceholder` 底色（`:332`），需确认缺图时是否也要圆角底块（否则「有图圆角、无图直角」不一致）。**必须同步改 `:327` 的「Kanesumi 铁律：图片不裁圆角」注释** |
| `ui/screen/SearchScreen.kt:624` | `ToplistRow` | 48dp | `AppShapes.small` + 1dp |
| `ui/screen/SearchScreen.kt:700` | `SearchHistoryItemCard` | 64dp | `AppShapes.small` + 1dp |
| `ui/screen/LibraryScreen.kt:444` | `PlaylistGridItem` | 格宽正方形 | 圆角（`AppShapes.small`）加在图片上；`PlayAllButton` 圆形叠加层（`:450-453`）同 `HomeScreen.kt:601` 行的说明 |
| `ui/screen/LibraryScreen.kt:472` | `LibraryAlbumGridItem` | 格宽正方形 | 同上（overlay `:478-481`）；**同步改 `:396` 注释「直角、无圆角」** |
| `ui/screen/LibraryPlaylistsTab.kt:351` | `QqPlaylistInlineRow` | 48dp | `AppShapes.small` + 1dp；容器 `Box` 有 `background(surfaceVariant)`（`:347`）。**必须同步删改 `:476-478` 的 `squareShape` + 注释**（该常量零引用，可直接删） |
| `ui/screen/ArtistDetailScreen.kt:364` | `AggregatedAlbumGridItem` | 格宽正方形 | `AppShapes.small` + 1dp（整条 `AsyncImage` 调用在 `:364-369`） |
| `ui/screen/ArtistDetailScreen.kt:416` | `ArtistAlbumGridItem` | 格宽正方形 | `AppShapes.small` + 1dp；该行是**单行超长声明**（`:416`），改造时需拆行 |

### 8.3 通知栏封面 —— 保持不动（无改造点）

| file:line | 现状 | 处理 |
|---|---|---|
| `player/PlaybackService.kt:1647` | `builder.setLargeIcon(currentArtworkBitmap)`（位图长边 ≤ 512px，`:1041-1048`） | **不改**。应用侧不做圆角；系统通知栏 chrome 自己负责外观 |
| `player/PlaybackService.kt:1405-1409` | `setArtworkData(JPEG bytes, PICTURE_TYPE_FRONT_COVER)` | **不改** |
| `player/PlaybackService.kt:823` / `:968` / `:1016` | `setArtworkUri(CoverUrls.large(...))` | **不改** |
| `player/PlaybackService.kt:1643-1644` | `.setColor(currentDominantColor)` + `.setColorized(true)` | **不改**。着色是系统 chrome 的一部分，本轮未找到需要改动的证据 |

### 8.4 不在改造范围（明确排除）

| 项 | 理由 |
|---|---|
| `ui/screen/UserScreen.kt:843` 头像 | 不是封面；且现状为直角（改造头像会改变设置页主视觉，需单独决策） |
| `ui/theme/MarkdownText.kt:54` | Markdown 正文插图，`ContentScale.Fit`，非封面 |
| `ui/CustomBackgroundLayer.kt:72` | 用户自定义背景图（§5.2） |
| `ui/components/QrLoginDialog.kt:154`、`ui/components/QqQrLoginDialog.kt:192` | 登录二维码，裁圆角会影响扫码识别 |
| `ui/screen/AboutScreen.kt:81` | 应用图标（`R.drawable.ic_launcher`） |
| `ui/player/WaveformRing.kt` / `AudioVisualizer.kt` | PCM 波形，非封面派生（§5.3） |
| `ui/components/SongCard.kt:284` `PlayAllButton` | 唯一既有 `clip(CircleShape)`，圆形按钮，**勿改为圆角矩形** |

### 8.5 正典（Kanesumi Design）冲突清单 —— 必须同步处理

改造「封面不裁圆角」等于改设计正典，下列源码注释会在改造后**自相矛盾**，需在同一逻辑提交内更新：

| file:line | 现注释 |
|---|---|
| `ui/screen/OfflineCacheOverlay.kt:327` | 「封面色块：有图就铺满（**Kanesumi 铁律：图片不裁圆角**）」 |
| `ui/screen/LibraryPlaylistsTab.kt:476` | 「未使用但保留的直角裁切常量（Kanesumi Design：**本页所有封面都是直角**）」 |
| `ui/components/ArtistRecoCard.kt:39` | 「样式沿用首页既有信息卡：**直角、无圆角**、封面 72dp」 |
| `ui/components/SongMenuSheet.kt:48` | 「**直角封面**，贴屏左边缘」 |
| `ui/screen/LibraryScreen.kt:396` | 「与歌单格子同尺寸，**直角、无圆角**」 |
| `ui/components/DetailScaffold.kt:189` | 「直角闪切」（指 scrim 动效，非封面；确认无需改） |
| `ui/theme/NcrustColors.kt:19` | 「两套都只有颜色差，**不产生任何阴影 / 圆角** —— 直角、无 elevation 的视觉识别不变」（描述颜色桥接，非封面；确认无需改） |

另：仓库外正典 `KANESUMI_DESIGN.md`（Ether monorepo 根）声明「right angles, no rounded corners, no borders」。**该文件不在本仓库、本次未读取，其需改条款未确认。**

---

## 9. 未确认项（需要什么才能确定）

| 项 | 状态 | 需要什么 |
|---|---|---|
| 1dp 描边在深/浅两套主题下的实际可见度与对比度 | **未确认** | 真机截图（浅色 `#F6F2E9` 底 / 深色 OLED `#000000` 底各一张）。`LocalMetroColors.current.divider` 的实际色值需读 Kanesumi 库源码或真机取色；本仓库内无该常量定义 |
| 16dp 圆角在 mini bar（56dp，`miniScale` 缩放）下的观感是否可接受 | **未确认**（算术可推：窄屏 1080px 宽时约 0.83dp，视觉等同直角；但「可接受性」是设计判断） | 真机截图对比；`coverSizePx` 真机实测值（`wideCoverSizePx` 由 `onGloballyPositioned` 运行时得到，随设备变化） |
| SystemUI 是否对 `setLargeIcon` 位图自行加圆角 | **未确认** | 真机通知栏截图 + `dumpsys notification --noredact`（本仓库既有此取证先例，见 `PlaybackService.kt:1673-1677` 注释） |
| `PlayAllButton`（圆形，`padding(6.dp)`，34dp/36dp）与 8dp 圆角在右下角的几何重叠是否造成视觉瑕疵 | **未确认** | 真机截图（`HomeScreen.kt:607-611`、`LibraryScreen.kt:450-453`、`LibraryScreen.kt:478-481`） |
| 详情页头图（`DetailScaffold.kt:240`/`:266`）是否归入「播放页大封面 = 16dp」 | **未确认** | 产品规则确认（规则集原文只写了「播放页大封面」） |
| `SongCard` GRID 分支（`:198`）格宽跨过 8dp/16dp 的分界线 | **未确认** | 设计规则确认（需给出「大封面」的尺寸阈值；窄屏 `<600dp` 时 `ResponsiveContent` 把内容限宽 360dp，网格列数决定实际格宽） |
| 仓库外正典 `KANESUMI_DESIGN.md` 的具体条款与修改流程 | **未确认** | 读取 Ether monorepo 根目录该文件（不在本仓库内） |
| 是否存在 Compose 无关的第二渲染路径（如 RemoteViews 自定义通知布局） | 已排查：**不存在** | 证据：`grep -rn "RemoteViews\|setCustomContentView"` 无命中（本项未单独存档，若需可补跑） |
| **列表/菜单小封面取 8dp 还是 12dp** | **未确认（口径冲突）** | 任务书给本次的规则是「列表/菜单小封面 = 8dp」；并发新增的 `ui/theme/AppShapes.kt:70-82` 把 8dp 定义为「**列表项**」、12dp 定义为「卡片 / **封面缩略图容器**」。§8.2 的 18 个点里 12 个是列表行内 48~112dp 封面、7 个是网格封面，两种口径给出不同取值。需产品裁决 |
| **`AppShapesSingleSourceTest` 与既有 `CircleShape` 的冲突如何收场** | **未确认（规划中的测试会红）** | 该测试按 `AppShapes.kt:30-33` 的描述会禁止 `AppShapes.kt` 之外出现 `CircleShape`，而现网已有 `ui/components/SongCard.kt:8` + `:284`（`PlayAllButton`）。二选一：把 `PlayAllButton` 改用 `AppShapes.pill`（视觉等价，均为 50% 圆角）、或在测试里豁免该文件。**该测试文件本次探测时尚未创建（已用 `find` 确认）** |
| `AppShapes.kt` / `AppMotion.kt` 的最终内容 | **未确认（仍在变动）** | 两文件在本次探测期间（19:23:38 / 19:23:54）由并发工作线创建，`git status` 仍为 untracked。引用其行号前请重新读取 |

---

## 附：本次使用的只读命令清单

全部输出见 `docs/verification/v2.5.0/probe-raw/cover-greps.txt`：

1. `grep -rn "AsyncImage\|rememberAsyncImagePainter\|painterResource\|Image(" app/src/main/java --include=*.kt`
2. `grep -rn "\.clip(\|\.border(\|Modifier.shadow\|RoundedCornerShape" app/src/main/java --include=*.kt`
3. `grep -rn "CornerSize\|cornerRadius\|radius\|shape =\|Shape" app/src/main/java --include=*.kt`
4. `grep -rn "CoverUrls\." app/src/main/java --include=*.kt`
5. `grep -rn "albumPicUrl\|artworkData\|currentArtworkBitmap\|loadArtwork\|setLargeIcon" app/src/main/java --include=*.kt`
6. `grep -rn "直角\|圆角\|铁律" app/src/main/java --include=*.kt`
7. `grep -rni "rounded\|roundrect\|drawround\|CIRCLE\|clipPath\|Shape\b" app/src/main/java --include=*.kt`
8. `grep -rn "BitmapPainter\|drawImage\|asImageBitmap\|ImageBitmap" app/src/main/java --include=*.kt`
9. `grep -rn "ImageRequest.Builder\|imageLoader(\|Coil.imageLoader\|\.load(" app/src/main/java --include=*.kt`
10. `grep -rn "WaveformRing" app/src/main/java --include=*.kt`
11. `grep -n "barColor" ui/player/AudioVisualizer.kt ui/player/PlayerCard.kt`
12. 计数：`border(` 11 / `clip(` 1 / `Modifier.shadow` 0 / `RoundedCornerShape` 0 / `elevation` 1 / material3 声明 0 / `androidx.compose.material3` import 0 / `foundation.shape` import 1
13. `ls ui/player/`
14. `grep -rn "import androidx.compose.foundation.border" app/src/main/java --include=*.kt`（5 个文件）
15. `grep -rn "import androidx.compose.foundation.shape" app/src/main/java --include=*.kt`（1 行：`CircleShape`）

补充命令（**在 `cover-greps.txt` 生成之后才执行**，故未存档在该文件里；`AppShapes.kt`/`AppMotion.kt` 是并发新增文件，第 2~3、12、15 组 grep 的输出**早于它们的创建时刻 19:23:38**，因此那些组里看不到这两个文件）：

16. `grep -rn "RemoteViews\|setCustomContentView\|setCustomBigContentView\|setContentView" app/src/main/java --include=*.kt` → **0 命中**
17. `git status --porcelain` → `?? ui/theme/AppMotion.kt`、`?? ui/theme/AppShapes.kt`、`?? docs/verification/v2.5.0/`
18. `grep -n "RoundedCornerShape\|clip(\|border(\|CornerSize\|dp" ui/theme/AppShapes.kt` → §7.1 的 token 表
19. `find app/src -name "AppShapesSingleSourceTest*"` → **无结果（尚未创建）**
20. `ls -la --time-style=full-iso ui/theme/AppShapes.kt ui/theme/AppMotion.kt` → `2026-09-25 19:23:38` / `19:23:54`
21. `ls -la docs/verification/v2.5.0/*.md` → `probe-cover.md`（本文）、`probe-motion.md`、`probe-theme.md`（后两者由并发工作线所写，非本文作者）

---

**未修改任何源码文件；本次只新增 `probe-cover.md` 与 `probe-raw/cover-greps.txt`。** `ui/theme/AppShapes.kt` 与 `ui/theme/AppMotion.kt` 是**其他工作线**在本探测期间创建的，本文只读取、未改动。
