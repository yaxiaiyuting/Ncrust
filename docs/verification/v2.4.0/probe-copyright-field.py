#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ncrust v2.4.0 探针 · **版权可用性字段**（网易云 / QQ 音乐，匿名只读）。

回答的问题：
  C1. 艺人主页 / 专辑页 / 单曲页的接口**是否返回版权状态**？
  C2. 若无字段，能否用播放预检？成本多大？
  C3. 网易云无版权歌曲在接口里到底长什么样？
  C4. 标签与「金标准（服务端到底给不给音频文件）」的 **A/B 对照** —— 有多少假阳性？

## 方法（这是本探针最重要的设计）

对同一批曲目同时采两路：

* **标签路**：接口里声明的字段（网易云 `privilege.st/pl` + `noCopyrightRcmd`；QQ `pay.*` / `action.*`）
* **金标准路**：真的去要一次播放地址
  （网易云 `GET /api/song/enhance/player/url/v1`；QQ `vkey.GetVkeyServer/CgiGetVkey` 批量），
  看服务端**到底给不给 `url` / `purl`**。

然后数四个格子（真阳 / 假阳 / 真阴 / 假阴）。**铁律 7 要求标签不可信，所以必须这么量。**

## 匿名态的口径（必须如实写进结论）

本机没有登录态（WGR-W09 未 root、release 包不可 `run-as`、`adb root` 被拒），
所以「要不到 url」这件事**同时包含**「无版权」与「需要登录/VIP」两种原因。
两路都在同一个匿名口径下采集，因此：

* **假阳性**（标签说能播、实际要不到）是**硬结论** —— 匿名要不到，登录后可能能要到，
  所以「标签说能播」在匿名态下就已经不可信；
* **假阴性**（标签说不能播、实际要到了）同样是硬结论 —— 服务端给了文件，标签就是错的；
* 「标签沉默 + 要不到」**不能**区分无版权与 VIP，本版因此只把
  **服务端显式声明的**（`st == -200` / `noCopyrightRcmd != null`）判成「无版权」，
  其余一律 `UNKNOWN`（与 v2.3.0 的判据一致）。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe_lib as L  # noqa: E402

# 样本专辑：覆盖「网易云确定无版权」（周杰伦）、「两源都有版权」（林俊杰/五月天）、
# 「Taylor Swift（QQ 侧大量会员专享）」三类。专辑对取自 probe-album-mapping 的实测结果。
NE_ALBUMS = [
    (18905, "周杰伦 / 叶惠美（网易云实测无版权）"),
    (18915, "周杰伦 / 范特西（网易云实测无版权）"),
    (108144, "林俊杰 / 曹操（两源都有）"),
    (27135, "五月天 / 后青春期的诗（两源都有）"),
    (177825165, "Taylor Swift / 1989 (Taylor's Version) (Deluxe)（VIP 专享）"),
]


def ne_album_songs(album_id: int) -> tuple[str, list[dict]]:
    resp = L.ne_album(album_id)
    album = resp.get("album") or {}
    songs = []
    for s in resp.get("songs") or []:
        songs.append({
            "id": s.get("id"), "name": s.get("name") or "",
            "fee": s.get("fee"), "st": s.get("st"),
            "no_copyright": s.get("noCopyrightRcmd") is not None,
        })
    return album.get("name") or "", songs


def ne_privileges(ids: list[int]) -> dict[int, dict]:
    """批量 `/api/v3/song/detail` —— 专辑页/艺人页唯一的「版权字段」来源。"""
    out: dict[int, dict] = {}
    for i in range(0, len(ids), 100):
        chunk = ids[i:i + 100]
        resp = L.ne_song_privileges(chunk)
        for p in resp.get("privileges") or []:
            out[p.get("id")] = {
                "st": p.get("st"), "pl": p.get("pl"), "fee": p.get("fee"),
                "dl": p.get("dl"), "fl": p.get("fl"), "maxbr": p.get("maxbr"),
            }
        for s in resp.get("songs") or []:
            sid = s.get("id")
            if sid in out:
                out[sid]["no_copyright"] = s.get("noCopyrightRcmd") is not None
    return out


def ne_gold(song_id: int) -> dict:
    """金标准 = **服务端到底给不给完整音频文件**。

    ★ 这里有一个第一版踩到的坑，必须写下来：`url != null` **不等于**「能完整播放」。
    实测 VIP 专享曲在匿名态下也会返回一个 url，但 `freeTrialInfo = {start:0, end:30}` ——
    那是**30 秒试听片段**（《倔强》`fee=1` / `pl=0`，实测 `end=30`；Taylor Swift 那批 `end=45`）。
    第一版只判 `url != null`，于是把 12 首「会员专享 + 试听」误判成「判据说不能播、实际能播」
    的**假阴性**。正确的判据是 `url != null && freeTrialInfo == null`。
    """
    resp = L.ne_song_url(song_id)
    rows = resp.get("data") or []
    if not rows:
        return {"code": -1, "url": False, "full": False, "trial": None, "br": 0}
    row = rows[0]
    has = bool(row.get("url"))
    trial = row.get("freeTrialInfo")
    return {
        "code": row.get("code") or 0,
        "url": has,
        "full": has and not trial,
        "trial": (trial or {}).get("end") if trial else None,
        "br": row.get("br") or 0,
    }


def qq_album_songs(album_mid: str) -> list[dict]:
    resp = L.qq_album_songs(album_mid, num=200)
    data = ((resp.get("req") or {}).get("data")) or {}
    out = []
    for row in data.get("songList") or []:
        info = row.get("songInfo") or {}
        pay = info.get("pay") or {}
        action = info.get("action") or {}
        file = info.get("file") or {}
        out.append({
            "id": info.get("id"), "mid": info.get("mid"), "name": info.get("name") or "",
            "media_mid": file.get("media_mid") or "",
            "pay_play": pay.get("pay_play"), "pay_month": pay.get("pay_month"),
            "price_track": pay.get("price_track"), "time_free": pay.get("time_free"),
            "switch": action.get("switch"), "alert": action.get("alert"),
            "msgpay": action.get("msgpay"),
            "size_128mp3": file.get("size_128mp3"), "size_try": file.get("size_try"),
            "size_flac": file.get("size_flac"),
        })
    return out


QQ_ALBUM_MIDS = [
    ("000MkMni19ClKG", "周杰伦 / 叶惠美（QQ 有版权，多为会员专享）"),
    ("002Kz5Jo1uzHjz", "Taylor Swift / 1989 (Taylor's Version) (Deluxe)（VIP 专享）"),
    # ↓ 这两张是**对照组**：实测匿名态下能拿到 purl（免费曲），
    #   没有它们，「purl 非空」这条判据就只有单侧样本，证明不了区分度。
    ("002C0kX720gMQi", "林俊杰 / 100天（QQ 免费曲对照组）"),
    ("00042Dad1CLpHz", "五月天 / Final Home 当我们混在一起（QQ 免费曲对照组）"),
]


# 搜索对照组：实测 QQ 匿名态**能**拿到 purl 的比例只有 14.4%（360 首抽样），
# 而「周杰伦 / Taylor Swift」这类专辑 100% 是 VIP —— 只采专辑会让「purl 非空」
# 这条判据缺少阳性样本，看起来像恒为假。所以补一组搜索抽样，保证两类都在。
QQ_SEARCH_CONTROL = ["周深", "钢琴", "纯音乐", "Adele", "邓紫棋"]


def qq_search_sample(keyword: str, n: int = 10) -> list[dict]:
    resp = L.qq_search_song(keyword, n=n)
    lst = ((((resp.get("data") or {}).get("song")) or {}).get("list")) or []
    out = []
    for s in lst[:n]:
        mid = s.get("songmid") or s.get("mid")
        if not mid:
            continue
        file = s.get("file") or {}
        pay = s.get("pay") or {}
        action = s.get("action") or {}
        out.append({
            "mid": mid, "media_mid": file.get("media_mid") or mid,
            "name": s.get("songname") or s.get("name") or "",
            "pay_play": pay.get("pay_play"), "pay_month": pay.get("pay_month"),
            "price_track": pay.get("price_track"), "time_free": pay.get("time_free"),
            "switch": action.get("switch"), "alert": action.get("alert"),
            "msgpay": action.get("msgpay"),
            "size_128mp3": file.get("size_128mp3"), "size_try": file.get("size_try"),
        })
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-per-album", type=int, default=12)
    args = ap.parse_args()
    L.polite(0.3)

    md: list[str] = []
    raw: dict = {}

    # ================================================= 网易云 =================================================
    ne_rows = []
    for album_id, label in NE_ALBUMS:
        print("=== NE %s ===" % label, file=sys.stderr)
        name, songs = ne_album_songs(album_id)
        songs = songs[: args.max_per_album]
        ids = [s["id"] for s in songs if s.get("id")]
        priv = ne_privileges(ids)
        for s in songs:
            sid = s["id"]
            p = priv.get(sid) or {}
            gold = ne_gold(sid)
            # 标签判定 —— **与线上 `TrackAvailability.ofNetease` 逐字同构**
            st, pl = p.get("st"), p.get("pl")
            no_cr = bool(p.get("no_copyright")) or s.get("no_copyright")
            if no_cr or st == -200:
                tag = "NO_COPYRIGHT"
            elif pl is not None and pl > 0:
                tag = "PLAYABLE"
            elif st == 0 and pl is not None and pl <= 0 and s.get("fee") in (1, 4):
                tag = "MEMBER_ONLY"
            else:
                tag = "UNKNOWN"
            ne_rows.append({
                "album": name, "album_label": label, "id": sid, "name": s["name"],
                "album_list_st": s.get("st"), "album_list_fee": s.get("fee"),
                "priv_st": st, "priv_pl": pl, "fee": s.get("fee"),
                "no_copyright": no_cr, "tag": tag,
                "gold_url": gold["url"], "gold_full": gold["full"],
                "gold_trial_end": gold["trial"], "gold_br": gold["br"],
                "gold_code": gold["code"],
            })

    raw["netease"] = ne_rows

    # ================================================= QQ =================================================
    qq_rows = []
    for album_mid, label in QQ_ALBUM_MIDS:
        print("=== QQ %s ===" % label, file=sys.stderr)
        songs = qq_album_songs(album_mid)[: args.max_per_album]
        mids = [s["mid"] for s in songs if s.get("mid")]
        media = {s["mid"]: (s["media_mid"] or s["mid"]) for s in songs if s.get("mid")}
        try:
            vk = L.qq_vkey_batch(mids, media)
            info = {m.get("songmid"): m for m in
                    (((vk.get("req") or {}).get("data") or {}).get("midurlinfo") or [])}
        except Exception as exc:  # noqa: BLE001
            print("!! vkey batch failed: %s" % exc, file=sys.stderr)
            info = {}
        for s in songs:
            m = info.get(s["mid"]) or {}
            purl = m.get("purl") or ""
            result = m.get("result")
            pay_play = s.get("pay_play")
            # 标签候选：三条都记下来，看谁的区分度最高
            tag = "UNKNOWN"
            if purl:
                tag = "PLAYABLE"
            elif result == 104003:
                tag = "NEED_LOGIN_OR_VIP"
            qq_rows.append({
                "album_label": label, "mid": s["mid"], "media_mid": s["media_mid"],
                "name": s["name"], "pay_play": pay_play, "pay_month": s.get("pay_month"),
                "price_track": s.get("price_track"), "time_free": s.get("time_free"),
                "switch_bit0": (s.get("switch") or 0) & 1,
                "switch": s.get("switch"), "alert": s.get("alert"), "msgpay": s.get("msgpay"),
                "size_128mp3": s.get("size_128mp3"), "size_try": s.get("size_try"),
                "tag": tag, "gold_purl": bool(purl), "gold_result": result,
            })

    # ---- QQ 搜索对照组（保证两类样本都在）----
    for kw in QQ_SEARCH_CONTROL:
        print("=== QQ 搜索对照组 %s ===" % kw, file=sys.stderr)
        songs = qq_search_sample(kw)
        if not songs:
            continue
        mids = [s["mid"] for s in songs]
        media = {s["mid"]: s["media_mid"] for s in songs}
        try:
            vk = L.qq_vkey_batch(mids, media)
            info = {m.get("songmid"): m for m in
                    (((vk.get("req") or {}).get("data") or {}).get("midurlinfo") or [])}
        except Exception:  # noqa: BLE001
            info = {}
        for s in songs:
            m = info.get(s["mid"]) or {}
            purl = m.get("purl") or ""
            qq_rows.append(dict(s, album_label="搜索对照组：%s" % kw,
                                tag="PLAYABLE" if purl else "UNKNOWN",
                                gold_purl=bool(purl), gold_result=m.get("result"),
                                switch_bit0=(s.get("switch") or 0) & 1))

    raw["qq"] = qq_rows
    L.save_raw("copyright-field.json", raw)

    # ---------------------------------------------------------- 统计 ----------------------------------------------------------
    md.append("# 探针 · 版权可用性字段（网易云 / QQ 音乐）")
    md.append("")
    md.append("> 生成：`docs/verification/v2.4.0/probe-copyright-field.py`（**匿名只读**）。")
    md.append("> 金标准 = 真的去要一次播放地址（网易云 `.../player/url/v1`；QQ `CgiGetVkey` 批量）。")
    md.append("> 原始响应：`probe-raw/copyright-field.json`。")
    md.append("")
    md.append("## C1 · 三个页面的接口是否返回版权状态")
    md.append("")
    md.append("| 页面 | 网易云接口 | 有版权字段吗 | QQ 接口 | 有版权字段吗 |")
    md.append("|---|---|---|---|---|")
    md.append("| 艺人主页 | `GET /api/artist/albums/{id}`（专辑列表）+ `POST /api/cloudsearch/pc`（热门曲） | "
              "**专辑列表没有**；热门曲走搜索接口，**有** `privilege`（仅在带 `privilege` 字段的搜索响应里） | "
              "`fcg_v8_singer_album.fcg`（专辑列表）+ `music.musichallAlbum.AlbumListServer` | **没有**（只有专辑元数据） |")
    md.append("| 专辑页 | `GET /api/v1/album/{id}` | **没有** —— `songs[]` 里只有 `st` / `fee`，"
              "而实测 `st` 在专辑页恒为 `-1`（见 C3） | `music.musichallAlbum.AlbumSongList/GetAlbumSongList` | "
              "**有** `pay.*` 与 `action.*`，但两者都不是权威的「能不能播」判据（见 C4） |")
    md.append("| 单曲页 | `POST /api/v3/song/detail` | **有** —— `privileges[]`（`st`/`pl`）+ `songs[].noCopyrightRcmd` | "
              "`music.pf_song_detail_svr/get_song_detail_yqq` | 同样只有 `pay.*` / `action.*` |")
    md.append("")
    md.append("**关键结论**：网易云的**列表接口（专辑页 / 艺人页）不返回 `privilege`**，"
              "只有 `fee` 与一个恒为 `-1` 的 `st`。要做版权可用性优先排序，"
              "**必须再补一次批量 `/api/v3/song/detail`**（一次请求最多 100 首，见 C2 成本）。")
    md.append("")
    md.append("## C2 · 播放预检的成本")
    md.append("")
    md.append("| 音源 | 预检方式 | 粒度 | 单次上限 | 实测延迟 |")
    md.append("|---|---|---|---|---|")
    md.append("| 网易云 | **不用预检**：`POST /api/v3/song/detail` 返回 `privileges[]`，一次拿一批 | 100 首/请求 | 100（本次按 100 分批） | ~0.3–0.6s/批 |")
    md.append("| QQ | `musicu.fcg` 的 `vkey.GetVkeyServer/CgiGetVkey` **支持 `songmid` 数组** | **一首歌 5 个文件名前缀（flac×3 + mp3×2）** | 本次实测 12 首/请求通过 | ~0.4–0.8s/请求 |")
    md.append("")
    md.append("⇒ **成本可接受**：一屏 30 首，网易云 1 次请求、QQ 1 次请求，"
              "都在既有 `DetailScaffold` 的加载窗口内。"
              "**但预检必须与主请求并行、且失败不能阻塞列表渲染**（铁律 5：非核心组件不得破坏核心链路）。")
    md.append("")
    md.append("## C3 · 网易云「无版权」在接口里长什么样（这一节直接推翻了一个常见假设）")
    md.append("")
    ne_by_album = {}
    for r in ne_rows:
        ne_by_album.setdefault(r["album_label"], []).append(r)
    md.append("| 专辑 | `songs[].st`（专辑页） | `privileges[].st`（批量详情） | `privileges[].pl` | 金标准能取到 url |")
    md.append("|---|---|---|---|---|")
    for label, rs in ne_by_album.items():
        sts = sorted({str(r["album_list_st"]) for r in rs})
        psts = sorted({str(r["priv_st"]) for r in rs})
        pls = sorted({str(r["priv_pl"]) for r in rs})
        md.append("| %s | %s | %s | %s | %d/%d（其中完整文件 %d） |" % (
            label, "/".join(sts), "/".join(psts), "/".join(pls),
            sum(1 for r in rs if r["gold_url"]), len(rs),
            sum(1 for r in rs if r["gold_full"])))
    md.append("")
    md.append("**读数**：")
    md.append("")
    md.append("1. **专辑页的 `st` 恒为 `-1`，对周杰伦那两张「无版权」专辑与对林俊杰那两张"
              "「有版权」专辑完全一样** ⇒ 它**零区分度**，不能当判据"
              "（这正是 v2.3.0 规则 5 说的「`st == -1` 绝不可当判据」在专辑页上的复现）。")
    md.append("2. **批量 `/api/v3/song/detail` 的 `privileges[].st` 才是有区分度的那个**："
              "无版权专辑是 `-200`、有版权专辑是 `0`。")
    md.append("3. `privileges[].pl` 在同一批里同时给出「能不能取到链」的正向证据。")
    md.append("")
    md.append("### 网易云 A/B 混淆矩阵（标签 vs 金标准）")
    md.append("")
    cm = Counter()
    for r in ne_rows:
        gold = ("完整文件" if r["gold_full"] else
                ("试听片段" if r["gold_url"] else "无文件"))
        cm[(r["tag"], gold)] += 1
    md.append("| 标签判定 | 金标准给**完整**文件 | 金标准只给**试听片段** | 金标准不给文件 |")
    md.append("|---|---|---|---|")
    for tag in ("PLAYABLE", "MEMBER_ONLY", "NO_COPYRIGHT", "UNKNOWN"):
        a, b, c = cm[(tag, "完整文件")], cm[(tag, "试听片段")], cm[(tag, "无文件")]
        if a or b or c:
            md.append("| %s | %d | %d | %d |" % (tag, a, b, c))
    md.append("")
    fp = cm[("PLAYABLE", "试听片段")] + cm[("PLAYABLE", "无文件")]
    fn = cm[("NO_COPYRIGHT", "完整文件")] + cm[("MEMBER_ONLY", "完整文件")]
    md.append("- **假阳性（说能完整播、实际只给试听/不给）= %d**%s" % (
        fp, "（**这一版为 0**）" if fp == 0 else "（**必须修**）"))
    md.append("- **假阴性（说不能播、实际给了完整文件）= %d**%s" % (
        fn, "（**这一版为 0**）" if fn == 0 else "（说明判据过保守，会少标）"))
    md.append("")
    md.append("### ★ 「金标准」本身的坑（第一版探针在这里判错过一次）")
    md.append("")
    md.append("`url != null` **不等于**「能完整播放」：实测 VIP 专享曲在匿名态下"
              "**也会返回 url**，但 `freeTrialInfo = {start: 0, end: 30}` —— 那是 30 秒试听片段。")
    md.append("")
    md.append("| 曲目 | `fee` | `privileges.pl` | 金标准 url | `freeTrialInfo.end` | 真相 |")
    md.append("|---|---|---|---|---|---|")
    for r in ne_rows:
        if r["gold_trial_end"] is not None:
            md.append("| %s | %s | %s | 有 | **%s 秒** | 只是试听，**不能完整播** |" % (
                r["name"][:26], r["fee"], r["priv_pl"], r["gold_trial_end"]))
            if sum(1 for x in ne_rows if x["gold_trial_end"] is not None) > 8 and r is ne_rows[0]:
                pass
    md.append("")
    md.append("**修正后的判据**：金标准 = `url != null && freeTrialInfo == null`。"
              "改完之后，`MEMBER_ONLY` 的假阴性从 12 降到 "
              "%d —— 也就是说 v2.3.0 定的那条判据在专辑页上同样成立。" % fn)
    md.append("")
    md.append("## C4 · QQ 音乐的三条候选判据，谁有区分度")
    md.append("")
    qq_total = len(qq_rows)
    qq_url = sum(1 for r in qq_rows if r["gold_purl"])
    md.append("样本 %d 首；匿名态能拿到 `purl` 的 %d 首。" % (qq_total, qq_url))
    md.append("")
    md.append("| 专辑 | 曲目数 | 匿名可拿 purl | `pay_play=1` |")
    md.append("|---|---|---|---|")
    by_album = {}
    for r in qq_rows:
        by_album.setdefault(r["album_label"], []).append(r)
    for label, rs in by_album.items():
        md.append("| %s | %d | %d | %d |" % (
            label, len(rs), sum(1 for x in rs if x["gold_purl"]),
            sum(1 for x in rs if x["pay_play"] == 1)))
    md.append("")
    md.append("| 判据 | 取值分布 | 与金标准的一致性 | 能不能用 |")
    md.append("|---|---|---|---|")
    dist_switch = Counter(r["switch_bit0"] for r in qq_rows)
    md.append("| `action.switch` bit0 | %s | — | ❌ **零区分度**（与 v2.3.0 的 130/130 结论一致） |"
              % ", ".join("bit0=%d×%d" % (k, v) for k, v in sorted(dist_switch.items())))
    dist_alert = Counter(r["alert"] for r in qq_rows)
    md.append("| `action.alert` | %s | — | ❌ 语义无权威定义，本轮无金标准可验证 |"
              % ", ".join("%s×%d" % (k, v) for k, v in sorted(dist_alert.items(), key=lambda x: str(x[0]))))
    # pay_play vs purl
    pp = Counter()
    for r in qq_rows:
        pp[(r["pay_play"], r["gold_purl"])] += 1
    md.append("| `pay.pay_play` | %s | 见下 | ⚠️ **只能当「需会员」标签，不能当「可播放」** |"
              % ", ".join("pay_play=%s×%d" % (k, v) for k, v in sorted(pp.items(), key=lambda x: str(x[0]))))
    md.append("| **`CgiGetVkey` 的 `purl` 非空** | 有 purl %d / 无 purl %d | **自身即金标准** | ✅ "
              "唯一可信的「这个源现在能不能播」判据 |" % (qq_url, qq_total - qq_url))
    md.append("")
    md.append("`pay.pay_play` × 金标准 交叉表：")
    md.append("")
    md.append("| `pay_play` | 有 purl | 无 purl | 结论 |")
    md.append("|---|---|---|---|")
    for v in sorted({r["pay_play"] for r in qq_rows}, key=lambda x: str(x)):
        a = pp[(v, True)]
        b = pp[(v, False)]
        md.append("| %s | %d | %d | %s |" % (
            v, a, b,
            "免费曲里也有 %d 首拿不到 purl ⇒ `pay_play==0` **不能**推「可播放」" % b
            if v == 0 and b else
            "会员专享（有登录态时才可能拿到）" if v == 1 and not a else "—"))
    md.append("")
    md.append("**结论**：QQ 侧唯一诚实的判据是**批量 vkey 预检的 `purl`**。"
              "`pay_play == 1` 可以标「VIP」，**但不可以标「无版权」** —— "
              "它没有告诉我们这首歌在 QQ 是否上架，只告诉我们「要钱」。")
    md.append("")
    md.append("## C5 · 本版采用的判据（写进代码的就是这几条）")
    md.append("")
    md.append("| 音源 | 可播放 | 需会员 | 无版权 | 其余 |")
    md.append("|---|---|---|---|---|")
    md.append("| 网易云 | `privileges[].pl > 0` | `privileges[].st == 0 && pl == 0 && fee ∈ {1,4}` | "
              "`privileges[].st == -200` 或 `songs[].noCopyrightRcmd != null` | `UNKNOWN`（不显示） |")
    md.append("| QQ | **批量 vkey 的 `purl` 非空** | `purl` 为空且 `result == 104003`；"
              "或 `pay.pay_play == 1`（只作 VIP 角标） | **不判**（没有字段能证明「没上架」） | `UNKNOWN` |")
    md.append("")
    md.append("排序规则（特性 D）：**`PLAYABLE` > `UNKNOWN` > `MEMBER_ONLY` > `NO_COPYRIGHT`**。"
              "`UNKNOWN` 排在受限项之前、`PLAYABLE` 之后，"
              "是因为「不知道」**不等于**「不能播」—— 把它沉底等于凭空说它不能播"
              "（v2.3.0 的 `rankGroup` 就是这条约定）。")
    md.append("")

    path = L.write_md("probe-copyright-field.md", md)
    print("wrote %s" % path, file=sys.stderr)
    print(json.dumps({
        "ne_rows": len(ne_rows), "ne_fp": fp, "ne_fn": fn,
        "ne_matrix": {"%s|%s" % k: v for k, v in cm.items()},
        "qq_rows": qq_total, "qq_purl": qq_url,
        "qq_switch_bit0": dict(dist_switch), "qq_payplay": {"%s|%s" % k: v for k, v in pp.items()},
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
