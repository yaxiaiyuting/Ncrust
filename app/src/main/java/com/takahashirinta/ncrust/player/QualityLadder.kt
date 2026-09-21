/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v1.2.0 · A2：音质档位表单一来源 + 旧索引迁移。新增 jymaster（超清母带），
 *     插在 jyeffect 之后、dolby 之前（与官方客户端音质排序一致），因此已有档位的
 *     索引会 +1，必须迁移，否则用户原来选的「杜比」会变成别的档位。
 *     实测（登录态 + PC 身份，tools/probe-quality.py）：jymaster 请求返回 flac、
 *     br≈5.84 Mbps；而 sky（沉浸环绕声）请求只会回落成 exhigh(320k mp3)，故本次不加。
 */

package com.takahashirinta.ncrust.player

import android.content.SharedPreferences
import android.util.Log

object QualityLadder {
    const val PREFS = "ncrust_settings"
    const val KEY_WIFI = "wifi_quality"
    const val KEY_MOBILE = "mobile_quality"
    private const val KEY_VERSION = "quality_ladder_version"
    private const val TAG = "QualityLadder"

    /** 当前档位表版本。1 = 旧 7 档（dolby 在索引 6），2 = 8 档（新增 jymaster）。 */
    private const val CURRENT_VERSION = 2

    /** 迁移前 dolby 所在的索引。 */
    private const val OLD_INDEX_FIRST_SHIFTED = 6

    /**
     * 音质档位（低 → 高）。索引与 i18n qualityOptions 顺序、以及 eapi level 取值一一对应。
     * 索引越大音质越高，因此「实际索引 < 偏好索引」表示可能被降级（A3 起改用 br/type 判定）。
     */
    val LEVELS = listOf(
        "standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "jymaster", "dolby",
    )

    /** 按索引取 level；越界时给出兜底档位（不再让调用方各自写 getOrElse）。 */
    fun levelAt(index: Int, fallback: String): String = LEVELS.getOrElse(index) { fallback }

    /**
     * 把旧版本的档位索引一次性迁移到当前档位表。老版本 dolby = 6，新表 dolby = 7；
     * 0..5（压缩…高清环绕声）位置未变。幂等：写入版本号后直接返回。
     */
    fun migrate(prefs: SharedPreferences) {
        if (prefs.getInt(KEY_VERSION, 1) >= CURRENT_VERSION) return
        val edit = prefs.edit()
        for (key in listOf(KEY_WIFI, KEY_MOBILE)) {
            if (!prefs.contains(key)) continue
            val old = prefs.getInt(key, 0)
            if (old >= OLD_INDEX_FIRST_SHIFTED) edit.putInt(key, old + 1)
        }
        edit.putInt(KEY_VERSION, CURRENT_VERSION).apply()
        Log.i(TAG, "quality ladder migrated to v" + CURRENT_VERSION + " (" + LEVELS.size + " levels)")
    }
}
