# APK 可复现性实测（v2.5.3）

> 这份文件记录一次**实测到的意外**，以及它对「HEAD == 产物源码」这条纪律的影响。
> 不美化：本版发布产物**不是逐字节可复现**的，但可以证明**内容可复现**。

## 现象

同一个提交、同一条命令、同一个工作区状态，跑两次 `./gradlew assembleRelease`，
产出的 APK **sha256 不同**：

| 构建 | 时间 | sha256 |
|---|---|---|
| 第一次（`clean` 全量，HEAD = `e407b1a`） | 09:12 | `f97f839f0f95f8d2dfc50efeb6fce369a1ba90f3f25061ab668024fa402476e1` |
| 第二次（增量，HEAD = `d7fefd1`） | 09:16 | `ff9cbe93b4993d25ab525dfa2c4098126b67d9759e7ba24e063c5ebd82eb7f2d` |

一开始的怀疑是签名时间戳（v1/v2 签名方案都带签名时间）。
但逐 zip 条目比对之后，结论**不是**签名：

```
条目数                : 471 vs 471
CRC 不同的条目数      : 1
  → META-INF/version-control-info.textproto
只在 A / 只在 B       : 无
```

**470 / 471 个条目的 CRC 完全相同** —— 全部代码、资源、R8 产物、签名块都一致，
唯一的差异是 `META-INF/version-control-info.textproto`。

## 这是什么东西

AGP 从某个版本起会在 APK 里写一个 `version-control-info.textproto`，
内容是**构建时工作区的 VCS 状态**（提交号 / 是否有未提交改动）。
两次构建之间 HEAD 从 `e407b1a` 变成了 `d7fefd1`（一个只改 `docs/` 的提交），
所以这个文件——**而且是只有这个文件**——变了。

也就是说：**APK 的可复现性被一个「记录构建上下文的元数据文件」破坏了**，
而不是被编译/打包过程破坏。

## 对「HEAD == 产物源码」这条纪律意味着什么

`AGENTS.md` 的原文是「校验工作区干净 && `HEAD` 的 `app/` 源码 == 产物源码」。
本版用**两条独立证据**满足它：

1. **源码零差异**：`git diff --stat e407b1a d7fefd1 -- app/` 输出为空
   —— 两次构建之间 `app/` 下**一个字节都没改**（只改了 `docs/`）；
2. **内容零差异**：两次 APK 的 470 个内容条目 **CRC 全部相同**
   （见上面的比对），唯一差异是那个 VCS 戳。

两条合起来 = 「产物里跑的东西来自同一份源码」。

## 本版怎么处置（避免自欺）

因为那个 VCS 戳**会记录 HEAD**，所以最自洽的做法是让发布的 APK
**记录它自己被 tag 的那个提交**：

1. 先把这份说明 + 全部证据提交（成为新的 HEAD）；
2. 在**这个** HEAD 上（工作区 clean）重新 `assembleRelease`；
3. 发布**这一份** APK —— 它自己的 `version-control-info.textproto` 里写的就是被 tag 的提交号；
4. 再打 tag。

顺序不能反：先打 tag 再构建也能得到「记录 tag 提交」的产物，
但那样 `./gradlew` 有任何失败都会留下一个指向不存在产物的 tag。

## 给下一个人的可操作结论

- **不要**拿 sha256 当「产物 == 源码」的判据 —— 同源码两次构建的 sha256 本来就可能不同。
  要判就判 **zip 条目 CRC**（本文件给出的脚本片段即可），或者直接比 `app/` 源码 diff。
- 想拿到稳定 sha256 的话，得把 `version-control-info` 关掉
  （`android.buildFeatures`/Gradle 属性层面）—— **本版没做**，
  因为它会丢掉「这个 APK 是哪个提交构建的」这条有用的溯源信息，
  而这条信息在排查线上问题时比一个稳定的哈希值更值钱。
- 本版**没有**在 build.gradle 里加任何 `--no-...` 之类的复现性开关，
  也没有为了「让 sha256 好看」而改流程。

复现脚本：

```bash
python3 - <<'PY'
import zipfile
a, b = "<apk1>", "<apk2>"
za, zb = zipfile.ZipFile(a), zipfile.ZipFile(b)
na = {i.filename: i.CRC for i in za.infolist()}
nb = {i.filename: i.CRC for i in zb.infolist()}
print("条目数:", len(na), len(nb))
print("CRC 不同:", [k for k in na if k in nb and na[k] != nb[k]])
print("只在 A:", sorted(set(na) - set(nb)))
print("只在 B:", sorted(set(nb) - set(na)))
PY
```
