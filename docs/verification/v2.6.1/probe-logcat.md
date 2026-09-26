# 修复前 logcat 取证记录（v2.6.0-gpl / versionCode 48）

## 结论：**这条路径在 release 包上零日志**，所以「抓 logcat 看路由参数」拿不到任何东西

任务书 §2.1 要求抓「二级菜单点击事件 / 艺人路由参数 / 艺人详情请求 URL」。
实测（PCL110 与 WGR-W09 各一轮，`adb logcat -v time` 全量、未经 tag 过滤）：

| 想抓的东西 | 实际能不能抓到 | 原因（代码位置） |
|---|---|---|
| 二级菜单点击事件 | ❌ | `SongMenuAction.onClick` 是纯 lambda，没有任何日志（`ui/components/SongMenuSheet.kt:30-34`） |
| 艺人路由参数 | ❌ | `resolveAndNavigate` 全程无日志（`MainActivity.kt:1838-1864`）；`NavController` 自身在 release 上不打日志 |
| 艺人详情请求 URL | ❌ | `RetrofitClient` 的 `HttpLoggingInterceptor` 是 **`BuildConfig.DEBUG`-only**（AGENTS.md「Network Layer」一节写明） |
| 任何应用自身输出 | ❌ | 全量日志里 `grep -E "^\S+ \S+ [IWE]/" \| awk '{print $6}'` 的 top 命中是系统的通知/媒体模块，**没有一条来自 `com.takahashirinta.ncrust` 的 I/W/E** |

也就是说：**修复前的症状只能靠截图（用户可见事实）定位，不能靠日志定位。**
这一点本身是本 P0 拖到用户报告才被发现的原因之一，也是修复里加
`Log.i(TAG_ARTIST_NAV, …)` 的直接动机（见 `CHANGELOG-v2.6.1.md`）。

## 复现命令（可重跑）

```bash
S=3B15CD00GB700000          # PCL110（手机，Android 16）
adb -s $S logcat -c
adb -s $S logcat -v time > /tmp/before.txt &      # 全量，让「有没有应用日志」可自证
# …按 probe-artist-jump.md 的步骤点「转到歌手」…
grep -cE "ncrust" /tmp/before.txt                  # 只命中系统侧提到包名的行
grep -E "^\S+ \S+ [IWE]/\S+\( *[0-9]+\): " /tmp/before.txt \
  | grep -i "ncrust\|artist\|nav" | grep -v "Oplus\|Osense\|Hw\|mdns" | head
# ⇒ 空
```

## 为什么证据文件里没有原始 logcat

四份全量日志合计 **252 MB**（PCL110 两轮 129 MB、S6 一轮 109 MB、WGR-W09 一轮 16 MB），
其中与本 P0 相关的**应用**行数为 **0**。把它们塞进 git 只会让仓库多 250 MB 的
ColorOS / HarmonyOS 系统噪声，而不增加任何可复核的信息 —— 所以这里只保留
「结论 + 可重跑的命令」，原始日志不落库。

> 对照：v2.6.0 的 `p0-logcat-librarymanager.txt` 之所以值得入库，
> 是因为那条路径**确实**由应用自己打了 tag 为 `LibraryManager` 的日志。

## 修复后的取证

修复版加了 `TAG_ARTIST_NAV`（`MainActivity.kt`），所以本轮之后这条路径第一次变得可观测：

```
I/ArtistNav: 转到歌手降级为搜索 reason=MISSING_ID song=qqmusic:… keyword=周杰伦
W/ArtistNav: 转到歌手：无艺人信息，忽略 song=qqmusic:…
```

见 `verification/` 下的修复后验证记录。
