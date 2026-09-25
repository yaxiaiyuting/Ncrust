/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 回归单测：**`Strings` 的构造参数预算**。
 *
 * ## 为什么必须有这个测试（这不是理论风险，是本版真实踩到的）
 *
 * AGENTS.md 从 v2.0.0 · HF1 起就写着「`Strings` 的构造参数贴着 dex 单方法 255 参数上限，
 * 再加字段请拆组」。本版（v2.3.0）需要两组新文案，作者按惯例「拆成两个嵌套组、
 * 给 `Strings` 只加两个参数」——**编译通过、`:app:compileDebugKotlin` 全绿**，
 * 然后在跑单测时炸了：
 *
 * ```
 * java.lang.ClassFormatError: Too many arguments in method signature
 *     in class file com/takahashirinta/ncrust/ui/i18n/Strings
 * ```
 *
 * 那条 git 注释里写的「实际余量只剩 ~9 个」是**错的**。真正的算式是：
 *
 * ```
 * 槽位 = this (1) + 构造参数 N + 默认值 mask 个数 ceil(N/32) + DefaultConstructorMarker (1)
 * 需要 <= 255
 * N = 245 ⇒ 1 + 245 + 8 + 1 = 255   ← 刚好用满，这就是 HEAD 的真实值
 * N = 246 ⇒ 1 + 246 + 8 + 1 = 256   ← 溢出
 * ```
 *
 * 也就是说 **`Strings` 的可用余量是 0，不是 9**。`Strings` 本身装不下任何新参数了，
 * 新文案只能进嵌套组、而嵌套组也必须**先腾出位置**才能加。
 *
 * ## 这个测试怎么挡住下一次
 *
 * 它在 JVM 上**反射读取 `Strings` 的构造器**。参数超限时类加载会抛
 * `ClassFormatError` —— 但那只在**真的有测试加载这个类**时才会发生，
 * 而「新加了一组文案、恰好没有测试碰 `Strings`」是完全可能的
 * （本版的 `SongTagsTest` 用了 `zhCN`，纯属运气好才炸出来）。
 * 所以这里显式地加载并断言，把「运气」换成「必然」。
 *
 * ⚠️ 这条纪律与 AGENTS.md 的 v2.2.1 规则 5 同源：**能在一个便宜的层次上测出来的东西，
 * 不要留给真机**。真机上它的表现是启动即崩（`VerifyError`/`ClassFormatError`），
 * 而这里是一个红色用例。
 */

package com.takahashirinta.ncrust.ui.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StringsConstructorBudgetTest {

    /**
     * `Strings` 主构造器的参数个数上限。
     *
     * 245 = 255 − this − ceil(245/32) 个 mask − DefaultConstructorMarker。
     * **这个数不是配额，是天花板**：加文案请拆组，而拆组前必须先腾位置
     * （v2.3.0 的做法是把 `networkOfflineTitle` / `networkOfflineHint` 搬进 `OfflineStrings`）。
     */
    private val maxConstructorParams = 245

    @Test
    fun `Strings 可以被 JVM 加载（超过 255 槽会在类加载期抛 ClassFormatError）`() {
        // 这一行本身就是断言：加载失败会抛 Error，测试直接红。
        val clazz = Class.forName("com.takahashirinta.ncrust.ui.i18n.Strings")
        assertNotNull(clazz)
        assertTrue(clazz.declaredConstructors.isNotEmpty())
    }

    @Test
    fun `Strings 的构造参数不超过 dex 预算`() {
        val clazz = Class.forName("com.takahashirinta.ncrust.ui.i18n.Strings")
        // ★ 只看**非合成**的那个（= 主构造器）。Kotlin 为「带默认参数的主构造器」还会生成一个
        //   合成构造器：参数个数 = N + ceil(N/32) 个 mask + 1 个 DefaultConstructorMarker。
        //   N = 245 时它是 254，而加上 `this` 正好用满 255 个槽 —— 这就是上限的由来。
        //   直接取 maxOf{parameterCount} 会把那个合成构造器当成主构造器，量出来是 254。
        val primary = clazz.declaredConstructors.filter { !it.isSynthetic }.maxOf { it.parameterCount }
        assertTrue(
            "Strings 主构造器参数 $primary 超过预算 $maxConstructorParams —— " +
                "再加一个，合成构造器就会到 255、加上 this 溢出 255 槽，" +
                "真机启动即崩（ClassFormatError）。请把新文案放进嵌套组，并先腾出位置。",
            primary <= maxConstructorParams,
        )
        // 副断言：合成构造器仍然在 255 槽以内（等价于上面那条，但把算式写在明处）。
        val widest = clazz.declaredConstructors.maxOf { it.parameterCount }
        assertTrue("最大构造器参数 $widest + this 超过 255 槽", widest + 1 <= 255)
    }

    @Test
    fun `嵌套组自身远低于上限（给它们加字段是安全的）`() {
        // 嵌套组没有默认参数，所以它们的上限就是 254；这里给一个宽松但实际的检查。
        val groups = listOf(
            "com.takahashirinta.ncrust.ui.i18n.OfflineStrings",
            "com.takahashirinta.ncrust.ui.i18n.SourceStrings",
            "com.takahashirinta.ncrust.ui.i18n.PlaylistsStrings",
            "com.takahashirinta.ncrust.ui.i18n.TagsStrings",
            "com.takahashirinta.ncrust.ui.i18n.LocalPlaylistStrings",
        )
        groups.forEach { name ->
            val clazz = Class.forName(name)
            val widest = clazz.declaredConstructors.maxOf { it.parameterCount }
            assertTrue("$name 的构造参数 $widest 过大，应该再拆一层", widest <= 60)
        }
    }

    @Test
    fun `每一位文案提供者都填满了两个新组（漏填会编译错 这里再钉一次语义）`() {
        // 8 种语言都必须给出非空文案；空串会让界面上出现一块没有字的角标。
        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presets.forEach { s ->
            assertTrue(s.tagPlayable.isNotBlank())
            assertTrue(s.tagMemberOnly.isNotBlank())
            assertTrue(s.tagNoCopyright.isNotBlank())
            assertTrue(s.tagOriginal.isNotBlank())
            assertTrue(s.tagCover.isNotBlank())
            assertTrue(s.tagSwitchSourceHint.isNotBlank())
            assertTrue(s.tagCoverOrigin("A", "B").isNotBlank())
            assertTrue(s.localPlaylistSectionTitle.isNotBlank())
            assertTrue(s.localPlaylistNew.isNotBlank())
            assertTrue(s.localPlaylistEmpty.isNotBlank())
            assertTrue(s.localPlaylistAdopt.isNotBlank())
            assertTrue(s.localPlaylistAdopted("X").isNotBlank())
            assertTrue(s.localPlaylistClearConfirm("X").isNotBlank())
            assertTrue(s.localPlaylistSynced(1, 2).isNotBlank())
        }
    }

    @Test
    fun `离线空态文案搬家后仍然可达（转发属性没写错）`() {
        val presetList = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presetList.forEach { s ->
            assertEquals(s.offline.networkOfflineTitle, s.networkOfflineTitle)
            assertEquals(s.offline.networkOfflineHint, s.networkOfflineHint)
            assertTrue(s.networkOfflineTitle.isNotBlank())
            assertTrue(s.networkOfflineHint.isNotBlank())
        }
    }
}
