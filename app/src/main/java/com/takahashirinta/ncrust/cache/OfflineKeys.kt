/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

/**
 * 离线音频缓存的 key 规则（v1.6.0 · D1）。**纯逻辑**，无 Android 依赖，JVM 可单测。
 *
 * ## 为什么不能直接用 URL 当 cache key
 *
 * v1.5.0 的调研（AGENTS.md「离线下载」节）实测：网易的播放 URL **每次都变**
 * （host 在 m704/m804 间轮换、路径里带签发时间戳、20 分钟过期）。media3 的
 * SimpleCache 默认按 URL 做 key，那样缓存**永远不可能命中** —— 每次取链都是一个新 key，
 * 一首歌放十遍就存十份。
 *
 * 但同一个实测还给出了一条关键事实：**URL 的 query 参数是装饰**（删掉/篡改仍然 206），
 * CDN 只认路径里的签名。所以我们可以往 URL 上挂一个自己的参数当 key，既不影响播放，
 * 又能让「同一首歌 + 同一档位」的所有 URL 映射到同一份缓存。
 *
 * key 的形状是 \`song:<songId>:<level>\` —— 档位进 key 是**故意**的：
 * 无损和高解析是两个不同的文件，混在一起会播出错音频。
 */
object OfflineKeys {

    /** 挂在播放 URL 上的 query 参数名。小写无下划线，避免被某些 CDN 规则改写。 */
    const val QUERY_KEY = "ncrustkey"

    /** \`song:123:lossless\`。 */
    fun key(songId: Long, level: String): String = "song:" + songId + ":" + level

    /** 把 key 挂到 URL 上（已有 query 用 & 连接）。 */
    fun withKey(url: String, songId: Long, level: String): String {
        if (url.isEmpty() || songId <= 0L || level.isEmpty()) return url
        if (url.contains(QUERY_KEY + "=")) return url
        val sep = if (url.contains("?")) "&" else "?"
        return url + sep + QUERY_KEY + "=" + key(songId, level)
    }

    /**
     * 从 URL 里取回 key；没有就返回 null（调用方回落到「用 URL 当 key」的默认行为）。
     * 只做字符串处理，不引 java.net.URI —— 播放 URL 里的签名含 \`+\` \`/\` \`=\`，URL 解析器可能报错。
     */
    fun keyOf(url: String): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        var i = q + 1
        while (i < url.length) {
            var end = url.indexOf('&', i)
            if (end < 0) end = url.length
            val eq = url.indexOf('=', i)
            if (eq in i until end && url.substring(i, eq) == QUERY_KEY) {
                val v = url.substring(eq + 1, end)
                return v.ifEmpty { null }
            }
            i = end + 1
        }
        return null
    }

    /** 从 key 里解出档位；解不出返回 null。 */
    fun levelOf(key: String): String? {
        val parts = key.split(':')
        return if (parts.size == 3 && parts[0] == "song") parts[2].ifEmpty { null } else null
    }

    /** 从 key 里解出歌曲 id；解不出返回 null。 */
    fun songIdOf(key: String): Long? {
        val parts = key.split(':')
        if (parts.size != 3 || parts[0] != "song") return null
        return parts[1].toLongOrNull()?.takeIf { it > 0L }
    }
}
