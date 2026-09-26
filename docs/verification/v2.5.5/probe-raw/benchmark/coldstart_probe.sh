#!/usr/bin/env bash
# 冷启动实测（am start -W -S），用于给 macrobenchmark 单次迭代耗时定标。
# 只做 force-stop + start，不改任何持久设置。
set -u
SER="${1:?serial}"; OUT="${2:?outdir}"; N="${3:-5}"
ADB="${ADB:-/usr/bin/adb}"
F="$OUT/$SER-coldstart.txt"; : > "$F"
echo "== coldstart_probe.sh serial=$SER n=$N date=$(date -Is) ==" >> "$F"
for i in $(seq 1 "$N"); do
  echo "--- run $i" >> "$F"
  { echo "\$ am force-stop"; timeout 60 "$ADB" -s "$SER" shell am force-stop com.takahashirinta.ncrust 2>&1
    sleep 2
    echo "\$ am start -W -n com.takahashirinta.ncrust/.MainActivity"
    timeout 120 "$ADB" -s "$SER" shell am start -W -n com.takahashirinta.ncrust/.MainActivity 2>&1
    echo "[exit=$?]"
    sleep 3
  } >> "$F"
done
echo "== done ==" >> "$F"
echo "wrote $F"
