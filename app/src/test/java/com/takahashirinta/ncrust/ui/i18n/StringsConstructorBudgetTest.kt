/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 起：**`Strings` 的构造参数预算**（dex 单方法 255 参数寄存器）。
 * v2.5.3：阈值从「贴着天花板的 245」改成「留足余量的 150」，并扩成对整个
 *          `*Strings` 家族的**参数数量监控**（AGENTS.md v2.5.3 规则 1）。
 *
 * ## 为什么必须有这个测试（这不是理论风险，是踩过两次的）
 *
 * AGENTS.md 从 v2.0.0 · HF1 起就写着「`Strings` 的构造参数贴着 dex 单方法 255 参数上限，
 * 再加字段请拆组」。v2.3.0 需要两组新文案，作者按惯例「拆成两个嵌套组、
 * 给 `Strings` 只加两个参数」——**编译通过**，然后在跑单测时炸了：
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
 * N = 245 ⇒ 1 + 245 + 8 + 1 = 255   ← 刚好用满（v2.3.0 ~ v2.5.2 的真实值）
 * N = 246 ⇒ 1 + 246 + 8 + 1 = 256   ← 溢出，类加载期直接抛
 * ```
 *
 * 也就是说 v2.5.2 时 **`Strings` 的可用余量是 0** —— 一个新参数都装不下。
 *
 * ## v2.5.3 做了什么
 *
 * 把 120 条文案搬进 `SettingsStrings`(64) / `AboutStrings`(25) / `PlayerUiStrings`(31)，
 * 用 3 个组参数换掉 120 个 ⇒ 主构造器 **245 → 128**，槽位 **255 → 134**，
 * 余量从 **0 → 121**。老调用点由类体里的转发属性保住，一行都没改。
 *
 * ## 这个测试怎么挡住下一次
 *
 * 它在 JVM 上**反射读取**全部 `*Strings` 数据类的构造器：
 *   · 参数超限时类加载会抛 `ClassFormatError` —— 那只在**真的有测试加载这个类**时才发生，
 *     而「新加了一组文案、恰好没有测试碰 `Strings`」是完全可能的
 *     （v2.3.0 的 `SongTagsTest` 用了 `zhCN`，纯属运气好才炸出来）。
 *     所以这里显式地加载并断言，把「运气」换成「必然」。
 *   · 现在**每个组都在被监控**，不再是只看外层那一个 —— 组自己涨到 200 也是同一个坑。
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
     * `Strings` 主构造器的参数个数**硬上限**。
     *
     * v2.5.2 是 245（= 255 槽用满）。v2.5.3 拆成 128。
     * 这里取 **150** 而不是贴着 245：验收要求「显著低于 255、留足余量」，
     * 而「刚好不溢出」在实践中等于「下一个加文案的人必然踩坑」——
     * v2.3.0 那次就是这么发生的。
     *
     * 245 是**天花板**不是配额；150 是**预算**，且留了 100+ 个槽位的余量。
     */
    private val maxPrimaryParams = 150

    /**
     * 预警线：超过它不失败，但会打印一条 `WARN`。
     *
     * 存在的意义是「让人在**还来得及**的时候知道该拆了」——
     * 等到贴着 255 才报错时，任何一次加文案都会把构建卡住。
     */
    private val warnPrimaryParams = 140

    /** 单个嵌套组的参数硬上限（组没有默认参数，天花板是 254；这里留更宽的余量）。 */
    private val maxGroupParams = 120

    /** 预警线：组超过它就提示「该再拆一层」。 */
    private val warnGroupParams = 80

    /** `Strings` 家族的全部数据类（外层 + 嵌套组）。加组时**必须**同步这里。 */
    private val family = listOf(
        "com.takahashirinta.ncrust.ui.i18n.Strings",
        "com.takahashirinta.ncrust.ui.i18n.OfflineStrings",
        "com.takahashirinta.ncrust.ui.i18n.SourceStrings",
        "com.takahashirinta.ncrust.ui.i18n.PlaylistsStrings",
        "com.takahashirinta.ncrust.ui.i18n.TagsStrings",
        "com.takahashirinta.ncrust.ui.i18n.LocalPlaylistStrings",
        "com.takahashirinta.ncrust.ui.i18n.QueueStrings",
        "com.takahashirinta.ncrust.ui.i18n.MotionStrings",
        "com.takahashirinta.ncrust.ui.i18n.SettingsStrings",
        "com.takahashirinta.ncrust.ui.i18n.AboutStrings",
        "com.takahashirinta.ncrust.ui.i18n.PlayerUiStrings",
    )

    /** dex 槽位算式：`this(1) + N + ceil(N/32) 个默认值 mask + DefaultConstructorMarker(1)`。 */
    private fun dexSlots(params: Int, hasDefaults: Boolean): Int =
        1 + params + (if (hasDefaults) (params + 31) / 32 else 0) + (if (hasDefaults) 1 else 0)

    /**
     * **最宽的那个构造器**（主构造器，或带默认值时的合成构造器）。
     *
     * ★ 只看**非合成**的（= 主构造器）来数「主构造器参数个数」。
     *   Kotlin 为「带默认参数的主构造器」还会生成一个合成构造器：
     *   参数个数 = N + ceil(N/32) 个 mask + 1 个 DefaultConstructorMarker。
     *   直接取 `maxOf { parameterCount }` 会把那个合成构造器当成主构造器（量出来偏大）。
     */
    private fun primaryParams(clazz: Class<*>): Int =
        clazz.declaredConstructors.filter { !it.isSynthetic }.maxOf { it.parameterCount }

    private fun widestCtor(clazz: Class<*>): Int =
        clazz.declaredConstructors.maxOf { it.parameterCount }

    private fun hasSynthetic(clazz: Class<*>): Boolean =
        clazz.declaredConstructors.any { it.isSynthetic }

    // ---------------------------------------------------------------- 类加载

    @Test
    fun `Strings 家族全部可以被 JVM 加载（超过 255 槽会在类加载期抛 ClassFormatError）`() {
        family.forEach { name ->
            // 这一行本身就是断言：加载失败会抛 Error，测试直接红。
            val clazz = Class.forName(name)
            assertNotNull("$name 加载失败", clazz)
            assertTrue("$name 没有构造器", clazz.declaredConstructors.isNotEmpty())
        }
    }

    // ---------------------------------------------------------------- 外层预算

    @Test
    fun `Strings 的构造参数不超过预算`() {
        val clazz = Class.forName("com.takahashirinta.ncrust.ui.i18n.Strings")
        val primary = primaryParams(clazz)
        if (primary > warnPrimaryParams) {
            println(
                "WARN[StringsConstructorBudgetTest] Strings 主构造器参数 = $primary，" +
                    "已超过预警线 $warnPrimaryParams（硬上限 $maxPrimaryParams）。" +
                    "下一个要加文案的人请先拆组。",
            )
        }
        assertTrue(
            "Strings 主构造器参数 $primary 超过预算 $maxPrimaryParams —— " +
                "请把新文案放进语义相符的嵌套组，并在类体里补一条转发属性保住调用点。" +
                "（v2.5.2 时这个数是 245，即 255 个 dex 槽正好用满，再加一个真机启动即崩。）",
            primary <= maxPrimaryParams,
        )

        // 副断言：把算式写在明处。合成构造器 + this 必须仍在 255 槽以内。
        val widest = widestCtor(clazz)
        assertTrue(
            "Strings 最宽构造器 $widest + this = ${widest + 1} 超过 255 槽",
            widest + 1 <= 255,
        )
        assertEquals(
            "dex 槽位算式与预期不符（this + N + mask + marker）",
            dexSlots(primary, hasSynthetic(clazz)), widest + 1,
        )
    }

    /**
     * v2.5.3 的**搬家账**：128 才是本版的目标值。
     *
     * 单钉一个精确值而不是「< 150」是有意的：这条会在有人**顺手**往主构造器里
     * 加参数时立刻变红，迫使他在「拆组」与「改这个断言」之间做一次显式选择 ——
     * 而后者会留下一条可追溯的提交记录。范围断言做不到这一点。
     */
    @Test
    fun `v2_5_3 之后 Strings 主构造器稳定在 128`() {
        val clazz = Class.forName("com.takahashirinta.ncrust.ui.i18n.Strings")
        assertEquals(
            "Strings 主构造器参数数变了。若是有意加文案，请把新文案放进嵌套组" +
                "（外层一个都不要加），然后同步改这条断言并在提交信息里说明。",
            128, primaryParams(clazz),
        )
        // 余量：128 ⇒ 1 + 128 + 4 + 1 = 134 槽，距 255 还有 121。
        assertEquals(134, dexSlots(128, true))
        assertTrue("余量不足 100 个槽位", 255 - dexSlots(128, true) >= 100)
    }

    // ---------------------------------------------------------------- 组预算

    /**
     * **每个嵌套组**都在监控范围内。
     *
     * v2.5.3 之前只监控外层 `Strings` —— 那等于假设「组是无限安全的」，
     * 而组的天花板只是比外层高一点点（组没有默认参数，所以是 254 而不是 245）。
     * 一个涨到 250 的组会在**下一次加字段**时以完全相同的方式崩在真机上。
     */
    @Test
    fun `嵌套组自身也受参数数量监控`() {
        val report = mutableListOf<String>()
        family.drop(1).forEach { name ->
            val clazz = Class.forName(name)
            val n = primaryParams(clazz)
            report += "${clazz.simpleName}=$n"
            if (n > warnGroupParams) {
                println(
                    "WARN[StringsConstructorBudgetTest] 组 ${clazz.simpleName} 参数 = $n，" +
                        "已超过预警线 $warnGroupParams —— 该考虑再拆一层了。",
                )
            }
            assertTrue(
                "组 $name 的参数 $n 超过上限 $maxGroupParams，应该再拆一层" +
                    "（组没有默认参数，天花板 254 —— 但贴到那个数就晚了）。",
                n <= maxGroupParams,
            )
            assertTrue(
                "组 $name 最宽构造器 $n + this = ${n + 1} 超过 255 槽",
                widestCtor(clazz) + 1 <= 255,
            )
        }
        println("INFO[StringsConstructorBudgetTest] 各组参数：${report.joinToString(" ")}")
    }

    /** 三个新组各自的规模被钉住 —— 防止「搬进去又被慢慢加回来」。 */
    @Test
    fun `v2_5_3 三个新组的规模被钉住`() {
        val expected = mapOf(
            "com.takahashirinta.ncrust.ui.i18n.SettingsStrings" to 64,
            "com.takahashirinta.ncrust.ui.i18n.AboutStrings" to 25,
            "com.takahashirinta.ncrust.ui.i18n.PlayerUiStrings" to 31,
        )
        expected.forEach { (name, n) ->
            assertEquals("$name 的参数数变了", n, primaryParams(Class.forName(name)))
        }
        // 三个组一共从主构造器搬走了 120 条，换来 3 个组参数 ⇒ 净腾 117 个槽位。
        assertEquals("搬家账不对：245 - 120 + 3 应当等于 128", 128, 245 - 120 + 3)
    }

    // ---------------------------------------------------------------- 转发属性

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

    @Test
    fun `添加到下一首的文案搬家后仍然可达（转发属性没写错）`() {
        val presetList = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presetList.forEach { s ->
            // 搬进 QueueStrings 的两条既有文案：组内 == 转发属性（`Strings.xxx` 的写法不能失效），
            // 且两边都非空 —— 漏填会编译错，这里再钉一次语义。
            assertEquals(s.queue.actionInsertNext, s.actionInsertNext)
            assertEquals(s.queue.actionAppendToQueue, s.actionAppendToQueue)
            assertTrue(s.queue.actionInsertNext.isNotBlank())
            assertTrue(s.queue.actionAppendToQueue.isNotBlank())
            assertTrue(s.actionInsertNext.isNotBlank())
            assertTrue(s.actionAppendToQueue.isNotBlank())

            // v2.5.0 · D 新增的 6 条，8 种语言都必须有非空文案。
            assertTrue(s.queue.actionAddToNext.isNotBlank())
            assertTrue(s.queue.queueAddToNextDone.isNotBlank())
            assertTrue(s.queue.queueAddToNextMoved.isNotBlank())
            assertTrue(s.queue.queueAddToNextAlreadyNext.isNotBlank())
            assertTrue(s.queue.queueAddToNextCurrent.isNotBlank())
            assertTrue(s.queue.queueAddToNextStarted.isNotBlank())

            // ★ 两个动作不许是同一个词：插播会**立刻打断**当前播放，
            //   「添加到下一首播放」**不打断**。它们在同一个菜单里相邻，
            //   文案如果写成同一句，用户会以为自己点错了入口。
            assertTrue(
                "actionAddToNext 与 actionInsertNext 撞词了：${s.actionAddToNext}",
                s.actionAddToNext != s.actionInsertNext,
            )
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
    fun `v2_5_1 的页面转场文案组在 8 种语言里都非空且不撞词`() {
        val presetList = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        val labels = mutableSetOf<String>()
        presetList.forEach { s ->
            assertTrue("pageTransitionLabel 为空", s.motion.pageTransitionLabel.isNotBlank())
            assertTrue("pageTransitionDescription 为空", s.motion.pageTransitionDescription.isNotBlank())
            // ★ 标题不许与说明写成同一句：设置页里它们是上下两行，
            //   一样的话用户会看到重复的一行字（v2.1.3「跨功能文案不要复用」的同类要求）。
            assertTrue(
                "pageTransitionLabel 与 pageTransitionDescription 撞词了：${s.motion.pageTransitionLabel}",
                s.motion.pageTransitionLabel != s.motion.pageTransitionDescription,
            )
            labels.add(s.motion.pageTransitionLabel)
        }
        // 8 种语言必须给出 8 个不同的标题 —— 有两条一样说明有人只改了文件名没改内容。
        assertEquals("8 种语言的 pageTransitionLabel 应当互不相同", 8, labels.size)
    }
}
