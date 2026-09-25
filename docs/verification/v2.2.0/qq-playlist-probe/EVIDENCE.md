# EVIDENCE — v2.2.0 QQ 歌单探针的证据链

本文件记录**怎么跑出来的**：命令原文、时间、设备序列号、输出路径。
结论本身在 [PROBE.md](PROBE.md)；原始响应在 `probe-*.json` / `calls-*.json`。

---

## 1. 环境

| 项 | 值 |
|---|---|
| 主机工作区 | `/home/duanjb666/deepseek/ncrust-gpl` |
| 探针脚本 | `tools/probe-qq-playlist.py`、`tools/probe-qq-playlist-r3.py`、`tools/probe-qq-playlist-r4.py` |
| 输出目录 | `Ncrust-v220/docs/verification/v2.2.0/qq-playlist-probe/` |
| APK 源码基线 | `Ncrust` @ tag `v2.1.5-gpl`（`507c0e0`），用 `git worktree` 隔离到 `Ncrust-v220` |
| adb | `/home/duanjb666/Android/sdk/platform-tools/adb`（回退 `adb`） |
| 网络 | 主机可直连 `u.y.qq.com`（HTTPS） |

## 2. 设备

| 序列号 | 型号 | Android | SDK | ABI | root | 用途 |
|---|---|---|---|---|---|---|
| `3B15CD00GB700000` | PLC110 (OPPO) | **16** | 36 | arm64-v8a | KernelSU | 探针主设备 |
| `0715f763f54c023a` | SM-G9209 (Samsung S6) | **7.0** | 24 | armeabi-v7a | Magisk | 低端机 A/B（见真机验证文档） |
| `WVQ6R22124000968` | WGR_W09 (Huawei) | 12 | 31 | – | 无 | A/B 对照（无 root，不做探针） |

```
$ adb devices -l
0715f763f54c023a  device usb:3-1 product:zerofltctc model:SM_G9209  device:zerofltctc
3B15CD00GB700000  device usb:6-1 product:PLC110    model:PLC110    device:OP60EDL1
WVQ6R22124000968  device usb:4-2 product:WGR-W09   model:WGR_W09   device:HWWGR
```

## 3. 登录态的获取方式（**凭证从未离开内存**）

cookie 由 root 从应用私有目录读出，脚本内直接用于请求，**不打印、不落盘**：

```bash
adb -s 3B15CD00GB700000 shell \
  "su -c 'cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_qq_prefs.xml'"
```

脚本里只输出**字段名**与**长度**：

```
cookie 字段: psrf_musickey_createtime,qm_keyst,qqmusic_key,qqmusic_uin,uin（值不打印）
uin 长度=19  票据长度=163（都不打印值）
```

> 两台已 root 的设备（`3B15CD00GB700000` / `0715f763f54c023a`）上 `ncrust_qq_prefs` 的
> `uin` 哈希一致（`sha256(uin)[0:8] = 67847e11`）—— **同一个账号**。
> 因此「双账号切换」的真机验证**只能覆盖「切到未登录 / 失效态」**，不能覆盖两个真实账号互切；
> 这一缺口在发布说明里如实标注（见 §7）。

## 4. 四轮探针的完整命令与产出

### 第 1 轮（保留为「字段名踩空」的现场）

```bash
cd /home/duanjb666/deepseek/ncrust-gpl
python3 tools/probe-qq-playlist.py --serial 3B15CD00GB700000
```

- 结果：登录态自证通过；但**按参考实现的别名（`dissid`/`dirid`/`songnum`）解析全部拿到 `None`**。
- 原始产出：`round1/`（27 个文件，含 `logcat-probe-run.txt`、`calls.json`、`probe-*.json`）。
- **保留 round1 是有意的**：它是「为什么必须探针先行」的直接证据。

### 第 2 轮（修正字段名后重跑）

```bash
python3 tools/probe-qq-playlist.py --serial 3B15CD00GB700000
```

- 产出：`probe-run-20260925-133422.txt`、`calls-20260925-133422.json`、`probe-*.json`
- 定型结论：camelCase 列表字段、下划线详情字段、`dirId=201`、分页游标、`mid ≠ file.media_mid`、写接口存在性、登录态 A/B。

### 第 3 轮（补齐 5 个缺口）

```bash
python3 tools/probe-qq-playlist-r3.py --serial 3B15CD00GB700000
```

- 产出：`probe-r3-20260925-133536.txt`、`calls-r3-20260925-133536.json`
- 结论：目录分类字段、登录态失效返回码（`GetLoginUserInfo` = `1000`）、详情大页上限、游标单调性。

### 第 4 轮（收口）

```bash
python3 tools/probe-qq-playlist-r4.py --serial 3B15CD00GB700000
```

- 产出：`probe-r4-20260925-133617.txt`、`calls-r4-20260925-133617.json`
- 结论：非法 uin 白名单（全部 `80030` ⇒ `param.uin` 是查询主体）、榜单结构 `group[].toplist[]`、
  `dirId` 200–215 特殊目录表。

## 5. 请求规模

| 轮次 | 请求数 | `req.code == 0` | 写接口调用数 |
|---|---|---|---|
| 第 1 轮 | 33 | 14 | **0** |
| 第 2 轮 | 54 | 28 | **0** |
| 第 3 轮 | 33 | — | **0** |
| 第 4 轮 | 28 | — | **0** |

**四轮合计 148 次请求，全部为读接口；写接口（`AddPlaylist`/`DelPlaylist`/`AddSonglist`/`DelSonglist`/`FavPlaylist`/`CancelFavPlaylist`）一次都没有调用。**

## 6. 脱敏校验（可复核）

```bash
# 1) 落盘文件里不应出现 19 位 uin 原文
grep -rEo '\b[0-9]{9,}\b' docs/verification/v2.2.0/qq-playlist-probe/ | sort -u | head

# 2) 不应出现 cookie / 票据字段的值
grep -rn 'qqmusic_key=\|qm_keyst=\|authst=' docs/verification/v2.2.0/qq-playlist-probe/ | head

# 3) 脱敏占位符统计（证明确实执行了脱敏）
grep -roh '<redacted:[0-9]*>\|<text:[0-9]*>\|<url:[0-9]*>' \
  docs/verification/v2.2.0/qq-playlist-probe/ | sort | uniq -c | sort -rn | head
```

第 1 条的残留是公开目录 id（`songid`/`songmid`/`media_mid`/`dirId`/`tid`）与时间戳，
它们是身份字段映射结论的证据本体；**账号类标识（uin / 加密账号 / musicid）与内容类文本
（昵称 / 歌单名 / 歌名 / 歌手名）均已被占位符替换**。

## 7. 明确的未验证项（不掩盖）

| 项 | 状态 | 原因 |
|---|---|---|
| 500+ 首歌单端到端分页 | **未验证** | 该账号最大自建歌单 22 首；榜单最大 `totalNum=300`；造 500+ 歌单属写操作（本版禁止）。已验证的替代证据：大 `song_num`（500/1000/2000）被接受、逐页游标不重叠、越界优雅降级 |
| 两个**真实**QQ 账号互切 | **未验证** | 两台 root 设备登录的是同一个账号（uin 哈希一致）；已用「切到未登录/失效态」覆盖降级路径 |
| 「最近播放」 | **不可用** | 15 个候选 module/method 全部 `500003`/`40000`；`dirId 202/203` 语义未确认 |
| 收藏（他人）歌单的**非空**列表 | **未验证** | 该账号 `v_list` 为空（`total=0`）；接口本身已验证 `code=0`，且必须传 `encrypt_uin`（传裸 uin 得 `80050`） |
| 收藏歌单的 `dirid` 语义 | **未验证** | 同上，列表为空，拿不到样本 |

## 8. 输出文件清单

```
docs/verification/v2.2.0/qq-playlist-probe/
├── PROBE.md                          # 结论（接口清单/字段表/五大问题的明确回答）
├── EVIDENCE.md                       # 本文件：命令、时间、序列号、输出路径
├── probe-run-20260925-133422.txt     # 第 2 轮人读记录
├── probe-r3-20260925-133536.txt      # 第 3 轮
├── probe-r4-20260925-133617.txt      # 第 4 轮
├── probe-*.json                      # 脱敏后的请求/响应原文 + 字段形状
├── calls-*.json                      # 每轮请求台账
└── round1/                           # 第 1 轮原始证据（字段名踩空现场）
```
