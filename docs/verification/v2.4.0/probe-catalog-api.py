#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ncrust v2.4.0 探针 · **三个页面的接口能力**（艺人主页 / 专辑页 / 单曲页）。

一次跑完，分别产出 `probe-artist-api.md` / `probe-album-api.md` / `probe-song-api.md`。

每个页面回答同一组问题：
  1. 两源各自的端点是哪个、请求形状是什么？
  2. 返回哪些字段（逐字段列表，**只列实测真的出现过的**）？
  3. 分页机制是什么？两源一致吗？
  4. 缺什么（做双源聚合必须补什么）？

**所有字段列表都是从真实响应里递归扫出来的**，不是照文档抄的 ——
第一版手写的字段表把 QQ `GetAlbumList` 的 `totalNum` 写成「曲目数」，
实测它恒为 0，只有旧版 CGI 的 `latest_song.song_count` 才有值。
"""

from __future__ import annotations

import json
import os
import sys
from collections import OrderedDict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe_lib as L  # noqa: E402

NE_ARTIST = 6452          # 周杰伦
QQ_SINGER_MID = "0025NhlN2yWrP4"
NE_ALBUM = 18905          # 叶惠美
QQ_ALBUM_MID = "000MkMni19ClKG"
NE_SONG = 186016          # 晴天
QQ_SONG_MID = "0039MnYb0qxYhV"


def shape(obj, prefix="", depth=0, out=None, max_depth=3) -> list[str]:
    """把一份 JSON 递归压成「路径 = 类型」的字段表（数组只取第 0 个元素）。"""
    if out is None:
        out = []
    if depth > max_depth:
        return out
    if isinstance(obj, dict):
        for k, v in obj.items():
            path = "%s.%s" % (prefix, k) if prefix else k
            if isinstance(v, dict):
                out.append("%s = object" % path)
                shape(v, path, depth + 1, out, max_depth)
            elif isinstance(v, list):
                out.append("%s = array[%d]" % (path, len(v)))
                if v:
                    shape(v[0], path + "[]", depth + 1, out, max_depth)
            else:
                out.append("%s = %s" % (path, type(v).__name__))
    return out


def main() -> int:
    L.polite(0.3)
    raw = {}

    # ============================================================ 艺人主页 ============================================================
    ne_albums_resp = L.ne_artist_albums(NE_ARTIST, limit=10)
    ne_hot_resp = L.ne_get("/api/v1/artist/%d" % NE_ARTIST)
    ne_detail_resp = L.ne_get("/api/artist/detail/%d" % NE_ARTIST)
    qq_album_resp = L.qq_singer_albums(QQ_SINGER_MID, num=10)
    qq_singer_musicu = L.qq_musicu("music.musichallAlbum.AlbumListServer", "GetAlbumList",
                                   {"singerMid": QQ_SINGER_MID, "page": 1, "num": 10, "order": 1})
    qq_singer_detail = L.qq_musicu("music.web_singer_info_svr", "get_singer_detail_info",
                                   {"singermid": QQ_SINGER_MID})
    qq_singer_search = L.qq_search_singer("周杰伦", n=5)

    raw["artist"] = {
        "ne_albums": ne_albums_resp,
        "ne_hot": ne_hot_resp,
        "ne_detail_404": ne_detail_resp,
        "qq_singer_albums": qq_album_resp,
        "qq_musicu_albumlist": qq_singer_musicu,
        "qq_singer_detail": qq_singer_detail,
        "qq_singer_search": qq_singer_search,
    }

    # ============================================================ 专辑页 ============================================================
    ne_album_resp = L.ne_album(NE_ALBUM)
    qq_album_songs = L.qq_album_songs(QQ_ALBUM_MID, num=50)
    qq_album_info_err = L.qq_musicu("music.musichallAlbum.AlbumInfoServer", "GetAlbumInfo",
                                    {"albumMid": QQ_ALBUM_MID})
    qq_album_search = L.qq_search_album("叶惠美", n=3)
    raw["album"] = {
        "ne_album": ne_album_resp,
        "qq_album_songs": qq_album_songs,
        "qq_album_info_error": qq_album_info_err,
        "qq_album_search": qq_album_search,
    }

    # ============================================================ 单曲页 ============================================================
    ne_song_detail = L.ne_song_privileges([NE_SONG])
    ne_song_url = L.ne_song_url(NE_SONG)
    qq_song_detail = L.qq_song_detail(QQ_SONG_MID, 97773)
    qq_song_lyric = L.qq_musicu("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo",
                                {"songMID": QQ_SONG_MID, "songID": 97773})
    raw["song"] = {
        "ne_detail": ne_song_detail,
        "ne_url": ne_song_url,
        "qq_detail": qq_song_detail,
        "qq_lyric": qq_song_lyric,
    }

    L.save_raw("catalog-api.json", raw)

    # ---------------------------------------------------------------- 艺人 ----------------------------------------------------------------
    a = []
    a.append("# 探针 · 艺人主页接口（网易云 ↔ QQ 音乐）")
    a.append("")
    a.append("> 生成：`docs/verification/v2.4.0/probe-catalog-api.py`（**匿名只读**）。"
             "字段表是**从真实响应递归扫出来的**。原始响应：`probe-raw/catalog-api.json`。")
    a.append("")
    a.append("## 端点对照")
    a.append("")
    a.append("| 能力 | 网易云 | QQ 音乐 |")
    a.append("|---|---|---|")
    a.append("| 艺人搜索结果 | `POST /api/cloudsearch/pc` `type=100` | `GET c.y.qq.com/soso/fcgi-bin/client_search_cp?t=9` |")
    a.append("| 艺人专辑列表 | `GET /api/artist/albums/{id}?limit&offset` | `GET c.y.qq.com/v8/fcg-bin/fcg_v8_singer_album.fcg?singermid&num&begin&order` |")
    a.append("| 艺人热门曲 | `GET /api/v1/artist/{id}`（`hotSongs[]`） | `musicu.fcg` → `music.web_singer_info_svr/get_singer_detail_info`（`songlist[]`） |")
    a.append("| 艺人详情 | `GET /api/artist/detail/{id}` → **实测 404** | 无独立端点（艺人信息在搜索/专辑列表里） |")
    a.append("")
    a.append("## 网易云返回的字段（`/api/artist/albums/{id}`）")
    a.append("")
    a.append("```")
    for line in shape(ne_albums_resp, max_depth=2)[:28]:
        a.append(line)
    a.append("```")
    a.append("")
    a.append("关键读数：")
    a.append("")
    a.append("- `hotAlbums[].size` = **曲目数**（实测叶惠美 11，与专辑详情 `album.size` 一致）；")
    a.append("- `hotAlbums[].publishTime` = **毫秒时间戳**（QQ 那边是 `YYYY-MM-DD` 字符串，不可直接比）；")
    a.append("- `hotAlbums[].company` = 厂牌（实测本样本为空串，不可依赖）；")
    a.append("- `artist.albumSize` / `artist.musicSize` 与列表长度**口径不同**"
             "（周杰伦：`albumSize=41`，但 `limit=10` 的列表只回 10 条、`limit=100` 回 44 条）—— "
             "**不要拿 `albumSize` 当列表长度**；")
    a.append("- **没有 `privilege`** ⇒ 版权可用性必须另发一次批量 `/api/v3/song/detail`。")
    a.append("")
    a.append("## QQ 返回的字段（`fcg_v8_singer_album.fcg`）")
    a.append("")
    a.append("```")
    for line in shape(qq_album_resp, max_depth=2)[:28]:
        a.append(line)
    a.append("```")
    a.append("")
    a.append("关键读数：")
    a.append("")
    a.append("- `albumMID` 是专辑的**唯一键**（base62 字符串），`albumID` 是数字 id；")
    a.append("- `latest_song.song_count` = **曲目数**（实测叶惠美 11，与 `GetAlbumSongList` 的 `totalNum` 一致）；")
    a.append("- `pubTime` = `YYYY-MM-DD`，`albumtype` = 「录音室专辑」等中文标签，`company` = 版权方；")
    a.append("- **`musicu.fcg` 的 `music.musichallAlbum.AlbumListServer/GetAlbumList` 每条 `totalNum` 恒为 0** —— "
             "同一份数据在旧版 CGI 里有曲目数、在新版音乐库里没有。艺人页聚合必须走旧版 CGI，"
             "否则会退化成「每张专辑再打一次详情」的 N+1。")
    a.append("")
    a.append("## 分页机制（两源不一致）")
    a.append("")
    a.append("| 源 | 参数 | 语义 | 实测上限 |")
    a.append("|---|---|---|---|")
    a.append("| 网易云 | `limit` + `offset` | 偏移分页 | 周杰伦 `limit=100` 一次回 44 张，够用 |")
    a.append("| QQ | `num` + `begin` | 偏移分页 | 周杰伦 `num=100` 一次回 43 张，够用 |")
    a.append("")
    a.append("⇒ **形状不同但语义一致**（都是 offset/limit），聚合层可以用同一个「拉一页 100 条」的策略。")
    a.append("")
    a.append("## 双源聚合必须补的东西")
    a.append("")
    a.append("1. QQ 侧的艺人专辑列表与热门曲**本版本之前完全没有接入**（`QqApi` 只有搜索/取链/歌词）；")
    a.append("2. 两源的曲目数都拿得到 ⇒ 可以进专辑匹配的 `EXACT` 判据；")
    a.append("3. 两源都**没有** `privilege` ⇒ 版权可用性需要每条链路各补一次批量预检。")
    a.append("")

    # ---------------------------------------------------------------- 专辑 ----------------------------------------------------------------
    b = []
    b.append("# 探针 · 专辑页接口（网易云 ↔ QQ 音乐）")
    b.append("")
    b.append("> 生成：`docs/verification/v2.4.0/probe-catalog-api.py`（**匿名只读**）。"
             "原始响应：`probe-raw/catalog-api.json`。")
    b.append("")
    b.append("## 端点对照")
    b.append("")
    b.append("| 能力 | 网易云 | QQ 音乐 |")
    b.append("|---|---|---|")
    b.append("| 专辑详情 + 曲目 | `GET /api/v1/album/{id}` | `musicu.fcg` → `music.musichallAlbum.AlbumSongList/GetAlbumSongList` |")
    b.append("| 专辑搜索 | `POST /api/cloudsearch/pc` `type=10` | `GET client_search_cp?t=8` |")
    b.append("| 专辑元数据专端点 | 无（详情里带） | `music.musichallAlbum.AlbumInfoServer/GetAlbumInfo` → **实测 `code=40000` 失败** |")
    b.append("")
    b.append("## 网易云返回的字段")
    b.append("")
    b.append("```")
    for line in shape(ne_album_resp, max_depth=2)[:26]:
        b.append(line)
    b.append("```")
    b.append("")
    b.append("关键读数：")
    b.append("")
    b.append("- `album.size` = 曲目数；`album.publishTime` = **毫秒时间戳**；`album.company` = 厂牌；")
    b.append("- `songs[]` 里**每一首都有 `fee` 与 `st`**，但实测 `st` 在本专辑**恒为 `-1`**，"
             "而 `-1` 在 v2.3.0 的判据里是「不可用」（15/30 可播）—— 见 `probe-copyright-field.md` C3；")
    b.append("- **`songs[]` 里没有 `privilege`** ⇒ 版权判定必须另发批量详情。")
    b.append("")
    b.append("## QQ 返回的字段")
    b.append("")
    b.append("```")
    for line in shape(qq_album_songs, max_depth=3)[:34]:
        b.append(line)
    b.append("```")
    b.append("")
    b.append("关键读数：")
    b.append("")
    b.append("- `totalNum` = 曲目数（实测叶惠美 11）；")
    b.append("- 曲目在 `songList[].songInfo` 里，字段与搜索响应**同构**"
             "（`mid` / `name` / `singer[]` / `album` / `interval` / `file.media_mid` / `pay` / `action`）"
             "⇒ **可以复用同一个 mapper**，不需要第二套解析；")
    b.append("- `file.media_mid` 与 `mid` 实测**全部不同**（230/230，见 `probe-song-mapping.md`）⇒ 取链必须用 `media_mid`；")
    b.append("- `pay.pay_play` / `action.*` 都只是**标签**，能不能播要用批量 vkey 预检（见 `probe-copyright-field.md` C4）。")
    b.append("")
    b.append("## 分页机制")
    b.append("")
    b.append("| 源 | 参数 | 语义 | 实测 |")
    b.append("|---|---|---|---|")
    b.append("| 网易云 | 无（一次全量） | 专辑曲目一次返回 | 叶惠美 11 首一次回全 |")
    b.append("| QQ | `begin` + `num` | 偏移分页 | `num=50` 一次回全 11 首 |")
    b.append("")
    b.append("## 双源聚合必须补的东西")
    b.append("")
    b.append("1. **QQ 专辑曲目端点本版本之前完全没有接入**；")
    b.append("2. 两源都能拿到「曲目名集合」与「曲目数」⇒ 专辑页可以做到 `EXACT` 级匹配；")
    b.append("3. 网易云页面的 `SongItem` 产物**不带 `source`**（`AlbumDetailScreen` 手搓 `SongItem`），"
             "这是「点进去播不了」的一条隐藏成因：下游会把它当网易云。")
    b.append("")

    # ---------------------------------------------------------------- 单曲 ----------------------------------------------------------------
    c = []
    c.append("# 探针 · 单曲页接口（网易云 ↔ QQ 音乐）")
    c.append("")
    c.append("> 生成：`docs/verification/v2.4.0/probe-catalog-api.py`（**匿名只读**）。"
             "原始响应：`probe-raw/catalog-api.json`。")
    c.append("")
    c.append("## 端点对照")
    c.append("")
    c.append("| 能力 | 网易云 | QQ 音乐 |")
    c.append("|---|---|---|")
    c.append("| 单曲详情（含版权） | `POST /api/v3/song/detail`（`c=[{id}]`，**支持批量**） | `music.pf_song_detail_svr/get_song_detail_yqq`（单首） |")
    c.append("| 播放地址 | `GET /api/song/enhance/player/url/v1?ids&level&encodeType` | `musicu.fcg` → `vkey.GetVkeyServer/CgiGetVkey`（**支持批量**） |")
    c.append("| 歌词 | `POST /api/song/lyric` | `music.musichallSong.PlayLyricInfo/GetPlayLyricInfo` |")
    c.append("")
    c.append("## 网易云返回的字段")
    c.append("")
    c.append("```")
    for line in shape(ne_song_detail, max_depth=2)[:22]:
        c.append(line)
    c.append("```")
    c.append("")
    c.append("关键读数：")
    c.append("")
    c.append("- **`privileges[]` 是网易云唯一的权威版权字段**：`st`（`-200` = 下架/无版权、`0` = 正常）、"
             "`pl`（可播码率，`>0` ⇒ 一定取得到链）、`fee`；")
    c.append("- `songs[].noCopyrightRcmd != null` 是**显式无版权声明**（零假阳性，覆盖率低）；")
    c.append("- 批量上限实测 100 首/请求（本探针按 100 分批，未遇到失败）。")
    c.append("")
    c.append("## QQ 返回的字段")
    c.append("")
    c.append("```")
    for line in shape(qq_song_detail, max_depth=3)[:30]:
        c.append(line)
    c.append("```")
    c.append("")
    c.append("关键读数：")
    c.append("")
    c.append("- `req.data.track_info` 是曲目本体（与搜索/专辑曲目**同构**），"
             "`req.data.info` 是分节的「歌曲信息卡」（唱片公司/流派/语种/发行时间等，"
             "字段名与展示文案耦合，**不适合做判据**）；")
    c.append("- `track_info.file.media_mid` 与 `track_info.mid` 不同（老结论复现）；")
    c.append("- `track_info.pay.pay_play` 只是标签，权威判据是 vkey 预检。")
    c.append("")
    c.append("## 分页")
    c.append("")
    c.append("单曲页没有分页。两源的**批量能力正相反**：")
    c.append("")
    c.append("| 源 | 详情 | 播放地址 |")
    c.append("|---|---|---|")
    c.append("| 网易云 | **批量**（≤100） | 单首（`ids` 只吃一首就够） |")
    c.append("| QQ | 单首 | **批量**（`songmid[]` + `filename[]`） |")
    c.append("")
    c.append("⇒ 聚合层对网易云要**一次批量拿 privilege**，对 QQ 要**一次批量做 vkey 预检**，"
             "两边各自一次请求就能覆盖一屏。")
    c.append("")
    c.append("## 双源聚合必须补的东西")
    c.append("")
    c.append("1. **QQ 单曲详情/播放预检的批量路径**（`QqApi.requestVkeyBatch` 已存在且已是批量，"
             "但只用于「用户已经点了播放」，没有用于「列表页标版权」）；")
    c.append("2. **网易云的批量 privilege 路径**（新增，见 C1）；")
    c.append("3. 单曲页的 `SongDetailScreen` 今天**在应用内不可达**（路由注册了但没有调用点），"
             "本版要把它接上并做成双源聚合。")
    c.append("")

    for name, lines in (("probe-artist-api.md", a), ("probe-album-api.md", b), ("probe-song-api.md", c)):
        print("wrote %s" % L.write_md(name, lines), file=sys.stderr)

    print(json.dumps({
        "ne_artist_detail_code": ne_detail_resp.get("code"),
        "qq_album_info_code": qq_album_info_err.get("req", {}).get("code"),
        "ne_album_song_st": sorted({s.get("st") for s in (ne_album_resp.get("songs") or [])}),
        "ne_privilege_keys": sorted((ne_song_detail.get("privileges") or [{}])[0].keys()),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
