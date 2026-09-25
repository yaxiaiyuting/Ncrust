#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v2.3.0 探针 ①d/②/③：QQ 音乐一侧的字段普查。

用 App 自己走的那条主通道（旧版 GET `client_search_cp`，见 `QqRequests.legacySearchUrl`），
**匿名**请求，回答三个问题：

  Q1  同一个关键词下，QQ 与网易云**都有**哪些歌？（→ 特性 C 的「同一首歌两源都有」样本）
  Q2  QQ 有没有「能不能播」的字段？（对照网易云的 `privilege.pl`）
  Q3  QQ 有没有「官方 / 原唱 / 翻唱」的字段？（对照网易云的 `originCoverType`）

输出落盘到 probe-raw/qq-*.json，报告引用文件名。
"""
import json
import os
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
    "Referer": "https://y.qq.com/",
}

report = []


def say(line=""):
    print(line)
    report.append(line)


def qq_search(kw, tag, n=30):
    url = ("https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
           "?p=1&n=%d&w=%s&format=json&cr=1&new_json=1" % (n, urllib.parse.quote(kw)))
    req = urllib.request.Request(url, headers=HEADERS, method="GET")
    with urllib.request.urlopen(req, timeout=20) as r:
        txt = r.read().decode("utf-8", "replace")
    with open(os.path.join(RAW, tag + ".json"), "w", encoding="utf-8") as f:
        f.write(txt)
    # cr=1 会带 JSONP 包裹
    s = txt.strip()
    if s.startswith("callback("):
        s = s[len("callback("):-1]
    return json.loads(s)


def keys_of(obj, prefix="", depth=0, out=None):
    if out is None:
        out = set()
    if depth > 3 or not isinstance(obj, dict):
        return out
    for k, v in obj.items():
        out.add(prefix + k)
        if isinstance(v, dict):
            keys_of(v, prefix + k + ".", depth + 1, out)
        elif isinstance(v, list) and v and isinstance(v[0], dict):
            keys_of(v[0], prefix + k + "[].", depth + 1, out)
    return out


QUERIES = ["周杰伦", "晴天", "孤勇者", "起风了", "夜空中最亮的星", "Taylor Swift"]

say("=" * 78)
say("QQ 音乐 GET client_search_cp （App 主通道）字段普查")
say("=" * 78)

allkeys = set()
pool = []
for kw in QUERIES:
    try:
        r = qq_search(kw, "qq-search-" + urllib.parse.quote(kw, safe=""))
    except Exception as e:  # noqa
        say("关键词 %-18s EXC %s" % (kw, e))
        continue
    code = r.get("code")
    data = r.get("data") or {}
    songs = ((data.get("song") or {}).get("list")) or []
    total = (data.get("song") or {}).get("totalnum")
    say("")
    say("### 关键词 = %-16s code=%s totalnum=%s 本页 %d 条" % (kw, code, total, len(songs)))
    for s in songs:
        allkeys |= keys_of(s)
        pool.append((kw, s))
    if songs:
        say("    单曲 key: " + ", ".join(sorted(songs[0].keys())))
    time.sleep(0.2)

say("")
say("=" * 78)
say("Q2：QQ 有没有「能不能播」的字段？")
say("=" * 78)
say("全部字段（去重，含嵌套）：")
say("  " + ", ".join(sorted(allkeys)))
say("")
say("逐曲 pay / action / file / lyric 明细（前 40 条）：")
say("  name | pay.pay_play | pay.pay_download | pay.pay_status | pay.price_track | "
    "action.switch | action.alert | action.msgid | action.msg | file.media_mid | size_128mp3")
cnt_play = Counter()
cnt_switch = Counter()
for kw, s in pool[:40]:
    pay = s.get("pay") or {}
    act = s.get("action") or {}
    f = s.get("file") or {}
    cnt_play[pay.get("pay_play")] += 1
    cnt_switch[act.get("switch")] += 1
    say("  %-28s pp=%-3s pd=%-3s ps=%-3s pt=%-4s sw=%-12s alert=%-6s msgid=%-6s mid=%-16s sz128=%s" % (
        (s.get("name") or "")[:28], pay.get("pay_play"), pay.get("pay_download"),
        pay.get("pay_status"), pay.get("price_track"), act.get("switch"),
        act.get("alert"), act.get("msgid"), (f.get("media_mid") or "")[:16],
        f.get("size_128mp3")))
say("")
say("pay.pay_play 分布: %s" % json.dumps(dict(cnt_play), ensure_ascii=False))
say("action.switch 分布: %s" % json.dumps(dict(cnt_switch), ensure_ascii=False))

say("")
say("=" * 78)
say("Q3：QQ 有没有「官方 / 原唱 / 翻唱」的字段？")
say("=" * 78)
suspect = sorted(k for k in allkeys if any(
    t in k.lower() for t in ("type", "origin", "official", "cover", "tag", "ver", "kind",
                             "copyright", "label", "company")))
say("疑似相关字段：%s" % (suspect or "（一个都没有）"))
say("")
say("逐曲：name | songtype | type | ov | album.name | album.id | singer[].name | singer[].id | "
    "time_public | label")
for kw, s in pool[:40]:
    alb = s.get("album") or {}
    singers = ",".join(x.get("name", "") for x in (s.get("singer") or []))
    say("  %-26s songtype=%-4s type=%-4s ov=%-4s alb=%-22s albid=%-12s singer=%-22s pub=%-12s label=%s" % (
        (s.get("name") or "")[:26], s.get("songtype"), s.get("type"), s.get("ov"),
        (alb.get("name") or "")[:22], alb.get("id"), singers[:22],
        str(s.get("time_public"))[:12], s.get("label")))

say("")
say("=" * 78)
say("Q1：两源同时命中的样本（用于特性 C 的「同一首歌两源都有」）")
say("=" * 78)
qq_names = {(s.get("name") or "").strip(): s for kw, s in pool}
say("QQ 侧去重后曲名 %d 个；网易云侧见 probe-copyright-*.txt" % len(qq_names))
for nm in ["晴天", "孤勇者", "起风了", "夜空中最亮的星", "七里香", "稻香"]:
    s = qq_names.get(nm)
    if s:
        alb = s.get("album") or {}
        say("  QQ 命中「%s」: mid=%s media_mid=%s album=%s pay_play=%s" % (
            nm, s.get("mid"), (s.get("file") or {}).get("media_mid"),
            alb.get("name"), (s.get("pay") or {}).get("pay_play")))
    else:
        say("  QQ 未命中「%s」" % nm)

out = os.path.join(RAW, "probe-qq-fields.txt")
with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(report) + "\n")
print("\n[wrote] " + out)
