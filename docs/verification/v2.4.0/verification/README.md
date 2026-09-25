# v2.4.0 验证证据目录

| 文件 | 内容 |
|---|---|
| `build-summary.txt` | `clean testDebugUnitTest lint assembleDebug assembleRelease` 的完整结果（单测数、lint Fatal/Error、两个 APK 的 sha256） |
| `tag-verify.txt` | tag / APK badging / build.gradle 的 versionCode 三源交叉校验 |
| `device-*.txt` | 真机/虚拟机验证（安装、冷启动、搜索→艺人页→专辑→播放全链路、logcat 崩溃扫描） |
| `screenshots/` | 三个聚合界面与切源交互的截图 |
| `EVIDENCE.md`（上级目录） | 证据索引与未验证缺口清单 |
