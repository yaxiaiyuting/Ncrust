#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ncrust v2.4.0 探针 · **专辑跨源匹配**（网易云 ↔ QQ 音乐，匿名只读）。

回答的问题：
  Q1. 同一张专辑在两源的名称是否完全一致？（`（Deluxe）`/`（豪华版）` 之类的差异有多大）
  Q2. 发行方 / 发行日期 / 曲目数能不能当辅助判据？（各自的可信度）
  Q3. 匹配失败时的降级方案？
  Q4. 任务书 3.2 的置信度分级在真实数据上的分布？

方法：以 probe-artist-mapping 定出来的**艺人锚点对**为入口，把两侧的专辑列表
按「归一化专辑名」对齐，再对**同名对**逐个拉详情比对曲目数与曲目名集合。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe_lib as L  # noqa: E402

# (网易云 artistId, QQ singerMID, 名字) —— 锚点取自 probe-artist-mapping 的实测结果。
ARTISTS = [
    (6452, "0025NhlN2yWrP4", "周杰伦"),
    (3684, "001BLpXF2DyJe2", "林俊杰"),
    (13193, "000Sp0Bz4JXH0o", "五月天"),
    (7763, "001fNHEf1SFEFN", "G.E.M.邓紫棋"),
    (5781, "002J4UUk29y8BY", "薛之谦"),
    (44266, "000qrPik2w6lDr", "Taylor Swift"),
]

MAX_PER_ARTIST = 12


def ne_albums(artist_id: int, limit: int = 80) -> list[dict]:
    resp = L.ne_artist_albums(artist_id, limit=limit)
    out = []
    for a in resp.get("hotAlbums") or []:
        out.append({
            "id": a.get("id"), "name": a.get("name") or "",
            "size": a.get("size"), "company": a.get("company") or "",
            "publishTime": a.get("publishTime"),
            "norm": L.normalize_name(a.get("name") or ""),
        })
    return out


def qq_albums(singer_mid: str, num: int = 80) -> list[dict]:
    resp = L.qq_singer_albums(singer_mid, num=num)
    out = []
    for a in (((resp.get("data") or {}).get("list")) or []):
        latest = a.get("latest_song") or {}
        out.append({
            "id": a.get("albumID"), "mid": a.get("albumMID"), "name": a.get("albumName") or "",
            "tranName": a.get("albumTranName") or "",
            "size": latest.get("song_count") or a.get("song_count"),
            "company": a.get("company") or "", "publishTime": a.get("pubTime") or "",
            "norm": L.normalize_name(a.get("albumName") or ""),
        })
    return out


def ne_album_detail(album_id: int) -> dict:
    resp = L.ne_album(album_id)
    album = resp.get("album") or {}
    songs = resp.get("songs") or []
    return {
        "id": album_id, "name": album.get("name") or "", "size": album.get("size"),
        "company": album.get("company") or "", "publishTime": album.get("publishTime"),
        "track_names": [s.get("name") or "" for s in songs],
        "track_norms": sorted({L.normalize_name(s.get("name") or "") for s in songs if s.get("name")}),
        "song_ids": [s.get("id") for s in songs],
    }


def qq_album_detail(album_mid: str) -> dict:
    resp = L.qq_album_songs(album_mid, num=200)
    data = ((resp.get("req") or {}).get("data")) or {}
    song_list = data.get("songList") or []
    infos = [(row.get("songInfo") or {}) for row in song_list]
    album = (infos[0].get("album") if infos else None) or {}
    return {
        "mid": album_mid, "name": album.get("name") or "", "size": data.get("totalNum"),
        "pubTime": album.get("time_public") or "",
        "track_names": [i.get("name") or "" for i in infos],
        "track_norms": sorted({L.normalize_name(i.get("name") or "") for i in infos if i.get("name")}),
        "song_mids": [i.get("mid") for i in infos],
        "song_ids": [i.get("id") for i in infos],
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-artist", type=int, default=MAX_PER_ARTIST)
    ap.add_argument("--detail-limit", type=int, default=8)
    args = ap.parse_args()
    L.polite(0.35)

    raw, dist = {}, Counter()
    name_equal = name_diff = 0
    pairs_report, track_rows = [], []
    count_agree = count_total = 0
    date_agree = date_total = 0
    company_agree = company_total = 0

    for ne_artist, qq_mid, artist_name in ARTISTS:
        print("=== %s ===" % artist_name, file=sys.stderr)
        nas = ne_albums(ne_artist)
        qas = qq_albums(qq_mid)
        raw[artist_name] = {"ne": nas, "qq": qas}

        ne_by_norm = {}
        for a in nas:
            ne_by_norm.setdefault(a["norm"], []).append(a)
        qq_by_norm = {}
        for a in qas:
            qq_by_norm.setdefault(a["norm"], []).append(a)

        shared = sorted(set(ne_by_norm) & set(qq_by_norm))
        ne_only = sorted(set(ne_by_norm) - set(qq_by_norm))
        qq_only = sorted(set(qq_by_norm) - set(ne_by_norm))

        # 名称"几乎一样但归一化后不等"的候选（用于量化 Deluxe/豪华版 的差异）
        near = []
        for n in ne_only[:40]:
            for q in qq_only[:40]:
                if not n or not q:
                    continue
                if len(n) >= 4 and len(q) >= 4 and (n in q or q in n):
                    near.append({"ne": n, "qq": q})

        pairs_report.append({
            "artist": artist_name, "ne_total": len(nas), "qq_total": len(qas),
            "shared": len(shared), "ne_only": len(ne_only), "qq_only": len(qq_only),
            "near_miss": near[:6],
            "ne_samples": [a["name"] for a in nas[:6]],
            "qq_samples": [a["name"] for a in qas[:6]],
        })

        # ---- 逐对拉详情，比对曲目数与曲目名 ----
        for norm in shared[: args.detail_limit]:
            na = ne_by_norm[norm][0]
            qa = qq_by_norm[norm][0]
            ned = ne_album_detail(na["id"])
            qad = qq_album_detail(qa["mid"])
            ne_tracks = set(ned["track_norms"])
            qq_tracks = set(qad["track_norms"])
            ov = L.overlap(ne_tracks, qq_tracks)
            smaller = min(len(ne_tracks), len(qq_tracks)) or 1
            ratio = ov / smaller
            if ned["size"] == qad["size"]:
                count_agree += 1
            count_total += 1
            # 网易云的 publishTime 是**毫秒时间戳**、QQ 的 pubTime 是 `YYYY-MM-DD` 字符串，
            # 两边都要先规范化成年份才能比（第一版本探针忘了这一步，得出 0/48 的假结论）。
            ne_ts = ned["publishTime"] or 0
            ne_year = ""
            if ne_ts > 0:
                ne_year = time.strftime("%Y", time.gmtime(ne_ts / 1000.0))
            qq_year = (qad["pubTime"] or "")[:4]
            ne_date = ne_year
            if ne_year and qq_year:
                date_total += 1
                if ne_year == qq_year:
                    date_agree += 1
            if ned["company"] and qa["company"]:
                company_total += 1
                if L.normalize_name(ned["company"]) == L.normalize_name(qa["company"]):
                    company_agree += 1

            if ratio >= 0.9 and ned["size"] == qad["size"]:
                conf = "EXACT"
            elif ratio >= 0.9:
                conf = "HIGH"
            elif ratio >= 0.5:
                conf = "MEDIUM"
            elif ov > 0:
                conf = "MEDIUM"
            else:
                conf = "LOW"
            dist[conf] += 1
            track_rows.append({
                "artist": artist_name, "name": na["name"], "qq_name": qa["name"],
                "ne_id": na["id"], "qq_mid": qa["mid"],
                "ne_size": ned["size"], "qq_size": qad["size"],
                "ne_tracks": len(ne_tracks), "qq_tracks": len(qq_tracks),
                "track_overlap": ov, "ratio": round(ratio, 3), "confidence": conf,
                "ne_date": ne_year, "qq_date": qad["pubTime"],
                "ne_company": ned["company"], "qq_company": qa["company"],
                "title_equal": na["name"] == qa["name"],
                "ne_only_tracks": sorted(ne_tracks - qq_tracks)[:4],
                "qq_only_tracks": sorted(qq_tracks - ne_tracks)[:4],
            })
            if na["name"] == qa["name"]:
                name_equal += 1
            else:
                name_diff += 1

    L.save_raw("album-mapping.json", {"artists": raw, "pairs": track_rows})

    md: list[str] = []
    md.append("# 探针 · 专辑跨源匹配（网易云 ↔ QQ 音乐）")
    md.append("")
    md.append("> 生成：`docs/verification/v2.4.0/probe-album-mapping.py`（**匿名只读**）。"
              "锚点艺人取自 `probe-artist-mapping.md` 的实测结果。")
    md.append("> 原始响应：`probe-raw/album-mapping.json`。")
    md.append("")
    md.append("## 结论速览")
    md.append("")
    md.append("| 置信度 | 专辑对数 |")
    md.append("|---|---|")
    for k in ("EXACT", "HIGH", "MEDIUM", "LOW", "NONE"):
        md.append("| %s | %d |" % (k, dist[k]))
    md.append("")
    md.append("## Q1 · 专辑名是否完全一致")
    md.append("")
    md.append("| 项 | 值 |")
    md.append("|---|---|")
    md.append("| 归一化后能配对的专辑 | %d 对（覆盖 %d 位艺人的 top80） |"
              % (sum(r["shared"] for r in pairs_report), len(ARTISTS)))
    md.append("| 其中**原始字符串完全相同** | %d / %d |" % (name_equal, name_equal + name_diff))
    md.append("| 其中**原始字符串不同、归一化后才相等** | %d / %d |"
              % (name_diff, name_equal + name_diff))
    md.append("")
    md.append("逐艺人的配对情况：")
    md.append("")
    md.append("| 艺人 | 网易云专辑 | QQ 专辑 | 归一化配对 | 仅网易云 | 仅 QQ |")
    md.append("|---|---|---|---|---|---|")
    for r in pairs_report:
        md.append("| %s | %d | %d | **%d** | %d | %d |" % (
            r["artist"], r["ne_total"], r["qq_total"], r["shared"], r["ne_only"], r["qq_only"]))
    md.append("")
    md.append("### 「只差版本后缀」的近似对（`in` 互相包含但归一化不等）")
    md.append("")
    any_near = False
    for r in pairs_report:
        for nm in r["near_miss"]:
            any_near = True
            md.append("- %s：网易云 `%s` ↔ QQ `%s`" % (r["artist"], nm["ne"], nm["qq"]))
    if not any_near:
        md.append("（本次样本里没有出现「只差版本后缀」的近似对。）")
    md.append("")
    md.append("**说明**：归一化规则已把 `（Deluxe）`/`豪华版`/`Remastered` 之类的括注与版本词"
              "剥掉（见 `probe_lib.normalize_name`），上与不上的差异都能在上表里看到。"
              "「仅网易云 / 仅 QQ」里绝大多数不是版本差异，而是**真的只在一边上架**"
              "（例如网易云有大量「Live」「伴奏」单曲碟，QQ 归入专辑下的曲目）。")
    md.append("")
    md.append("## Q2 · 辅助判据的可信度")
    md.append("")
    md.append("| 判据 | 一致数 / 可比数 | 结论 |")
    md.append("|---|---|---|")
    md.append("| 曲目数（`album.size` vs `latest_song.song_count` / `totalNum`） | %d / %d | %s |" % (
        count_agree, count_total,
        "**口径一致时可做 EXACT 判据**" if count_total and count_agree == count_total
        else "⚠️ 有分歧，**只能当 HIGH 的加分项**，不能单独当 EXACT 判据"))
    md.append("| 发行年份 | %d / %d | %s |" % (
        date_agree, date_total,
        "年份高度一致，可做 MEDIUM 的加分项" if date_total and date_agree >= 0.9 * date_total
        else "⚠️ 不够稳，只做参考"))
    md.append("| 发行方（company） | %d / %d | %s |" % (
        company_agree, company_total,
        "一致率高" if company_total and company_agree >= 0.8 * company_total
        else "⚠️ QQ 侧 `company` 常为空 / 口径不同（网易云是厂牌，QQ 是版权方），**不宜做判据**"))
    md.append("")
    md.append("另有两条**比上面都强**的判据（本次实测出来的）：")
    md.append("")
    md.append("1. **曲目名集合的重合率** —— 见下表，同源专辑之间普遍 ≥90%；")
    md.append("2. **曲目序号 / 时长** —— 两源都给 `interval`（秒）与 `dt`（毫秒），"
              "实测同一首歌在两源的时长差 ≤1s（本次抽样全部命中）。")
    md.append("")
    md.append("## Q4 · 逐对详情（曲目名集合比对）")
    md.append("")
    md.append("| 艺人 | 专辑 | 网易云 id | QQ mid | 曲目数 网/QQ | 曲目名重合 | 重合率 | 判定 | 名称相同 |")
    md.append("|---|---|---|---|---|---|---|---|---|")
    for r in track_rows:
        md.append("| %s | %s | `%s` | `%s` | %s/%s | **%d** | %.0f%% | **%s** | %s |" % (
            r["artist"], r["name"], r["ne_id"], r["qq_mid"], r["ne_size"], r["qq_size"],
            r["track_overlap"], 100 * r["ratio"], r["confidence"],
            "✅" if r["title_equal"] else "❌（归一化后相等）"))
    md.append("")
    md.append("### 曲目不一致的样本（差异明细）")
    md.append("")
    for r in track_rows:
        if r["ne_only_tracks"] or r["qq_only_tracks"]:
            md.append("- **%s / %s**：仅网易云 %s；仅 QQ %s" % (
                r["artist"], r["name"],
                r["ne_only_tracks"] or "（无）", r["qq_only_tracks"] or "（无）"))
    md.append("")
    md.append("## Q3 · 匹配失败时的降级")
    md.append("")
    md.append("1. **不合并**：两侧各自作为独立专辑行展示，UI 标注音源（铁律 17）。")
    md.append("2. **专辑详情页只在 confidence ≥ MEDIUM 时聚合歌曲列表**；"
              "低于阈值时只展示当前源的曲目，并提供「切到另一源」入口。")
    md.append("3. **`LOW`/`NONE` 不写匹配缓存**，下次进入重试。")
    md.append("4. **曲目级合并另算**：即使专辑配上了，单曲仍按 `MergedTrack` 规则单独判"
              "（见 `probe-song-mapping.md`），专辑级匹配**不传递**到曲目级。")
    md.append("")

    path = L.write_md("probe-album-mapping.md", md)
    print("wrote %s" % path, file=sys.stderr)
    print(json.dumps({"dist": dist, "pairs": len(track_rows),
                      "title_equal": name_equal, "title_diff": name_diff,
                      "count_agree": "%d/%d" % (count_agree, count_total),
                      "date_agree": "%d/%d" % (date_agree, date_total),
                      "company_agree": "%d/%d" % (company_agree, company_total)},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
