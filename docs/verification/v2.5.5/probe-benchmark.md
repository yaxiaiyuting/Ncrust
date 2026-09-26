# v2.5.5 macrobenchmark 探针（release 包 · 铁律 16）

| 项 | 值 |
|---|---|
| 探针时间 | 2026-09-26 18:31–18:41（+08:00） |
| 探针范围 | **只做 `adb` 与读文件**。未跑任何 gradle 构建，未跑任何基准迭代（`am instrument` 只跑了一次「指定不存在的测试类」的 0 用例冒烟） |
| 被测对象 | 设备上已装的 `com.takahashirinta.ncrust` v2.5.4-gpl（versionCode 45）；本版要测的 v2.5.5 release 尚未构建 |
| 原始输出 | `docs/verification/v2.5.5/probe-raw/benchmark/`（见 §7 索引） |
| 库事实来源 | 本地 Gradle 缓存里的 `androidx.benchmark:benchmark-macro:1.4.1` / `benchmark-common:1.4.1` AAR 字节码（`javap`），不是文档推断 |

---

## §0 结论先行

**推荐设备：`3B15CD00GB700000`（OnePlus PLC110 / Android 16 / API 36 / arm64 / KernelSU root / 未锁屏 / 有 perfetto）。**

**推荐参数（拿到「第一份 release 基线」的最小可用组合）：**

| 项 | 值 | 理由 |
|---|---|---|
| `CompilationMode` | **`CompilationMode.Ignore`**（首选）或 `None` | `Ignore` 完全不动被测应用的编译/profile 状态（字节码实测 `shouldReset()=false`），保住登录态与既有 ART profile，最省时间；`None` 会把 profile 清掉（`cmd package compile -m verify` + `pm art clear-app-profiles`）换取「无 AOT」这一可复现起点 |
| `iterations` | **3**（先拿到数字）→ 稳定后再补到 5 | 现状 8/6/6/6 是 4 条基准 26 次迭代 |
| `StartupMode` | 保持各自现状（Startup=COLD、HomeScroll=COLD、SettingsScroll=COLD、ExpandPlayer=HOT） | 改 WARM 会改变指标语义（见 §4 组合 B 的代价） |
| 额外 runner 参数 | `-e androidx.benchmark.suppressErrors=UNLOCKED`（PCL110 已 root，可能触发「Unlocked CPU clocks」；该警告不抑制会让整条基准在收尾时判失败） | 见 §5 |
| 驱动方式 | 沿用 `benchmark/run_benchmark.sh` 的「先装 release、再 `am instrument`、不重装不卸载」流程 | `connectedCheck` 会重装 debug 包（违反铁律 16） |

**预估总耗时（PCL110，4 条基准 × 3 迭代 = 12 次迭代，只算测量阶段）：约 5–8 分钟**（§4.2 组合 A 的分项推导）；加上装包（`adb install -r`，10MB）、手动确认登录/预热几分钟，**一轮完整测量约 15 分钟**。同一套参数在 S6 上估 **5–18 分钟**，且**当前锁屏未解时 `HomeScroll`/`SettingsScroll`/`ExpandPlayer` 三条必然出不了数**（§2 #5），因此不推荐。

**一句话一台：**

| 设备 | 判定 | 一句话 |
|---|---|---|
| PCL110 `3B15CD00GB700000` | ✅ **可用（推荐）** | API 36 + arm64 + root + 未锁屏 + 有 perfetto + 320GB 空闲 + 插电 63%，四项硬前置全过；唯一要处理的是「启动时会闪一个权限对话框」和「load average 偏高」 |
| Cuttlefish `127.0.0.1:6524` | ⚠️ **技术上可跑，但不作基线** | x86_64 资产**碰巧**在 APK 里（`packagingOptions.resources.excludes` 对 assets 无效，实测四个 ABI 全在），但设备是 emulator：库会判 `EMULATOR_` 警告（须 `suppressErrors=EMULATOR`），且结果按库自己的说法「not representative」；**另需先把 v2.5.5 release 装上去（现在是 v2.5.3/vc44）** |
| S6 `0715f763f54c023a` | ❌ **本轮不要用** | API 24 技术上能跑（无 perfetto → 走 APK 内置 tracebox；`atrace` 实测可用），但**当前锁屏且是安全锁（Bouncer 持有焦点）**，`am instrument` 起得来、UiAutomator 点不到首页；且 jank 66.88%、可用内存 84MB、`adb root` 不可用 —— v2.5.4 就是卡在它上面 |

---

## §1 现有 benchmark 模块清单

### 1.1 四条基准（源码逐行读出的硬事实）

| 类 | 文件 | 指标 | iterations | CompilationMode | StartupMode | 每迭代 block 做什么 |
|---|---|---|---|---|---|---|
| `StartupBenchmark` | `StartupBenchmark.kt:22` | `StartupTimingMetric()` | **8** | `CompilationMode.DEFAULT` | `COLD` | `startActivityAndWait()`（仅此一行） |
| `HomeScrollBenchmark` | `HomeScrollBenchmark.kt:27` | `FrameTimingMetric()` | **6** | 未指定 → 默认 `DEFAULT` | `COLD` | 冷启进首页 → 点「首页」tab → 取可见高度最大的 scrollable → 上滑 6 次 + 下滑 3 次，每次 `waitForIdle()` |
| `SettingsScrollBenchmark` | `SettingsScrollBenchmark.kt:53` | `FrameTimingMetric()` | **6** | 未指定 → 默认 `DEFAULT` | `COLD` | 冷启 → 点「用户」tab（找不到则退英文 `Profile`/`User`）→ 同一套 6 上 + 3 下 |
| `ExpandPlayerBenchmark` | `ExpandPlayerBenchmark.kt:24` | `FrameTimingMetric()` | **6** | 未指定 → 默认 `DEFAULT` | **`HOT`** | `sleep(1500)` → `swipe(h*0.92 → h*0.15, 40 步)` → `sleep(600)` → 反向 swipe → `sleep(400)` |

> `AGENTS.md` / `CLAUDE.md` 里「benchmark 模块 = 3 个基准」是**过期**的：v2.5.4 新增了 `SettingsScrollBenchmark`，现在是 **4 条**。

**测量指标**：`StartupTimingMetric` → `timeToInitialDisplayMs` / `timeToFullDisplayMs`；`FrameTimingMetric` → `frameDurationCpuMs`（p50/p90/p95/p99）、`frameOverrunMs`、`frameCount`。

**产物路径**：
- 设备侧：`/storage/emulated/0/Android/media/com.takahashirinta.ncrust.benchmark/`（`*-benchmarkData.json` + perfetto trace；`run_benchmark.sh:143` 打印的路径）
- 仓库侧：`benchmark/build/outputs/connected_android_test_additional_output/benchmark/`（注释里写的 connectedCheck 路径；本仓库的**生产式流程不走它**）
- 与 JSON 同级的结构化输出在 instrumentation 的 stdout 里（`am instrument -w` 结束时一次性吐出）

### 1.2 `benchmark/build.gradle.kts` 关键配置（逐条实测核对）

| 配置 | 现状 | 实测/判定 |
|---|---|---|
| `targetProjectPath = ":app"` | 有（第 27 行） | 指向被测工程 ✔ |
| `experimentalProperties["android.experimental.self-instrumenting"] = true` | 有（第 64 行） | 设备上实测 instrumentation 组件为 `com.takahashirinta.ncrust.benchmark/androidx.test.runner.AndroidJUnitRunner (target=com.takahashirinta.ncrust.benchmark)` —— target 是自己，符合 self-instrumenting |
| `signingConfig` | **没有**（整个文件无 signingConfig/buildTypes 块） | 见 §1.3：release 变体**没有**被结构保证 |
| `packagingOptions { resources { excludes += ... } }` | 想排除 x86/arm 的 6 个 trace 二进制 | ❌ **无效**。实测 `benchmark-debug.apk`（39,971,640 B）里 `assets/` 下 **aarch64/arm/x86/x86_64 四个 `trace_processor_shell_*` 与四个 `tracebox_*` 全在**；且真实资产名是 `*_aarch64`（不是排除列表里写的 `*_arm64`）—— 要排 assets 得用 `packagingOptions.assets.excludes` |
| 依赖 | `benchmark-macro-junit4:1.4.1`、`uiautomator:2.3.0`、`runner:1.5.2`、`junit:1.1.5` | 与 §5 的库行为分析同一版本 |
| `minSdk = 24` | 与 app 一致 | API 24 设备（S6）能装 |

### 1.3 「必须用 release 包测」有没有被结构保证？—— **没有**

- AGP 8.5.1 的 `com.android.test` DSL（`javap com.android.build.api.dsl.TestExtension`）**只有 `targetProjectPath` 一个属性**：既没有 `targetVariant`，也没有 extension 级 `signingConfig`。测试模块默认只有 `debug` 这一个 build type，被测变体因此是 app 的 **debug** 变体。
- `:benchmark:assembleDebug` 只产出**测试** APK，不会构建/安装被测 app。所以：
  - `run_benchmark.sh` 的「人工先 `:app:assembleRelease` + `adb install` + 再 `am instrument`」是**目前唯一**把 release 变体送进测量的机制 —— 它是**流程保证**，不是结构保证；
  - 一旦有人跑 `:benchmark:connectedAndroidTest`，AGP 会构建并安装 app 的 **debug** 变体，且测后卸载 → 违反铁律 16（`benchmark/build.gradle.kts:17-19` 的注释自己也承认这点）；
  - 结论：**要结构保证，必须另加机制**（例如给测试模块加 `targetVariant`/专用 build type 并断言 `dumpsys package` 里 `DEBUGGABLE` 不存在，或在 `run_benchmark.sh` 里硬断言 `versionName` 与 `flags=[...]` 不含 `DEBUGGABLE`）。本版不改代码，只记录。

### 1.4 `cookie.secret.example` 与代码**不一致**（文档债）

- `cookie.secret.example` 说「测试运行时 `LoginCookieInjector` 每次迭代 setup 用 run-as 把 cookie 写进被测应用」。
- 实测：`benchmark/src` 下**只有 4 个 benchmark 类 + AndroidManifest.xml**，全仓库 grep `LoginCookieInjector` / `cookieSecret` **0 命中**，`benchmark/cookie.secret` 也不存在（只在 `.gitignore` 里有这一行）。
- 所以当前流程**没有** cookie 注入：登录态只能靠「设备上已装的 release 包自己的登录态」（`run_benchmark.sh` 坚持不重装不卸载正是为此）。
- 好消息（实测）：本地 `app-release.apk` 与 S6/PCL110 上已安装的 `base.apk` **签名证书完全一致**（SHA-256 `e75af3ff…5511`，DN `CN=Ncrust GPL Fork`）⇒ v2.5.5 release 可用 `adb install -r` **覆盖安装**，**登录态不丢**（无需卸载）。
- PCL110 侧证据：`shared_prefs/` 里存在 `ncrust_prefs.xml`，且 `user_cookie` / `MUSIC_U` 两个 key 均在（只数了 key 名，未打印值）。

---

## §2 v2.5.4 卡点复盘（逐条，标注证据等级）

v2.5.4 的原话（`docs/verification/v2.5.4/EVIDENCE.md:155-168`）：
① benchmark APK 构建/安装成功；② `SettingsScrollBenchmark` 通过 `am instrument -w` **启动成功**（首行类名已打出），但 6 迭代 × COLD + **`CompilationMode.Full`** 下「运行约 2 分钟后仍未产出任何迭代数据」；③ 同一轮更早的 `run_benchmark.sh all` 在 `StartupBenchmark` 上「挂了 20 分钟无输出」；④ 用户指示中止；⑤ 本版没有可交付数值。

| # | 卡点 | 判定 | 证据 |
|---|---|---|---|
| 1 | **不是设备不满足**（S6 硬件层面能跑） | API 24 在支持范围内；无 perfetto 时库走 APK 内置 `tracebox`；`atrace -t 1 gfx view` 实测成功（551 条 entries），`/sys/kernel/debug/tracing` 存在且 shell 可读，`cmd package compile` 子命令存在 | `0715f763f54c023a.txt`（perfetto absent / atrace present）、`0715f763f54c023a-trace-capability.txt` |
| 2 | **`EVIDENCE.md` 的「`CompilationMode.Full`」是记错的口径** | 源码用的是 `CompilationMode.DEFAULT`；字节码实测 `DEFAULT` 在 **API ≥ 24 上是 `Partial(BaselineProfileMode.UseIfAvailable, warmupIterations=0)`**，**只有 API < 24 才是 `Full`** ⇒ 在 S6（API 24）上实际跑的是 `Partial`：装 baseline profile + `speed-profile` 编译，**不是**每次迭代 `speed` 全量 AOT | `raw-lib-compilationmode.txt`（`CompilationMode.<clinit>`：`SDK_INT >= 24 → Partial`，否则 `Full`） |
| 3 | **是时间不够 + 没有进度输出**（主因） | `am instrument -w` 对 JUnit 是「一个测试方法跑完才出结果」：4 条基准各只有 1 个 `@Test`，宏基准的 N 次迭代全在这一个方法里 ⇒ **首行类名之后长时间没有任何输出是设计行为，不是死锁**。而单迭代真实成本 = 进程 kill + 冷启 + trace 采集 + **设备端 `trace_processor_shell` 解析**，S6 上「2 分钟无任何迭代数据」反推**单迭代 ≥ 20s** | `EVIDENCE.md` 原文 + §4 的分项估算 |
| 4 | **S6 本身是极差基准设备（数字层面）** | `dumpsys gfxinfo … framestats`：该应用 `Total frames rendered: 202782 / Janky frames: 135624 (66.88%)`；`uptime` load average 4.17；`MemTotal 2.7GB / MemFree 84MB`；安装时 dexopt filter = `interpret-only` | `0715f763f54c023a.txt` |
| 5 | **S6 现在仍然锁屏（且是安全锁）** | `mShowingLockscreen=true mDreamingLockscreen=true`，`mCurrentFocus=Window{… Bouncer}`，`KeyguardServiceDelegate.secure=true, inputRestricted=true`。实测冷启后应用确实画出来了（`ActivityManager: Displayed …: +824ms`）但焦点仍在 Bouncer ⇒ UiAutomator 找不到「首页/用户」tab，scroll/expand 三条必然失败（`StartupBenchmark` 也许能出数） | `0715f763f54c023a.txt`、`0715f763f54c023a-displayed.txt` |
| 6 | **动画被留在关闭状态**（v2.5.4 的副作用） | S6 上三个动画比例现在都是 `0`（`window/transition/animator_duration_scale = 0`）。`run_benchmark.sh:108-113` 的 `restore_animations` 在「原值是 `null`」时**不写回**（`[ "$ORIG" = "null" ] || put` 直接短路），而 PCL110/Cuttlefish 的原值 `animator_duration_scale` 正是 `null` ⇒ 跑一次脚本就会把动画永久留在 0。对 `ExpandPlayerBenchmark`（Compose 动画）是**致命的测量污染**（动画瞬间完成） | `probe_device.sh` 的 animations 段 + `run_benchmark.sh` 源码 |
| 7 | **同一台设备上做过搜索/播放操作**（`EVIDENCE.md:183`） | 对帧率类指标是噪声源，但不改变「跑不完」的结论 | `EVIDENCE.md:183` |

**修正后的结论**：v2.5.4 不是「设备不满足」，也不是「dropped/崩溃」，而是**「单迭代成本（设备端 trace 解析）× 26 次迭代 × 无进度输出」撞上了时间预算**；叠加 S6 的锁屏与低端硬件，以及 `EVIDENCE.md` 把 `DEFAULT` 误记成 `Full` 导致后续「降级方案」选错了杠杆（真正该降的是**迭代数**与**测量条数**，`CompilationMode` 从 `Partial` 换 `None/Ignore` 省的是**一次**编译，不是每次迭代）。

---

## §3 设备能力矩阵（逐格：命令 → 实测）

命令模板：`/usr/bin/adb -s <serial> <...>`；完整逐条输出见 `probe-raw/benchmark/<serial>.txt`。

| 检查项 | 命令 | S6 `0715f763f54c023a` | PCL110 `3B15CD00GB700000` | Cuttlefish `127.0.0.1:6524` |
|---|---|---|---|---|
| 型号 / OS | `shell getprop ro.product.model` `ro.build.version.release` | SM-G9209 / 7.0 | PLC110 / 16 | Cuttlefish x86_64 phone 64-bit only / 17 |
| SDK | `getprop ro.build.version.sdk` | **24** | **36** | **37** |
| ABI | `getprop ro.product.cpu.abi` | arm64-v8a | arm64-v8a | **x86_64** |
| build 类型 | `getprop ro.build.type` / `ro.debuggable` | `user` / 0 | `user` / 0 | `userdebug` / 1 |
| 指纹是否 `:eng/` | `getprop ro.build.fingerprint` | `…:user/release-keys`（否） | `…:user/release-keys`（否） | `…:userdebug/test-keys`（**否**，不会触发 ENG-BUILD 警告） |
| `adb root` | `adb -s <s> root` | ❌ `adbd cannot run as root in production builds`（但装了 Magisk） | ✅ `adbd is already running as root`（uid=0，SELinux `u:r:ksu:s0`） | ✅ 探测开始时 adbd **已经就是 root**（`adb root` 返回 `adbd is already running as root`，`shell id` 首次即为 uid=0）；我中途 `unroot` 过，已用 `adb root` 恢复 |
| shell uid / SELinux | `shell id` / `getenforce` | uid=2000(shell) / Enforcing | uid=0(root) / — | uid=0(root) `context=u:r:su:s0` / Enforcing（已恢复为 root） |
| 锁屏 | `dumpsys window \| grep -i mDreamingLockscreen` | ❌ **锁屏**（`mShowingLockscreen=true mDreamingLockscreen=true`，Bouncer 持焦点，secure=true） | ✅ 未锁屏（`mDreamingLockscreen=false`） | ✅ 未锁屏 |
| 亮屏 | `dumpsys window policy \| grep mScreenOnFully` | `mAwake=true mScreenOnEarly=true mScreenOnFully=true` | 亮屏 | 亮屏 |
| 动画原值 | `settings get global {window,transition,animator}_animation_scale` | **0 / 0 / 0** | **1.0 / 1.0 / null** | **1.0 / 1.0 / null** |
| 动画置 0 是否成功 | `settings put global … 0` → 回读 | ✅ 0/0/0 | ✅ 0/0/0 | ✅ 0/0/0 |
| 动画是否已恢复 | 回写原值 / `settings delete`（原值 null 时）→ 回读 | ✅ 恢复为 0/0/0（与探测前一致） | ✅ 恢复为 1.0/1.0/null | ✅ 恢复为 1.0/1.0/null |
| `am instrument` 是否可用 | `pm list instrumentation` + 0 用例冒烟 | ✅ instrumentation 已注册，`am instrument -w` 实测能拉起 runner 并正常收尾（0.014s，`Tests run: 1, Failures: 1` 仅因类名不存在） | ⚠️ 未装 benchmark APK，无可拉起的 ncrust instrumentation（设备上其他 app 的 instrumentation 很多） | ⚠️ `pm list instrumentation` 为空，未装 benchmark APK |
| 已装被测应用 | `dumpsys package com.takahashirinta.ncrust \| grep versionCode` | v2.5.4-gpl / **vc45**，非 debuggable（`flags=[HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP]`） | v2.5.4-gpl / **vc45**，非 debuggable | **v2.5.3-gpl / vc44**（需先升到 v2.5.5） |
| 已装 benchmark APK | `pm list packages \| grep ncrust` | ✅ `com.takahashirinta.ncrust.benchmark`（vc0/versionName null，arm64-v8a，今天 15:03 装的） | ❌ 未装 | ❌ 未装 |
| 签名能否覆盖安装 | 拉设备上的 base.apk 比证书 | ✅ 与本地 release APK 同证书 `e75af3ff…5511` | ✅ 同证书 | 未测（待装 v2.5.5） |
| 插电 / 电量 | `dumpsys battery` | USB powered=true / **73%** | USB powered=true / **63%** | AC+USB powered=true / **85%** |
| 低电阈值（库要求 ≥25%） | 见 §5 | 通过 | 通过 | 通过 |
| 屏幕常亮设置 | `settings get global stay_on_while_plugged_in` / `system screen_off_timeout` | 7 / 600000 | **15** / 600000 | 1 / **60000** |
| 存储空闲 | `df -h /data` | 23G 可用（26G 总） | **320G 可用**（459G 总） | 7.5G 可用 |
| 内存 | `cat /proc/meminfo` | 2.7GB 总 / **84MB 空闲** | 15.7GB 总 / 4.9GB 可用 | 3.9GB 总 / 2.2GB 可用 |
| CPU 负载 | `uptime` | load 4.17（8 核） | **load 20.09**（偏高，测量前需复核） | load 0.01 |
| 图形健康度 | `dumpsys gfxinfo <pkg> framestats` | **jank 66.88%**（135624/202782） | jank **1.31%**（138/10527） | jank 14.71%（34 帧，样本太小） |
| perfetto | `which perfetto` | ❌ 不存在（→ 走 tracebox） | ✅ `/system/bin/perfetto` | ✅ `/system/bin/perfetto` |
| atrace | `which atrace` + 实跑 | ✅ `atrace -t 1 gfx view` 出 551 条 | ✅ 存在 | ✅ 存在 |
| tracefs | `ls /sys/kernel/tracing` / `/sys/kernel/debug/tracing` | `/sys/kernel/tracing` 不存在；`/sys/kernel/debug/tracing` 存在可读（`tracing_on=0`） | `/sys/kernel/tracing` 存在（`persist.traced.enable=1`） | 同左 |
| `cmd package compile` | `cmd package compile -m speed -f <不存在的包>` | ✅ 子命令存在（`Failure: package … could not be compiled`） | ✅ 存在（`Error: Package not found`） | ✅ 存在 |
| 启动期权限弹窗 | `am start -W` + logcat | 无（API 24 无 POST_NOTIFICATIONS 运行时权限） | ⚠️ **每次冷启都会闪 `GrantPermissionsActivity`**（属被测应用 task，`A=10347`；该 app 只在 `MainActivity.kt:211-213` 请求 `POST_NOTIFICATIONS`，而它在 PCL110 上已是 granted=true；CAMERA 为 denied） | 未测 |
| 自定义被测应用冷启 | `am start -W` / logcat `Displayed` | `ActivityManager: Displayed …: +824 / +860 / +815ms`（3 次） | `am start -W TotalTime` = 436 / 462 / 524 / 476 / 445 ms（**被权限弹窗污染**，作上界看） | 未测 |
| 单次 swipe 成本 | `input swipe … 300`（3 次） | 未测 | 361 / 368 / 374 ms（含 adb 往返） | 未测 |

---

## §4 预估耗时表

### 4.1 单迭代成本模型（分项 + 依据）

| 分项 | 依据 | PCL110（估） | S6（实测下界/估） |
|---|---|---|---|
| 被测应用冷启到首帧 | 实测：PCL110 `am start -W` 0.44–0.52s（含弹窗污染）；S6 `Displayed` 0.82–0.86s | 0.5–1.0s | 0.8–1.5s |
| 交互块（scroll 类：9 次 swipe + 9 次 `waitForIdle`） | 实测 `input swipe` ≈ 0.36–0.37s/次；UiAutomator `swipe` 步数更多、另加 idle 等待 | 6–12s | 12–25s |
| 交互块（ExpandPlayer：2 次 swipe + 2.5s 固定 sleep） | 源码固定值 | 4–7s | 6–10s |
| trace 采集起停（tracebox/perfetto + 落盘） | 库结构：每迭代 start/stop（`ShellServerLifecycleManager`） | 2–4s | 4–8s |
| **设备端** trace 解析（`trace_processor_shell` 以 HTTP server 跑在设备上，每迭代查一次指标） | 库结构 + S6 实测「≥120s 未产出任何迭代数据」⇒ 单迭代 ≥ 20s | 5–15s | 15–60s |
| **单迭代合计** | — | **约 15–30s** | **约 25–90s** |
| 每次 `measureRepeated` 一次性开销 | `shouldReset()` 为 true 时清 profile / 重装 + 编译；`Partial` 还要装 baseline profile 并 `speed-profile` 编译（S6 的 APK 是 R8 9.6MB，dex2oat heap 512MB） | `Ignore` 0s；`None` 5–20s；`Partial` 10–60s | `Ignore` 0s；`None` 30–90s（API<34 走重装路径）；`Partial` 2–5 分钟 |

> 单迭代数值是**估算**（没有跑基准）。S6 的「≥20s/迭代」是**实测下界**（v2.5.4 记录：2 分钟无任何迭代产出）。

### 4.2 组合对比（4 条基准，含 `Partial`/`None`/`Ignore` × COLD/WARM × 迭代 3/5）

| 组合 | 参数 | 一次性开销 | 迭代总时长（PCL110） | **测量阶段总估时（PCL110）** | 同一组合在 S6 | 备注 |
|---|---|---|---|---|---|---|
| **A（推荐）** | `CompilationMode.Ignore` + 保持各基准 StartupMode + **iterations=3** | 0 | 12 × 15–30s ≈ 3–6 min | **≈ 4–8 min** | 12 × 25–90s ≈ 5–18 min（另有锁屏阻断） | 不改设备/应用状态，登录态与 ART profile 原样，最省时 |
| A′ | `CompilationMode.None` + 保持 StartupMode + iterations=3 | 5–20s | 同上 | ≈ 5–9 min | 30–90s + 12 × 25–90s ≈ 6–20 min | `None` 会清 profile（可复现的「无 AOT」起点），但数字比 A 慢、且不再等于用户手里的状态 |
| **B** | `CompilationMode.None` + `StartupMode.WARM` + iterations=3 | 5–20s | Startup 迭代降到 8–12s（12 × 8–12s ≈ 2–3 min） | **≈ 4–6 min** | — | ⚠️ **语义变化**：scroll 类改 WARM 后进程不再被杀，页面首次组合不再计入帧时间（v2.5.4 特意选 COLD 就是为了把「首次组合整页」算进去，见 `SettingsScrollBenchmark.kt:63-66`） |
| C | `CompilationMode.DEFAULT`(=`Partial`) + 保持 StartupMode + iterations=5 | 10–60s | 20 × 15–30s ≈ 5–10 min | ≈ 7–13 min | 2–5 min + 20 × 25–90s ≈ 12–35 min | 与 v2.5.4 同口径、只减迭代；S6 上就是这一档没跑完 |
| D（v2.5.4 实际） | `DEFAULT`(=Partial) + COLD + 8/6/6/6 | 2–5 min（S6） | 26 次迭代 | — | **≈ 15–25 min（S6，估）** | 实测表现：`run_benchmark.sh all` 20 分钟无输出、`SettingsScroll` 2 分钟无迭代数据 —— 与估算一致 |

**给父 agent 的执行建议（不跑构建，只给参数）**：先按 **A** 跑 `Startup + SettingsScroll` 两条（v2.5.5 的转发属性问题只需要这两条），确认能在 10 分钟内出数；再补 `HomeScroll + ExpandPlayer`。

---

## §5 未验证项 / 风险

### 5.1 Android 7（API 24 / S6）的已知限制

1. **没有 perfetto**（实测 `which perfetto` 空）→ 库用 APK 内置的 `tracebox_<abi>` 起 atrace 数据源。实测 `atrace` 与 `/sys/kernel/debug/tracing` 都可用（production build 也能），所以**不是硬阻断**，但采集/解析都比 API 29+ 慢。
2. `FrameTimingMetric` 走的是 perfetto/proto 解析路径（`FrameTimingQuery`），trace 由 tracebox 产出 —— 链路更长，出错面更大；本探针**未做端到端验证**（不做构建就跑不了）。
3. API < 34 时库的「reset」路径是**重装 APK**（`CompilationMode.resetAndCompile` 里 API ≥ 34 走 `cmd package compile -m verify` + `pm art clear-app-profiles`，否则走 `Reinstalling`）。是否保留 app 数据**本次未验证**；若用 `DEFAULT`/`None`，建议先确认登录态还在再开测（`Ignore` 不走这条路，所以推荐它）。
4. 设备当前是**安全锁屏**：必须人工解锁并保持常亮（`stay_on_while_plugged_in=7` 已含 USB，但要再确认屏幕不灭）。
5. `ExpandPlayerBenchmark` 在 S6 上**无意义**：`animator_duration_scale=0`（v2.5.4 遗留），Compose 动画瞬间完成；`run_benchmark.sh` 的 restore 对「原值 null」失效（§2 #6）。
6. 队列非空前置：`ExpandPlayerBenchmark` 需要 miniBar 有歌可拉（源码注释），S6 上该 app 的登录态/队列状态本次**未验证**。

### 5.2 Cuttlefish（x86_64 / API 37）的风险

1. **`EMULATOR_` 警告会让整条基准在收尾判失败**，必须 `-e androidx.benchmark.suppressErrors=EMULATOR`；库自己写明 emulator 数字「not representative」，只能做相对比较。
2. x86_64 的 `trace_processor_shell`/`tracebox` 目前**在 APK 里纯属意外**（`resources.excludes` 对 assets 无效）。**谁要是"修好"了那个 exclude，x86_64 就立刻跑不了**（`PerfettoHelper` 按 ABI 取 `trace_processor_shell_x86_64` / `tracebox_x86_64`）。
3. 现在是 **v2.5.3 / vc44**，且没装 benchmark APK ⇒ 要先 `adb install -r` v2.5.5 release + 装 benchmark APK。
4. 磁盘只剩 7.5G：benchmark APK 39.9MB + 每迭代 trace + `*-benchmarkData.json`，够用但不宽裕。
5. `screen_off_timeout=60000`（60 秒熄屏）+ `stay_on_while_plugged_in=1`（仅 AC）：跑长基准前建议确认屏幕不会灭（或临时调大，改后记得还原）。

### 5.3 PCL110 的风险

1. **启动期权限弹窗**：实测每次冷启都闪 `GrantPermissionsActivity`（属 app task）。`StartupBenchmark` 本身可能仍能出数，但 `HomeScroll/SettingsScroll` 要靠 UiAutomator 点「首页/用户」tab —— 弹窗盖住界面时**第一步就找不到 tab**。建议测量前先处理掉（`pm grant` 或手工点掉/勾选不再询问），并且**记录你改了什么**。
2. **`load average 20.09`**（5/15 分钟均值 20.86/21.03）：明显偏载。开测前先 `uptime` 复核；若仍 >20，先 `am kill-all`/清后台或重启设备，否则帧时间噪声会盖过 v2.5.5 想看的 ≤1ms Δ。
3. **已 root（KernelSU）**：可能触发 `UNLOCKED_`（Unlocked CPU clocks）警告 → 不抑制就整条失败。建议 `-e androidx.benchmark.suppressErrors=UNLOCKED`（若同时用了模拟器再加 `,EMULATOR`）。
4. `screen_off_timeout=600000` + `stay_on_while_plugged_in=15`：暂时够用，但长时间跑（>10 min）仍要盯一眼。
5. ColorOS 后台管控可能杀 app/降频：本轮未验证省电策略是否对 `com.takahashirinta.ncrust` 生效。

### 5.4 结构性 / 流程性风险

1. **release 变体没有结构保证**（§1.3）：只能靠 `run_benchmark.sh` 的人工流程 + 事后断言。建议在脚本里加一条硬断言：`dumpsys package <pkg>` 的 `flags=` 不得含 `DEBUGGABLE`（v2.5.4 的脚本已经有类似的软警告，见 `run_benchmark.sh:99-101`，但没有中止）。
2. **`cookie.secret` 机制不存在**（§1.4）：任何「重装被测应用」的路径都会丢登录态，而当前没有注入机制 ⇒ 必须坚持「`adb install -r` 覆盖安装 + 不卸载」。
3. `run_benchmark.sh` 的 `restore_animations` 对 `null` 原值失效（§2 #6）：**跑完务必手工核对三个动画比例**，否则设备会被永久留在动画关闭状态（S6 就是现状）。
4. `run_benchmark.sh` 第 138 行的 usage 提示串写的是 `[startup|scroll|expand|all]`，漏了它实际支持的 `settings`（不影响功能，但会让人以为没有这一档）。
5. 库前置检查（全部在 `androidx.benchmark.Errors` 里，`MacrobenchmarkKt` 收尾时调 `throwIfError()`；WARNING 与 ERROR 一样会**让整条基准判失败**，除非用 `androidx.benchmark.suppressErrors` 列名抑制）：
   - `DEBUGGABLE_`：release 包不会命中（v2.5.4 的注释里那个 `suppressErrors=DEBUGGABLE` 就是给它准备的）；
   - `LOW-BATTERY_`：<25% 才命中，三台均 63–85% ⇒ 通过；
   - `EMULATOR_`：Cuttlefish 必命中 ⇒ 必须抑制；
   - `UNLOCKED_`（rooted + CPU 时钟未锁）：PCL110 已 root，**很可能命中**（依赖库的 root 检测与调频状态，未实测）⇒ 建议预防性抑制；
   - `NOT-AOT-COMPILED_`：**不会**因为 `Ignore`/`None` 而命中 —— 字节码实测它被 `Arguments.requireAot` 门控，而该参数默认 `false`（`Arguments.<clinit>`：无参数时 `iconst_0`）。所以「无 AOT 就跑不了」是**错的**，不必为此抑制。

### 5.5 本探针未验证的项（明确列出，避免误读）

- 任何基准的**实际单迭代耗时**与端到端能否跑通（需要构建 benchmark APK 并跑迭代 —— 本探针不做）。
- 设备端 `trace_processor_shell` 的真实解析耗时。
- `CompilationMode.None/Partial` 的 reset 路径是否会清掉 app 数据（登录态）。
- v2.5.5 release 包在 PCL110/Cuttlefish 上的安装与首次启动（包还没构建）。
- `HomeScrollBenchmark` 在 PCL110（Android 16 + ColorOS）上「首页」tab 的 a11y 文本能否命中、`SettingsScrollBenchmark` 的「用户」tab 同理。
- PCL110 的权限弹窗是否会影响 UiAutomator 找节点（未跑基准，无法确认）。

---

## §6 我在设备上改动了什么、有没有恢复

| 设备 | 改动 | 原值 | 恢复动作 | 结果 |
|---|---|---|---|---|
| S6 `0715f763f54c023a` | `settings put global {window,transition,animator}_animation_scale 0` | **0 / 0 / 0**（探测前就已经是 0） | 回写 0 / 0 / 0 | ✅ 与探测前一致（**净变化：无**） |
| PCL110 `3B15CD00GB700000` | 同上三个 put | **1.0 / 1.0 / null** | `put 1.0`、`put 1.0`、`delete animator_duration_scale` | ✅ 回读 1.0 / 1.0 / null（**净变化：无**） |
| PCL110 `3B15CD00GB700000` | `adb unroot`（我为了测「shell 是否 root」跑的） | 探测开始时 adbd **已是 root**（`adbd is already running as root`） | `adb root` → 回读 `uid=0(root) … context=u:r:ksu:s0` | ✅ 已恢复为 root（**净变化：无**） |
| Cuttlefish `127.0.0.1:6524` | 三个动画比例 put | **1.0 / 1.0 / null** | `put 1.0`、`put 1.0`、`delete animator_duration_scale` | ✅ 回读 1.0 / 1.0 / null（**净变化：无**） |
| Cuttlefish `127.0.0.1:6524` | `adb unroot`（我为了测「shell 是否 root」跑的） | 探测开始时 adbd **已是 root**（`shell id` 首次即 uid=0，`adb root` 回 `adbd is already running as root`） | `adb root` → `wait-for-device` → 回读 | ✅ 已恢复为 root（`uid=0(root) … context=u:r:su:s0`）（**净变化：无**） |
| 三台 | `logcat -c`、`am force-stop`、`am start`（测冷启）、`input swipe`（测耗时）、`atrace -t 1`（测 trace 能力，跑完 `tracing_on=0`） | — | 无需恢复（非持久设置；app 最后被 force-stop） | ✅ |
| S6 | `am instrument -w -e class <不存在的类>` 0 用例冒烟 | — | 无副作用（被测 app 未被卸载：`pm path` 仍可解析） | ✅ |

**没有做的事**：未安装/卸载任何 APK，未改权限（`pm grant/revoke` 一律没做，权限弹窗问题只记录不动手），未改 `screen_off_timeout` / `stay_on_while_plugged_in` / dexopt 状态 / 省电策略，未跑任何 gradle 任务，未碰华为控制中心卡片，未跑任何基准迭代。

---

## §7 原始输出索引

`docs/verification/v2.5.5/probe-raw/benchmark/`：

| 文件 | 内容 |
|---|---|
| `probe_device.sh` | 前置条件探针脚本（可复现；动画改后自动恢复） |
| `0715f763f54c023a.txt` / `3B15CD00GB700000.txt` / `127.0.0.1:6524.txt` | 三台设备逐条命令的原始输出（含 `getprop` 全量、`dumpsys package/window/power/battery/deviceidle/thermalservice`、`cmd package`、`atrace`、动画 before/while/after、`df/meminfo/uptime`） |
| `0715f763f54c023a-trace-capability.txt` | S6 上 `/sys/kernel/debug/tracing` 与 `atrace -t 1` 实跑 |
| `0715f763f54c023a-instrument-smoke.txt` | S6 上 `am instrument` 0 用例冒烟 + benchmark APK 版本 + 被测 app 未被卸载 |
| `0715f763f54c023a-displayed.txt` / `3B15CD00GB700000-displayed.txt` | 冷启实测（logcat `Displayed` / 前台窗口 / 权限弹窗） |
| `3B15CD00GB700000-coldstart.txt` | PCL110 `am start -W` 5 次 |
| `3B15CD00GB700000-adbroot-restore.txt` | PCL110 adbd 恢复为 root + 登录态 key 计数 + swipe 计时 |
| `127.0.0.1:6524-adbroot-restore.txt` | Cuttlefish adbd 恢复为 root（探测开始时它本来就是 root） |
| `raw-local-benchmark-apk.txt` | `benchmark-debug.apk` 的 assets/libs 清单（证明 4 个 ABI 全在）+ 体积 |
| `raw-local-release-apk.txt` | `app-release.apk` 的 `aapt2 badging` + `apksigner verify` + 体积 |
| `raw-signature-consistency.txt` | 本地 release APK 与 S6/PCL110 上已安装 base.apk 的证书对比（同证书 ⇒ 可覆盖安装保登录态） |
| `raw-lib-compilationmode.txt` | `CompilationMode.DEFAULT` 的 API 分支、四种 mode 的 `shouldReset/compileImpl`、reset 里的 profile 清除路径 |
| `raw-lib-errors-and-tracepipe.txt` | `androidx.benchmark.Errors` 全部检查项字符串（DEBUGGABLE/EMULATOR/ENG-BUILD/LOW-BATTERY/NOT-AOT-COMPILED/UNLOCKED/SIMPLEPERF…）、`PerfettoHelper` 的 ABI→资产后缀与 API 29 分支、`ShellServerLifecycleManager` 的 trace_processor HTTP server |
| `raw-agp-testextension.txt` | AGP 8.5.1 `TestExtension`/`TestBuildType` 的 DSL 面（无 `targetVariant`） |
| `raw-src-and-manifest.txt` | benchmark 源码清单、`LoginCookieInjector`/`cookie.secret` 0 命中、测试 manifest 未声明 localhost 明文 HTTP、app 的 `<profileable>`、`MainActivity` 的权限请求 |

**复现（不涉及构建）**：

```bash
export ANDROID_HOME=/home/duanjb666/Android/sdk
R=docs/verification/v2.5.5/probe-raw/benchmark
bash $R/probe_device.sh 3B15CD00GB700000 $R          # 前置条件（含动画改后恢复）
bash $R/displayed_probe.sh 3B15CD00GB700000 $R 5     # 冷启实测
```
