#!/usr/bin/env bash
# v2.6.0 真机 UI 驱动的**唯一**口径（可重跑）。
#
# 为什么需要它：本版的四个验收点里有两个（布局切换、手动折叠）是**纯 UI 行为**，
# 单测证明不了「点了有没有立即生效」。而用 `adb shell input tap x y` 硬写坐标
# 是不可复现的（换台机器、换字号、换语言全都不对）。
#
# 所以这里用 `uiautomator dump` 读**语义树里的文字与 bounds**，
# 按文字找坐标再点 —— 坐标来自设备当刻的真实布局，不是写死的。
#
# 用法：
#   ui-drive.sh <serial> dump                 # 打印当前界面的可点文字 + 中心坐标
#   ui-drive.sh <serial> tap "列表式"          # 点某个文字所在的节点中心
#   ui-drive.sh <serial> find "列表式"         # 只打印匹配到的坐标（找不到则退出 3）
#   ui-drive.sh <serial> shot <out.png>       # 截图
#   ui-drive.sh <serial> prefs <file>          # 读私有 prefs（需 root）
set -uo pipefail

SERIAL="${1:?usage: ui-drive.sh <serial> <cmd> [arg]}"
CMD="${2:?usage: ui-drive.sh <serial> <cmd> [arg]}"
ARG="${3:-}"

dump_xml() {
  adb -s "$SERIAL" shell "uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1"
  # `adb shell cat` 在这个 Android 版本上没有 `head -c`，所以拉回本地再解析。
  adb -s "$SERIAL" pull /sdcard/_ui.xml /tmp/_ui.xml >/dev/null 2>&1 || return 1
  [ -s /tmp/_ui.xml ] || return 1
}

# 文字 → 中心坐标。匹配规则：节点 text 或 content-desc **完全等于**目标，
# 找不到时退化为「包含」。完全匹配优先是为了不误点到「展开全部 3 个」这种前缀相同的节点。
find_xy() {
  local want="$1"
  python3 - "$want" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
try:
    root = ET.parse('/tmp/_ui.xml').getroot()
except Exception as e:
    print("PARSE_FAIL", e, file=sys.stderr); sys.exit(2)
exact, loose = [], []
for n in root.iter('node'):
    for attr in ('text', 'content-desc'):
        v = n.get(attr) or ''
        if not v.strip():
            continue
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds') or '')
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        if v == want:
            exact.append((cy, cx, v))
        elif want in v:
            loose.append((cy, cx, v))
hit = exact or loose
if not hit:
    sys.exit(3)
hit.sort()
print(f"{hit[0][1]} {hit[0][0]}")
PY
}

case "$CMD" in
  dump)
    dump_xml || { echo "dump failed" >&2; exit 1; }
    python3 - <<'PY'
import re, xml.etree.ElementTree as ET
root = ET.parse('/tmp/_ui.xml').getroot()
for n in root.iter('node'):
    t = (n.get('text') or '').strip() or (n.get('content-desc') or '').strip()
    if not t:
        continue
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds') or '')
    if not m: continue
    x1,y1,x2,y2 = map(int, m.groups())
    print(f"{(x1+x2)//2:5d} {(y1+y2)//2:5d}  clickable={n.get('clickable')}  {t[:60]}")
PY
    ;;
  find)
    dump_xml || { echo "dump failed" >&2; exit 1; }
    xy="$(find_xy "$ARG")" || { echo "NOT_FOUND: $ARG" >&2; exit 3; }
    echo "$xy"
    ;;
  tap)
    dump_xml || { echo "dump failed" >&2; exit 1; }
    xy="$(find_xy "$ARG")" || { echo "NOT_FOUND: $ARG" >&2; exit 3; }
    set -- $xy
    adb -s "$SERIAL" shell input tap "$1" "$2"
    echo "tapped '$ARG' at $xy"
    ;;
  shot)
    adb -s "$SERIAL" exec-out screencap -p > "$ARG"
    [ -s "$ARG" ] || { echo "screenshot failed" >&2; exit 1; }
    echo "wrote $ARG ($(wc -c < "$ARG") bytes)"
    ;;
  prefs)
    adb -s "$SERIAL" shell "su -c 'cat /data/data/com.takahashirinta.ncrust/shared_prefs/$ARG'" 2>/dev/null
    ;;
  *)
    echo "unknown cmd: $CMD" >&2; exit 2 ;;
esac
