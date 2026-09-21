# 第三方依赖许可证审计（GPLv3 兼容性）

审计对象：`Ncrust` fork 的 **app 模块 debug 运行时 classpath**（即真正会被编译进 APK 并随 GPLv3 分发的代码集合）。

- 审计日期：2026-09-20
- 审计方法：`./gradlew :app:dshDeps`（自定义 init script 打印 `debugRuntimeClasspath` 的全部已解析组件），再逐个读取 Gradle 缓存中该组件的 `*.pom` 的 `<licenses>` 声明。
- 复现方式：
  ```bash
  ./gradlew :app:dshDeps -I /tmp/listdeps.gradle   # init script 见文末
  ```

## 一、结论

**随 APK 分发的 164 个运行时依赖，全部为 Apache-2.0 或 Apache-2.0 双许可（BSD），与 GPLv3 兼容，无阻塞项。**

Apache-2.0 与 GPLv3 兼容（Apache-2.0 → GPLv3 单向兼容；GPLv2 才不兼容），因此把 Apache-2.0 的 AndroidX / Compose / Media3 等链接进 GPLv3 的 APK 是允许的。

许可证分布（运行时依赖）：

| 数量 | 许可证 | 兼容 GPLv3 |
|---|---|---|
| 144 | The Apache Software License, Version 2.0 | ✅ |
| 8 | The Apache License, Version 2.0 | ✅ |
| 1 | Apache-2.0（`com.google.code.gson:gson`） | ✅ |
| 1 | Apache-2.0 | BSD（`androidx.camera:camera-core`，双许可，可选 Apache-2.0） | ✅ |
| 10 | POM 未声明 `<licenses>`，见下方逐项核实 | ✅ |

## 一之二、FFmpeg 解码扩展（v1.1.0-gpl 新增，同为 GPL）

| 项 | 值 |
|---|---|
| 坐标 | `org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1` |
| 来源 | **Maven Central**（不可变、可复现，非 JitPack 按需构建） |
| 上游 | https://github.com/jellyfin/jellyfin-androidx-media |
| 声明许可证 | **GPL-3.0**（仓库 LICENSE 与 POM `<licenses>` 一致） |
| 与本项目关系 | 本项目整体以 **GPLv3** 分发 —— **许可证一致，完全兼容** |
| 分发形态 | 每个 ABI 一个独立 `libffmpegJNI.so`，运行时由 `FfmpegLibrary` 动态加载 |

**引入原因**：API 24–26（Android 7.0/7.1）系统的 `media_codecs*.xml` 中，`audio/flac`
**只有 `OMX.google.flac.encoder`，没有解码器**（本机 API 24 模拟器已实测确认），
导致无损档位在这些设备上只能降级到 mp3。内置 FFmpeg 软件解码器后，
`FfmpegAudioRenderer` 会在平台无解码器时接管 FLAC 解码。

**ABI 覆盖**（已确认 APK 内实际存在）：

| ABI | libffmpegJNI.so |
|---|---|
| arm64-v8a | 1,471,232 B |
| armeabi-v7a | 1,370,136 B |
| x86 | 1,476,784 B |
| x86_64 | 1,563,520 B |

**合规说明**：

- 该扩展是 **GPL-3.0**，与本项目整体许可证一致，**不引入任何新的分发限制**。
- 它**不是** LGPL。若未来替换为 LGPL 构建的 FFmpeg，则需满足 LGPL 的「动态链接 + 可替换」
  要求 —— 当前实现已是独立的 `.so` 动态加载，该条件天然满足。
- 引入 GPL 组件后，**本项目的整体 GPLv3 分发方式不可回退为 MIT 或闭源**；
  原 MIT 部分（`LICENSE-MIT`）仍完整保留。

**实测证据**（Android 7.0 / API 24 模拟器 logcat）：

```
SongUrlFetcher: FLAC capability: platformDecoder=false ffmpegExtension=true
                ffmpegVersion=Lavc60.3.100 => canDecodeFlac=true
```

### 联动的版本变更

为与该扩展严格对齐（其版本号规则为 `<media3版本>+<修订>`，Maven Central 上没有 1.4.x 构建），
Media3 由 **1.4.1 升级为 1.5.0**（exoplayer / session / ui 三者保持一致）。Media3 仍为 Apache-2.0。

## 二、POM 未声明许可证的依赖（逐项人工核实）

这些组件的 POM 没有 `<licenses>` 段，已逐个按其上游仓库许可证确认：

| 组件 | 实际许可证 | 依据 |
|---|---|---|
| `com.google.zxing:core:3.5.3` | Apache-2.0 | ZXing 上游仓库 LICENSE |
| `com.google.guava:guava:33.0.0-android` | Apache-2.0 | Guava 上游仓库 LICENSE |
| `com.google.guava:failureaccess:1.0.2` | Apache-2.0 | Guava 子模块 |
| `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` | Apache-2.0 | 空占位 artifact，无代码 |
| `com.google.auto.value:auto-value-annotations:1.6.3` | Apache-2.0 | AutoValue 上游仓库 LICENSE |
| `kanesumi-core / anim / controls / structure`（组合构建，源码编入） | Apache-2.0 | 已在 `../Kanesumi-sec-a/LICENSE` 核实（Copyright 2026 TakahashiRinta） |

## 三、风险项

### R1 — JUnit 4.13.2 为 EPL-1.0，与 GPLv3 不兼容（**不阻塞分发**）

- 坐标：`junit:junit:4.13.2`，声明为 `testImplementation`。
- 许可证：**Eclipse Public License 1.0**（EPL-1.0 与 GPLv3 **不兼容**；EPL-2.0 才兼容）。
- **为什么目前不构成问题**：JUnit 只进入单元测试 classpath，**不在** `debugRuntimeClasspath` 中，不会被编译进 APK，因此不随本 Fork 分发，GPLv3 的分发义务不触发。
- 建议（可选）：若要彻底消除该风险，把单元测试从 JUnit4 迁到 JUnit5（EPL-2.0，兼容 GPLv3）或改用 Apache-2.0 的测试框架。**当前不必处理。**

### R4 — org.json:json（Public Domain，测试专用，不阻塞）

- 坐标：`org.json:json:20231013`，v1.3.0 · B1 起声明为 **`testImplementation`**。
- 为什么需要：Android SDK 里的 `org.json` 在 JVM 单元测试中是抛异常的桩类，歌单写操作的
  「业务码 → 结果」映射（200/502/405/403/301…）必须真跑解析才能回归。
- 许可证：Public Domain（JSON.org 原始声明）。与 GPLv3 无冲突，且只在测试 classpath，
  **不进 APK、不随分发**。

### R2 — androidTest / Espresso 依赖（Apache-2.0，不阻塞）

`androidx.test.ext:junit:1.1.5`、`androidx.test.espresso:espresso-core:3.5.1`、`androidx.benchmark:benchmark-macro-junit4:1.4.1` 均为 Apache-2.0，且只在 androidTest/benchmark 中，不进 APK。

### R3 — Apache-2.0 的署名义务（**需要遵守，非兼容性问题**）

Apache-2.0 第 4 条要求在分发时保留版权、许可证与 NOTICE 声明。Kanesumi 的源码被组合构建编入 APK，因此：

- 必须保留 `Kanesumi-sec-a/LICENSE`（Apache-2.0）与其版权声明；
- 若 Kanesumi 含 `NOTICE` 文件，需一并保留并在本仓库可见；
- 本仓库已在 README「许可证」一节与本文档中声明 Kanesumi 为 Apache-2.0 并给出出处。

### R4 — GPLv3 与 MIT 的并存（已按你的要求处理）

原始 MIT 代码与新增 GPLv3 代码混合后整体以 GPLv3 分发：MIT 允许再许可（sublicense）为 GPLv3，MIT 原文与版权声明已完整保留在 `LICENSE-MIT`（未作任何修改，sha256 与上游一致）。

## 四、构建工具链（不随 APK 分发，仅供参考）

| 工具 | 许可证 |
|---|---|
| Gradle 9.3.1 | Apache-2.0 |
| Android Gradle Plugin 8.5.0 | Apache-2.0 |
| Kotlin 1.9.24 | Apache-2.0 |

## 附：审计用 init script（`/tmp/listdeps.gradle`）

```groovy
gradle.projectsEvaluated {
  rootProject.allprojects { p ->
    p.tasks.register("dshDeps") {
      doLast {
        def c = p.configurations.findByName("debugRuntimeClasspath")
        if (c == null) { println "NOCONF " + p.path; return }
        c.incoming.resolutionResult.allComponents.collect { it.id.displayName }
          .sort().each { println "DEP " + it }
      }
    }
  }
}
```
