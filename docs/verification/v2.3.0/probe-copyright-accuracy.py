#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v2.3.0 探针 ①c：把「搜索阶段能不能诚实标注可播放」这件事**量化**。

背景：①b 得到 `privilege.st` 的三个取值（0 / -1 / -200）与 `pl`（可播最高码率）。
小样本（7 首）显示 `st >= 0 且 pl > 0` 预测「可播」的一致率只有 5/7 —— 不够。
本脚本用**更大样本**测三套候选判据的准确率，结论直接决定特性 C 是「直接标注」
还是「降级成播放失败时提示」：

  判据 A: st >= 0 且 pl > 0                       （宽）
  判据 B: st == 0 且 pl > 0                       （严）
  判据 C: st == -200 或 noCopyrightRcmd != null   （只判「确定无版权」）

金标准 = `/api/song/enhance/player/url/v1` 在 standard 档位能否拿到非空 url
（与 App 的 `SongUrlFetcher` 同一条路）。匿名请求，不改任何远端状态。
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
    if tag:
        with open(os.path.join(RAW, tag + ".json"), "w", encoding="utf-8") as f:
            f.write(txt)
    return json.loads(txt)


# 取样关键词：故意混入「官方原唱」「大热翻唱」「伴奏」「冷门」四类，
# 否则样本会全落在 st=0/pl>0 那一档，测不出判据的假阳性。
QUERIES = [
    "周杰伦", "陈奕迅", "五月天", "林俊杰", "邓紫棋", "薛之谦",
    "起风了", "晴天", "孤勇者", "夜空中最亮的星", "千本樱 伴奏",
    "Taylor Swift", "Adele", "米津玄師", "ヨルシカ", "钢琴 纯音乐",
    "DJ版", "翻唱", "remix", "伴奏",
]

say("=" * 78)
say("取样：%d 组关键词，每组 30 条" % len(QUERIES))
say("=" * 78)

pool = []          # (id, name, st, pl, fee, cp, ncr, oct, kw)
seen = set()
for kw in QUERIES:
    try:
        r = post("https://music.163.com/api/cloudsearch/pc",
                 {"s": kw, "type": 1, "limit": 30, "offset": 0},
                 "sem-" + urllib.parse.quote(kw, safe=""))
    except Exception as e:  # noqa
        say("关键词 %-22s EXC %s" % (kw, e))
        continue
    songs = (r.get("result") or {}).get("songs") or []
    n_new = 0
    for s in songs:
        sid = s.get("id")
        if sid in seen:
            continue
        seen.add(sid)
        p = s.get("privilege") or {}
        pool.append(dict(id=sid, name=s.get("name"), st=p.get("st"), pl=p.get("pl"),
                         fee=s.get("fee"), cp=s.get("copyright"),
                         ncr=s.get("noCopyrightRcmd"), oct=s.get("originCoverType"),
                         kw=kw))
        n_new += 1
    say("关键词 %-22s 命中 %2d 条，新增 %2d（累计 %d）" % (kw, len(songs), n_new, len(pool)))
    time.sleep(0.15)

say("")
say("样本总量（去重后）= %d" % len(pool))
say("st 分布 = %s" % json.dumps(dict(Counter(x["st"] for x in pool)), ensure_ascii=False))
say("(st, pl>0) 联合分布 = %s" % json.dumps(
    {"%s/%s" % (x["st"], "pl>0" if (x["pl"] or 0) > 0 else "pl=0"): 1 for x in pool} and
    dict(Counter("%s/%s" % (x["st"], "pl>0" if (x["pl"] or 0) > 0 else "pl=0") for x in pool)),
    ensure_ascii=False))
say("oct 分布 = %s" % json.dumps(dict(Counter(x["oct"] for x in pool)), ensure_ascii=False))

# ---- 抽样做金标准预检：分层抽样，保证 st=-1 / st=-200 / st=0+pl=0 / st=0+pl>0 都有 ----
buckets = {}
for x in pool:
    key = "%s/%s" % (x["st"], "pl>0" if (x["pl"] or 0) > 0 else "pl=0")
    buckets.setdefault(key, []).append(x)

random.seed(20260925)
PER_BUCKET = 12
sample = []
for key, xs in sorted(buckets.items()):
    random.shuffle(xs)
    sample.extend(xs[:PER_BUCKET])
    say("分层 %-12s 共 %4d 条，抽 %d 条做预检" % (key, len(xs), min(PER_BUCKET, len(xs))))

say("")
say("金标准预检（standard 档位）：%d 首" % len(sample))
say("st | pl | fee | 预测A(st>=0&pl>0) | 预测B(st==0&pl>0) | 预测C(无版权) | 实测可播 | code | level | 耗时")
tpA = fpA = tnA = fnA = 0
tpB = fpB = tnB = fnB = 0
tpC = fpC = tnC = fnC = 0
rows = []
for x in sample:
    try:
        t0 = time.time()
        r = post("https://music.163.com/api/song/enhance/player/url/v1",
                 {"ids": "[%d]" % x["id"], "level": "standard", "encodeType": "aac"}, None)
        dt = (time.time() - t0) * 1000
        d = (r.get("data") or [{}])[0]
        playable = bool(d.get("url"))
        A = (x["st"] is not None and x["st"] >= 0 and (x["pl"] or 0) > 0)
        B = (x["st"] == 0 and (x["pl"] or 0) > 0)
        C = (x["st"] == -200 or x["ncr"] is not None)   # 预测「确定无版权」
        nocp_actual = not playable
        if A and playable: tpA += 1
        elif A and not playable: fpA += 1
        elif not A and playable: fnA += 1
        else: tnA += 1
        if B and playable: tpB += 1
        elif B and not playable: fpB += 1
        elif not B and playable: fnB += 1
        else: tnB += 1
        if C and nocp_actual: tpC += 1
        elif C and not nocp_actual: fpC += 1
        elif not C and nocp_actual: fnC += 1
        else: tnC += 1
        rows.append((x, playable, A, B, C))
        say("%-6s %-8s %-4s %-8s %-8s %-10s %-8s %-4s %-10s %.0fms  %s" % (
            x["st"], x["pl"], x["fee"], A, B, C, playable, r.get("code"),
            str(d.get("level"))[:10], dt, (x["name"] or "")[:24]))
    except Exception as e:  # noqa
        say("%-6s %-8s EXC %s" % (x["st"], x["pl"], e))

say("")
say("=" * 78)
say("准确率")
say("=" * 78)


def acc(tp, fp, tn, fn, label):
    n = tp + fp + tn + fn
    if n == 0:
        say("%s: 无样本" % label)
        return
    say("%-34s 准确率 = %d/%d = %.1f%%   （TP=%d FP=%d TN=%d FN=%d）" % (
        label, tp + tn, n, 100.0 * (tp + tn) / n, tp, fp, tn, fn))
    say("%-34s   把「不可播」误判成「可播」= %d 次（假阳性，用户最痛）" % ("", fp))
    say("%-34s   把「可播」误判成「不可播」= %d 次（假阴性，白灰一首歌）" % ("", fn))


acc(tpA, fpA, tnA, fnA, "A: st>=0 且 pl>0 ⇒ 可播")
acc(tpB, fpB, tnB, fnB, "B: st==0 且 pl>0 ⇒ 可播")
acc(tpC, fpC, tnC, fnC, "C: st==-200 或 ncr ⇒ 无版权")

say("")
say("=" * 78)
say("特性 C 的降级判据（只用「显式声明」的字段，不做预测）")
say("=" * 78)
nocp = [x for x in pool if x["ncr"] is not None or x["st"] == -200]
say("整池 %d 条里，显式声明「无版权/下架」的 = %d 条 (%.1f%%)" % (
    len(pool), len(nocp), 100.0 * len(nocp) / max(1, len(pool))))
for x in nocp[:25]:
    say("  %-30s st=%-6s ncr=%s" % ((x["name"] or "")[:30], x["st"],
                                    json.dumps(x["ncr"], ensure_ascii=False)))
# 显式无版权 vs 实测不可播的混淆矩阵（在上面抽到的样本里）
sub = [(x, p, C) for (x, p, A, B, C) in rows]
tp = sum(1 for x, p, C in sub if C and not p)
fp = sum(1 for x, p, C in sub if C and p)
fn = sum(1 for x, p, C in sub if not C and not p)
tn = sum(1 for x, p, C in sub if not C and p)
say("")
say("在抽到的 %d 首预检样本里：显式无版权 %d 条，实测不可播 %d 条" % (
    len(sub), tp + fp, tp + fn))
say("  ┌ 显式无版权 & 实测不可播 = %d（真阳性）" % tp)
say("  ├ 显式无版权 & 实测**可播** = %d（假阳性 —— 会误伤，故此判据只用「-200 / ncr」）" % fp)
say("  ├ 未声明     & 实测不可播 = %d（假阴性 —— 沉默，不标「可播放」）" % fn)
say("  └ 未声明     & 实测可播   = %d" % tn)

out = os.path.join(RAW, "probe-copyright-accuracy.txt")
with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(report) + "\n")
print("\n[wrote] " + out)
