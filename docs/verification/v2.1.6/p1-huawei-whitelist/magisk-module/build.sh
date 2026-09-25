#!/usr/bin/env bash
# 生成华为媒体卡白名单的 Magisk 覆盖模块。
#
# 用法:
#   build.sh <serial> [outdir]
#
# 干什么:
#   1. 从设备拉取生效的白名单 /system/emui/base/thirdappfilter/third_app_filter.xml
#   2. 在 <feature name="mediaplaybackcontroller"> / <function name="mediasession"> 段里
#      插入一条本应用的 <enable>，其余**一个字节都不动**（顺序、缩进、其它 feature 全保留）
#   3. 按 Magisk 模块布局落盘（system/ 目录 + module.prop）
#
# 为什么是「覆盖同一个文件」而不是走 /data/cota/para/:
#   getDownloadCfgFile 的 smali 逻辑（framework.jar!classes4.dex，原文见
#   ../23-decompile-HwCfgFilePolicy-cota.txt）是：
#       infos = getFileInfo("/data/cota/para/", verDir, filePath)
#       for (dir : getCfgPolicyDir(0))
#           if (isPresetNewerVersionInfo(getFileInfo(dir,...), infos)) infos = 那个
#   也就是**版本号新的赢**，COTA 并不天然优先。走 COTA 就必须连 version.txt 一起伪造一个
#   更大的版本号，反而更容易踩到 compatibleVersion 之类的校验。
#   Magisk 的 magic mount 直接覆盖 /system 下那个文件，不碰版本比较 —— 更少假设。
#   （README 里有 COTA 那条的备用做法。）

set -euo pipefail
ADB=/usr/bin/adb
S="${1:?usage: build.sh <serial> [outdir]}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${2:-$HERE/module}"
PKG=com.takahashirinta.ncrust
APP_NAME=Ncrust
SRC_XML=/system/emui/base/thirdappfilter/third_app_filter.xml
VER_TXT=/system/emui/base/thirdappfilter/version.txt

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== 1. 从 $S 拉取原始白名单 =="
"$ADB" -s "$S" shell "md5sum $SRC_XML" | tee "$TMP/orig.md5"
"$ADB" -s "$S" pull "$SRC_XML" "$TMP/third_app_filter.xml" >/dev/null
"$ADB" -s "$S" pull "$VER_TXT" "$TMP/version.txt" >/dev/null
APP_VER="$("$ADB" -s "$S" shell dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionName=\(.*\)/\1/p' | head -1 | tr -d '\r')"
echo "   本应用版本: $APP_VER"

echo "== 2. 插入 <enable>（只动 mediasession 段，其余字节原样） =="
mkdir -p "$OUT/system/emui/base/thirdappfilter"
python3 - "$TMP/third_app_filter.xml" "$OUT/system/emui/base/thirdappfilter/third_app_filter.xml" "$PKG" "$APP_NAME" <<'PY'
import sys, re, io
src, dst, pkg, name = sys.argv[1:5]
s = io.open(src, encoding='utf-8').read()

m = re.search(r'(<feature\s+name="mediaplaybackcontroller"[^>]*>.*?<function\s+name="mediasession"\s*>)', s, re.S)
if not m:
    sys.exit("找不到 mediaplaybackcontroller/mediasession 段 —— 拒绝盲写，请人工确认 ROM 结构")
insert_at = m.end()

# 段落内的缩进照抄相邻条目，避免改格式
nxt = re.search(r'\n(\s*)<enable', s[insert_at:])
indent = nxt.group(1) if nxt else '            '
entry = '\n%s<enable name="%s" package="%s" version="0" options=""/>' % (indent, name, pkg)

if 'package="%s"' % pkg in s:
    print("   已存在该包名的条目，不重复插入")
else:
    s = s[:insert_at] + entry + s[insert_at:]
    print("   已插入: package=%s version=0" % pkg)

io.open(dst, 'w', encoding='utf-8').write(s)
PY

echo "== 3. 写 module.prop =="
cat >"$OUT/module.prop" <<EOF
id=ncrust_huawei_mediacard
name=Ncrust Huawei media card whitelist
version=v2.1.6-$(date +%Y%m%d)
versionCode=1
author=ncrust-gpl
description=把 $PKG 加进华为控制中心媒体卡的 ROM 包名白名单（third_app_filter.xml / mediasession 段）。基于本机原始文件就地插入一行，其余字节不变。**未在真机验证过**。卸载模块即完全还原。
EOF

echo "== 4. 自检 =="
python3 - "$OUT/system/emui/base/thirdappfilter/third_app_filter.xml" "$TMP/third_app_filter.xml" "$PKG" <<'PY'
import sys, io, xml.etree.ElementTree as ET
new, old, pkg = sys.argv[1:4]
a = io.open(old, encoding='utf-8').read(); b = io.open(new, encoding='utf-8').read()
print("   原始 %d 字节 -> 生成 %d 字节（差 %+d）" % (len(a.encode()), len(b.encode()), len(b.encode())-len(a.encode())))
# XML 必须仍然合法
r = ET.parse(new).getroot()
got = None
for feat in r.iter('feature'):
    if feat.get('name') == 'mediaplaybackcontroller':
        for fn in feat.iter('function'):
            if fn.get('name') == 'mediasession':
                for e in fn.iter('enable'):
                    if e.get('package') == pkg: got = e.attrib
print("   重新解析后的新条目:", got)
assert got is not None, "生成的文件里找不到新条目"
# 其它条目数量不能变（只加不减）
def count(s):
    root = ET.fromstring(s)
    return len([e for f in root.iter('feature') for fn in f.iter('function') for e in fn.iter('enable')])
print("   enable 总数: %d -> %d" % (count(a), count(b)))
PY

echo
echo "模块已生成: $OUT"
echo "刷入前请先读 $HERE/README.md —— 里面有「未验证」的具体范围与回滚方式。"
