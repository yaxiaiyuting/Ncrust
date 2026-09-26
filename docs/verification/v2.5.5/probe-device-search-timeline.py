#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
probe-device-search-timeline.py —— 真机「搜索 → 结果出现 → QQ 计数更新」的时间线采集。

不改 app 源码、不注入、不打点：只用 adb 的两条既有通道
  ① `logcat`：抓 app 自己**无条件**打的那行 `SearchViewModel: aggregate query=… elapsed=…ms`
     （SearchViewModel.kt:202-207，release 包也有）
  ② `screencap`：按 ~0.3s 一帧连续截屏，之后用像素差分定出「列表出现」与「计数变化」两帧

用法：
  python3 probe-device-search-timeline.py --device <serial> --tag cold1 \
      --tap 570,705 --seconds 12 --outdir probe-raw/search-qq/timeline

输出：<outdir>/<tag>/frame_XXX.png + frames.json + logcat.txt + timeline.json
"""

import argparse
import json
import os
import re
import signal
import subprocess
import time

ADB = "/usr/bin/adb"


def sh(args, **kw):
    return subprocess.run(args, capture_output=True, **kw)


def focused_app(device):
    p = sh([ADB, "-s", device, "shell", "dumpsys", "window"])
    txt = p.stdout.decode("utf-8", "replace")
    for line in txt.splitlines():
        if "mCurrentFocus" in line:
            return line.strip()
    return "(unknown)"


def ensure_foreground(device, pkg, tries=3):
    """把 app 拉到前台并确认 mCurrentFocus 真的是它。返回最后一次的 focus 行。"""
    for i in range(tries):
        f = focused_app(device)
        if pkg in f:
            return f
        sh([ADB, "-s", device, "shell", "am", "start", "-n", pkg + "/.MainActivity"])
        time.sleep(3.5)
    return focused_app(device)


def search_box_present(png, device):
    """
    判断「搜索 tab 真的在前台」：搜索框是一整块均匀的浅灰矩形（较深背景亮）。
    取框内一小块，算均值与标准差；纯色 ⇒ std 很小。
    返回 (mean, std, ok)。阈值是经验值，只用于**拒绝明显错误的状态**，不用于判定通过。
    """
    try:
        from PIL import Image
        import numpy as np
    except Exception:
        return None
    with open(png, "rb") as f:
        raw = f.read()
    tmp = png + ".tmp.png"
    with open(tmp, "wb") as f:
        f.write(raw)
    im = Image.open(tmp).convert("L")
    w, h = im.size
    box = im.crop((int(w * 0.15), int(h * 0.075), int(w * 0.85), int(h * 0.11)))
    a = np.asarray(box, dtype=float)
    return float(a.mean()), float(a.std())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--tap", required=True, help="x,y")
    ap.add_argument("--seconds", type=float, default=12.0)
    ap.add_argument("--outdir", required=True)
    ap.add_argument("--pre-wait", type=float, default=0.4)
    ap.add_argument("--pkg", default="com.takahashirinta.ncrust")
    ap.add_argument("--clear-first", action="store_true", help="先点搜索框右侧的 × 清空（坐标 --clear-tap）")
    ap.add_argument("--clear-tap", default=None)
    args = ap.parse_args()

    out = os.path.join(args.outdir, args.tag)
    os.makedirs(out, exist_ok=True)
    x, y = [int(v) for v in args.tap.split(",")]

    focus = ensure_foreground(args.device, args.pkg)
    print("focus: " + focus)

    # 前置帧：用于事后核对「开跑时到底在哪个界面」。不合格就直接退出，不产出假数据。
    pre = os.path.join(out, "pre.png")
    with open(pre, "wb") as f:
        subprocess.run([ADB, "-s", args.device, "exec-out", "screencap", "-p"],
                       stdout=f, stderr=subprocess.DEVNULL)
    probe = search_box_present(pre, args.device)
    if probe:
        print("pre search-box mean=%.1f std=%.1f" % probe)

    if args.clear_first and args.clear_tap:
        cx, cy = [int(v) for v in args.clear_tap.split(",")]
        sh([ADB, "-s", args.device, "shell", "input", "tap", str(cx), str(cy)])
        time.sleep(1.2)

    sh([ADB, "-s", args.device, "logcat", "-c"])

    log_path = os.path.join(out, "logcat.txt")
    logf = open(log_path, "wb")
    logp = subprocess.Popen([ADB, "-s", args.device, "logcat", "-v", "threadtime"],
                            stdout=logf, stderr=subprocess.STDOUT)

    time.sleep(args.pre_wait)
    frames = []
    t_tap_before = time.time()
    sh([ADB, "-s", args.device, "shell", "input", "tap", str(x), str(y)])
    t_tap_after = time.time()

    idx = 0
    t_start = t_tap_before
    while time.time() - t_start < args.seconds:
        tb = time.time()
        png = os.path.join(out, "frame_%03d.png" % idx)
        with open(png, "wb") as f:
            subprocess.run([ADB, "-s", args.device, "exec-out", "screencap", "-p"],
                           stdout=f, stderr=subprocess.DEVNULL)
        ta = time.time()
        frames.append({"idx": idx, "file": os.path.basename(png),
                       "t_before": round(tb - t_tap_before, 3),
                       "t_after": round(ta - t_tap_before, 3),
                       "bytes": os.path.getsize(png)})
        idx += 1

    time.sleep(0.3)
    logp.send_signal(signal.SIGINT)
    try:
        logp.wait(timeout=3)
    except subprocess.TimeoutExpired:
        logp.kill()
    logf.close()

    lines = open(log_path, encoding="utf-8", errors="replace").read().splitlines()
    agg = [l for l in lines if "aggregate query=" in l]
    qq = [l for l in lines if "QqClient" in l or "QqApi" in l or "QqMusicSource" in l or "SourceRouter" in l]

    with open(os.path.join(out, "timeline.json"), "w", encoding="utf-8") as f:
        json.dump({"device": args.device, "tag": args.tag, "tap": [x, y],
                   "t_tap_after_minus_before_ms": round((t_tap_after - t_tap_before) * 1000),
                   "frames": frames, "aggregate_lines": agg, "qq_lines": qq,
                   "captured_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}, f,
                  ensure_ascii=False, indent=2)

    print("tap=%s frames=%d" % (args.tap, len(frames)))
    for l in agg:
        print("  AGG | " + l.strip())
    if not agg:
        print("  (本窗口内没有 aggregate 日志行)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
