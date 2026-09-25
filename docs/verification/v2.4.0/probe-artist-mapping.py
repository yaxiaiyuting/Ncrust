#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ncrust v2.4.0 探针 · **艺人跨源匹配**（网易云 ↔ QQ 音乐，匿名只读）。

回答的问题（PROBE-SUMMARY 要求）：
  P1. 两源的艺人 ID 体系是否互通？（有没有共同的键）
  P2. 有没有别名表 / 拼音 / 英文名可用于匹配？
  P3. 同名艺人（李健 / 周杰伦 / 邓紫棋）如何区分？专辑列表重合度够不够？
  P4. 匹配失败时的降级方案是什么？
  P5. 依任务书的置信度分级，真实数据上的**分布**是多少？
  P6. **朴素同名锚定**与**专辑重合校验**两种算法差多少？（第 2 版补的，见下）

## 为什么本脚本有「朴素」与「校验」两套算法

第一版探针用「归一化名称完全相等」挑锚点，结果在 **邓紫棋** 上全军覆没：
网易云的 `邓紫棋`(62017015, albumSize=1) 是个仿冒号，真身叫 `G.E.M.邓紫棋`(7763, 58 张)；
QQ 侧根本没有裸 `邓紫棋`，只有 `G.E.M.邓紫棋`(13948, 68 张)。
朴素算法给出 `NONE`，而**正确答案是 HIGH**。

所以探针保留两套并逐条对照 —— 朴素算法的那一列就是**铁律 17 的反面教材**：
「名字对了」不等于「是同一个人」。线上算法必须是
**召回（名称包含）→ 校验（专辑列表重合）→ 分级**，而不是一次字符串比较。

用法： python3 probe-artist-mapping.py [--max-albums 80] [--max-candidates 3]
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe_lib as L  # noqa: E402

# 样本：故意混入「同名歧义严重」与「华语/欧美」两类。
ARTISTS = [
    ("周杰伦", "华语·网易云无版权；网易云 3 个同名字号"),
    ("林俊杰", "华语·两源都有版权；两源各有 2 个同名字号"),
    ("五月天", "华语乐队"),
    ("陈奕迅", "华语·同名少"),
    ("邓紫棋", "华语·**艺名与真名不同**（真身 G.E.M.邓紫棋）"),
    ("李健", "华语·同名歧义严重（QQ 同名字号 9 个）"),
    ("周深", "华语·新生代"),
    ("薛之谦", "华语"),
    ("Taylor Swift", "欧美·英文名"),
    ("Adele", "欧美·单名"),
    ("张学友", "华语·老牌"),
]

MIN_OVERLAP_HIGH = 3
MIN_OVERLAP_MEDIUM = 1


# --------------------------------------------------------------- 响应解析 ----

def ne_artists(keyword: str, limit: int = 20) -> list[dict]:
    resp = L.ne_search(keyword, 100, limit=limit)
    return [{
        "id": a.get("id"), "name": a.get("name") or "",
        "alias": a.get("alias") or [], "albumSize": a.get("albumSize") or 0,
        "musicSize": a.get("musicSize") or 0,
    } for a in (((resp.get("result") or {}).get("artists")) or [])]


def qq_artists(keyword: str, n: int = 20) -> list[dict]:
    resp = L.qq_search_singer(keyword, n=n)
    lst = (((resp.get("data") or {}).get("singer") or {}).get("list")) or []
    return [{
        "id": s.get("singerID"), "mid": s.get("singerMID"), "name": s.get("singerName") or "",
        "albumNum": s.get("albumNum") or 0, "songNum": s.get("songNum") or 0,
    } for s in lst]


def ne_albums(artist_id: int, limit: int) -> list[dict]:
    resp = L.ne_artist_albums(artist_id, limit=limit)
    out = []
    for a in resp.get("hotAlbums") or []:
        out.append({"id": a.get("id"), "name": a.get("name") or "",
                    "size": a.get("size"), "norm": L.normalize_name(a.get("name") or "")})
    return out


def qq_albums(singer_mid: str, num: int) -> list[dict]:
    resp = L.qq_singer_albums(singer_mid, num=num)
    out = []
    for a in (((resp.get("data") or {}).get("list")) or []):
        latest = a.get("latest_song") or {}
        out.append({"id": a.get("albumID"), "mid": a.get("albumMID"),
                    "name": a.get("albumName") or "",
                    "size": latest.get("song_count") or a.get("song_count"),
                    "norm": L.normalize_name(a.get("albumName") or "")})
    return out


# ------------------------------------------------------------- 匹配算法 ----

def recall(anchor_name: str, candidates: list[dict]) -> list[dict]:
    """名称**包含**召回（不是相等）—— 这是为了 `邓紫棋` ↔ `G.E.M.邓紫棋` 这类艺名。

    包含关系只做召回，**不做判定**：`周杰伦` 会召回 `周杰伦jay` / `周杰伦.` / `周杰伦♚`，
    真正的区分交给下一步的专辑列表重合。
    """
    key = L.normalize_name(anchor_name)
    if not key:
        return []
    out = []
    for c in candidates:
        n = L.normalize_name(c["name"])
        if not n:
            continue
        if n == key or key in n or n in key:
            out.append(c)
    return out


def grade(ov: int, unique: bool, anchor_set: set, cand_set: set) -> tuple[str, str]:
    if ov >= MIN_OVERLAP_HIGH:
        smaller = min(len(anchor_set), len(cand_set))
        if unique and smaller and ov >= 0.5 * smaller:
            return "EXACT", "唯一同名候选 + 专辑重合 %d 张（占较小侧 %.0f%%）" % (
                ov, 100.0 * ov / smaller)
        return "HIGH", "同名 + 专辑重合 %d 张" % ov
    if ov >= MIN_OVERLAP_MEDIUM:
        return "MEDIUM", "同名 + 专辑重合 %d 张" % ov
    return "LOW", "仅同名（专辑零重合）"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-albums", type=int, default=80)
    ap.add_argument("--max-candidates", type=int, default=3)
    args = ap.parse_args()
    L.polite(0.35)

    rows, raw = [], {}
    dist = Counter()
    naive_dist = Counter()
    traps = []

    for keyword, note in ARTISTS:
        print("=== %s (%s) ===" % (keyword, note), file=sys.stderr)
        ne_list = ne_artists(keyword)
        qq_list = qq_artists(keyword)
        raw[keyword] = {"ne_artists": ne_list, "qq_artists": qq_list}

        # ---- 朴素算法（第一版的反面教材）：归一化名称**完全相等** ----
        nk = L.normalize_name(keyword)
        naive_ne = [a for a in ne_list if L.normalize_name(a["name"]) == nk]
        naive_qq = [a for a in qq_list if L.normalize_name(a["name"]) == nk]
        if naive_ne and naive_qq:
            n_ne = {a["norm"] for a in ne_albums(naive_ne[0]["id"], args.max_albums)}
            n_qq = {a["norm"] for a in qq_albums(naive_qq[0]["mid"], args.max_albums)}
            naive_conf, _ = grade(L.overlap(n_ne, n_qq),
                                  len(naive_ne) == 1 and len(naive_qq) == 1, n_ne, n_qq)
        else:
            naive_conf = "NONE"
        naive_dist[naive_conf] += 1

        # ---- 锚点选择：名称包含召回里「专辑数最多」的那个（模拟用户点搜索结果第一名）----
        ne_recall = recall(keyword, ne_list) or ne_list[:1]
        ne_anchor = max(ne_recall, key=lambda a: a["albumSize"])
        ne_set = {a["norm"] for a in ne_albums(ne_anchor["id"], args.max_albums) if a["norm"]}

        qq_recall = [c for c in recall(keyword, qq_list) if c["albumNum"] >= 1]
        qq_recall.sort(key=lambda c: -c["albumNum"])
        tried, best = [], None
        for cand in qq_recall[: args.max_candidates]:
            cset = {a["norm"] for a in qq_albums(cand["mid"], args.max_albums) if a["norm"]}
            ov = L.overlap(ne_set, cset)
            tried.append({"name": cand["name"], "mid": cand["mid"], "albumNum": cand["albumNum"],
                          "albums_fetched": len(cset), "overlap": ov})
            if best is None or ov > best[1]:
                best = (cand, ov, cset)
            if ov >= MIN_OVERLAP_HIGH:
                break

        if best is None:
            rows.append({"kw": keyword, "note": note, "ne_id": ne_anchor["id"],
                         "ne_name": ne_anchor["name"], "qq_mid": None, "qq_name": None,
                         "ne_albums": len(ne_set), "qq_albums": 0, "overlap": 0,
                         "confidence": "NONE", "reason": "QQ 侧名称包含召回为空",
                         "naive": naive_conf, "tried": tried,
                         "ne_sample": [], "qq_sample": [], "ne_only": [], "qq_only": []})
            dist["NONE"] += 1
            continue

        cand, ov, cset = best
        unique = len([c for c in qq_recall if c["albumNum"] == cand["albumNum"]]) == 1
        conf, reason = grade(ov, unique, ne_set, cset)
        dist[conf] += 1

        if naive_conf != conf:
            traps.append({
                "kw": keyword, "naive": naive_conf, "verified": conf,
                "why": "朴素同名锚到「%s」；包含召回 + 专辑重合锚到「%s」(%s)"
                       % (naive_ne[0]["name"] if naive_ne else "-", cand["name"], cand["mid"]),
            })

        rows.append({
            "kw": keyword, "note": note,
            "ne_id": ne_anchor["id"], "ne_name": ne_anchor["name"],
            "qq_mid": cand["mid"], "qq_name": cand["name"],
            "ne_albums": len(ne_set), "qq_albums": len(cset), "overlap": ov,
            "confidence": conf, "reason": reason, "naive": naive_conf, "tried": tried,
            "ne_sample": sorted(ne_set)[:6], "qq_sample": sorted(cset)[:6],
            "ne_only": sorted(ne_set - cset)[:6], "qq_only": sorted(cset - ne_set)[:6],
        })
        raw[keyword]["verdict"] = rows[-1]

    L.save_raw("artist-mapping.json", raw)

    md: list[str] = []
    md.append("# 探针 · 艺人跨源匹配（网易云 ↔ QQ 音乐）")
    md.append("")
    md.append("> 生成：`docs/verification/v2.4.0/probe-artist-mapping.py`（**匿名只读**）。")
    md.append("> 无登录态可用：真机 WGR-W09 未 root（`su` 不存在）、release 包不可 `run-as`、"
              "`adb root` 被拒（production build）。匿名态对**艺人/专辑/单曲的 id、名称、"
              "曲目列表**没有任何影响（这些是公开目录数据），但会影响 `purl`（播放地址）——"
              "那部分单独在 `probe-copyright-field.md` 里做 A/B。")
    md.append("> 原始响应：`probe-raw/artist-mapping.json`。")
    md.append("")
    md.append("## 结论速览")
    md.append("")
    md.append("| 置信度 | 校验算法（本版要实现的） | 朴素同名算法（反面教材） |")
    md.append("|---|---|---|")
    for k in ("EXACT", "HIGH", "MEDIUM", "LOW", "NONE"):
        md.append("| %s | %d | %d |" % (k, dist[k], naive_dist[k]))
    md.append("")
    hit = dist["EXACT"] + dist["HIGH"] + dist["MEDIUM"]
    md.append("**可合并率（confidence ≥ MEDIUM）= %d/%d = %.0f%%**；"
              "其中 ≥HIGH = %d/%d = %.0f%%。" % (
                  hit, len(ARTISTS), 100.0 * hit / len(ARTISTS),
                  dist["EXACT"] + dist["HIGH"], len(ARTISTS),
                  100.0 * (dist["EXACT"] + dist["HIGH"]) / len(ARTISTS)))
    md.append("")
    md.append("## 逐艺人实测")
    md.append("")
    md.append("| 艺人 | 标注 | 网易云锚点 | QQ 锚点 | 网易云专辑 | QQ 专辑 | 重合 | 校验判定 | 朴素判定 |")
    md.append("|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        md.append("| %s | %s | `%s` %s | `%s` %s | %d | %d | **%d** | **%s** | %s |" % (
            r["kw"], r["note"], r["ne_id"], r["ne_name"], r["qq_mid"] or "-", r["qq_name"] or "-",
            r["ne_albums"], r["qq_albums"], r["overlap"], r["confidence"], r["naive"]))
    md.append("")
    md.append("### 判定依据（逐条，可追溯）")
    md.append("")
    for r in rows:
        md.append("- **%s** → `%s`：%s" % (r["kw"], r["confidence"], r["reason"]))
        for t in r["tried"]:
            md.append("  - 候选 `%s` (%s, albumNum=%s)：拉回 %d 张专辑，与网易云重合 **%d**"
                      % (t["name"], t["mid"], t["albumNum"], t["albums_fetched"], t["overlap"]))
    md.append("")
    md.append("## P6 · 两种算法的差异（**这一段是本探针最重要的产出**）")
    md.append("")
    if traps:
        md.append("| 艺人 | 朴素算法 | 校验算法 | 为什么不同 |")
        md.append("|---|---|---|---|")
        for t in traps:
            md.append("| %s | **%s** | **%s** | %s |" % (t["kw"], t["naive"], t["verified"], t["why"]))
    else:
        md.append("（本次样本里两种算法结论一致。）")
    md.append("")
    md.append("**教训（写进 AGENTS.md 的铁律 17 依据）**："
              "「名字对了」不等于「是同一个人」。邓紫棋那一行里，朴素算法把网易云的"
              "**仿冒号** `邓紫棋`(62017015, 1 张专辑) 当成锚点，于是两边都配不上；"
              "而真身 `G.E.M.邓紫棋`(7763, 58 张) 在两边都在。"
              "所以线上必须是「**召回 → 校验 → 分级**」三步："
              "召回用包含关系、判定用专辑列表重合 —— 名字只用来召回，绝不用来判定。")
    md.append("")
    md.append("## P1 · 两源 ID 体系是否互通")
    md.append("")
    md.append("**不互通，且没有任何共同键。** 实测证据：")
    md.append("")
    md.append("| 事实 | 实测值 |")
    md.append("|---|---|")
    md.append("| 网易云周杰伦 | `id=6452`（十进制） |")
    md.append("| QQ 周杰伦 | `singerID=4558` / `singerMID=0025NhlN2yWrP4`（base62 字符串） |")
    md.append("| 两源响应里有没有对方的 id | **没有** —— `/api/artist/albums/6452` 与 "
              "`fcg_v8_singer_album.fcg?singermid=0025NhlN2yWrP4` 的字段集合完全不相交 |")
    md.append("| 有没有 ISRC / 指纹 / 共享艺人码 | **没有**：两源的艺人接口都不返回 ISRC |")
    md.append("| QQ 的 `singerID` 能不能直接当网易云 id | **不能**：实测同一艺人 `6452 ≠ 4558`，"
              "两个编号空间各自独立 |")
    md.append("")
    md.append("⇒ 跨源艺人身份**只能靠可观测属性**匹配（名称 + 专辑列表重合度）。"
              "它是启发式而不是权威事实 —— 这正是必须做置信度分级、且低于阈值不合并的原因。")
    md.append("")
    md.append("## P2 · 可用于匹配的属性")
    md.append("")
    md.append("| 属性 | 网易云 | QQ 音乐 | 可用于匹配？ |")
    md.append("|---|---|---|---|")
    md.append("| 艺名 | `name` | `singerName` | ✅ **召回**用 |")
    md.append("| 别名/英文名 | **`alias[]`**（周杰伦 = `['Jay Chou','周董']`） | "
              "艺人搜索**不返回别名**（`singerTransName` 只在专辑搜索里） | ⚠️ 单边属性，"
              "**不能**做对称判据。反例：网易云 `Jay`(122200643) 的 `alias` 同时含"
              "周杰伦/林俊杰/权志龙/章若楠 —— 拿别名判定会一次错配四个人 |")
    md.append("| 繁体/异体名 | 归一化后仍不同（周杰伦 ≠ 周杰倫） | QQ 同时有 `周杰伦`(4558, 43 张) "
              "与 `周杰倫`(23063564, 1 张，疑似仿冒) | ⚠️ 需要简繁归一化，**本版不做**（见 P4） |")
    md.append("| 专辑数 | `albumSize` | `albumNum` | ⚠️ 口径不同（周杰伦：41 vs 43），"
              "只用于**候选排序**，不用于判定 |")
    md.append("| 歌曲数 | `musicSize` | `songNum` | ⚠️ 口径差异更大（568 vs 1012，"
              "QQ 含伴奏/多版本） |")
    md.append("| 粉丝数 | 艺人搜索**不返回** | 艺人搜索**不返回** | ❌ |")
    md.append("")
    md.append("## P3 · 同名艺人如何区分")
    md.append("")
    md.append("实测的同名分布（匿名搜索 top20 内，归一化名称相等）：")
    md.append("")
    md.append("| 艺人 | 网易云同名字号 | QQ 同名字号 | 区分手段 |")
    md.append("|---|---|---|---|")
    for kw, note in ARTISTS:
        ne_dupes = len([a for a in raw[kw]["ne_artists"]
                        if L.normalize_name(a["name"]) == L.normalize_name(kw)])
        qq_dupes = len([a for a in raw[kw]["qq_artists"]
                        if L.normalize_name(a["name"]) == L.normalize_name(kw)])
        md.append("| %s | %d | %d | 专辑数排序 + 专辑列表重合校验 |" % (kw, ne_dupes, qq_dupes))
    md.append("")
    md.append("**结论**：靠「名称」永远分不开（周杰伦在网易云有 3 个同名字号、"
              "`李健` 在 QQ 有 9 个）；靠「粉丝数」不行（接口不返回）。"
              "唯一在实测中稳定的是**专辑列表重合度**：真身之间重合 9~63 张，"
              "仿冒号之间重合 0 张。")
    md.append("")
    md.append("## P4 · 匹配失败时的降级")
    md.append("")
    md.append("1. **不合并**（铁律 17）：两侧各自成组，UI 上标注音源，用户自己选。")
    md.append("2. **不做「猜网易云」**：合并失败时的默认音源由**可播放性**决定，"
              "不由 id 空间决定。")
    md.append("3. **不缓存失败**：`NONE` / `LOW` 不写匹配缓存，下次进页面重试。")
    md.append("4. **保留人工入口**：艺人页可切「只看网易云 / 只看 QQ / 双源」。")
    md.append("5. **繁简/别名不做隐式归一**：`周杰伦` 与 `周杰倫` 在本版**视为两个艺人**"
              "（宁可不合并，也不冒错配风险）。")
    md.append("")
    md.append("## P5 · 分级分布")
    md.append("")
    md.append("见「结论速览」。样本 %d 位艺人，覆盖华语/欧美、有版权/无版权、"
              "同名歧义/无歧义、艺名≠真名四类。" % len(ARTISTS))
    md.append("")
    md.append("## 明细样本（归一化后的专辑名，前 6 条）")
    md.append("")
    for r in rows:
        md.append("### %s — %s" % (r["kw"], r["confidence"]))
        md.append("")
        md.append("- 网易云锚点：`%s` %s（%d 张）" % (r["ne_id"], r["ne_name"], r["ne_albums"]))
        md.append("- QQ 锚点：`%s` %s（%d 张）" % (r["qq_mid"], r["qq_name"], r["qq_albums"]))
        md.append("- 两侧都有（归一化）：%s" % (", ".join(r["ne_sample"]) or "（无）"))
        md.append("- 仅网易云：%s" % (", ".join(r.get("ne_only") or []) or "（无）"))
        md.append("- 仅 QQ：%s" % (", ".join(r.get("qq_only") or []) or "（无）"))
        md.append("")

    path = L.write_md("probe-artist-mapping.md", md)
    print("wrote %s" % path, file=sys.stderr)
    print(json.dumps({"verified": dist, "naive": naive_dist, "traps": len(traps)},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
