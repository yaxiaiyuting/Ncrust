# v2.5.4 lint 摘要

命令: ./gradlew :app:lintDebug
结果: 0 Error / 0 Fatal；8 Warning + 2 Information

8 条 Warning 全部落在**本版未改动**的文件上（存量问题）：
  local/LocalPlaylistStore.kt  91/108/113  ApplySharedPref
  qq/QqPlaylistStore.kt       159/164/181  ApplySharedPref
  ui/components/DetailScaffold.kt 88       ModifierParameter ×2

本版新增/修改的文件：0 条 Warning。

2 条 Information 是 lint-baseline.xml 自身的统计（56 errors/102 warnings 已被 baseline 过滤）。
