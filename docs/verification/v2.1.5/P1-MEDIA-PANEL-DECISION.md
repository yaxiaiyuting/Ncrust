# P1 结论：控制中心媒体面板的歌词能力边界（v2.1.5）

> 本文件是**决策记录**。原始证据在 `p1-baseline/`（三台设备的 dumpsys + 能力矩阵 + 逐条命令），
> 探针代码在 `PlaybackService.logMediaPanelSnapshot()`（tag `NcrustMediaPanel`，仅 debug 包）。

---

## 1. 问题

用户报「华为 wgrw09 平板（以及部分手机）的控制中心媒体面板里，歌词不实时更新」。

这句话里其实混了两个完全不同的问题，必须先拆开：

| 子问题 | 判据 | 本版结论 |
|---|---|---|
| A. 应用有没有把当前歌词行发布到 MediaSession？ | 探针 + 代码 | **有**。`METADATA_KEY_TITLE` = 当前歌词行，2Hz 采样、跨行重 post |
| B. ROM 有没有消费这份 metadata？ | `dumpsys activity service SystemUIService` | **有**。华为 SystemUI 的 `MediaDataManager` 已经把它解析成了自己的数据模型 |
| C. 控制中心的**卡片上肉眼**是否显示、是否实时？ | 只能看屏幕 | **adb 无法判定** —— 本版不声称已修复 |

---

## 2. 实测证据（v2.1.4-gpl 原样，只读探针）

### 2.1 应用侧的发布事实

`dumpsys media_session` / `dumpsys notification --noredact`：

| 设备 | 会话数 | legacy metadata | 通知正文 |
|---|---|---|---|
| WGR-W09（华为平板 / Android 12 / API 31） | 2（`NcrustSession` + media3） | `size=5`；title=`像一幅画`、artist=`紫荆花盛开 · 李荣浩/梁咏琪` | `android.title=像一幅画`、`android.text=紫荆花盛开 · 李荣浩/梁咏琪` |
| PLC110（一加 / ColorOS 系 / Android 16 / API 36） | 2 | `size=5`；title=`我一定让自己让自己坚定`、artist=`爱笑的眼睛 · 林俊杰` | 同上两行 |
| SM-G9209（三星 / Android 7.0 / API 24） | **0** | 无会话可观测 | 应用未 post（仅在 archive） |

两台活着的设备上，title 都是**歌词行**（不是歌名）、artist 都是 `歌名 · 艺人`
—— 正是 v1.6.0 与用户约定的排布。所以「应用没发布」这个假设**被证伪**。

### 2.2 ROM 侧的消费事实（关键）

华为设备上 `dumpsys activity service SystemUIService` 暴露了 AOSP 的
`com.android.systemui.media.MediaDataManager`，其中**已经**有 Ncrust 的条目：

```
mediaEntries: {0|com.takahashirinta.ncrust|1|null|10274=MediaData(
    artist=紫荆花盛开 · 李荣浩/梁咏琪, song=像一幅画,
    artwork=Icon(typ=BITMAP size=512x512), ... active=false ...)}
```

`MediaDataManager` 是 SystemUI 媒体卡的数据源 —— 也就是说华为**确实读了**我们发布的
TITLE / ARTIST / ART。媒体控制 UI 的宿主是 `com.android.systemui` 与
`com.huawei.mediacontroller`（`/system/priv-app/MediaPlaybackController/`，
持有 `com.huawei.intent.action.MEDIA_CONTROLLER_CENTER`）。

### 2.3 为什么不能"换一个专门的歌词 key"

任务书列了四个候选 key。逐个查证（含正向对照，方法学与原始扫描见
`p1-baseline/LOCAL_LIBRARY_lyrics_key_evidence.txt` 与 `..._positive_control.txt`）：

| 候选 | 结论 |
|---|---|
| `METADATA_KEY_DISPLAY_DESCRIPTION` | 平台有，但语义是"描述"，华为侧未见消费；不是歌词位 |
| `METADATA_KEY_DISPLAY_SUBTITLE` | 平台有。应用**显式清空**它 —— 因为 v1.6.0 起歌词已经在 TITLE，再写一遍会在支持三行的车机上重复显示 |
| 自定义 `"android.media.metadata.LYRICS"` | **平台不存在这个 key**。华为 SystemUI.apk 与 media3-session 1.5.0 里的键集都是同 31 个 `android.media.metadata.*`，无 LYRICS |
| media3 `MediaMetadata.Builder.setLyrics()` | **本仓库锁定的 media3 1.5.0 没有这个方法**（1.5.0 的 `MediaMetadata` 是 35 个 `FIELD_*` 常量，无 lyric；`setLyrics()` 是更高版本才有的 API） |

`androidx.media:media:1.7.0` 的 `MediaMetadataCompat` 里 226 个 class 全扫，
`METADATA_KEY_*` 共 31 个，**0 个**与 lyric 相关。

⇒ **不存在一个"正确的歌词 key"可以换。** 把歌词放进 TITLE 不是权宜之计，而是这个
平台版本上**唯一可行**的通道 —— 而它已经在工作（§2.1/§2.2）。

### 2.4 华为另有一套私有的歌词通道（不在 MediaSession 里）

华为侧还存在基于**文件**的歌词能力：`LyricUtil` / `LyricStateData` /
`getContentFromLyricFile` / `writeToLyricFile` / `getLocalLyricImg` /
`jumpLyricPermission` / `isAppOpAllowed` / `updateMediaCommand(LYRIC_STATE)` /
`LYRICS_DISPLAY|HIDE|UNLOOK`。它**不是** MediaSession metadata，
Ncrust 源码里对它的引用数为 **0**。

它是否对第三方应用可达（权限 / 文件路径 / AIDL 契约）**未验证** —— 这是一条
可能的 v2.1.6 方向，但需要真机 + 屏幕才能判断收益。

---

## 3. 本版的决定

1. **不改媒体面板的发布方式。** 它已经在正确的通道上、发布了正确的内容，
   而且 ROM 已经消费 —— 没有可修的 bug。
2. **不动 v2.0.2 刚稳定的通知重 post 路径。** 那条路径（跨行重 post + `LyricNotifyGate`
   限流/延后）是本仓库花了两版真机排查才立住的，而 P1 在**没有屏幕上证据**的前提下
   改它（例如把行节流从 250ms 放宽到 500ms、加 seek/播放态事件分类）属于
   用风险换一个无法验证的收益 —— 不做。拆分到 v2.1.6，等有屏幕侧验证手段再说。
3. **交付探针 + 证据 + 降级说明**（任务书 §3.5 明确允许的路径）：
   - 探针：`NcrustMediaPanel` 每次 setMetadata 后打印
     `keys[] / title / artist / displaySubtitle / line / lastAt /
     gate[post,defer,same,notStarted] / notifyBuilds`。
     它把「应用发布了什么」变成可 grep 的事实，与 `dumpsys` 的「系统收到什么」互为独立证据。
   - 证据：`p1-baseline/`。
   - 降级说明：**应用内歌词（SweepTrack 逐字渐变）与自定义通知栏歌词是可靠的**；
     控制中心面板**能否**显示歌词、以整行还是逐字、以什么频率刷新，取决于 ROM，
     应用只保证「按平台唯一可行的通道，把正在唱的那一行持续发布出去」。

## 4. 对用户的降级说明（可直接引用）

> 控制中心的媒体卡片由系统（华为的 `com.android.systemui` / `com.huawei.mediacontroller`）绘制。
> 应用能做的只有把歌词发布到系统媒体会话里 —— 这一点已经做到，并且系统确实收到了。
> 卡片上是否把这一行显示出来、多久刷新一次，由 ROM 决定。
> 如果控制中心看不到歌词，请使用**应用内歌词**或**通知栏歌词**，那两条路径不经过 ROM 的媒体卡片。

## 5. 仍未验证（不得当作已验证）

- 华为控制中心卡片**肉眼**是否显示歌词、是否实时 —— adb 无法判定，本版不做任何声称。
- 华为那套私有歌词协议对第三方应用是否可达。
- API 24（三星 S6）上的媒体会话行为 —— `dumpsys` 里根本没有 Ncrust 会话，
  这是**测量缺口**而不是负面结论（该机的会话只在真正播放时才建立）。
- `dumpsys` 只打印 `metadata:size=N` 与 title/artist/album，**拿不到 key 的身份列表**；
  `size=5` 与代码里写的 5 个 key 是**吻合**，不是枚举证明。key 身份的证明来自
  源码 + 探针，不来自 dumpsys。
