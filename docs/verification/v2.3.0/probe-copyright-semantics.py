#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v2.3.0 探针 ①b：把 ① 里发现的字段的**取值语义**钉死。

① 的发现：`cloudsearch/pc` 的每个 song 对象里**自带 `privilege`**（不在 result.privileges），
且同层还有 `noCopyrightRcmd` / `originCoverType` / `originSongSimpleData` / `copyright` /
`resourceState` / `st` / `fee` / `ftype` / `rtype` / `version`。本脚本回答三个问题：

  Q1  `privilege.st` 的取值到底表示什么？（-200 / 0 / 其它）
  Q2  `noCopyrightRcmd` 是不是「无版权」的直接判据？它出现在哪些歌上？
  Q3  `originCoverType` / `originSongSimpleData` 能不能当「原唱 vs 翻唱」的判据？
      如果能，取值表是什么？准确率怎么验？
"""
import json
import os
import urllib.parse
import urllib.request
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "probe-raw")
os.makedirs(RAW, exist_ok=True)

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
HEADERS = {"User-Agent": UA, "Referer": "https://music.163.com/",
           "Content-Type": "application/x-www-form-urlencoded"}

report = []


def say(line=""):
    print(line)
    report.append(line)


def post(url, data, tag):
    req = urllib.request.Request(url, data=urllib.parse.urlencode(data).encode(),
                                 headers=HEADERS, method="POST")
    with urllib.request.urlopen(req, timeout=20) as r:
        txt = r.read().decode("utf-8", "replace")
    with open(os.path.join(RAW, tag + ".json"), "w", encoding="utf-8") as f:
        f.write(txt)
    return json.loads(txt)


def search(kw, tag, limit=30):
    return post("https://music.163.com/api/cloudsearch/pc",
                {"s": kw, "type": 1, "limit": limit, "offset": 0}, tag)


# 关键词集的选取原则：既要覆盖「官方原唱」，也要覆盖「明显的翻唱/伴奏/remix/DJ 版」，
# 否则永远看不到 originCoverType 的另一侧。
QUERIES = [
    ("晴天 周杰伦", "q-qingtian"),
    ("晴天 钢琴版", "q-qingtian-piano"),
    ("起风了 买辣椒也用券", "q-qifeng-orig"),
    ("起风了 翻唱", "q-qifeng-cover"),
    ("夜空中最亮的星 逃跑计划", "q-yekong"),
    ("富士山下 陈奕迅", "q-fuji"),
    ("千本樱 伴奏", "q-instrumental"),
    ("孤勇者 陈奕迅", "q-guyong"),
]

say("=" * 78)
say("Q1/Q2/Q3 采样：8 组关键词 × 每组的字段取值")
say("=" * 78)

st_hist = Counter()
cover_hist = Counter()
nocp_hits = []
priv_missing = 0
total = 0
rows = []

for kw, tag in QUERIES:
    r = search(kw, tag)
    songs = (r.get("result") or {}).get("songs") or []
    say("")
    say("### 关键词 = %s   （%d 条）" % (kw, len(songs)))
    say("    name | fee | st | pl | copyright | resourceState | originCoverType | "
        "noCopyrightRcmd | originSongSimpleData.name / .artists | version | ftype | rtype")
    for s in songs:
        p = s.get("privilege") or {}
        if not p:
            priv_missing += 1
        total += 1
        st = p.get("st")
        st_hist[st] += 1
        oct_ = s.get("originCoverType")
        cover_hist[oct_] += 1
        ncr = s.get("noCopyrightRcmd")
        if ncr:
            nocp_hits.append((s.get("name"), ncr))
        oss = s.get("originSongSimpleData") or {}
        oss_names = ",".join(a.get("name", "") for a in (oss.get("artists") or []))
        rows.append(dict(kw=kw, name=s.get("name"), fee=s.get("fee"), st=st, pl=p.get("pl"),
                         copyright=s.get("copyright"), resourceState=s.get("resourceState"),
                         originCoverType=oct_, ncr=ncr, oss_name=oss.get("name"),
                         oss_artists=oss_names, version=s.get("version"),
                         ftype=s.get("ftype"), rtype=s.get("rtype")))
        say("    %-28s fee=%-3s st=%-6s pl=%-8s cp=%-3s rs=%-4s oct=%-4s ncr=%-6s oss=%-20s ver=%-8s ft=%-4s rt=%-4s" % (
            (s.get("name") or "")[:28], s.get("fee"), st, p.get("pl"), s.get("copyright"),
            s.get("resourceState"), oct_,
            "有" if ncr else "-",
            ((oss.get("name") or "")[:14] + "/" + oss_names[:14]) if oss else "-",
            str(s.get("version") or "")[:8], s.get("ftype"), s.get("rtype")))

say("")
say("=" * 78)
say("汇总")
say("=" * 78)
say("总样本 = %d；privilege 缺失 = %d（%s）" % (
    total, priv_missing, "→ 每首歌都带 privilege" if priv_missing == 0 else "→ 有缺失，不能按行标注"))
say("privilege.st 取值分布: %s" % json.dumps(dict(st_hist), ensure_ascii=False))
say("originCoverType 取值分布: %s" % json.dumps({str(k): v for k, v in cover_hist.items()},
                                             ensure_ascii=False))
say("")
say("noCopyrightRcmd 非空的样本（共 %d 条）：" % len(nocp_hits))
for n, v in nocp_hits[:20]:
    say("  %-28s -> %s" % ((n or "")[:28], json.dumps(v, ensure_ascii=False)))
say("")
say("同 st 下的 name（看 st 是不是「能不能播」的判据）：")
by_st = {}
for row in rows:
    by_st.setdefault(row["st"], []).append(row["name"])
for st, names in sorted(by_st.items(), key=lambda kv: (kv[0] is None, kv[0])):
    say("  st=%-8s (%d 条) 例: %s" % (st, len(names), ", ".join((n or "")[:18] for n in names[:5])))

say("")
say("originCoverType 与 originSongSimpleData 的联合分布：")
by_oct = {}
for row in rows:
    by_oct.setdefault(row["originCoverType"], []).append(row)
for oct_, rs in sorted(by_oct.items(), key=lambda kv: (kv[0] is None, kv[0])):
    with_oss = sum(1 for x in rs if x["oss_name"] or x["oss_artists"])
    say("  originCoverType=%-5s 共 %3d 条，其中带 originSongSimpleData 的 %3d 条" % (
        oct_, len(rs), with_oss))
    for x in rs[:6]:
        say("      %-26s fee=%-3s st=%-6s oss=%s/%s" % (
            (x["name"] or "")[:26], x["fee"], x["st"],
            (x["oss_name"] or "")[:16], (x["oss_artists"] or "")[:20]))

say("")
say("=" * 78)
say("Q1 补充：st/pl 与「取链预检」的一致性（金标准对照）")
say("=" * 78)
# 金标准 = /api/song/enhance/player/url/v1 在 standard 档位能不能拿到 url。
# 对上面每条样本抽 1 首做预检，看 st/pl 能否预测它。
import time
samples = []
for row in rows:
    if row["st"] is None:
        continue
    samples.append(row)
# 按 st 分组抽样，保证两侧都覆盖到
picked = []
for st in sorted({r["st"] for r in samples}):
    picked.extend([r for r in samples if r["st"] == st][:3])
say("预检样本数 = %d" % len(picked))
id_by_name = {}
for kw, tag in QUERIES:
    r = json.load(open(os.path.join(RAW, tag + ".json"), encoding="utf-8"))
    for s in (r.get("result") or {}).get("songs") or []:
        id_by_name[(kw, s.get("name"))] = s.get("id")
agree = 0
checked = 0
for row in picked:
    sid = id_by_name.get((row["kw"], row["name"]))
    if not sid:
        continue
    try:
        t0 = time.time()
        r5 = post("https://music.163.com/api/song/enhance/player/url/v1",
                  {"ids": "[%d]" % sid, "level": "standard", "encodeType": "aac"},
                  "urlcheck-%d" % sid)
        dt = (time.time() - t0) * 1000
        d = (r5.get("data") or [{}])[0]
        playable = bool(d.get("url"))
        # 预测：st >= 0 且 pl > 0 ⇒ 可播
        predicted = (row["st"] is not None and row["st"] >= 0 and (row["pl"] or 0) > 0)
        checked += 1
        if predicted == playable:
            agree += 1
        say("  %-24s st=%-6s pl=%-8s 预测可播=%-5s 实测可播=%-5s code=%-4s fee=%-4s level=%-8s %.0fms" % (
            (row["name"] or "")[:24], row["st"], row["pl"], predicted, playable,
            r5.get("code"), d.get("fee"), d.get("level"), dt))
    except Exception as e:  # noqa
        say("  %-24s EXC %s" % ((row["name"] or "")[:24], e))
say("")
say("st/pl 预测 vs 取链预检 一致率 = %d/%d" % (agree, checked))

out = os.path.join(RAW, "probe-copyright-semantics.txt")
with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(report) + "\n")
print("\n[wrote] " + out)
