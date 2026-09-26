/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.1 · P0：艺人跳转的**路由契约**单测（必须带 source + source 与 id 值域必须匹配）。
 */

package com.takahashirinta.ncrust.ui.navigation

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.ArtistNav
import com.takahashirinta.ncrust.source.ArtistNavigator
import com.takahashirinta.ncrust.source.MusicSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.1 · P0：把「艺人跳转必须带音源」从一句注释变成**会变红的契约**。
 *
 * ## 背景（为什么这条值得单独一个文件）
 *
 * v2.4.0 就加了带音源的两段路由 `artist/{source}/{artistId}`，但到 v2.6.0 为止
 * 它**只有两个调用点，且都来自硬编码网易云的专辑页** —— 也就是说
 * 「QQ 主源歌手页」在真机上**根本不可达**，而 5 个入口全都走那条
 * 把音源写死成网易云的老路由。这个 P0 的形状就是「新路由建好了，老入口没人改」。
 *
 * 所以这里做三件事：
 *
 * 1. [带音源的艺人路由形状固定] —— 路由串本身的形状（回归时会立刻看出来）；
 * 2. [解析器产出的每一个 Direct 都能编码进路由并按原值还原] —— 端到端往返；
 * 3. [源码扫描：所有 NavRoutes_artist 调用点都必须带音源] —— 这条防的是
 *    **将来**有人再加一个入口时顺手用了老的单参数重载。它不是风格检查：
 *    老重载在 composable 里把 source 写成 NETEASE，用错的表现就是本 P0。
 */
class ArtistRouteContractTest {

    // ------------------------------------------------------------ 1. 路由形状

    @Test
    fun `带音源的艺人路由形状固定`() {
        assertEquals(
            "artist/qqmusic/0025NhlN2yWrP4",
            NavRoutes.artist(MusicSource.QQMUSIC, "0025NhlN2yWrP4"),
        )
        assertEquals("artist/netease/6452", NavRoutes.artist(MusicSource.NETEASE, "6452"))
        // 路由模板本身也钉住：改成一段就没法带音源了。
        assertEquals("artist/{source}/{artistId}", NavRoutes.ARTIST_SRC)
        assertEquals("artist/{artistId}", NavRoutes.ARTIST)
    }

    @Test
    fun `带音源的路由与老路由不会互相匹配`() {
        // 导航库按路径段数匹配：老模板吃 1 段、新模板吃 2 段。
        // 这条用例锁住 v2.4.0 的设计前提（两套路由可以并存）。
        assertFalse(
            "artist/4558 是 1 段，不能匹配 artist/{source}/{artistId}",
            matches(NavRoutes.ARTIST_SRC, NavRoutes.artist(4558L)),
        )
        assertTrue(matches(NavRoutes.ARTIST, NavRoutes.artist(4558L)))
        assertTrue(matches(NavRoutes.ARTIST_SRC, NavRoutes.artist(MusicSource.NETEASE, "6452")))
        assertFalse(matches(NavRoutes.ARTIST, NavRoutes.artist(MusicSource.NETEASE, "6452")))
    }

    // ------------------------------------------- 2. 解析器 → 路由：往返一致

    @Test
    fun `解析器产出的每一个 Direct 都能编码进路由并按原值还原`() {
        val songs = listOf(
            // 主路径：QQ 有 singerMID
            song(MusicSource.QQMUSIC, ArtistItem(id = 4558L, name = "周杰伦", mid = "0025NhlN2yWrP4")),
            // 主路径：网易云十进制
            song(MusicSource.NETEASE, ArtistItem(id = 6452L, name = "周杰伦")),
            // P0 本体：只有数字 QQ id
            song(MusicSource.QQMUSIC, ArtistItem(id = 4558L, name = "周杰伦")),
            // 冷启动恢复：只有名字
            song(MusicSource.QQMUSIC, ArtistItem(name = "周杰伦")),
            // 边界：QQ mid 形如 base62 但很短
            song(MusicSource.QQMUSIC, ArtistItem(id = 1L, name = "某人", mid = "00aBc")),
        )

        for (s in songs) {
            when (val nav = ArtistNavigator.resolve(s)) {
                is ArtistNav.Direct -> {
                    val route = NavRoutes.artist(nav.source, nav.id)
                    val parsed = parseArtistSrc(route)
                    assertNotNull("带音源路由必须能被解析回来：$route", parsed)
                    assertEquals("路由里的 source 必须与决策一致", nav.source, parsed!!.first)
                    assertEquals("路由里的 id 必须与决策一致", nav.id, parsed.second)
                    assertTrue(
                        "路由里的 id 必须满足该音源的值域：$route",
                        ArtistNavigator.idDomainMatches(nav.source, parsed.second),
                    )
                    // 本 P0 的核心断言：QQ 的数字 singerID（4558）不许出现在任何路由里。
                    assertFalse(
                        "QQ 曲目的路由不许带数字 singerID：$route",
                        nav.source == MusicSource.QQMUSIC && parsed.second == "4558",
                    )
                }

                is ArtistNav.Search -> assertTrue(
                    "降级为搜索时必须有关键词：$nav",
                    nav.keyword.isNotBlank(),
                )

                ArtistNav.Unavailable -> Unit
            }
        }
    }

    // ------------------------------------------------- 3. 源码扫描：入口闸门

    @Test
    fun `所有艺人导航调用点都必须带音源`() {
        val offenders = mutableListOf<String>()
        for (f in kotlinSources()) {
            f.readLines().forEachIndexed { i, raw ->
                val line = raw.substringBefore("//")
                val trimmed = line.trim()
                // 跳过注释行：KDoc 里**必须**能提「老重载叫什么」，
                // 否则这条契约的说明本身就成了违规。只扫真代码。
                if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.isEmpty()) {
                    return@forEachIndexed
                }
                // 找 `NavRoutes.artist(`，再看它后面第一个实参是不是一个 MusicSource。
                val idx = line.indexOf("NavRoutes.artist(")
                if (idx < 0) return@forEachIndexed
                val args = line.substring(idx + "NavRoutes.artist(".length)
                val firstArg = args.substringBefore(',').trim()
                val carriesSource = firstArg.startsWith("MusicSource.") ||
                    firstArg.endsWith(".source") ||
                    firstArg.contains("musicSource") ||
                    firstArg == "source"
                if (!carriesSource) {
                    offenders += "${f.path}:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "这些调用点用了不带音源的老重载 `NavRoutes.artist(artistId: Long)` —— " +
                "它在 composable 里把 source 写死成 NETEASE，QQ 曲目会被送到同号的网易云艺人页" +
                "（真机实测：周杰伦 4558 → 马洪波）。改用 `NavRoutes.artist(source, id)`；" +
                "身份判定走 `ArtistNavigator`，按构造就是网易云的入口（剪贴板链接、推荐卡、" +
                "搜索艺人 tab）也要**显式**写 `MusicSource.NETEASE`。" +
                "违规行：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `源码扫描真的扫到了调用点——否则上一条是空转`() {
        // 阳性对照：没有它，「正则写错 ⇒ 一个都没匹配 ⇒ 永远绿」会静默发生。
        // 只数**非注释**行，与上一条用同一个过滤口径。
        val hits = kotlinSources().sumOf { f ->
            f.readLines().count { raw ->
                val t = raw.substringBefore("//").trim()
                !t.startsWith("*") && !t.startsWith("/*") && t.contains("NavRoutes.artist(")
            }
        }
        assertTrue("扫描路径或匹配串写错了，一个调用点都没找到", hits >= 1)
    }

    // ------------------------------------------------------------------ 工具

    /** `artist/{source}/{artistId}` → `(MusicSource, id)`；形状不对返回 null。 */
    private fun parseArtistSrc(route: String): Pair<MusicSource, String>? {
        val parts = route.split('/')
        if (parts.size != 3 || parts[0] != "artist") return null
        val source = MusicSource.values().firstOrNull { it.key == parts[1] } ?: return null
        return source to parts[2]
    }

    /** 极简的导航模板匹配：只支持 `{name}` 占位（本仓库的路由形状够用）。 */
    private fun matches(template: String, route: String): Boolean {
        val t = template.split('/')
        val r = route.split('/')
        if (t.size != r.size) return false
        return t.indices.all { i -> t[i].startsWith("{") || t[i] == r[i] }
    }

    private fun song(source: MusicSource, artist: ArtistItem?) = SongItem(
        id = 0x4000_0000_0001_79ADL,
        name = "晴天",
        artists = artist?.let { listOf(it) },
        album = null,
        duration = null,
        source = if (source == MusicSource.NETEASE) null else source.key,
    )

    /** 与 PersistenceFieldNameContractTest 同一套路径兜底（IDE / 命令行 / 模块目录三种 cwd）。 */
    private fun kotlinSources(): List<File> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate.walkTopDown().filter { it.extension == "kt" }.toList()
            if (dir.name == "app" && File(dir, "src/main/java").isDirectory) {
                return File(dir, "src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
            }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 app/src/main/java —— cwd=${System.getProperty("user.dir")}")
    }
}
