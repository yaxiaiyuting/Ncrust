#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ncrust v2.4.0 探针公共库 —— 网易云 / QQ 音乐**匿名**只读客户端 + 名称归一化。

================================================================ 铁律 ====
* **只读**：只调用搜索 / 详情 / 播放地址预检三类读接口，不做任何写操作。
* **无凭证**：本机没有可用的登录态（连接的真机 WGR-W09 未 root、release 包不可
  `run-as`、`adb root` 被拒），因此**全部探针都是匿名态**。匿名态与登录态的差异
  在文档里逐条标注（会员专享曲的 `purl` 缺失不影响「可不可播」的判定方向）。
* **不落盘任何凭证**：本脚本不发登录请求，也不读任何 cookie。
* 落盘的 JSON 全部是**公开目录数据**（艺人 / 专辑 / 歌曲元数据），不含用户隐私。

设计要点（给下一个改探针的人）：

1. 两个音源的「同一个东西」在服务端**没有任何共同的 id**（无 ISRC、无指纹直通字段），
   所以跨源匹配只能靠**可观测属性**（名称 / 艺人 / 曲目数 / 时长 / 发行方）——
   这正是本版要量化的东西，探针必须把「属性一致到什么程度」逐项打出来。
2. 名称归一化函数 `normalize_*` 是**纯函数**，与 Kotlin 侧
   `crosssource/NameNormalizer.kt` 必须逐字对应 —— 探针算出来的准确率只有在
   两侧归一化一致时才代表线上行为。改动这里必须同步改 Kotlin 并重跑探针。
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import unicodedata
import urllib.parse
import urllib.request

REPO = "/home/duanjb666/deepseek/ncrust-gpl/Ncrust"
OUT = os.path.join(REPO, "docs/verification/v2.4.0")
RAW = os.path.join(OUT, "probe-raw")

UA_PC = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
UA_QQ = "QQMusic 14090008(android 10)"

QQ_COMM = {
    "ct": 11, "cv": 14090008, "v": 14090008, "chid": "10003505",
    "tmeAppID": "qqmusic", "QIMEI36": "0123456789abcdef0123456789abcdef0123",
    "uin": "0", "format": "json", "inCharset": "utf-8", "outCharset": "utf-8",
    "notice": 0, "platform": "wk_v17",
}

_sleep = 0.0


def polite(seconds: float = 0.35) -> None:
    """节流。探针是给人看的，不是压测 —— 别把服务端打出风控。"""
    global _sleep
    _sleep = seconds


def _get(url: str, *, data: bytes | None = None, headers: dict | None = None, timeout: int = 25):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    last = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read().decode("utf-8", "replace")
            if _sleep:
                time.sleep(_sleep)
            return body
        except Exception as exc:  # noqa: BLE001 - 探针要如实记录失败，不抛栈
            last = exc
            time.sleep(1.0 + attempt)
    raise RuntimeError("request failed: %s (%s)" % (url, last))


# ----------------------------------------------------------------- 网易云 ----

NE_BASE = "https://music.163.com"


def ne_post(path: str, form: dict) -> dict:
    body = urllib.parse.urlencode(form).encode()
    text = _get(
        NE_BASE + path,
        data=body,
        headers={
            "User-Agent": UA_PC,
            "Referer": NE_BASE + "/",
            "Content-Type": "application/x-www-form-urlencoded",
            "Cookie": "os=pc; appver=2.9.7",
        },
    )
    return json.loads(text)


def ne_get(path: str, query: dict | None = None) -> dict:
    url = NE_BASE + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    text = _get(url, headers={"User-Agent": UA_PC, "Referer": NE_BASE + "/",
                              "Cookie": "os=pc; appver=2.9.7"})
    return json.loads(text)


def ne_search(keyword: str, stype: int, limit: int = 30, offset: int = 0) -> dict:
    """stype: 1=单曲 10=专辑 100=艺人。"""
    return ne_post("/api/cloudsearch/pc",
                   {"s": keyword, "type": stype, "limit": limit, "offset": offset})


def ne_artist_albums(artist_id: int, limit: int = 100, offset: int = 0) -> dict:
    return ne_get("/api/artist/albums/%d" % artist_id, {"limit": limit, "offset": offset})


def ne_album(album_id: int) -> dict:
    return ne_get("/api/v1/album/%d" % album_id)


def ne_song_privileges(song_ids: list[int]) -> dict:
    """`/api/v3/song/detail` 一次批量拿 privilege —— 专辑页/艺人页判定版权的**唯一**入口。"""
    payload = json.dumps([{"id": i} for i in song_ids], separators=(",", ":"))
    return ne_post("/api/v3/song/detail", {"c": payload})


def ne_song_url(song_id: int, level: str = "standard") -> dict:
    """金标准：服务端最终给不给音频文件。探针用它给「可播放」打标。"""
    return ne_get("/api/song/enhance/player/url/v1",
                  {"ids": "[%d]" % song_id, "level": level, "encodeType": "mp3"})


# ----------------------------------------------------------------- QQ 音乐 ----

QQ_MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg"
QQ_LEGACY = "https://c.y.qq.com"


def qq_musicu(module: str, method: str, param: dict) -> dict:
    body = json.dumps({"comm": QQ_COMM, "req": {"module": module, "method": method, "param": param}},
                      ensure_ascii=False).encode()
    text = _get(QQ_MUSICU, data=body,
                headers={"User-Agent": UA_QQ, "Content-Type": "application/json",
                         "Referer": "https://y.qq.com/"})
    return json.loads(text)


def qq_legacy(path: str, query: dict) -> dict:
    url = QQ_LEGACY + path + "?" + urllib.parse.urlencode(query)
    text = _get(url, headers={"User-Agent": UA_PC, "Referer": "https://y.qq.com/"})
    # 旧版 CGI 有 JSONP 包装（callback=...），探针显式不要 callback
    text = text.strip()
    if text and not text.startswith("{"):
        text = text[text.index("{"):text.rindex("}") + 1]
    return json.loads(text)


def qq_search_singer(keyword: str, n: int = 30, p: int = 1) -> dict:
    """旧版 GET `client_search_cp`，t=9 是歌手。实测比 musicu 的 song 通道信息更全。"""
    return qq_legacy("/soso/fcgi-bin/client_search_cp",
                     {"w": keyword, "format": "json", "n": n, "p": p, "t": 9, "new_json": 1})


def qq_search_song(keyword: str, n: int = 30, p: int = 1) -> dict:
    return qq_legacy("/soso/fcgi-bin/client_search_cp",
                     {"w": keyword, "format": "json", "n": n, "p": p, "t": 0, "new_json": 1})


def qq_search_album(keyword: str, n: int = 30, p: int = 1) -> dict:
    return qq_legacy("/soso/fcgi-bin/client_search_cp",
                     {"w": keyword, "format": "json", "n": n, "p": p, "t": 8, "new_json": 1})


def qq_singer_albums(singer_mid: str, num: int = 100, begin: int = 0, order: int = 1) -> dict:
    """`fcg_v8_singer_album.fcg` —— 比 musicu 的 GetAlbumList 多给 `latest_song.song_count`。

    实测 `music.musichallAlbum.AlbumListServer/GetAlbumList` 每条 `totalNum` 恒为 0，
    曲目数要再打一次专辑详情才拿得到 ⇒ 艺人页聚合会变成 N+1 次请求。
    旧版 CGI 直接给 `song_count`，所以**艺人专辑列表走它**。
    """
    return qq_legacy("/v8/fcg-bin/fcg_v8_singer_album.fcg",
                     {"singermid": singer_mid, "num": num, "begin": begin,
                      "order": order, "format": "json", "platform": "yqq"})


def qq_album_songs(album_mid: str, num: int = 200, begin: int = 0) -> dict:
    return qq_musicu("music.musichallAlbum.AlbumSongList", "GetAlbumSongList",
                     {"albumMid": album_mid, "begin": begin, "num": num})


def qq_song_detail(song_mid: str, song_id: int = 0) -> dict:
    return qq_musicu("music.pf_song_detail_svr", "get_song_detail_yqq",
                     {"song_type": 0, "song_mid": song_mid, "song_id": song_id})


QQ_QUALITY_PREFIXES = [("AI00", "flac"), ("RS01", "flac"), ("F000", "flac"),
                       ("M800", "mp3"), ("M500", "mp3")]


def qq_vkey_batch(song_mids: list[str], media_mids: dict[str, str] | None = None) -> dict:
    """批量播放地址预检（**一次请求 ≤ 100 首**）。

    `midurlinfo[].purl` 为空 + `result == 104003` ⇒ 需要登录/VIP（会员专享或受地区限制）；
    `purl` 非空 ⇒ 服务端愿意给这个文件，也就是**真的能播**。

    这是 QQ 侧唯一的「可播放性」真值来源 —— 匿名响应里的 `pay` / `action` 字段
    都是**标签**，而铁律 7 说得很清楚：标签不可信。探针因此同时采两路，做 A/B 对照。
    """
    media_mids = media_mids or {}
    filenames, mids, types = [], [], []
    for mid in song_mids:
        mm = media_mids.get(mid, mid)
        filenames.append("M500%s.mp3" % mm)
        mids.append(mid)
        types.append(0)
    return qq_musicu("vkey.GetVkeyServer", "CgiGetVkey",
                     {"guid": "10000", "songmid": mids, "songtype": types,
                      "uin": "0", "loginflag": 1, "platform": "20", "filename": filenames})


# ------------------------------------------------------------- 名称归一化 ----

# 括注类后缀：（）() 【】[] 〈〉 以及中英文破折号后面的补充说明。
_BRACKET = re.compile(r"[（(\[【〈<][^）)\]】〉>]*[）)\]】〉>]")
# 常见「同一张专辑的不同版本」标记。归一化后必须相等，否则同一张专辑会被拆成两张。
_VERSION_WORDS = [
    "deluxe edition", "deluxe version", "deluxe", "remastered", "remaster",
    "special edition", "limited edition", "collector's edition", "anniversary edition",
    "豪华版", "豪華版", "典藏版", "纪念版", "紀念版", "特别版", "特別版", "限量版",
    "珍藏版", "加强版", "数字版", "數位版", "完整版", "精装版", "精裝版",
]
# 版本/介质描述：出现即视为「同一作品的不同版本」，匹配时应降级而不是判不等。
_EDITION_MARKERS = re.compile(
    r"(?i)\b(deluxe|remaster(ed)?|special|limited|collector'?s?|anniversary|"
    r"live|acoustic|instrumental|remix|demo|mono|stereo|ep|single|bonus)\b"
    r"|豪华版|豪華版|典藏版|纪念版|紀念版|特别版|特別版|限量版|珍藏版|加强版|"
    r"现场|現場|演唱会|演唱會|翻唱|伴奏|纯音乐|純音樂|重制|重置"
)

_PUNCT = re.compile(r"[\s\u3000!-/:-@\[-`{-~·・…—–\-_,.;:!?'\"“”‘’（）()【】\[\]《》〈〉、。，！？]+")


def to_halfwidth(text: str) -> str:
    """全角 → 半角（NFKC 同时处理了罗马数字、带圈字符等）。"""
    return unicodedata.normalize("NFKC", text or "")


def strip_brackets(text: str) -> str:
    return _BRACKET.sub("", text or "")


def normalize_name(text: str) -> str:
    """专辑/单曲/艺人名的**强归一化**：用于「完全一致」判据（EXACT）。

    顺序很重要：先去括注 → 再去版本词 → 再去标点空白 → 最后小写。
    先去掉版本词再去标点，否则 `(Deluxe Edition)` 会在第一步被整个括注规则吃掉，
    而 `Deluxe Edition`（没有括号）只会被版本词规则吃掉 —— 两条路必须收敛到同一个结果。
    """
    s = to_halfwidth(text)
    s = strip_brackets(s)
    low = s.lower()
    for w in _VERSION_WORDS:
        low = low.replace(w, " ")
    s = _PUNCT.sub("", low)
    return s


def normalize_loose(text: str) -> str:
    """宽松归一化：保留括注内容、只做全角/标点/大小写。用于「可能同源的候选」召回。"""
    s = to_halfwidth(text).lower()
    return _PUNCT.sub("", s)


def has_edition_marker(text: str) -> bool:
    return bool(_EDITION_MARKERS.search(to_halfwidth(text or "")))


def artist_names_of_ne(song_or_album: dict) -> list[str]:
    for key in ("artists", "ar"):
        arr = song_or_album.get(key)
        if isinstance(arr, list):
            return [a.get("name", "") for a in arr if a.get("name")]
    return []


def artist_names_of_qq(obj: dict) -> list[str]:
    arr = obj.get("singer") or obj.get("singer_list") or []
    return [a.get("name") or a.get("singer_name") or "" for a in arr if (a.get("name") or a.get("singer_name"))]


# ------------------------------------------------------------------ 落盘 ----

def save_raw(name: str, obj) -> str:
    os.makedirs(RAW, exist_ok=True)
    path = os.path.join(RAW, name)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=1)
    return path


def write_md(name: str, lines: list[str]) -> str:
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines).rstrip() + "\n")
    return path


def jaccard(a: set, b: set) -> float:
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def overlap(a: set, b: set) -> int:
    return len(a & b)
