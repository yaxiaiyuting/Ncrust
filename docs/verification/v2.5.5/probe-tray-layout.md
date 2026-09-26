# probe-tray-layout —— 竖屏播放托盘「三层布局」探针

> 被测产物：v2.5.4-gpl release（`versionCode = 45`）在 PLC110（Android 16 / 363×800 dp）上的**实际渲染**
> 目标布局（任务书 §5.1）：
> ```
> 实时歌词              ← 第一行
> 歌名                  ← 第二行，独占
> 作者        音源      ← 第三行，左右对齐
> ```
> 探针人：本 session（自动）。所有几何数字都来自真机截图的逐行像素剖面（方法见 `probe-tray-regression.md` §1）。

---

## §0 结论先行

| 问题 | 结论 |
|---|---|
| 当前 `PlayerCard` 的托盘 Box 结构 | `Box(fillMaxWidth → statusBarsPadding → height(56dp) → clickable(展开) → graphicsLayer{alpha} → background(surface))` 内套 `Row(fillMaxSize, CenterVertically)` |
| 高度约束 | **硬编码 56dp**，`Row` 垂直居中，无 `weight`、无 `heightIn`、无滚动 |
| 两层 → 三层的可行性 | **可行，但必须加高到 80dp**（量化见 `probe-tray-regression.md` §3：三行排版盒 182px vs 56dp 的 196px，只剩 2dp 每侧） |
| 歌词过长 | 现状 `maxLines = 1` + `TextOverflow.Ellipsis`（`TrayLyricLine`），**截断** |
| 歌名过长 | 现状 `maxLines = 1` + `Ellipsis` + `weight(1f, fill = false)`，**截断** |
| 跑马灯 | **不做**（与铁律 17 冲突：跑马灯会持续排帧；见 §4） |
| 作者 + 音源的间距与对齐 | 现状是**同一段** `ArtistLineWithSource`（`·` 分隔、`alignByBaseline`）；三层要求「左对齐 + 右对齐」，需要拆成两段 + `weight` 撑开 |
| 无歌词时降级 | 现状 `line ?: " "`（**一个空格**，不是空串）—— 真机实测空串量出来 0 高、托盘会塌成一行再跳回两行。保留该降级 |
| 点各区域的现状 | 点歌词 = 展开 + 进歌词（v2.5.4）；点作者 = 艺人页（v2.5.4）；**点歌名 / 点音源 = 无独立点击区**（落在整条托盘的展开点击上） |
| 点音源应该做什么 | **不做独立动作**（§6 给出判据） |
| 三种形态 | 竖屏手机 / 平板竖屏：同一份布局（`isWidePlayer` 不影响托盘）；**横屏**：托盘同样是这一份（`usesSideCover` 只改大封面落点，不改托盘） |

---

## §1 当前 Box 结构（v2.5.4 逐行）

`app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt:1362-1534`

```
Box(                                     // 迷你播放栏叠加层（永远挂载）
  fillMaxWidth()
  .statusBarsPadding()                   // 顶部让开状态栏
  .height(56.dp)                         // ← 唯一的高度约束，硬编码
  .then(if (miniBarEnabled && hasSong) clickable{ progress.animateTo(1f) } else Modifier)
  .graphicsLayer { alpha = (1 - progress*5).coerceIn(0,1) }   // 展开态视觉淡出（仍可命中）
  .background(surface)
) {
  Row(fillMaxSize(), verticalAlignment = CenterVertically) {
    if (hasSong) {
      Spacer(fillMaxHeight().aspectRatio(1f))      // 56×56dp 封面占位（唯一封面 overlay 落点）
      Column(weight(1f).padding(horizontal = 12.dp)) {
        TrayLyricLine(lyricsFlow, positionFlow, …)  // 第 1 行：歌词（自带 clickable → 展开+歌词）
        Row {                                       // 第 2 行：歌名 · 作者 · 音源
          MetroText(name, bodyMedium, weight(1f, fill=false))   // 歌名（无独立 clickable）
          Spacer(6.dp)
          MetroText(artist, bodySmall, weight(1f, fill=false).clickable{ 艺人页 })  // 作者
          ArtistLineWithSource(showArtist = false)              // 音源角标（无 clickable）
        }
      }
      if (miniBarEnabled) {                          // 收起态才挂载（无障碍 + 命中区）
        MetroIconButton(onPlayPause) { Pause/PlayArrow }   // 48dp
        MetroIconButton(onPlayNext)  { SkipNext }          // 48dp
      } else Spacer(width = 96.dp)
    } else {
      MetroText(strings.noSongPlaying, weight(1f))
      if (miniBarEnabled) { MetroIconButton(onPlayNothing){PlayArrow}; Spacer(12.dp) }
    }
  }
}
```

**没有** `heightIn`、**没有** `verticalScroll`、**没有** `maxLines` 的总量控制 ——
高度是硬的，超出的部分会被 `Column` 的父约束裁掉（`Column` 无 `weight`，内容超出即溢出）。

---

## §2 三层布局的高度预算（真机数字）

| 行 | 样式 | 行高 | px @560dpi | 来源 |
|---|---|---|---|---|
| 1 歌词 | `LocalMetroTypography.bodySmall`（12sp） | 16sp | 56 | 实测字形 2458–2495 |
| 2 歌名 | `bodyMedium`（14sp） | 20sp | 70 | 实测字形 2508–2551（与第 3 行同块） |
| 3 作者 + 音源 | `bodySmall`（12sp） | 16sp | 56 | 同上 |
| 行间距 ×2（本版新增 2dp） | — | 2dp | 7 | `TrayLayout.LINE_GAP_DP` |
| **内容合计** | | **56dp** | **196** | |

56dp 托盘**刚好等于内容**（0 留白，视觉贴死）⇒ 必须加高。
选定 **80dp**（`TrayLayout.HEIGHT_DP`），留白 24dp（每侧 12dp）。
推导与 72dp 的对比见 `probe-tray-regression.md` §3。

---

## §3 溢出处理（现状 vs 本版）

| 元素 | 过长时 | 依据 |
|---|---|---|
| 歌词行 | `maxLines = 1` + `TextOverflow.Ellipsis` | 已有（v2.5.4 `TrayLyricLine`）。歌词是**整句**，截断比滚动合理：用户要的是「现在是哪一句」，不是「完整读完这句」——完整阅读是歌词面板的职责 |
| 歌名 | `maxLines = 1` + `Ellipsis` | 已有。三层里歌名**独占一行**，所以不再与作者/音源抢宽度；`weight(1f, fill = false)` 改成 `fillMaxWidth()` |
| 作者 | `maxLines = 1` + `Ellipsis` + `weight(1f)` | 改：从 `weight(1f, fill=false)`（按内容收窄）改成 `weight(1f)`（吃掉中间所有剩余空间），这样音源才能真正右对齐 |
| 音源角标 | **永不省略**（定宽） | 保留 v2.1.0 的约定：角标被挤掉就丢掉了它存在的意义 |
| 跑马灯 | **不做** | 见 §4 |

### §3.1 为什么不做跑马灯（量化）

`Modifier.basicMarquee()` 会**持续排帧**（每帧一次 offset 平移），只要文本超宽就一直跑。
本应用的一条硬约束（AGENTS.md v2.5.0 规则 1 / 铁律 17）是「UI 动效不得影响播放性能」，
而托盘**在播放全程常驻**——它跑跑马灯意味着播放期间永远有一个每帧重绘的节点。
收益是「一行 12sp 的小字能读完」，代价是播放期间 60fps 的额外绘制。
**明确不做**，并在 `TrayLayout` 的 KDoc 里写死原因，防止下一个人「顺手加上」。

---

## §4 三种目标形态

托盘是**同一份代码**，`isWidePlayer` / `bigScreenActive` 都不参与它的布局
（它们只改大封面的落点与是否两栏）。因此：

| 形态 | 屏宽 | 托盘可用宽 | 文本列（三个 48dp 按钮 + 56dp 封面 + 24dp padding） | 预期 |
|---|---|---|---|---|
| 手机竖屏（S6 / 360dp） | 360dp | 360dp | 360−56−144−24 = **136dp** | 歌名大概率被省略到 4~5 个汉字；作者被省略；角标完整 |
| 手机竖屏（PLC110 / 363dp） | 363dp | 363dp | **139dp** | 同上 |
| 手机横屏（PCL110 / 800dp） | 800dp | 800dp | **576dp** | 三行都完整 |
| 平板竖屏（WGR-W09 / 800dp） | 800dp | 800dp | **576dp** | 三行都完整（但底部导航在宽屏下换成 sidebar，托盘仍贴底） |
| 平板横屏（1280dp） | 1280dp | 1280dp | **1056dp** | 三行都完整 |

**136dp 是窄屏的下界**，这一条决定了 §5.3 的「不加更多按钮」的取舍。

---

## §5 「垂直间距统一走 AppShapes / AppMotion」——**任务书前提修正**

任务书 §5.2 要求「三层垂直间距统一走 AppShapes / AppMotion 规范」。实测：

| 文件 | 实际内容 | 能否承载间距 |
|---|---|---|
| `ui/theme/AppShapes.kt` | **圆角** token（`extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28 / full 50%`） | ❌ |
| `ui/theme/AppMotion.kt` | **动效** token（六弹簧 + tween 词汇 + `PAGE_TRANSITION_MS`） | ❌ |
| 全仓库 | **没有** `AppSpacing` / `AppDimens` 一类的间距 token 文件（`grep -rn "object App" app/src/main/java/com/takahashirinta/ncrust/ui/theme/` 只有 `AppShapes` / `AppMotion`） | — |

**处置**：不为「托盘三行之间那 2dp」新建一套全局 spacing 体系。
判据是 v2.5.2 规则 2 的**反向用法**：那条规则说「一个渲染常量出现第二个消费者时必须抽成唯一落点」，
而托盘的行间距**只有一个消费者**（`PlayerCard` 的这一个 `Column`）。为一个消费者造全局 token
只会得到一份没人用的常量表。间距改为写进 `TrayLayout.LINE_GAP_DP`（与高度、封面尺寸同处一个
唯一落点文件），并在 KDoc 里给出上面这张表，让下一个人知道 `AppShapes` / `AppMotion` 不装间距。

**这是与任务书的一处显式偏差，如实记录。**

---

## §6 点击区域（现状 + 本版设计）

| 区域 | 现状（v2.5.4） | 本版 | 理由 |
|---|---|---|---|
| 点歌词 | 展开 + `showLyrics = true` | **不变** | v2.5.4 已实现且有真机截图证据 |
| 点歌名 | 无独立 `clickable`，落到整条托盘的「展开」 | **不变**（不加独立 `clickable`） | 行为与整条托盘**完全相同**。再包一层同名 `clickable` 只是多一个命中层——AGENTS.md 触摸陷阱 §5/§6 明确说这是最难查的一类问题 |
| 点作者 | 进艺人页（有 `artist.id > 0` 时），否则回落「转到歌手/专辑」菜单 | **不变** | v2.5.4 已实现 |
| 点音源 | 无 `clickable` | **不加**（见下） | |
| 三个控制按钮 | 播放/暂停、下一首 | **补上一首** | 铁律 19 |

### §6.1 点音源为什么**不做**独立动作

三个候选动作，逐个否决，理由都是**结构性的**（不是「没时间」）：

1. **「切到另一个音源播同一首」** —— v2.3.0 的探针已经判决过：接口里**没有任何跨源标识**
   （无 ISRC、无指纹），程序上配不成对。v2.4.0 的跨源匹配要**结构判据**（专辑重合 / 曲名集合 / 时长）
   且必须先搜一次；在托盘的一个角标上挂「异步搜索 + 相似度判定 + 失败降级」，
   等于把一条网络链路塞进一个 12sp 的标签里，违背铁律 4（非核心组件不得破坏核心播放链路）。
2. **「显示音源详情 / 版权信息」** —— 现状已经有 `onSongInfoClick`（F 键那套「转到歌手/转到专辑」），
   再给角标一个「信息」语义会让同一个托盘出现两个「看信息」的入口，用户分不清。
3. **「切换默认音源」** —— 这是**设置项**（`source/` 的路由偏好），属于设置页的职责。
   放在托盘上等于让一个只读角标承担全局状态写入。

**结论**：音源角标保持**纯展示**（与列表行的 `SongCard.sourceBadge` 一致），
这一条写进 `TrayLayout` 的 KDoc + `TrayLayoutTest` 里的一条「音源角标不参与点击」断言
（断言它的枚举里没有可点动作，防止下一个人顺手加）。

---

## §7 无歌词时的降级

v2.5.4 的实现：`TrayLyric.lineAt(...)` 返回 `null` ⇒ `MetroText(text = line ?: " ")`。

**保留「一个空格」而不是空串**：真机实测（S6 / Android 7.0）空串的 `MetroText` 量出来 **0 高**，
托盘会塌成一行、歌词就绪的那一刻再跳成两行。三行布局下同样成立（塌成两行再跳三行，更明显）。
这一条**代码里已有注释**，本版一字不改，只在 `TrayLayoutTest` 里加一条断言把「占位符非空」钉住。

四种「没有歌词」的来源都要走同一条降级（`TrayLyric.lineAt` 已覆盖）：
① 歌词还在加载；② 该曲确实没有歌词；③ 位置早于第一行（前奏）；④ 当前行是纯空白行。

---

## §8 未验证 / 交给真机

| 项 | 状态 |
|---|---|
| 三层布局在 PLC110 竖屏的实际渲染 | 本版实测（`verification/`） |
| 平板竖屏的实际渲染 | 本版实测（平板 AVD；WGR-W09 未接入本机） |
| Android 7.0（API 24）真实触摸 | **阻塞**（S6 PIN 锁屏）⇒ 用 API 24 AVD 顶替，差距如实记录 |
| 三行在 136dp 文本列下的实际省略位置 | 本版实测（会用一个长歌名种子） |
