import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------- 安全加载签名配置 ----------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.takahashirinta.ncrust"
    compileSdk = 36

    // ---------- 签名配置 ----------
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.takahashirinta.ncrust"
        minSdk = 24
        targetSdk = 36
        // 本 fork 自有的版本线：基于上游 1.3.1 (versionCode 6)。
        // versionCode 必须严格大于上游，否则后续 fork 版本无法覆盖安装。
        // v1.9.0 的 versionCode 是**实测**定的，不是按任务书推的：任务书写「v1.8.0 = 21，本版 22」，
        // 但 aapt2 dump badging dist/Ncrust-v1.8.1-gpl-release.apk 实测 v1.8.1 已经是 22。
        // 照抄 22 会与已发布的 v1.8.1 撞号、导致无法覆盖安装，故本版取 23。
        // v1.9.1 是 v1.9.0 的 hotfix：修正镜像回退逻辑（jsdelivr 的 403/404 不能判「无此歌词」）。
        // 已发布的 v1.9.0-gpl 制品**不原地替换**，走新的 versionCode，保持发布产物可复现。
        // v1.9.2 修的是 v1.9.0 的分轨缺陷（TTML 胜出时整轨丢弃网易云的译文/音译）。
        // **第三次撞号教训**：任务书写「本版 v1.9.1」，但 aapt2 实测 v1.9.1-gpl 早已发布
        // （versionCode 24，修的是歌词镜像回退），照抄会与线上包撞号、无法覆盖安装 ⇒ 本版取 25。
        // v1.9.3 是渲染层受控解禁：只加音译行渲染（开关默认关），SweepTrack 核心算法一个字节未改。
        // **第四次**：任务书建议「versionCode 以 next-version.sh 输出为准」—— 本版起这就是硬流程，
        // 脚本输出的 26 与上面三个来源一致（最近 5 个 tag / dist 36 个 APK / 仓库当前 build.gradle
        // 的最大值都是 25）。动版本号之前必须先跑 tools/next-version.sh，不要再靠记忆推断。
        // v2.0.0 是 major 版：离线缓存做成可管理的完整能力（Phase 2）+ 三个用户报告的 bug 修复
        // （大屏保持横屏 / 乱序不再静默退回顺序播放 / 歌词加载期控制栏）+ 播放时禁止熄屏 +
        // 动态字号（实验性，默认关）。**仍然没有显式「下载」入口**（合规定位不变）。
        // **第五次按脚本定号**：冷启动跑 tools/next-version.sh，三源（最近 5 个 tag /
        // dist 38 个 APK 的 aapt2 badging / 仓库当前 build.gradle）最大值都是 26 ⇒ 本版取 27。
        // v2.0.1 是 v2.0.0 的 hotfix：修「大屏模式下逐字歌词在还没进入第一句时被拉到最顶端、
        // 第一句看不见」（根因 = 歌词面板固定 200dp 的顶部留白在大屏短面板里占掉 77%，
        // 加上前奏期间三条自动定位路径全部提前返回；详见 AGENTS.md 的 v2.0.1 节与仓库外的
        // PHASE0-REPORT-v2.0.1.md）。**SweepTrack.kt 核心算法一个字节未改**（v1.5.2 冻结）。
        // **第六次按脚本定号**：冷启动跑 tools/next-version.sh，三源（最近 5 个 tag /
        // dist 40 个 APK 的 aapt2 badging / 仓库当前 build.gradle）最大值都是 27 ⇒ 本版取 28。
        // v2.0.2 是媒体通知专项 hotfix：修「华为/荣耀双通知栏」+「通知栏歌词只有暂停/播放才
        // 刷新」。两条都是应用层 bug，已在华为平板 WGR-W09（HarmonyOS 4.2 / EMUI 14.2.0 /
        // Android 12 / API 31）与荣耀 AGT-AN00（MagicOS_9.0.0 / Android 15 / API 35）上真机
        // 复现并取证；根因、证据与未验证项见 AGENTS.md 的 v2.0.2 节、dist 下的
        // RELEASE-NOTES-v2.0.2-gpl.md 与仓库外的 PHASE0-REPORT-v2.0.2.md。
        // **第七次按脚本定号**：冷启动跑 tools/next-version.sh，三源（最近 5 个 tag /
        // dist 42 个 APK 的 aapt2 badging / 仓库当前 build.gradle）最大值都是 28 ⇒ 本版取 29。
        // v2.1.0 是 minor 版：接入 **QQ 音乐音源**（网易云 + QQ 双音源并存、各自独立登录）。
        // 架构上引入了 MusicSourceProvider 抽象与 SourceRouter；渲染层核心
        // （SweepTrack 的扫词算法）**一个字节未改**；`applicationId`、签名、权限全部未动。
        // 关键设计：QQ 音乐的数字 id 用 `1L shl 62` 标志位与网易云的 id 空间**结构性隔离**，
        // 因此离线缓存 key / 歌词缓存 key / 续播进度表 / 队列判重这些既有结构一个都没改，
        // 也没有任何数据迁移（对照方案是在 5 个文件里各做一次 key 带音源 + 老 key 兼容读）。
        // **第九次按脚本定号**：冷启动跑 tools/next-version.sh，三源（最近 5 个 tag /
        // dist 46 个 APK 的 aapt2 badging / 仓库当前 build.gradle）最大值都是 30 ⇒ 本版取 31。
        // v2.1.1 是 patch 版，但修的是一个**让功能整体不可用**的根因，外加两个新能力：
        // ① QQ 扫码登录一直报「网络不稳定」的真根因是 `ptqrtoken` 的 hash33 初值抄错了
        //    （用了 g_tk 的 5381，官方是 0）—— 每一次轮询都被 WAF 判成畸形请求 403；
        //    同时 65/67 状态码映射也是反的（一扫就报「二维码已过期」并停止轮询）。
        //    v2.1.0 把 403 归因成「IP 级风控」，是错的。
        // ② 新增手机号验证码登录（微信用户没有 QQ 号、也没法同机扫码时的可用路径）。
        // ③ 播放界面显示音源来源。
        // `applicationId`、签名、权限全部未动（权限仍是 11 条，逐条与 v2.1.0 一致）。
        versionCode = 31
        versionName = "2.1.1-gpl"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 全量优化：删除死代码、内联、混淆、shrink 资源。
            // 低端机冷启动的最大杠杆——DEX 体积可减 30%+，类加载时间线性下降。
            // proguard-rules.pro 保护 Gson model 反射与 Retrofit interface 注解。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    // v1.3.0 · B1：单元测试里 android.util.Log 等框架方法是抛异常的桩，会让「走日志的纯逻辑」
    // 直接 RuntimeException（本次踩到：parseWriteResponse 的空 body 分支）。置 true 后桩方法
    // 返回默认值，测的是逻辑而不是 Android 运行时。仅影响 testDebugUnitTest，不影响 APK。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        // 让 AboutScreen 通过 BuildConfig.VERSION_NAME 动态显示版本号，
        // 避免每次发版忘记同步硬编码。
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    // Kanesumi —— Metro 组件库。通过组合构建从 ../Kanesumi-sec-a 接入
    // (见 settings.gradle.kts 的 includeBuild)。core/anim 由 controls/structure
    // 的 api 依赖自动带出,这里只列上游两件。
    implementation("io.github.takahashirinta:kanesumi-controls:0.1.0-SNAPSHOT")
    implementation("io.github.takahashirinta:kanesumi-structure:0.1.0-SNAPSHOT")

    implementation("androidx.core:core-ktx:1.13.0")
    // 登录二维码: eapi unikey 接口不返回图片, 客户端本地生成(官方客户端同款做法)
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.activity:activity:1.9.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Media3 —— 1.5.0 是能与下面 FFmpeg 扩展版本严格对齐的最低版本（扩展版本号规则为
    // <media3版本>+<修订>，Maven Central 上没有 1.4.x 的构建）。
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    // v1.6.0 · D1：离线缓存（SimpleCache / CacheDataSource / StandaloneDatabaseProvider）。
    // 这两个之前是 media3-exoplayer 的传递依赖，显式声明以免上游改依赖树时静默消失。
    implementation("androidx.media3:media3-datasource:1.5.0")
    implementation("androidx.media3:media3-database:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")

    // FFmpeg 软件音频解码扩展（Jellyfin 构建，GPL-3.0，与本项目 GPLv3 兼容）。
    // 目的：API 24–26（Android 7.0/7.1）系统只带 OMX.google.flac.encoder，
    // 没有 FLAC 解码器，无损档位只能降级到 mp3；随包带 FFmpeg 后即可真正解 FLAC。
    // 覆盖 arm64-v8a / armeabi-v7a / x86 / x86_64 四个 ABI。
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // CameraX——手机端扫码授权（扫描平板登录二维码后经局域网回传 cookie）
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Coil
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    // Palette & Media
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.media:media:1.7.0")

    // MediaSession extension
    implementation("androidx.legacy:legacy-support-v4:1.0.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Baseline Profile installer——把 src/main/baseline-prof.txt 打包进 APK，
    // 首次启动时 profileinstaller 会请求 ART AOT 编译这些类，消除冷启前 30s 的 JIT 抖动。
    // 生成方式：手写 class-level 预加载列表（当前）；后续可接 :baselineprofile 模块自动采样。
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")

    testImplementation("junit:junit:4.13.2")
    // v1.3.0 · B1：纯 JVM 单测要真正跑 JSON 解析。Android SDK 里 org.json 是抛异常的桩
    // （unit test 里 new JSONObject(...) 直接 throw），所以测试 classpath 补一份实现。
    // 许可证：Public Domain（JSON.org 原文 "The Software shall be used for Good, not Evil."），
    // 仅 testImplementation，不进 APK，见 THIRD-PARTY-LICENSES.md R4。
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}