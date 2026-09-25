# v2.3.0 证据索引（EVIDENCE）

> 本文件是 v2.3.0 全部验证证据的**入口**。每条结论都指向一个真实存在的文件；
> 命令与观测值都在对应文件里，不在本文件里重述。

构建产物与发布信息见 `../../../../`（仓库根）的 `CHANGELOG` 一节与最终的 draft release 说明。

---

## 0. 结论速查

| 验收项 | 结论 | 证据 |
|---|---|---|
| 探针完整回答五个问题 | ✅ | `PROBE-SUMMARY.md` + 五份 `probe-*.md` |
| 单测 / lint / assembleDebug / assembleRelease | 见 §4 | `verification/build-*.txt` |
| release 包真机验证 | 见 §5 | `verification/device-*.txt` + `screenshots/` |
| 横屏 5s 自动居中 | 见 §5.3 | `verification/lyric-autocenter-*.txt` + `screenshots/v230-*` |
| 本地歌单 7 规则 + tombstone 持久化 | 见 §2 与 §5.2 | `verification/local-playlist-*.txt` |
| versionCode 三源交叉校验 | 见 §6 | `verification/version-check.txt` |

---

## 1. 探针（阶段一，落盘后才动代码）

| 文件 | 内容 |
|---|---|
| `PROBE-SUMMARY.md` | 五个探针问题的总结论 + 探针→实现对照表 |
| `probe-copyright.md` | 版权字段位置、`pl`/`st` 语义、三套判据的准确率、预检成本 |
| `probe-source-attribution.md` | 跨源同一性不存在、现状只标 QQ、QQ 无可用字段 |
| `probe-official-tag.md` | 「官方版本」的定义、`originCoverType` 自洽率 96.7%、明确不做的部分 |
| `probe-local-playlist.md` | 本地歌单概念存在性审计、存储设施、为什么不能复用 `PlaylistTrack` |
| `probe-lyric-landscape.md` | 横屏面板几何、5s 超时只放旗子、决定性复现、播放/暂停/seek 的探针回答 |
| `probe-raw/probe-copyright-report.txt` | ①的逐行报告（字段普查） |
| `probe-raw/probe-copyright-semantics.txt` | ①b 的逐行报告（取值语义） |
| `probe-raw/probe-copyright-accuracy.txt` | ①c 的逐行报告（12 首/层的第一轮准确率） |
| `probe-raw/probe-judgement-validity.txt` | ①e/③b 的逐行报告（30 首/层 + 60 条自洽性抽查） |
| `probe-raw/probe-qq-fields.txt` | QQ 字段普查 + `action.switch` 位表 |
| `probe-raw/ne-*.json` / `q-*.json` / `sem-*.json` / `urlcheck-*.json` / `qq-search-*.json` | 全部原始响应 |
| `probe-raw/device-prefs-list.txt` | 真机 prefs 清单（证明此前没有本地歌单存储） |

**探针脚本**（可复现，均为只读匿名请求）：`probe-copyright.py`、
`probe-copyright-semantics.py`、`probe-copyright-accuracy.py`、
`probe-judgement-validity.py`、`probe-qq-fields.py`。

---

## 2. 横屏歌词的真机基线（阶段一，v2.2.1 未修改）

| 文件 | 内容 |
|---|---|
| `probe-raw/landscape-device-notes.md` | **主笔记**：设备、几何、决定性复现、T4、VERDICT、全部文件清单 |
| `probe-raw/lyric-panel-measurements.txt` | 22 个 dump 的逐节点测量总表（bounds / `frac_itemtop` / `frac_center` / 当前行判定） |
| `probe-raw/pixel-diff-report.txt` | 逐字节静止性证明（md5 + `compare -metric AE`） |
| `probe-raw/landscape-raw-runlog.txt` | 逐步原始过程记录（含命令差异与重做记录） |
| `probe-raw/quiescence-control-q1/q2/q3.png` | 暂停静止性三连拍（三张 md5 相同，0px 差异） |
| `probe-raw/invalid-timing/README.txt` | **作废留档**：首次播放态试验只等了 2.4s（< 5s 超时），已隔离重做 |
| `screenshots/01..20, PAUSED*, PLAYING*`（27 张 PNG） | 竖屏 / 横屏 / 播放 / 暂停 / 滑动前后 / 静置 |
| `ui-*.xml`（27 个） | 与各截图同一标签抓取的 uiautomator 层级，所有 bounds 的直接出处 |
| `logcat-landscape-baseline.txt` | 全量 logcat 12758 行（16:46:12–16:59:29） |
| `logcat-landscape-filtered.txt` | 过滤后 1235 行（换行日志、大屏模式日志、jank） |

---

## 3. 实现

见最终回复的「关键文件」一节；每个新模块的 KDoc 里都写了「为什么这么做」以及它对应的探针结论。

---

## 4. 构建与静态检查

| 文件 | 内容 |
|---|---|
| `verification/build-summary.txt` | `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease` 的结果摘要 |
| `verification/unit-tests.txt` | 单测统计（总数 / 通过 / 失败）与新增测试类清单 |
| `verification/lint-result.txt` | lint 报告位置与 Error/Fatal 计数 |

命令：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease
```

---

## 5. 真机验证（release 包）

> 设备 `0715f763f54c023a`（SM-G9090 / 见下）—— 实际为 **SM-G9209 / Android 7.0**；
> 与阶段一同一台，且**先备份了 2.2.1 的整套 SharedPreferences**
> （`/sdcard/ncrust-v230-backup/shared_prefs/`），用于验证「老数据不丢」。

| 文件 | 内容 |
|---|---|
| `verification/device-install.txt` | 安装 release 包、`dumpsys package` 的 versionCode/versionName、签名 |
| `verification/device-migration.txt` | 升级后 prefs 对照：老 key 仍在、新 key `ncrust_local_playlists` 出现 |
| `verification/local-playlist-rules.txt` | 7 条规则的真机复现记录（含重启后 tombstone 仍生效） |
| `verification/lyric-autocenter-before.txt` | 横屏自动居中**修复前**：滑动后静置不回正（引用阶段一基线） |
| `verification/lyric-autocenter-after.txt` | 横屏自动居中**修复后**：滑动后静置 5s 回到目标位置 |
| `verification/search-tags.txt` | 搜「周杰伦」的音源归属与版权标注实测（含截图引用） |
| `verification/official-tags.txt` | 原唱 / 翻唱角标实测（含原曲副标题） |
| `verification/source-attribution.txt` | 双音源切换与库界面信息架构实测 |
| `screenshots/v230-*.png` | v2.3.0 的全部新截图 |
| `logcat-v230-*.txt` | v2.3.0 的各轮 logcat |

---

## 6. 版本与发布

| 文件 | 内容 |
|---|---|
| `verification/version-check.txt` | `tools/next-version.sh`（**带 fetch**）的三源交叉校验输出 |
| `verification/tag-verify.txt` | `git show v2.3.0-gpl:app/build.gradle.kts` + `aapt2 dump badging` 的三源一致性 |
| `verification/release-create.txt` | `gh release create --draft` 的输出 |
| `CHANGELOG-v2.3.0.md` | 用户可读的更新说明与未验证缺口清单 |

---

## 7. 未验证 / 缺口（与 release notes 一致，不重复叙述）

见最终回复的「遗留风险与未验证项」一节与 `CHANGELOG-v2.3.0.md` 的同名小节。
