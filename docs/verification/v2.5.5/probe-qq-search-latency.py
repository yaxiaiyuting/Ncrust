#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
probe-qq-search-latency.py —— 在**仓库外**（宿主机）直接打 QQ 音乐搜索接口，量接口侧延迟。

为什么在宿主机跑而不是在手机上跑：
  · 手机（PLC110 / S6）与宿主机在同一个 /24 网段、走同一个出口 NAT（见报告 §1 实测），
    所以宿主机测出的「服务端 + 网络」耗时是手机上那一段的可用近似；
  · 手机上没有 curl/wget，只有 toybox nc，发不了 HTTPS。

它复刻的是 app 里 **逐字节相同**的两条通道（见 QqApi.searchSongs / QqClient.legacyGet ·
musicuEnvelope / QqRequests.legacySearchUrl · searchEnvelope）：

  A) 主通道 `GET https://c.y.qq.com/soso/fcgi-bin/client_search_cp`
     ?p=1&n=30&w=<kw>&format=json&cr=1&new_json=1
     UA = Chrome/120 桌面 UA，Referer = https://y.qq.com/
  B) 兜底通道 `POST https://u.y.qq.com/cgi-bin/musicu.fcg`
     body = {"music.search.SearchCgiService": {module, method, param}}  ← **不含 comm**

用 curl -w 拿分相耗时：namelookup / connect / appconnect(TLS) / starttransfer(TTFB) / total。

用法：
  python3 probe-qq-search-latency.py --n 30 --keyword 晴天 \
      [--cookie-file /tmp/qq_cookie.txt] [--out raw.json]

cookie 默认不带（app 实测匿名也能搜到歌）。带 cookie 的跑法用于验证「登录态是否影响耗时」。
**脚本不会把 cookie 写进任何输出文件**，只记录「带/不带」。
"""

import argparse
import json
import statistics
import subprocess
import sys
import time
import urllib.parse

LEGACY = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg"

UA_WEB = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

# curl -w 模板：一次性拿到分相耗时 + 结果规模
W = ("%{time_namelookup} %{time_connect} %{time_appconnect} %{time_starttransfer} "
     "%{time_total} %{http_code} %{size_download} %{http_version}")


def legacy_url(keyword, limit=30, page=1):
    return (LEGACY + "?p=" + str(page) + "&n=" + str(limit) +
            "&w=" + urllib.parse.quote(keyword) + "&format=json&cr=1&new_json=1")


def musicu_body(keyword, limit=30, page=1, search_id="0"):
    param = {
        "searchid": search_id,
        "query": keyword,
        "search_type": 0,
        "num_per_page": limit,
        "page_num": page,
        "highlight": True,
        "grp": True,
        "selectors": {},
        "vec_selectors": [],
    }
    return json.dumps({"music.search.SearchCgiService": {
        "module": "music.search.SearchCgiService",
        "method": "DoSearchForQQMusicMobile",
        "param": param,
    }}, ensure_ascii=False, separators=(",", ":"))


def run_curl(args, cookie):
    cmd = ["curl", "-sS", "--noproxy", "*", "-o", "/tmp/_qq_probe_body.json", "-w", W] + args
    if cookie:
        cmd += ["-H", "Cookie: " + cookie]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    if p.returncode != 0:
        return None, p.stderr.strip()
    parts = p.stdout.strip().split()
    if len(parts) < 8:
        return None, "unexpected -w output: " + p.stdout.strip()
    with open("/tmp/_qq_probe_body.json", "rb") as f:
        body = f.read()
    return {
        "dns": float(parts[0]), "connect": float(parts[1]), "tls": float(parts[2]),
        "ttfb": float(parts[3]), "total": float(parts[4]),
        "http": int(parts[5]), "bytes": int(parts[6]), "httpver": parts[7],
    }, body


def count_legacy(body):
    """data.song.list[] 条数（QqSongMapper.songsFromLegacySearch 读的就是它）。"""
    try:
        j = json.loads(body)
        return len(j.get("data", {}).get("song", {}).get("list", []) or [])
    except Exception:
        return -1


def count_musicu(body):
    """SearchCgiService.data.body.item_song.items 条数 + 业务 code。"""
    try:
        j = json.loads(body)
        node = j.get("music.search.SearchCgiService", {})
        code = node.get("code")
        items = (node.get("data", {}).get("body", {})
                 .get("item_song", {}).get("items", []) or [])
        return code, len(items)
    except Exception:
        return None, -1


def pct(xs, p):
    if not xs:
        return None
    xs = sorted(xs)
    k = (len(xs) - 1) * p / 100.0
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def stats(name, samples):
    ok = [s for s in samples if s.get("ok")]
    tot = [s["total"] * 1000 for s in ok]
    ttfb = [s["ttfb"] * 1000 for s in ok]
    tls = [s["tls"] * 1000 for s in ok]
    dns = [s["dns"] * 1000 for s in ok]
    out = {
        "channel": name,
        "runs": len(samples),
        "ok": len(ok),
        "failed": [s.get("error") or ("http " + str(s.get("http"))) for s in samples if not s.get("ok")],
        "empty_results": sum(1 for s in ok if s.get("items") == 0),
        "items": [s.get("items") for s in ok],
        "total_ms": {"p50": pct(tot, 50), "p95": pct(tot, 95), "min": min(tot) if tot else None,
                     "max": max(tot) if tot else None, "mean": statistics.fmean(tot) if tot else None},
        "ttfb_ms": {"p50": pct(ttfb, 50), "p95": pct(ttfb, 95)},
        "tls_ms": {"p50": pct(tls, 50), "p95": pct(tls, 95)},
        "dns_ms": {"p50": pct(dns, 50), "p95": pct(dns, 95)},
        "http_versions": sorted({s["httpver"] for s in ok}),
        "samples": samples,
    }
    print("\n== %s ==" % name)
    print("  runs=%d ok=%d empty_results=%d" % (out["runs"], out["ok"], out["empty_results"]))
    if out["failed"]:
        print("  failures: %s" % out["failed"])
    if tot:
        print("  total  P50=%.0fms P95=%.0fms min=%.0f max=%.0f mean=%.0f"
              % (out["total_ms"]["p50"], out["total_ms"]["p95"], out["total_ms"]["min"],
                 out["total_ms"]["max"], out["total_ms"]["mean"]))
        print("  TTFB   P50=%.0fms P95=%.0fms" % (out["ttfb_ms"]["p50"], out["ttfb_ms"]["p95"]))
        print("  TLS    P50=%.0fms P95=%.0fms   DNS P50=%.0fms"
              % (out["tls_ms"]["p50"], out["tls_ms"]["p95"], out["dns_ms"]["p50"]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keyword", default="晴天")
    ap.add_argument("--n", type=int, default=30)
    ap.add_argument("--limit", type=int, default=30)
    ap.add_argument("--cookie-file", default=None)
    ap.add_argument("--out", default=None)
    ap.add_argument("--sleep", type=float, default=0.4)
    args = ap.parse_args()

    cookie = None
    if args.cookie_file:
        cookie = open(args.cookie_file, encoding="utf-8").read().strip() or None

    report = {"keyword": args.keyword, "limit": args.limit, "n": args.n,
              "cookie": bool(cookie), "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
              "legacy_url_shape": legacy_url(args.keyword, args.limit),
              "host": subprocess.run(["hostname"], capture_output=True, text=True).stdout.strip()}

    print("keyword=%s limit=%d n=%d cookie=%s" % (args.keyword, args.limit, args.n, bool(cookie)))
    print("legacy URL: %s" % legacy_url(args.keyword, args.limit))

    # ---- A: 主通道，每次新建连接（模拟 app 冷启动后的第一次搜索） ----
    a = []
    for i in range(args.n):
        r, body = run_curl(["-H", "User-Agent: " + UA_WEB, "-H", "Referer: https://y.qq.com/",
                            legacy_url(args.keyword, args.limit)], cookie)
        if r is None:
            a.append({"ok": False, "error": str(body)[:200]})
        else:
            r["ok"] = (r["http"] == 200)
            r["items"] = count_legacy(body) if r["ok"] else None
            a.append(r)
        time.sleep(args.sleep)
    report["legacy_fresh"] = stats("A) legacy GET, fresh connection", a)

    # ---- B: 主通道，同一个 curl 进程内连续 K 次（连接复用 = app 里 OkHttp 连接池已热的稳态） ----
    K = 10
    cmd = ["curl", "-sS", "--noproxy", "*", "-w", W + "\\n",
           "-H", "User-Agent: " + UA_WEB, "-H", "Referer: https://y.qq.com/"]
    if cookie:
        cmd += ["-H", "Cookie: " + cookie]
    for _ in range(K):
        cmd += ["-o", "/dev/null", legacy_url(args.keyword, args.limit)]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    b = []
    for line in p.stdout.strip().splitlines():
        parts = line.split()
        if len(parts) < 8:
            continue
        b.append({"ok": int(parts[5]) == 200, "dns": float(parts[0]), "connect": float(parts[1]),
                  "tls": float(parts[2]), "ttfb": float(parts[3]), "total": float(parts[4]),
                  "http": int(parts[5]), "bytes": int(parts[6]), "httpver": parts[7], "items": None})
    report["legacy_warm_keepalive"] = stats("B) legacy GET ×%d 同一连接" % K, b)
    if b:
        print("  per-transfer total(ms): %s" % ["%.0f" % (x["total"] * 1000) for x in b])

    # ---- C: 兜底通道 musicu POST ----
    c = []
    for i in range(max(5, args.n // 2)):
        sid = str(18014398509481984 + int(time.time() * 1000) % 86400000 + i)
        body_json = musicu_body(args.keyword, args.limit, 1, sid)
        r, body = run_curl(["-H", "User-Agent: " + UA_WEB, "-H", "Referer: https://y.qq.com/",
                            "-H", "Content-Type: application/json",
                            "--data-binary", body_json, MUSICU], cookie)
        if r is None:
            c.append({"ok": False, "error": str(body)[:200]})
        else:
            r["ok"] = (r["http"] == 200)
            code, items = count_musicu(body) if r["ok"] else (None, -1)
            r["biz_code"] = code
            r["items"] = items
            c.append(r)
        time.sleep(args.sleep)
    report["musicu"] = stats("C) musicu.fcg POST (兜底通道)", c)
    report["musicu_biz_codes"] = [s.get("biz_code") for s in c if s.get("ok")]

    report["finished_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print("\nraw -> %s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
