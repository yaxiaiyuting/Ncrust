# v2.5.6 探针 A：baseline profile 现状与 profileinstaller 机制

> 探针时间：2026-09-26
> 被测仓库：`/home/duanjb666/deepseek/ncrust-gpl/Ncrust`（`HEAD = fef5e7b`，v2.5.5）
> 真机：`0715f763f54c023a` = Samsung SM-G9209 / **Android 7.0 / API 24**（已 root，Magisk）、
> `WVQ6R22124000968` = Huawei WGR-W09 / Android 12 / API 31、
> `3B15CD00GB700000` = OnePlus PLC110 / Android 16 / API 36

## 0. 结论先行

**任务书 §2.1 的第一条前提（「确认仓库当前无 androidx.profileinstaller 依赖」）与
v2.5.5 遗留清单里那条结论，双双为假。** 实测：

| 断言 | 出处 | 实测 | 判定 |
|---|---|---|---|
| 仓库无 `androidx.profileinstaller` 依赖 | 任务书 §2.1；v2.5.5 `EVIDENCE.md:197` | `app/build.gradle.kts` 自 **v1.2.1（`f9d45f4`，2026-07-30）** 起就有 `implementation("androidx.profileinstaller:profileinstaller:1.4.0")`；v2.5.3/2.5.4/2.5.5 三个 tag 里 `grep -c` 均为 1 | **假** |
| APK 清单里没有 profileinstaller | v2.5.5 `EVIDENCE.md:191` | `aapt2 dump xmltree --file AndroidManifest.xml` 命中 **7** 处，含 `androidx.profileinstaller.ProfileInstallReceiver` 与 `ProfileInstallerInitializer` | **假** |
| API 24~30 的设备永远不会安装这份 baseline profile | v2.5.5 `EVIDENCE.md:201` | S6（**API 24**）上 `am broadcast …INSTALL_PROFILE` → `D ProfileInstaller: RESULT_INSTALL_SUCCESS` | **假** |
| macrobenchmark 的 `DROP_SHADER_CACHE` 广播无人应答 | v2.5.5 `EVIDENCE.md:257` | 两台设备实测 `Broadcast completed: result=14`（= 期望值） | **假** |

**v2.5.5 是怎么错的（根因已定位，不是猜测）**：它用的命令是

```
$ aapt2 dump xmltree app-release.apk AndroidManifest.xml
missing required flag --file
dump xmltree [options] --file arg files...
count: 0
```

`aapt2` 在**新版里要求显式 `--file`**；写错时它**不产出任何 XML**，只在 stderr 打一行用法。
那条命令的 stdout 是空的，而 `grep -ci profile` 对空输入返回 **0** ——
于是「命令根本没跑起来」被读成了「清单里没有 profileinstaller」。
**这是一条会伪装成阴性结果的失败命令**，也是本探针最值得记下来的方法学教训。

> 同一形状的错误在本仓库已经不是第一次：v2.5.5 报告里 `CompilationMode.Full` 那次也是
> 「证据来自一条没人复核的命令/记忆」。⇒ 本次起，凡是用「计数为 0」当结论的地方，
> **必须同时留 stderr 与退出码**。（已写进 AGENTS.md，见 §9 新规则 2 的配套条款。）

**那么 P0 的真实缺口是什么？** 不是「没接依赖」，而是**profile 的内容是残的**：

| 层 | 现状 | 缺口 |
|---|---|---|
| 安装器（`androidx.profileinstaller`） | ✅ 已接、已在 API 24 上验证能装 | — |
| 打包（`assets/dexopt/baseline.prof` + `.profm`） | ✅ release/debug APK 里都有 | — |
| **内容** | ❌ **手写的 class-only 清单**（`app/src/main/baseline-prof.txt`，91 行 `Lcom/…;`） | 没有方法级 hot 标记 ⇒ 拿不到 AOT 的主要收益（该文件自己的注释写着「覆盖 60~70% 收益」） |
| **生成管线** | ❌ **不存在**（无 `BaselineProfileRule`、无 `:baselineprofile` 模块、无 CI 任务） | profile 无法随代码演进自动更新 |
| **效果数据** | ❌ **从未测过** | 「冷启动有没有变快」至今没有任何数字 |

⇒ v2.5.6 · P0 的施工面 = **补生成管线 + 用真实采样替换手写清单 + 量化效果**，
而不是「加一条已经在了的依赖」。

---

## 1. 探针方法与原始证据

### 1.1 依赖是否存在

```bash
$ git log --oneline -S "profileinstaller" -- app/build.gradle.kts
f9d45f4 perf: Metro 手感与冷启预热——fling 曲线 + touchSlop + Baseline Profile

$ git show f9d45f4:app/build.gradle.kts | grep -E "^\s+version(Code|Name)"
        versionCode = 3
        versionName = "1.2.1"

$ for t in v2.5.3-gpl v2.5.4-gpl v2.5.5-gpl; do
    printf "%s: " $t; git show $t:app/build.gradle.kts | grep -c "profileinstaller:profileinstaller"; done
v2.5.3-gpl: 1
v2.5.4-gpl: 1
v2.5.5-gpl: 1
```

依赖在 `app/build.gradle.kts` 的 `dependencies` 块里，带一段注释：

```kotlin
// Baseline Profile installer——把 src/main/baseline-prof.txt 打包进 APK，
// 首次启动时 profileinstaller 会请求 ART AOT 编译这些类，消除冷启前 30s 的 JIT 抖动。
// 生成方式：手写 class-level 预加载列表（当前）；后续可接 :baselineprofile 模块自动采样。
implementation("androidx.profileinstaller:profileinstaller:1.4.0")
```

最后一行注释**自己就预告了本版的施工内容**。

### 1.2 APK 里到底有什么

```bash
$ unzip -l dist/Ncrust-v2.5.5-gpl-release.apk | grep -i "dexopt\|baseline\|\.prof"
     6470  1981-01-01 01:01   assets/dexopt/baseline.prof
      838  1981-01-01 01:01   assets/dexopt/baseline.profm
        6  1981-01-01 01:01   META-INF/androidx.profileinstaller_profileinstaller.version
```

`baseline.prof` 6470 B + `baseline.profm` 838 B ⇒ **AGP 确实把 `src/main/baseline-prof.txt`
编译成了二进制 profile 并打进 APK**。`META-INF/…_profileinstaller.version` 是 AGP 对
AndroidX 库的版本戳，它单独就证明了 profileinstaller 在依赖树里。

```bash
$ $AAPT dump xmltree --file AndroidManifest.xml dist/Ncrust-v2.5.5-gpl-release.apk | grep -ci profile
7
```

命中的 7 处（节选）：

```
E: receiver
  A: android:name="androidx.profileinstaller.ProfileInstallReceiver"
  A: android:permission="android.permission.DUMP"
  A: android:exported=true
    E: intent-filter → androidx.profileinstaller.action.INSTALL_PROFILE
    E: intent-filter → androidx.profileinstaller.action.SKIP_FILE
    E: intent-filter → androidx.profileinstaller.action.SAVE_PROFILE
    E: intent-filter → androidx.profileinstaller.action.BENCHMARK_OPERATION
E: provider
  A: android:name="androidx.startup.InitializationProvider"
    E: meta-data androidx.profileinstaller.ProfileInstallerInitializer
```

两条入口都在：`ProfileInstallerInitializer`（普通启动路径，走 androidx.startup）
与 `ProfileInstallReceiver`（benchmark / adb 用的显式路径）。

### 1.3 **决定性证据**：在 API 24 真机上它真的装了

被测机上装的是 v2.5.5（`versionCode=46`）。触发显式安装路径（**非破坏性**，不卸载、
不动登录态）：

```bash
$ adb -s 0715f763f54c023a shell am broadcast \
    -a androidx.profileinstaller.action.INSTALL_PROFILE \
    com.takahashirinta.ncrust/androidx.profileinstaller.ProfileInstallReceiver
Broadcast completed: result=1

$ adb -s 0715f763f54c023a logcat -d | grep ProfileInstaller
09-26 19:30:04.949 12581 12581 D ProfileInstaller: Installing profile for com.takahashirinta.ncrust
09-26 19:30:04.977 12581 12581 D ProfileInstaller: RESULT_INSTALL_SUCCESS
```

**`RESULT_INSTALL_SUCCESS` on API 24。** 落盘也确认了（设备已 root）：

```bash
$ adb -s 0715f763f54c023a shell "su -c ls -la /data/misc/profiles/cur/0/com.takahashirinta.ncrust/"
-rw------- 1 u0_a193 u0_a193 27018 2026-09-26 19:26 primary.prof
```

⇒ 「API 24~30 永远不会安装这份 baseline profile」被**在目标 API 上直接证伪**。

同一形状的交叉验证（另一台设备、另一种 API）：

```bash
$ adb -s WVQ6R22124000968 shell am broadcast \
    -a androidx.profileinstaller.action.BENCHMARK_OPERATION \
    -e EXTRA_BENCHMARK_OPERATION DROP_SHADER_CACHE \
    com.takahashirinta.ncrust/androidx.profileinstaller.ProfileInstallReceiver
Broadcast completed: result=14          # API 31 华为平板
$ adb -s 0715f763f54c023a shell am broadcast …同上…
Broadcast completed: result=14          # API 24 三星 S6
```

`result=14` = `RESULT_BENCHMARK_OPERATION_SUCCESS`（v2.5.5 期望的正是 14，实际拿到 0）。
⇒ v2.5.5 遗留清单第 3 条（「三条 macrobenchmark 基准跑不了」）的**根因判定也是错的**：
接收器在，广播有人应答，那三条基准**现在就能跑**（本版已实跑，见 §3）。

### 1.4 安装时机与 API 分档（字节码实测，不是文档推断）

`androidx.profileinstaller:profileinstaller:1.4.0` 的 `classes.jar` 反编译：

```
androidx.profileinstaller.ProfileInstallerInitializer.create(Context):
   0: getstatic  Build$VERSION.SDK_INT
   3: bipush     24
   5: if_icmpge  16          // SDK_INT >= 24 → 继续
   8: new Result; …; areturn  // SDK_INT < 24  → **空操作**
  16: Choreographer.getInstance().postFrameCallback(…)

androidx.profileinstaller.ProfileInstallerInitializer.installAfterDelay(Context):
   0: getstatic  Build$VERSION.SDK_INT
   3: bipush     28
   5: if_icmplt  18          // SDK_INT < 28 → new Handler(mainLooper)
   8: Handler28Impl.createAsync(mainLooper)   // SDK_INT >= 28 → **异步** Handler
  ...
  postDelayed(runnable, 5000 + Random().nextInt(max(1,1000)))
```

| API | profileinstaller 的行为 |
|---|---|
| < 24 | **空操作**（`create()` 直接 return）。本应用 `minSdk = 24`，这一档不可达 |
| **24 ~ 30** | **首次启动首帧之后 5~6 秒**，在后台线程把 `baseline.prof` 写进 ART profile 目录。这是本版要争取的那一档 |
| 31+ | 同一条路径**仍然会跑**（没有被跳过），只是 API 31+ 平台侧还多了 `ArtManagerLocal`，两者不冲突 |

### 1.5 对 APK 体积的影响

`baseline.prof` 6470 B + `baseline.profm` 838 B ≈ **7.1 KB**（release APK 总量 10 059 204 B）
= **0.07%**。生成的**方法级** profile 会大得多（经验值 50~500 KB），
本版实测数字见 §3.4 —— 那里给的是**实测**，不是估计。

---

## 2. 「能不能生成 baseline profile」与「CI 能不能跑」

| 问题 | 探针结论 | 证据 |
|---|---|---|
| benchmark 模块现在能生成 profile 吗？ | **不能**（探针前）：模块里只有 4 个 `MacrobenchmarkRule` 基准，**零个** `BaselineProfileRule` | `benchmark/src/main/java/…/` 只有 Startup / HomeScroll / SettingsScroll / ExpandPlayer / BenchArgs |
| 生成器需要什么？ | `BaselineProfileRule`，来自 `androidx.benchmark:benchmark-macro-junit4`（**已在依赖里，1.4.1**）+ 一个 `com.android.test` 模块（**已存在**，`targetProjectPath = ":app"`、`self-instrumenting = true`） | `benchmark/build.gradle.kts` |
| 需要新模块吗？ | **不需要**。官方 `:baselineprofile` 模板只是「再开一个 `com.android.test` 模块」；本仓库已有的 `:benchmark` 模块**结构完全等价**（自测量、`targetProjectPath`、macrobenchmark 依赖齐备）⇒ 把生成器加进去是**最低风险**路径，且采样与被测应用共用同一套安装流程 | 本版新增 `BaselineProfileGenerator.kt` |
| 生成器 API 签名 | `collect(packageName, maxIterations, warmupIterations, fileName, killProcessOnStart, strictStabilityCheck, filterPredicate, profileBlock)` | `javap` 见 §1.6 |
| 生成需要多久？ | 每轮 = 冷启动 + 7 条旅程，默认 15 轮 + 5 轮预热。实测量级 **5~15 分钟**（含 release 构建与安装） | 本版实跑记录见 §3 |
| CI 能不能跑？ | **能，但 GitHub 托管 runner 跑不了**：需要一台 `arm64` 的 **API 28+** 真机或模拟器（macrobenchmark 要 `UiAutomation` + 可控编译态，托管 runner 无 KVM 可用且无设备）。⇒ CI 的正确形态是**带自托管 runner 的可选任务**，不能写成「push 即跑」的必过门 —— 否则每个 PR 都会红 | 见 `.github/workflows/baseline-profile.yml`（`workflow_dispatch` + 自托管 runner） |
| profile 生成需要哪些场景？ | 任务书 §3.2 的 7 条，逐条落进 `BaselineProfileGenerator` | 见该文件 KDoc 的旅程表 |

### 2.1 生成设备的选型

profile 是**方法命中集合**，与 API 级别关系不大（同一份 dex），但**代码路径**与设备强相关
（平板走 `windowWidthDp >= 600` 分支、低端机走 FLAC 降级分支）。

本次选 **S6 / API 24** 作为生成设备，理由：
1. 它正是本版要争取的那一档（API 24~30）；
2. 机器空闲、已 root、保持登录态（不需要重新扫码）；
3. 低端机上采样到的「慢路径」正是需要在 API 24~30 上做 AOT 的那批方法。

---

## 3. 效果验证（本版实测）

见 `EVIDENCE.md` §2。方法与判据：

- **必须 release 包**（铁律 16）。`benchmark/run_benchmark.sh` 已有硬断言拦住 debuggable 包。
- 指标：`StartupTimingMetric` 的 `timeToInitialDisplayMs` / `timeToFullDisplayMs`
  （不是 `am start -W` 的 `TotalTime` —— 后者不扣窗口转场，且不含首帧内容）。
- A/B 口径：**同一台设备、同一份 release 源码、只换 APK 里的 `assets/dexopt/baseline.prof`**。
  编译模式固定为 `CompilationMode.DEFAULT`（API 24 上 = `Partial(UseIfAvailable, 0)`，
  字节码实测见 v2.5.5 `probe-benchmark.md`）。迭代数默认 8。

> ⚠️ **A/B 的公平性陷阱**：`BaselineProfileMode.UseIfAvailable` 读的是 **APK 里**的 profile，
> 所以「换 profile」必须**换 APK**（重装）；不能只广播一次 `INSTALL_PROFILE` ——
> 那样 `cur/` 目录里的 profile 与 APK 里的不一致，测的既不是 A 也不是 B。
> 本版的做法：A 用「手写 class-only」profile 构建一次 APK，B 用「生成的方法级」profile
> 构建一次 APK，各自 `adb install -r` 后测量。

---

## 4. 对任务书前提的验证 / 推翻

| 任务书原文 | 实测 | 处置 |
|---|---|---|
| §2.1「确认仓库当前无 `androidx.profileinstaller` 依赖」 | **有**，自 v1.2.1 起，且 v2.5.3/2.5.4/2.5.5 的 APK 里都能在 API 24 上装上 | **推翻**。本版不再「加依赖」，改为「用真实采样替换手写 profile + 补生成管线 + 量化效果」 |
| §2.1「确认 profileinstaller 对 API 24~30 的具体作用机制」 | 已由字节码 + 真机 logcat 给出：API<24 空操作；24~30 首帧后 5~6s 后台安装；31+ 照跑 | **成立**，已量化 |
| §2.1「API 31+ 是否由系统自动处理？」 | 31+ 平台侧另有 `ArtManagerLocal`，但 profileinstaller 路径**不被跳过**（同一份代码无 31 分支）；两者不冲突 | 部分成立，已澄清 |
| §2.1「确认 CI 能否跑 profile 生成任务」 | 托管 runner **不能**；自托管 runner **能** | 结论与任务书假设不同，已落成 `workflow_dispatch` + 自托管 |
| v2.5.5 遗留「API 24~30 永远不会安装 baseline profile」 | **假**（§1.3 真机 `RESULT_INSTALL_SUCCESS`） | 推翻，并记录根因（aapt2 命令失败伪装成阴性结果） |
| v2.5.5 遗留「三条 macrobenchmark 基准结构上跑不了」 | **假**（`result=14`） | 推翻；本版实跑四条基准 |

---

## 5. 未测得项与原因（诚实清单）

| 项 | 状态 | 原因 |
|---|---|---|
| 「手写 profile vs 无 profile」的差值 | **未测得** | 需要一份**完全不含** profile 的 APK 做第三组。本版只做了「手写 vs 生成」二组 A/B（任务书要的是「生成前后」，那正是这两组）。三组的边际价值低，未做 |
| API 25~30 设备上的冷启动 | **未测得（无设备）** | 手上只有 API 24 / 31 / 36 三台。API 24 是目标区间的**下界**，结论对 24~30 整段是**外推**，已如实标注 |
| 真实用户账号下的首页数据量 | 已记录而未控制 | 首页内容随账号/时间变化，A/B 两次测量间隔数分钟，服务端返回可能不同。缓解：两次测量在同一次会话内背靠背完成，且指标是**首帧**（大部分在等网络之前） |
| CI workflow 的真实执行 | **未执行** | 本仓库无自托管 runner。已写成 `workflow_dispatch` + `runs-on: [self-hosted]`，并附「本机等价命令」；**未声称 CI 已跑通** |
| profile 在 API 31+ 上的收益 | 未测 | 任务书只要求 API 24~30 |
