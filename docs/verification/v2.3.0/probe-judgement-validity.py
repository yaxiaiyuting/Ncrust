#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v2.3.0 探针 ③b/①e：把两个候选判据的**准确率**钉死。

A. `originCoverType`（原唱 / 翻唱）的内部自洽性检验
   做法不依赖我当裁判：对每条 `originCoverType == 2`（翻唱）且带
   `originSongSimpleData.songId` 的记录，**回头去查它声称的那首「原曲」**，
   看那首的 `originCoverType` 是不是 1。
   - 若绝大多数是 1 ⇒ 服务端自己的 1/2 语义是自洽的（原唱 / 翻唱），可以直接用；
   - 若乱 ⇒ 这个字段不可信，特性 D 只能什么都不做。
   同时做**反向对照**：对 `originCoverType == 1` 的记录，检查它们带不带
   `originSongSimpleData`（若带，说明 1 不是「原唱」而是别的东西）。

B. 「可播放」判据 `privilege.pl > 0` 的扩大样本验证
   ①c 的 12/12 太薄。这里对 `pl > 0` / `st==0&pl==0` / `st==-1&pl==0` 三档
   各抽 30 首，仍以 `/api/song/enhance/player/url/v1` 的 standard 档为金标准。
"""
import json
import os
import random
import time
import urllib.parse
import urllib.request
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "probe-raw")
os.makedirs(RAW, exist_ok=True)

HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"),
    "Referer": "https://music.163.com/",
    "Content-Type": "application/x-www-form-urlencoded",
}

report = []


def say(line=""):
    print(line)
    report.append(line)


def post(url, data, tag=None):
    req = urllib.request.Request(url, data=urllib.parse.urlencode(data).encode(),
                                 headers=HEADERS, method="POST")
    with urllib.request.urlopen(req, timeout=20) as r:
        txt = r.read().decode("utf-8", "replace")
    if tag:
        with open(os.path.join(RAW, tag + ".json"), "w", encoding="utf-8") as f:
            f.write(txt)
    return json.loads(txt)


QUERIES = ["周杰伦", "陈奕迅", "五月天", "林俊杰", "邓紫棋", "薛之谦", "起风了",
           "晴天", "孤勇者", "夜空中最亮的星", "千本樱 伴奏", "翻唱", "DJ版",
           "Taylor Swift", "Adele", "米津玄師", "ヨルシカ", "钢琴 纯音乐",
           "remix", "伴奏", "李荣浩", "毛不易", "周深", "Beyond", "张国荣"]

say("=" * 78)
say("取样：%d 组关键词" % len(QUERIES))
say("=" * 78)
pool = []
seen = set()
for kw in QUERIES:
    try:
        r = post("https://music.163.com/api/cloudsearch/pc",
                 {"s": kw, "type": 1, "limit": 30, "offset": 0}, None)
    except Exception as e:  # noqa
        say("  %-18s EXC %s" % (kw, e))
        continue
    for s in (r.get("result") or {}).get("songs") or []:
        sid = s.get("id")
        if sid in seen:
            continue
        seen.add(sid)
        p = s.get("privilege") or {}
        oss = s.get("originSongSimpleData") or {}
        pool.append(dict(
            id=sid, name=s.get("name"), st=p.get("st"), pl=p.get("pl"),
            fee=s.get("fee"), cp=s.get("copyright"), oct=s.get("originCoverType"),
            ncr=s.get("noCopyrightRcmd"),
            oss_id=oss.get("songId"), oss_name=oss.get("name"),
            oss_artists=",".join(a.get("name", "") for a in (oss.get("artists") or [])),
            kw=kw,
        ))
    time.sleep(0.12)
say("去重后样本 = %d" % len(pool))
say("originCoverType 分布 = %s" % json.dumps(
    dict(Counter(x["oct"] for x in pool)), ensure_ascii=False))

# ------------------------------------------------------------------ A
say("")
say("=" * 78)
say("A. originCoverType 自洽性检验")
say("=" * 78)
oct2 = [x for x in pool if x["oct"] == 2]
oct2_with = [x for x in oct2 if x["oss_id"]]
oct1 = [x for x in pool if x["oct"] == 1]
oct1_with = [x for x in oct1 if x["oss_id"]]
say("oct==2（假设=翻唱） 共 %d 条，其中带 originSongSimpleData.songId 的 %d 条 (%.0f%%)" % (
    len(oct2), len(oct2_with), 100.0 * len(oct2_with) / max(1, len(oct2))))
say("oct==1（假设=原唱） 共 %d 条，其中带 originSongSimpleData 的 %d 条" % (
    len(oct1), len(oct1_with)))
if oct1_with:
    say("  ⚠ 若 >0，说明 1 并不等于「原唱」，需要重新解释：")
    for x in oct1_with[:10]:
        say("     %-28s oss=%s/%s" % ((x["name"] or "")[:28], x["oss_name"], x["oss_artists"]))

# 抽查 60 条「翻唱」声称的原曲，看它们的 oct 是不是 1
random.seed(20260926)
random.shuffle(oct2_with)
probe = oct2_with[:60]
say("")
say("抽查 %d 条「翻唱」声称的原曲（按 songId 回查）" % len(probe))
hit1 = hit_other = miss = 0
detail = Counter()
for x in probe:
    try:
        r = post("https://music.163.com/api/v3/song/detail",
                 {"c": json.dumps([{"id": x["oss_id"]}], ensure_ascii=False)}, None)
        songs = r.get("songs") or []
        if not songs:
            miss += 1
            detail["原曲查不到"] += 1
            continue
        o = songs[0]
        ooct = o.get("originCoverType")
        detail[str(ooct)] += 1
        if ooct == 1:
            hit1 += 1
        else:
            hit_other += 1
        say("  翻唱《%s》 → 原曲《%s》/ %s  oct=%s  id=%s" % (
            (x["name"] or "")[:22], (o.get("name") or "")[:22],
            ",".join(a.get("name", "") for a in (o.get("ar") or []))[:20],
            ooct, x["oss_id"]))
    except Exception as e:  # noqa
        miss += 1
        say("  EXC %s" % e)
    time.sleep(0.08)
say("")
say("原曲 oct==1（自洽）= %d；原曲 oct!=1（不自洽）= %d；查不到 = %d" % (hit1, hit_other, miss))
say("原曲 oct 分布 = %s" % json.dumps(dict(detail), ensure_ascii=False))
n = hit1 + hit_other
if n:
    say("自洽率 = %d/%d = %.1f%%" % (hit1, n, 100.0 * hit1 / n))

# ------------------------------------------------------------------ B
say("")
say("=" * 78)
say("B. 「可播放」判据的扩大样本验证")
say("=" * 78)
buckets = {}
for x in pool:
    key = "%s/%s" % (x["st"], "pl>0" if (x["pl"] or 0) > 0 else "pl=0")
    buckets.setdefault(key, []).append(x)
PER = 30
sample = []
for key, xs in sorted(buckets.items()):
    random.shuffle(xs)
    sample.extend(xs[:PER])
    say("分层 %-12s 共 %4d 条，抽 %d" % (key, len(xs), min(PER, len(xs))))

say("")
say("预检 %d 首（金标准 = player/url/v1 standard 档 url 非空）" % len(sample))
stat = {}
for x in sample:
    try:
        r = post("https://music.163.com/api/song/enhance/player/url/v1",
                 {"ids": "[%d]" % x["id"], "level": "standard", "encodeType": "aac"}, None)
        d = (r.get("data") or [{}])[0]
        playable = bool(d.get("url"))
    except Exception as e:  # noqa
        say("  EXC %s" % e)
        continue
    key = "%s/%s" % (x["st"], "pl>0" if (x["pl"] or 0) > 0 else "pl=0")
    st = stat.setdefault(key, dict(n=0, play=0, noplay=0, fees=Counter()))
    st["n"] += 1
    st["fees"][x["fee"]] += 1
    if playable:
        st["play"] += 1
    else:
        st["noplay"] += 1
    time.sleep(0.05)

say("")
say("| 分层 (st/pl) | 预检数 | 实测可播 | 实测不可播 | fee 分布 |")
say("|---|---|---|---|---|")
for key in sorted(stat):
    v = stat[key]
    say("| %s | %d | %d | %d | %s |" % (
        key, v["n"], v["play"], v["noplay"],
        json.dumps(dict(v["fees"]), ensure_ascii=False)))

say("")
tot_pl_pos = stat.get("0/pl>0", dict(n=0, play=0, noplay=0))
tot_pl_zero = stat.get("0/pl=0", dict(n=0, play=0, noplay=0))
tot_st_neg1 = stat.get("-1/pl=0", dict(n=0, play=0, noplay=0))
if tot_pl_pos["n"]:
    say("判据「pl > 0 ⇒ 可播放」：%d/%d 命中，假阳性 %d（这是关键 —— 假阳性会让用户点了才发现播不了）"
        % (tot_pl_pos["play"], tot_pl_pos["n"], tot_pl_pos["noplay"]))
if tot_pl_zero["n"]:
    say("分层 st==0 & pl==0：实测可播 %d / 不可播 %d ⇒ %s" % (
        tot_pl_zero["play"], tot_pl_zero["noplay"],
        "全部不可播，可作为「受限」判据" if tot_pl_zero["play"] == 0 else "混合，不可作为判据"))
if tot_st_neg1["n"]:
    say("分层 st==-1 & pl==0：实测可播 %d / 不可播 %d ⇒ %s" % (
        tot_st_neg1["play"], tot_st_neg1["noplay"],
        "混合，**不可**作为判据" if tot_st_neg1["play"] and tot_st_neg1["noplay"] else "单一，可用"))

out = os.path.join(RAW, "probe-judgement-validity.txt")
with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(report) + "\n")
print("\n[wrote] " + out)
