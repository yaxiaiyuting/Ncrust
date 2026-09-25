# 探针 · 主题系统（v2.5.0）

> 目标：回答任务书 §2.2 的三问 —— ① Theme 定义在哪里、用了 Material 3 哪些组件；
> ② 有没有动态取色（Material You）、接入成本多大；③ 当前圆角规范是什么、是否统一。
>
> 方法：只读源码 + `grep` 实测计数，不采信任务书里的初步判断。所有结论给 `file:line`。

## 结论（先行）

| 问题 | 实测答案 |
|---|---|
| Theme 在哪 | `ui/theme/ThemeManager.kt:80` `NcrustTheme(primaryColor, isDark)` |
| 用了哪些 M3 组件 | **一个都没有**。全仓库 `material3` 依赖为空、`material3` import 为 0 处 |
| 有没有动态取色 | **有**，且是**三档**（预设 / 封面 / 系统），v1.2.0 · B2-C 就已落地 |
| 动态取色的算法 | `androidx.palette` 的 `Palette`（`vibrantSwatch` → `dominantSwatch` → `mutedSwatch`），**不是** `QuantizerCelebi` + HCT |
| 当前圆角规范 | **「无圆角」就是规范** —— `RoundedCornerShape` 全仓库 **0** 命中 |
| 圆角是否统一 | 统一（统一地没有）。`clip(`/`border(`/`shadow`/`CircleShape`/`CornerSize` 合计仅 **13** 处 |

> ⚠️ **与任务书的头号冲突**：任务书 §3.1 给的是 `androidx.compose.material3.Shapes`，
> 而本工程**没有 material3 依赖**；且设计正典（`AGENTS.md` §Terminology / Kanesumi `AGENTS.md` 铁律）
> 明确写着「无圆角、无阴影、无边框，直角切一切；Cover/图片铺满不裁圆角」。
> 本版按用户裁定：**只在 Ncrust app 侧建立并应用圆角规范，不改 Kanesumi 库**。

## P1. 当前 Theme 定义

### 1.1 三件套

| 组件 | 文件 | 说明 |
|---|---|---|
| 配色板 | `ui/theme/NcrustColors.kt:22` | `@Immutable data class NcrustColors`，14 个色槽 |
| 默认值 | `ui/theme/NcrustColors.kt:56` / `:81` | `DefaultNcrustColors`（OLED 黑）/ `LightNcrustColors`（米色纸底） |
| CompositionLocal | `ui/theme/NcrustColors.kt:97` | `LocalNcrustColors = compositionLocalOf { DefaultNcrustColors }` |
| 主题入口 | `ui/theme/ThemeManager.kt:80` | `NcrustTheme(primaryColor, isDark) { … }` |
| 字体 | `ui/theme/NcrustTypography.kt` | `LocalNcrustTypography` |
| 桥接 | `ui/theme/NcrustColors.kt:113` | `NcrustColors.toMetroColors(isDark): MetroColors` |

色槽（`NcrustColors.kt:22-45`）：`primary` / `onPrimary` / `background` / `surface` / `surfaceVariant` /
`onBackground` / `onSurface` / `onSurfaceVariant` / `surfaceContainerLowest` / `surfaceContainerLow` /
`surfaceContainerHigh` / `surfaceContainerHighest` / `outline` / `outlineVariant`。

> `surfaceContainer*` 是**借 M3 的语义命名**，注释（`NcrustColors.kt:14-19`）写明
> 「只作取值参照系，不引入 material3 依赖」。

### 1.2 Material 3 组件使用情况：**零**

```
$ grep -rn "material3" app/src/main/java app/build.gradle.kts
app/src/main/java/.../ui/theme/NcrustColors.kt:15: * material3 依赖），取值是本设计系统自己的低亮度阶梯。…
```

唯一命中是一句**注释**。`app/build.gradle.kts` 里 Compose 依赖只有：

- `androidx.compose.ui:{ui, ui-graphics, ui-tooling-preview}`
- `androidx.compose.material:material-icons-extended`（**只有图标资产**）
- `androidx.activity:activity-compose`
- `androidx.lifecycle:lifecycle-viewmodel-compose`

UI 组件来自外部 **Kanesumi** 库（组合构建 `includeBuild("../Kanesumi-sec-a")`），
应用实际用到的 Kanesumi 组件（`grep -rhn "import io.github.takahashirinta.kanesumi"` 去重）：

```
anim.sokuou.{MetroDefault, metroViewConfiguration, rememberMetroFlingBehavior,
             SokuouPresets, sokuouSpring, SokuouTweens}
controls.{MetroBottomSheet, MetroButton, MetroDialog, MetroDivider, MetroDropdownMenu,
          MetroDropdownMenuItem, MetroIconButton, MetroProgressIndicator, MetroSelectorFlyout,
          MetroSwitch, MetroTabItem, MetroTabRow, MetroTextField}
core.insets.metroNavigationBarsPadding
core.theme.{LocalMetroColors, LocalMetroTypography, MetroColors, MetroIcon, MetroText,
            MetroTheme, MetroTypography}
structure.{MetroTopScrim}
structure.bottomnav.{MetroBottomNav, MetroBottomNavItem}
structure.sidebar.{MetroSidebar, MetroSidebarItem}
```

**这条是特性 A/B/C 的施工边界**：上表里 `controls.*` 与 `structure.*` 的画法（直角、无边框）
在 **Kanesumi 仓库**里，本版**不改**；app 侧只能通过调用点的 `Modifier.clip(...)` 影响。

## P2. 动态取色：**已经有了**，且比任务书假设的更完整

### 2.1 三档来源

`ui/theme/AccentSource.kt:24`：

```kotlin
enum class AccentSource { PRESET, COVER, SYSTEM }
```

| 档 | 取值路径 | 落盘 key |
|---|---|---|
| `PRESET` | `themeColorForIndex(index)`（6 个预设，`ThemeManager.kt:25`） | `theme_color_index` |
| `COVER` | `playerViewModel.coverAccentRgb` → `processAccentColor(rgb, isDark)` | `accent_source` |
| `SYSTEM` | `android.R.color.system_accent1_200`（**API 31+**，低版本返回 null 回落预设） | `accent_source` |

装配点在 `MainActivity.kt:277-286`：

```kotlin
val accentColor = remember(accentSource, themeIndex, coverAccentRgb, isDark, systemAccent) {
    when (accentSource) {
        AccentSource.COVER  -> coverAccentRgb?.let { processAccentColor(it, isDark) } ?: fallback
        AccentSource.SYSTEM -> systemAccent?.let { processAccentColor(it.toArgb(), isDark) } ?: fallback
        AccentSource.PRESET -> themeColorForIndex(themeIndex)
    }
}
NcrustTheme(primaryColor = accentColor, isDark = isDark) { … }   // MainActivity.kt:297
```

`remember` 的 key **刻意含 `isDark`**（`MainActivity.kt:276` 注释）：深浅色的亮度锚定区间不同，
同一张封面必须重算。这一条已经是「平台假设 A/B 对照」的正确形状。

### 2.2 封面取色的实现（`player/PlaybackService.kt:1080-1113`）

```
封面 URL → loadArtwork → createScaledBitmap(112×112) → Palette.from(bitmap).generate()
        → vibrantSwatch?.rgb ?: dominantSwatch?.rgb ?: mutedSwatch?.rgb
        → onCoverAccent(rgb)（回调）→ PlayerViewModel.coverAccentRgb（MutableStateFlow）
```

已经满足任务书对取色的**四条工程要求**中的三条：

| 任务书要求 | 现状 | 证据 |
|---|---|---|
| 后台线程执行，不阻塞 UI | ✅ `scope.launch(Dispatchers.Default)` | `PlaybackService.kt:1088` |
| 取色失败回退默认主题，不崩溃 | ✅ `try/catch` → `null` → 调用方 `?: fallback` | `PlaybackService.kt:1090-1098`、`MainActivity.kt:279` |
| 结果缓存，同一首不重复计算 | ✅ 按 **URL** 去重（`if (url == paletteUrl)`） | `PlaybackService.kt:1081-1085` |
| 灰度封面回退 | ✅ `processAccentColor` 饱和度 `< 0.08` 返回 null | `AccentSource.kt:126,135` |

**并发保护**：`artworkGeneration` 世代号（`PlaybackService.kt:1096`）—— 取色期间切歌则结果作废，
不会把上一首的颜色推给新歌。这条与 v2.3.0 的「世代号」教训同源。

**颜色治理**（`ui/theme/AccentSource.kt`）：

- `processAccentColor(argb, isDark)`：饱和度上限 `0.6`（`:119`）、亮度锚定
  深色 `0.4–0.6` / 浅色 `0.4–0.55`（`:122-123`）、灰度阈值 `0.08`（`:126`）；
- `onAccentColor(accent)`：黑白取对比度高者（`:149`），保证 WCAG AA；
- `contrastRatio(fg, bg)`：`AccentSource.kt:109`，阈值 `MIN_ACCENT_CONTRAST = 4.5`（`:116`）；
- `rgbToHsv` / `hsvToArgb`：**纯 Kotlin**，注释（`:58-60`）写明「颜色数学不依赖 `android.graphics`，
  才能在 JVM 单测里真跑（framework 方法在单测里是未实现的 stub）」。

> 这条约定是本版色调工作的**复用资产**：新的 HCT/量化实现同样必须纯 Kotlin，才能被 JVM 单测覆盖。

### 2.3 与任务书要求的差距（这才是本版色调的真正增量）

| 任务书要求 | 现状 | 差距 |
|---|---|---|
| 降采样 **50×50** | **112×112** | 采样边长不同（112 是为通知栏着色服务的，与小图解码成本正相关） |
| 量化 **QuantizerCelebi，目标 128 色** | `androidx.palette` 的 `ColorCutQuantizer`（默认 16 色） | **算法不同** |
| 基于 **HCT** 生成 primary/secondary/tertiary/neutral 调色板 | 只取**一个** accent 色 + 饱和度/亮度钳制 | **只产出一个角色**，没有多角色调色板 |
| 播放页**背景**跟随封面 | accent 色跟随；背景色未跟随 | **缺背景派生** |

结论：**「有没有动态取色」这个问题的答案是「有」**；本版要补的是
「**多角色调色板**（HCT）+ **播放页背景派生**」，不是从零接 Material You。

### 2.4 接入成本的实测判断

- **不新增 Gradle 依赖**：`material-color-utilities` 不在本机 Gradle 缓存
  （`find ~/.gradle/caches -iname '*material-color*'` → 空）。引入它需要新依赖 + 网络解析，
  且会让「`git clone` 即可复现发布产物」这条纪律变弱。
- **选择：内置移植**（vendored port，Apache-2.0，带出处与许可证头），放在
  `ui/theme/color/`。理由与 v1.5.0 把 `MetroLyricsPanel` 搬进本仓库**逐字同源**
  （`AGENTS.md` v1.5.0 ·「渲染：`drawWithContent` 两层」一节）。
- 纯 Kotlin ⇒ 可 JVM 单测 ⇒ 满足「非核心计算必须可测 + 静默降级」。
- 许可证署名义务见 `THIRD-PARTY-LICENSES.md` **R5**（Apache-2.0 §4，与 GPLv3 兼容）。

### 2.5 ⚠️ 任务书 §3.2 的 HCT 期望值是**错的**（探针实测）

任务书说「封面提取：50×50 Canvas → 像素量化 → 颜色评分 → 主题生成」，并把这套归因于
SPlayer-Next。探针核实过程中发现两件必须记录的事：

**① 采样边长与评分方式都不是 SPlayer-Next 的真实做法。**
它的真实常量是 `COVER_SAMPLE_SIZE = 64`，且 `color.ts` **从未 import `Score`**
（用的是手写评分器）。「50×50」与「`Score` 评分」只存在于该文件的一句 JSDoc 注释里。
详见 `probe-splayer-ref.md` §2。

**② 任务书隐含的 HCT 校验值不成立。**

| | hue | chroma | tone |
|---|---|---|---|
| 任务书口径（未标出处） | 220 | 68–72 | 53 |
| **实测 `Hct.fromInt(0xFF4285F4)`** | **265.979** | **62.269** | **56.550** |

`220` 是 `#4285F4` 的 **HSV/HSL 色相（217.5）**，不是 **CAM16 色相** ——
两个色彩空间的色相本来就不相等，把它们当成同一个数是这条错误的来源。

实测值的**三方独立复核**（都逐值吻合，原始输出见 `EVIDENCE.md`）：

1. 官方 **Kotlin 移植**（`material-color-utilities` main 分支 `kotlin/hct/`，本地编译运行）
   → `hue=265.97939535792614 chroma=62.26911127457101 tone=56.55034873735502`（**逐位相同**）；
2. 官方**现存**测试锚点 `typescript/hct/hct_test.ts` 的期望值（green / blue /
   `from(282.788, 87.230, 90)` / CAM16 red / white / `ViewingConditions.DEFAULT` 全部命中）。
   注意：官方 `java/hct/HctTest.java` 在 main 分支**已不存在**（全仓库无 `*Test.java`），
   **不能**拿它当锚点；
3. 第三方 PyPI 包 `material-color-utilities 0.2.6` → `265.979403 / 62.269111 / 56.550349`
   （一致到 1e-5）。

**处置**：单测断言**实测值**（265.98 / 62.27 / 56.55，±0.05），**不迁就任务书里那个错的 ballpark**。
铁律 12：不伪造测试结果。

> ⚠️ 另一条 oracle 陷阱（记下来免得下次踩）：第三方 PyPI 包在**量化聚类数**上与参考实现不一致
> （它保留更多簇），所以它**不是**量化层的有效 oracle，只能用于 HCT/色调板层。

### 2.6 灰度降级的一个反直觉发现（探针实测，直接决定实现）

`Score.score(map, desired, fallback)` 的**默认参数 `filter = true`** 会把"不够彩"的颜色过滤掉，
然后在结果为空时**回落到 Google Blue（chroma 68）**。
也就是说：**用默认参数做灰度判定，纯灰度封面会被"救"成一个蓝色主题**，灰度降级形同虚设。

因此判据必须是三条一起（缺一不可）：

1. `filter = false`（把"够不够彩"的判定权收回来，不交给 `Score` 隐式过滤）；
2. 兜底色取**「人口最多的量化色」**，而不是 `Score` 默认的 Google Blue；
3. 取到种子后**显式**比对 HCT chroma 阈值。

**阈值 4.0 的依据**：上游没有这个常量（已 grep 全仓库确认），但
`CorePalette.of` 造中性色板用的是 `TonalPalette.fromHueAndChroma(hue, 4.)` ——
chroma 4 就是上游心目中的"中性灰"；旁证是 `Score.CUTOFF_CHROMA = 5`。
实测：白 `2.869` / `#EEEEEE 2.752` / `#E0E0E0 2.654` / `#808080 1.896`，全部 < 4 ⇒ 判为灰度。

### 2.7 色调映射的出处（写进实现注释的那一份）

角色 → tone 的映射取自 `java/scheme/Scheme.java`（light/dark from CorePalette），
色板取法取自 `java/palettes/CorePalette.java`：
`a1=(hue, max(48, chroma))` / `a2=(hue, 16)` / `a3=(hue+60, 24)` / `n1=(hue, 4)` / `n2=(hue, 8)`。

两个**容易抄错**的点（已写进实现 KDoc）：

- 深色的 `outline` 取 `n2/**60**`，**不是** 50（浅色才是 50）；
- 浅色的 `neutral` 取 `n1/**99**`，**不是** 100。

## P3. 圆角规范：现状与缺口

### 3.1 现状：**零圆角**

```
$ grep -rn "RoundedCornerShape" app/src/main/java        → 0 命中
$ grep -rn "Shapes(" app/src/main/java                   → 0 命中
$ grep -rn "\.clip(\|\.border(\|Modifier.shadow\|CircleShape\|CornerSize" app/src/main/java
  → 13 命中（明细见下表）
```

13 处的完整明细：

| file:line | 内容 | 有无 shape 参数 |
|---|---|---|
| `ui/components/QrLoginDialog.kt:192` | `.border(1.dp, primary)` | ❌ 无（直角描边） |
| `ui/components/QrLoginDialog.kt:207` | `.border(1.dp, divider)` | ❌ |
| `ui/components/QrScannerScreen.kt:230` | `.border(1.dp, primary)` | ❌ |
| `ui/components/BackgroundActivityDialog.kt:70` | `.border(1.dp, onSurfaceVariant 40%)` | ❌ |
| `ui/components/SongCard.kt:284` | `.clip(CircleShape)` | ✅ **全仓库唯一的圆**（艺人头像） |
| `ui/screen/UserScreen.kt:625` | `.border(1.dp, divider)` | ❌ |
| `ui/screen/UserScreen.kt:996` | `.border(...)` | ❌ |
| `ui/screen/UserScreen.kt:1254` | `.border(1.dp, onSurfaceVariant 40%)` | ❌ |
| `ui/screen/UserScreen.kt:1285` | `.border(1.dp, borderColor)` | ❌ |
| `ui/screen/UserScreen.kt:1322` | `.border(1.dp, onSurfaceVariant 40%)` | ❌ |
| `ui/theme/ThemeColorSelector.kt:55` | `.border(1.dp, Color.Gray 50%)` | ❌ |
| `ui/theme/AccentSourceSelector.kt:42` | `.border(...)` | ❌ |

**读法**：现状是「统一地没有圆角」，不是「圆角散落不统一」。
所以特性 A 的验收项「无散落硬编码」在本版的正确表述是
**「新引入的圆角不得散落硬编码」** —— 因为引入之前一处都没有。

### 3.2 为什么必须建立单一落点

引入圆角的那一刻才会产生「散落」风险：13 处既有 `border(...)`、17 个文件的封面渲染
（`grep -rln AsyncImage`）、以及各列表行的容器，如果各自写 `RoundedCornerShape(12.dp)`，
下一次改规范就要全仓库找。因此：

- 新增 `ui/theme/AppShapes.kt` 作为**唯一**圆角落点；
- 配一条**回归单测**（`AppShapesSingleSourceTest`）扫源码树：
  `ui/theme/` 之外出现 `RoundedCornerShape(` 即失败。这是把「无散落硬编码」变成**可测事实**，
  与 v2.3.0「落盘 key 名断言」、v2.4.0「`NameNormalizer` 逐字对应」是同一种做法。

### 3.3 任务书的圆角值 vs 本版取值

任务书 §3.1 给的五个值与 Material 3 官方 shape scale 一致，**已独立核实**（不是自证）：

| token | 值 | 出处 |
|---|---|---|
| `extraSmall` | 4dp | androidx `tokens/ShapeTokens.kt`（VERSION 14_1_0）|
| `small` | 8dp | 同上 |
| `medium` | 12dp | 同上 |
| `large` | 16dp | 同上 |
| `extraLarge` | 28dp | 同上 |
| `full` | 50% | 同上（与上五档并列的第六档，本版会用到）|

出处核实见 `probe-splayer-ref.md` §4：androidx `ShapeTokens.kt` 与 MDC-Android
`docs/theming/Shape.md` **逐值一致**，两处独立来源互相印证。

> ⚠️ **但任务书给出的「参考 SPlayer-Next」这个理由不成立。**
> 探针实测：`SPlayer-Dev/SPlayer-Next` 是 **Electron + Vue 3 + Rust 的桌面端**应用
> （AGPL-3.0），浅克隆后 `find` 查 `*.gradle*` / `AndroidManifest.xml` / `*.kt` /
> `pubspec.yaml` **全部为空** —— 它没有 Android 代码、没有 Compose、没有设计 token 文件。
> 任务书里「50×50 采样」「`Score` 评分」两条都只存在于该项目 `color.ts` 的**一句 JSDoc 注释**里，
> 与代码不符（真实常量是 `COVER_SAMPLE_SIZE = 64`，且该文件**从未 import `Score`**）。
>
> **本版的处置**：圆角**取值**照收（因为它另有 Google 一手出处），
> 但**不引用 SPlayer-Next 作为依据**。详见 `probe-splayer-ref.md` §1/§2。

本版 token 列表（`ui/theme/AppShapes.kt`）：

| token | 值 | 本版用途 |
|---|---|---|
| `extraSmall` | 4dp | 角标 / 药丸内层 |
| `small` | 8dp | 列表项；**小封面缩略图**（渲染边长 < 160dp） |
| `medium` | 12dp | 卡片 |
| `large` | 16dp | **大封面**（渲染边长 ≥ 160dp）/ 歌词面板 |
| `extraLarge` | 28dp | 弹窗 |
| `full` | 50% | 按钮 / 搜索框 / 音源标签 / 圆形头像与圆形播放键 |

两条口径说明：

- **名字是 `full` 不是 `pill`**：`full` 既是任务书 §3.1 的用词，也是 Material 3 官方
  这一档的名字（与上五档并列）。用官方名，将来真接入 material3 时调用点不用改名。
- **封面按尺寸分两档（`large` / `small`）而不是全用 `large`**：任务书 §4.2 的验收是
  「大/小封面**视觉协调**」，而半径不随尺寸缩放时并不协调 —— 16dp 圆角放在 56dp 缩略图上
  会吃掉边长的一半，看起来是个团块。所以按渲染边长分档，分界取 160dp。
  这是本版的一个**判断**（不是任务书原文），已写入发布说明与 AGENTS.md。

## P4. 与本版实现直接相关的三条既有约定

1. **颜色数学必须纯 Kotlin**（`AccentSource.kt:58-60`）—— 否则 JVM 单测里
   `android.graphics.Color.colorToHSV` 是未实现的 stub。新的 HCT 代码同样不得 import `android.*`。
2. **主题色必须过 `processAccentColor` + `onAccentColor`**（`AccentSource.kt:9-12` 的文件头
   修改说明）—— 否则高饱和封面会把界面变霓虹灯、浅色主题色上的白字看不清。
   新的多角色调色板若直接进 UI，必须同样受 WCAG 4.5:1 约束。
3. **`remember` 的 key 必须含 `isDark`**（`MainActivity.kt:276`）—— 同一张封面在深浅色下
   产出不同主题色。

## P5. 未确认项

- Kanesumi `MetroDialog` / `MetroBottomSheet` 的内部画法（是否已有 shape 参数）**未逐行读**：
  本版按用户裁定不改 Kanesumi，因此只在 app 侧调用点处理；若后续要给弹窗统一圆角，
  必须先读 `Kanesumi-sec-a/kanesumi-controls/` 的这几个文件确认有无注入点。
- `androidx.palette` 的 `ColorCutQuantizer` 与本版新量化器的**取色一致性**未做 A/B：
  两者会在同一张封面上给出可能不同的种子色。本版的做法是**新增 HCT 通路、保留 Palette 通路**
  （Palette 继续服务通知栏着色），并在单测里钉住 HCT 通路的确定性与降级；
  「两条通路给出同一个颜色」**不是**本版的目标，也不应被当作验收项。
