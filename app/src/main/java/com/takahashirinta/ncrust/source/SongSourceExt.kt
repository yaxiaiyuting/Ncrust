/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · A：把「这首歌属于哪个音源」的判定收在一处。**纯逻辑，JVM 可单测。**
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.network.SongItem

/**
 * 该曲所属音源。null / 未知值 ⇒ [MusicSource.NETEASE]（v2.1.0 之前的数据）。
 *
 * 命名成 `musicSource` 而不是 `source` 是有意的：[SongItem] 上已经有一个同名的
 * `String?` 字段，扩展属性重名会让调用方分不清拿到的是原始字符串还是枚举。
 */
val SongItem.musicSource: MusicSource
    get() = MusicSource.fromKey(source)

/**
 * 该曲**是否具备取链的必要信息**。
 *
 * 只有 QQ 音乐可能为 false（它必须带 songmid）；网易云恒为 true。
 * 调用方拿到 false 时应当把这首歌当「不可播放」（跳歌 / 灰显），
 * **不要**退回网易云取链 —— id 相同不代表是同一首歌。
 */
val SongItem.isResolvable: Boolean
    get() = !musicSource.requiresSourceId || !sourceId.isNullOrEmpty()

/** `netease:123` / `qqmusic:456`。用于缓存 key、日志与诊断。 */
val SongItem.trackKey: String
    get() = SourceIds.trackKey(musicSource, id)

/**
 * media3 `MediaItem` 用的 id：网易云仍是 `song:123`（与 v2.0.2 逐字节相同），
 * QQ 音乐是 `song:qqmusic:456`。[id] 非正时返回 null。
 */
val SongItem.mediaIdOrNull: String?
    get() = SourceIds.mediaId(musicSource, id)

/**
 * 队列内判重用的身份串。
 *
 * **不能用 [SongItem.id] 单独判重**：QQ 音乐的数字 songid 与网易云的 songId 各自独立编号，
 * 撞号是迟早的事，撞上就是「点了 QQ 的歌，播放器跳到了网易云那首同名 id 的歌」。
 */
val SongItem.dedupeKey: String
    get() = trackKey

/**
 * 从 `(source, id, sourceId)` 三件套构造一个 [SongItem]（只有 id 与来源，没有元数据）。
 *
 * 用在「只知道 id 就要把歌塞进队列」的场合（例如从 mediaId 反解、Android Auto browse tree）。
 * 元数据缺失时展示层会退回「加载中」而不是崩，这与既有行为一致。
 */
fun songRefOf(source: MusicSource, id: Long, sourceId: String? = null): SongItem = SongItem(
    id = id,
    name = "",
    artists = null,
    album = null,
    duration = null,
    source = if (source == MusicSource.NETEASE) null else source.key,
    sourceId = sourceId,
)
