#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PlayReporter 跨音源泄露探针 · 纯逻辑复算（不依赖 Android / 不联网）

复算两件事，全部对应 app 源码里的**逐行事实**（行号见 probe-playreporter.md §2）：

  A. `SourceIds.qqId()` 造出来的 id 在 64 位有符号 Long 域里长什么样
     —— 十进制值 / 符号 / 是否 > 0，以及 `isQqId()` 的判定。
  B. `PlayReporter.reportPlay()` 的入口卫语句逐条对一个 QQ 合成 id 求值，
     以及最终会拼出的上报 JSON 体。

这不是「把 app 代码搬过来跑」，而是**同一套位运算与判定的等价复算**；
脚本里每一条判定都注明了源文件与行号，便于人工逐条比对。

用法：
    python3 bit62_guard_probe.py
"""

MASK64 = (1 << 64) - 1


def as_signed_long(v: int) -> int:
    """把任意整数折成 Java/Kotlin 的 64 位有符号 long 语义。"""
    v &= MASK64
    return v - (1 << 64) if v >= (1 << 63) else v


QQ_ID_FLAG = as_signed_long(1 << 62)  # SourceIds.QQ_ID_FLAG = 1L shl 62

# 真机实测样本（PCL110 / Android 16 / v2.5.4，见 device-evidence.md）：
#   /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_playback_state.xml
#   <long name="song_id" value="4611686018784987997" />
#   song_name=怪我太天真  song_artist=苏谭谭  song_source=qqmusic
#   song_source_id=001A7neE3LB7Ki  song_media_id=000g0rin0v8VU2
DEVICE_QQ_ID = 4611686018784987997

CASES = [
    ("真机实测 QQ 曲目（怪我太天真 / 苏谭谭）", DEVICE_QQ_ID),
    ("真机实测 QQ 曲目反解出的裸 songid", 357600093),
    ("网易云样本（S6 落盘的 song_id）", 557920),
    ("网易云《晴天》", 186016),
    ("1L shl 62 本身（标志位）", 1 << 62),
    ("QQ 标志位 + 1000000000", (1 << 62) + 1000000000),
    ("QQ 标志位 + 1", (1 << 62) + 1),
]


def is_qq_id(v: int) -> bool:
    """SourceIds.kt:167  `fun isQqId(id: Long) = (id and QQ_ID_FLAG) != 0L`"""
    return (v & QQ_ID_FLAG) != 0


def source_of_id(v: int) -> str:
    """SourceIds.kt:188  `sourceOfId = if (isQqId(id)) QQMUSIC else NETEASE`"""
    return "QQMUSIC" if is_qq_id(v) else "NETEASE"


def qq_raw_id(v: int) -> int:
    """SourceIds.kt:209  `qqRawId = if (isQqId(id)) id and (QQ_ID_FLAG - 1) else null`"""
    return v & (QQ_ID_FLAG - 1) if is_qq_id(v) else None


print("=" * 78)
print("A. bit62 合成 id 的 64 位有符号 Long 形态")
print("=" * 78)
print("QQ_ID_FLAG = 1L shl 62 =", QQ_ID_FLAG)
print("2^63 - 1 (Long.MAX_VALUE) =", as_signed_long((1 << 63) - 1))
print()
fmt = "{:<42} {:>22} {:>6} {:>8} {:>9} {:>9} {:>14}"
print(fmt.format("样本", "十进制值", "符号", "> 0 ?", "isQqId", "sourceOfId", "qqRawId"))
print("-" * 118)
for label, raw in CASES:
    v = as_signed_long(raw)
    print(fmt.format(
        label,
        str(v),
        "-" if v < 0 else "+",
        "yes" if v > 0 else "no",
        str(is_qq_id(v)),
        source_of_id(v),
        str(qq_raw_id(v)),
    ))

print()
print("判据结论：bit62 置位后 **符号位（bit63）仍为 0**，所以 id 依然是一个巨大的正数，")
print("`songId <= 0` 这条卫语句**拦不住**它；而 `isQqId()` 是唯一能一眼分辨音源的判据。")
print()

print("=" * 78)
print("B. PlayReporter.reportPlay() 入口卫语句逐条求值")
print("=" * 78)


def report_play_guards(song_id: int, cookie: str, csrf: str):
    """逐条复算 PlayReporter.kt:47-51 的入口卫语句，返回 (是否通过, 逐条结果)。"""
    steps = []
    # L47: val cookie = RetrofitClient.getCookie() ?: return
    steps.append(("L47  getCookie() != null", cookie is not None, "cookie=%s" % ("<非空>" if cookie else "null")))
    if cookie is None:
        return False, steps
    # L48: if (!cookie.contains("MUSIC_U") || songId <= 0) return
    has_music_u = "MUSIC_U" in cookie
    steps.append(("L48a cookie.contains(\"MUSIC_U\")", has_music_u, "网易云登录态"))
    steps.append(("L48b songId <= 0", not (song_id <= 0), "songId=%d ⇒ songId<=0 为 %s" % (song_id, song_id <= 0)))
    if not has_music_u or song_id <= 0:
        return False, steps
    # L50: val csrf = RetrofitClient.getCsrfToken() ?: return
    steps.append(("L50  getCsrfToken() != null", csrf is not None, "cookie 里是否有 __csrf="))
    if csrf is None:
        return False, steps
    return True, steps


for label, raw in CASES:
    sid = as_signed_long(raw)
    print()
    print("--- 样本：%s" % label)
    print("    songId = %d   （sourceOfId = %s）" % (sid, source_of_id(sid)))
    ok, steps = report_play_guards(sid, cookie="MUSIC_U=...; __csrf=deadbeef", csrf="deadbeef")
    for name, passed, note in steps:
        print("    [%s] %-38s %s" % ("PASS" if passed else "BLOCK", name, note))
    print("    => %s" % ("通过全部卫语句，**会发出上报**" if ok else "被拦下，不上报"))
    if ok:
        # L53-66 构造 JSON（字段顺序与源码一致）
        body = (
            '{"type":"song","wifi":1,"download":0,"id":%d,"time":%d,"end":"playend",'
            '"mainsite":"1","mainsiteWeb":"1"}' % (sid, 200000)
        )
        logs = '[{"action":"play","json":%s}]' % body
        print("    将 POST 到 https://clientlogusf.music.163.com/api/feedback/weblog?csrf_token=deadbeef")
        print("    form: logs=%s" % logs)

print()
print("=" * 78)
print("C. reachedCompletion 的浮点判定（纯函数，与音源无关）")
print("=" * 78)
THRESHOLD = 0.8  # PlayReporter.kt:28


def reached_completion(position_ms: int, duration_ms: int) -> bool:
    """PlayReporter.kt:82  `durationMs > 0 && positionMs.toFloat()/durationMs >= 0.8f`"""
    if duration_ms <= 0:
        return False
    return (position_ms / duration_ms) >= THRESHOLD


for pos, dur in [(0, 0), (0, 200000), (159999, 200000), (160000, 200000), (200000, 200000)]:
    print("    reachedCompletion(pos=%7d, dur=%7d) = %s" % (pos, dur, reached_completion(pos, dur)))
print("    判定只看两个 Long 的比值，**输入里没有音源维度** ⇒ 对 QQ 曲目与网易云曲目完全同构。")
