#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v2.3.0 探针 ①：版权字段是否存在于 搜索 / 艺人 / 专辑 / 单曲 四条接口。

设计原则（与 v2.2.0/v2.2.1 的探针一致）：
  * 只发**匿名**请求（不带 Cookie），因为「版权可用性」必须在**未登录**时也能判，
    否则登录态一变，标注就跟着变，用户看到的不是「版权」而是「我的会员状态」。
  * 每个结论都落盘原始 JSON 到 probe-raw/，报告里引用的是文件名而不是记忆。
  * 不修改任何远端状态（全程 GET/POST 只读）。
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "probe-raw")
os.makedirs(RAW, exist_ok=True)

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
# 与 App 的 RetrofitClient 保持同一族 UA/Referer（匿名，不带 Cookie）
HEADERS = {
    "User-Agent": UA,
    "Referer": "https://music.163.com/",
    "Content-Type": "application/x-www-form-urlencoded",
}


def post(url, data: dict, tag: str):
    body = urllib.parse.urlencode(data).encode()
    req = urllib.request.Request(url, data=body, headers=HEADERS, method="POST")
    with urllib.request.urlopen(req, timeout=20) as r:
        txt = r.read().decode("utf-8", "replace")
    with open(os.path.join(RAW, tag + ".json"), "w", encoding="utf-8") as f:
        f.write(txt)
    return json.loads(txt)


def get(url, tag: str):
    req = urllib.request.Request(url, headers=HEADERS, method="GET")
    with urllib.request.urlopen(req, timeout=20) as r:
        txt = r.read().decode("utf-8", "replace")
    with open(os.path.join(RAW, tag + ".json"), "w", encoding="utf-8") as f:
        f.write(txt)
    return json.loads(txt)


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


report = []


def say(line=""):
    print(line)
    report.append(line)


# ---------------------------------------------------------------- ① 搜索
say("=" * 78)
say("① 网易云 POST /api/cloudsearch/pc  type=1  关键词=周杰伦")
say("=" * 78)
r = post("https://music.163.com/api/cloudsearch/pc",
         {"s": "周杰伦", "type": 1, "limit": 30, "offset": 0}, "ne-search-zhoujielun")
say("HTTP body 顶层 key: " + ", ".join(sorted(r.keys())))
res = r.get("result", {})
say("result 顶层 key: " + ", ".join(sorted(res.keys())))
songs = res.get("songs") or []
say("songs 条数 = %d" % len(songs))
has_priv = "privileges" in res
say("result.privileges 存在? %s" % has_priv)
if songs:
    say("单曲对象 key: " + ", ".join(sorted(songs[0].keys())))
say("")
say("逐曲：name | fee | privilege(st,pl,dl,fl,sp,cp,maxbr,flag,toast) | 是否有 al.id")
fees = {}
priv_present = 0
st_neg = 0
for s in songs:
    fee = s.get("fee")
    fees[fee] = fees.get(fee, 0) + 1
    al = s.get("al") or {}
    say("  %-34s fee=%-4s al.id=%-12s ar=%s" % (
        (s.get("name") or "")[:34], fee, al.get("id"),
        ",".join(a.get("name", "") for a in (s.get("ar") or []))))

say("")
say("fee 分布: %s" % json.dumps(fees, ensure_ascii=False))

# privileges 与 songs 的对齐关系（这一条决定了「能不能按行标注」）
if has_priv:
    privs = res["privileges"]
    say("privileges 条数 = %d（songs 条数 = %d）" % (len(privs), len(songs)))
    say("privilege 对象 key: " + ", ".join(sorted(privs[0].keys())))
    ids_songs = [s.get("id") for s in songs]
    ids_priv = [p.get("id") for p in privs]
    say("id 序列逐个相等? %s" % (ids_songs == ids_priv))
    for p in privs:
        if p.get("st", 0) is not None and p.get("st", 0) < 0:
            st_neg += 1
    say("st < 0（下架/无版权）条数 = %d" % st_neg)
    say("")
    say("逐曲 privilege 明细：id | st | pl | dl | fl | sp | cp | maxbr | flag | toast | preSell")
    for p in privs:
        say("  %-14s st=%-4s pl=%-8s dl=%-8s fl=%-8s sp=%-4s cp=%-4s maxbr=%-8s flag=%-4s toast=%s" % (
            p.get("id"), p.get("st"), p.get("pl"), p.get("dl"), p.get("fl"),
            p.get("sp"), p.get("cp"), p.get("maxbr"), p.get("flag"), p.get("toast")))

# ---------------------------------------------------------------- ② 艺人页
say("")
say("=" * 78)
say("② 网易云 艺人页 GET /api/v1/artist/{id}  （周杰伦 id=6452）")
say("=" * 78)
r2 = get("https://music.163.com/api/v1/artist/6452", "ne-artist-6452")
say("顶层 key: " + ", ".join(sorted(r2.keys())))
hot = r2.get("hotSongs") or []
say("hotSongs 条数 = %d" % len(hot))
if hot:
    say("hotSong key: " + ", ".join(sorted(hot[0].keys())))
say("privilege 存在? %s" % ("privilege" in r2))
for s in hot[:10]:
    say("  %-34s fee=%-4s al=%s" % ((s.get("name") or "")[:34], s.get("fee"),
                                    (s.get("al") or {}).get("name")))

# ---------------------------------------------------------------- ③ 专辑页
say("")
say("=" * 78)
say("③ 网易云 专辑页 GET /api/v1/album/{id}")
say("=" * 78)
r3 = get("https://music.163.com/api/v1/album/1883703", "ne-album-1883703")
say("顶层 key: " + ", ".join(sorted(r3.keys())))
say("songs 条数 = %d" % len(r3.get("songs") or []))
if r3.get("songs"):
    say("song key: " + ", ".join(sorted(r3["songs"][0].keys())))
say("privilege 存在? %s" % ("privilege" in r3))
if r3.get("privilege"):
    say("privilege key: " + ", ".join(sorted(r3["privilege"][0].keys())))
    say("sample: %s" % json.dumps(r3["privilege"][0], ensure_ascii=False))
alb = r3.get("album") or {}
say("album.company = %s" % alb.get("company"))

# ---------------------------------------------------------------- ④ 单曲详情
say("")
say("=" * 78)
say("④ 网易云 单曲详情 POST /api/v3/song/detail")
say("=" * 78)
r4 = post("https://music.163.com/api/v3/song/detail",
          {"c": json.dumps([{"id": 186016}], ensure_ascii=False)}, "ne-songdetail-186016")
say("顶层 key: " + ", ".join(sorted(r4.keys())))
say("privilege 存在? %s" % ("privilege" in r4))
if r4.get("songs"):
    say("song key: " + ", ".join(sorted(r4["songs"][0].keys())))
if r4.get("privileges"):
    say("sample privilege: %s" % json.dumps(r4["privileges"][0], ensure_ascii=False))

# ---------------------------------------------------------------- ⑤ 取链预检
say("")
say("=" * 78)
say("⑤ 网易云 取链预检 POST /api/song/enhance/player/url/v1  （成本测量）")
say("=" * 78)
# 186016 = 周杰伦《晴天》；34507006 = 一首已知无版权/下架的样本（若它返回 null 即为判据）
for sid in (186016, 34507006):
    t0 = time.time()
    try:
        r5 = post("https://music.163.com/api/song/enhance/player/url/v1",
                  {"ids": "[%d]" % sid, "level": "standard", "encodeType": "aac"},
                  "ne-url-%d" % sid)
        dt = (time.time() - t0) * 1000
        d = (r5.get("data") or [{}])[0]
        say("id=%-10d code=%-5s url=%-6s fee=%-5s level=%-10s time=%.0fms" % (
            sid, r5.get("code"), "有" if d.get("url") else "空",
            d.get("fee"), d.get("level"), dt))
    except Exception as e:  # noqa
        say("id=%-10d EXC %s" % (sid, e))

# ---------------------------------------------------------------- ⑥ 无版权样本
say("")
say("=" * 78)
say("⑥ 网易云「无版权/下架」在搜索阶段能不能识别 —— 用已知样本对照")
say("=" * 78)
for kw in ("周杰伦 晴天", "Taylor Swift", "五月天"):
    try:
        rr = post("https://music.163.com/api/cloudsearch/pc",
                  {"s": kw, "type": 1, "limit": 10, "offset": 0},
                  "ne-search-" + kw.replace(" ", "_"))
        rs = rr.get("result", {})
        ss = rs.get("songs") or []
        pp = rs.get("privileges") or []
        say("关键词 %-14s 命中 %2d 条；privileges %s" % (kw, len(ss), "有" if pp else "无"))
        for s, p in zip(ss, pp or [{}] * len(ss)):
            say("    %-30s fee=%-4s st=%-4s pl=%-8s toast=%s" % (
                (s.get("name") or "")[:30], s.get("fee"), p.get("st"), p.get("pl"), p.get("toast")))
    except Exception as e:  # noqa
        say("关键词 %-14s EXC %s" % (kw, e))

# ---------------------------------------------------------------- ⑦ 官方/原唱字段
say("")
say("=" * 78)
say("⑦ 「官方 / 原唱 / 翻唱」字段搜索")
say("=" * 78)
kw_pool = set()
for s in songs:
    kw_pool |= keys_of(s)
for s in (r3.get("songs") or [])[:3]:
    kw_pool |= keys_of(s)
for s in hot[:3]:
    kw_pool |= keys_of(s)
suspect = sorted(k for k in kw_pool if any(
    t in k.lower() for t in ("type", "origin", "official", "cover", "tag", "ver", "kind", "copyright")))
say("所有单曲级字段（搜索接口）：")
say("  " + ", ".join(sorted(kw_pool)))
say("")
say("疑似「官方/原唱/翻唱」相关字段：%s" % (suspect or "（一个都没有）"))

out = os.path.join(HERE, "probe-raw", "probe-copyright-report.txt")
with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(report) + "\n")
print("\n[wrote] " + out)
