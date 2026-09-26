#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
split-strings.py — v2.5.3 · P0：把 `Strings` 的主构造参数机械地搬进三个功能分组。

这是**一次性机械变换**（不是长期工具），保留在探针目录里是为了让这次搬运
可复核、可重放：谁都可以拿 v2.5.2 的源码重跑一遍，得到逐字节相同的结果。

## 变换做了什么

1. `Strings.kt`
   · 从主构造器里**摘掉** 120 个参数（连同它们头顶的 KDoc / `//` 注释一起搬走）；
   · 插入 3 个组参数 `settings` / `about` / `playerUi`（**净腾出 117 个槽位**）；
   · 在类体末尾补上 120 条**转发属性** —— 老调用点 `strings.xxx` 一字不改；
   · 追加三个 `data class ...Strings` 定义（带各自的 KDoc）。
2. 8 个语言文件
   · 把对应的 120 条实参从 `Strings(...)` 里**原样**（含多行 lambda 与注释）搬进
     新建的 `SettingsStrings(...)` / `AboutStrings(...)` / `PlayerUiStrings(...)`。

## 为什么必须逐字符搬运而不是重排

`Strings` 里有 47 条是 lambda（`(Int) -> String` 之类），格式串里的空格/标点
就是**用户看到的文案**。任何"顺手格式化"都会变成一次静默的文案改动，
而文案改动在编译期完全看不出来。所以本脚本对**保留下来的实参**一律原样透传，
只做「搬家」，不碰内容。正确性由 `StringsMigrationTest` 对着黄金快照逐值证明。

用法（在仓库根目录）：
    python3 docs/verification/v2.5.3/split-strings.py            # 预览统计
    python3 docs/verification/v2.5.3/split-strings.py --apply    # 落盘
"""

import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
I18N = os.path.join(REPO, "app", "src", "main", "java", "com",
                    "takahashirinta", "ncrust", "ui", "i18n")
STRINGS_KT = os.path.join(I18N, "Strings.kt")

LOCALE_FILES = {
    "zhCN": "zh_CN.kt", "zhTW": "zh_TW.kt", "en": "en.kt", "jpJP": "jp_JP.kt",
    "jpMY": "jp_MY.kt", "koNK": "ko_NK.kt", "deDE": "de_DE.kt", "ruRU": "ru_RU.kt",
}

# ---------------------------------------------------------------------------
# 分组表（**唯一**的真源；改名/加组只改这里）
#
# 归属判据 = 探针算出来的「主要消费文件」（probe-strings.raw.txt §3）：
#   · SettingsStrings  ← ui/screen/UserScreen.kt 及其卫星（存储/背景/账户/电池/扫码入口）
#   · AboutStrings     ← ui/screen/AboutScreen.kt
#   · PlayerUiStrings  ← ui/player/{PlayerCard,FullPlayerControls,LyricsView,QueueView}.kt
# 一个 key 落在哪一组**不影响任何调用点**（每组都有转发属性），
# 所以归属只关乎可读性，不构成正确性风险。
# ---------------------------------------------------------------------------
GROUPS = [
    (
        "settings", "SettingsStrings", "设置页",
        "v2.5.3 · P0：**设置页（含其卫星对话框）**的文案组。",
        [
            # 音质
            "qualitySectionTitle", "wifiQualityLabel", "mobileQualityLabel", "qualityOptions",
            "qualityFlacUnsupportedHint",
            # 主题色来源
            "accentSourceSectionTitle", "accentSourcePreset", "accentSourceCover",
            "accentSourceSystem", "accentSourceSystemHint", "accentSystemRefresh",
            # 播放 / 无缝
            "playbackSectionTitle", "gaplessSectionTitle", "gaplessDescription",
            # 歌词相关开关
            "lyricsTranslationLabel", "lyricsWordByWordLabel", "lyricsWordAnimationLabel",
            "lyricsWordAnimationOptions", "lyricsInMediaSessionLabel", "lyricsInMediaSessionHint",
            "lyricsSweepQualityLabel", "lyricsSweepQualityOptions", "lyricsTtmlEnabledLabel",
            "lyricsTtmlFirstLabel", "lyricsRomanizationLabel", "lyricsRomanizationHint",
            # 屏幕 / 字号
            "keepScreenOnLabel", "keepScreenOnHint", "dynamicFontLabel", "dynamicFontHint",
            "lyricsFontScaleLabel",
            # 旋转 / 可视化
            "autoRotateLabel", "autoRotateDescription",
            "audioVisualizerLabel", "audioVisualizerDescription",
            # 主题
            "themeSectionTitle", "themeModeSectionTitle", "themeModeSystem", "themeModeDark",
            "themeModeLight", "themeColorNames",
            # 语言 / 关于入口 / 存储
            "languageSectionTitle", "aboutButton", "storageSectionTitle",
            "clearCache", "clearCacheConfirm",
            # 自定义背景
            "bgSectionTitle", "bgPick", "bgChange", "bgRemove", "bgImportFailed",
            # 账户
            "accountDialogTitle", "nicknameLabel", "uidLabel", "logoutButton",
            "notLoggedIn", "loginHint",
            # 扫码入口 / 头像描述
            "scanEntryTitle", "userAvatarDesc", "userIconDesc",
            # 电池优化
            "batteryTitle", "batteryMessage", "batteryAllow", "batteryLater",
        ],
    ),
    (
        "about", "AboutStrings", "关于页",
        "v2.5.3 · P0：**关于页**的文案组（项目信息 / 技术栈 / 名单 / 致谢）。",
        [
            "aboutTitle", "aboutAppSubtitle", "aboutSectionProject", "aboutVersion",
            "aboutDeveloperOriginal", "aboutDeveloperFork", "aboutLicense",
            "aboutLicenseGplWithMit", "aboutRepositoryFork", "aboutRepositoryOriginal",
            "aboutSectionTechStack", "aboutLangLabel", "aboutUIFrameworkLabel",
            "aboutDesignSystemLabel", "aboutAudioEngineLabel", "aboutNetworkLabel",
            "aboutImageLabel", "aboutSectionTeam", "aboutRoleDev", "aboutRoleTester",
            "aboutRoleForkMaintainer", "aboutSectionCredits", "aboutCreditCli",
            "aboutCreditAnim", "aboutCreditDesign",
        ],
    ),
    (
        "playerUi", "PlayerUiStrings", "播放器界面",
        "v2.5.3 · P0：**播放器界面**的文案组（传输控件 / 歌词页 / 队列面板）。",
        [
            # 传输控件
            "prevButton", "playButton", "pauseButton", "nextButton",
            "lyricsButton", "queueButton", "addToLibraryButton",
            # 音质角标
            "qualityDowngradedBadge", "qualityNoEntitlementBadge", "qualitySongLacksTierBadge",
            # 歌词页 / 大屏 / 旋转
            "lyricsFontSmaller", "lyricsFontLarger", "controlsHandleLabel",
            "bigScreenEnter", "bigScreenExit", "autoRotateOn", "autoRotateOff",
            # 播放器空态
            "noLyrics", "emptyQueue", "collapsePlayer",
            # 队列面板
            "lyricsLabel", "queueTitle", "playModeButton", "saveAsPlaylist", "noSongPlaying",
            "queueSectionPast", "queueSectionNow", "queueSectionUpcoming",
            "queueInfinityPlaceholder", "queueClearAll", "clearQueue",
        ],
    ),
]

GROUP_PARAM = {g[0]: g for g in GROUPS}
MOVED = {}          # param -> group param name
for _p, _cls, _label, _doc, _keys in GROUPS:
    for k in _keys:
        assert k not in MOVED, f"重复归属: {k}"
        MOVED[k] = _p


# ---------------------------------------------------------------------------
def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8") as f:
        f.write(s)


def strip_strings_and_comments(code):
    """等长抹平字符串内容与注释（`match_paren` 要用清理后的下标切原文）。"""
    out = list(code)
    i, n = 0, len(code)

    def blank(a, b):
        for k in range(a, min(b, n)):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        ch = code[i]
        if ch == '"':
            if code.startswith('"""', i):
                j = code.find('"""', i + 3)
                j = n if j < 0 else j + 3
                blank(i, j)
                i = j
                continue
            j = i + 1
            while j < n:
                if code[j] == "\\":
                    j += 2
                    continue
                if code[j] == '"' or code[j] == "\n":
                    j += 1
                    break
                j += 1
            blank(i + 1, j - 1)
            i = j
            continue
        if ch == "/" and i + 1 < n and code[i + 1] == "/":
            j = code.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
            continue
        if ch == "/" and i + 1 < n and code[i + 1] == "*":
            j = code.find("*/", i)
            j = n if j < 0 else j + 2
            blank(i, j)
            i = j
            continue
        i += 1
    return "".join(out)


def match_paren(text, open_idx):
    clean = strip_strings_and_comments(text)
    depth = 0
    for i in range(open_idx, len(clean)):
        if clean[i] == "(":
            depth += 1
        elif clean[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


def split_spans(block):
    """按顶层逗号切块，返回 [(原文片段, 起, 止)]。"""
    clean = strip_strings_and_comments(block)
    spans, depth, start = [], 0, 0
    i, n = 0, len(clean)
    while i < n:
        ch = clean[i]
        if ch == "-" and i + 1 < n and clean[i + 1] == ">":
            i += 2
            continue
        if ch == "<":
            prev = clean[i - 1] if i > 0 else ""
            if prev.isalnum() or prev in "_>)]":
                depth += 1
        elif ch == ">":
            if depth > 0:
                depth -= 1
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            spans.append((block[start:i], start, i))
            start = i + 1
        i += 1
    if block[start:].strip():
        spans.append((block[start:], start, len(block)))
    return spans


def first_line_of(raw):
    """片段里第一条非空、非纯注释的内容行（用来认参数名）。"""
    for line in raw.splitlines():
        t = line.strip()
        if t and not t.startswith("//") and not t.startswith("*") and not t.startswith("/*"):
            return t
    return raw.strip().splitlines()[0] if raw.strip() else ""


def dedent(raw, spaces):
    pad = " " * spaces
    out = []
    for line in raw.splitlines():
        out.append(line[len(pad):] if line.startswith(pad) else line)
    return "\n".join(out)


def norm_block(raw, indent):
    """把一段片段规整成 `indent + 内容`，内部行按原相对缩进平移。

    只动**缩进**，一个字符都不动内容 —— 文案就藏在字符串里。

    ⚠️ 只在**跨缩进层级搬家**时用（语言文件里 `Strings(` 的实参 → `XxxStrings(` 的实参）。
    同一层级内的搬家一律用原件（见 [transform_strings_kt]）——
    规整会顺手把原来的空行结构抹平，那是无谓的 diff 噪音。
    """
    lines = raw.split("\n")
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    if not lines:
        return ""
    base = len(lines[0]) - len(lines[0].lstrip())
    out = []
    for ln in lines:
        if not ln.strip():
            out.append("")
        else:
            cur = len(ln) - len(ln.lstrip())
            out.append(" " * max(0, indent + cur - base) + ln.lstrip())
    return "\n".join(out)


def join_chunks(pieces, original_block):
    """按原样用逗号把片段拼回去，并保住原文的尾随逗号。"""
    out = ",".join(pieces)
    if original_block.rstrip().endswith(","):
        out = out + ","
    return out


# ---------------------------------------------------------------------------
def transform_strings_kt():
    text = read(STRINGS_KT)
    m = re.search(r"^data class Strings\(", text, re.M)
    open_idx = m.end() - 1
    close_idx = match_paren(text, open_idx)
    block = text[open_idx + 1:close_idx]
    spans = split_spans(block)

    params = []  # (name, raw)
    for raw, _a, _b in spans:
        pm = re.search(r"val\s+(\w+)\s*:", raw)
        params.append((pm.group(1) if pm else None, raw))
    names = [p for p, _ in params if p]
    assert len(names) == len(set(names)), "参数名重复"
    missing = [k for k in MOVED if k not in names]
    assert not missing, f"分组表里有源码中不存在的 key: {missing}"

    # ---- 重建主构造器：保留的片段**逐字符原样**透传 ----
    pieces, inserted = [], set()
    for name, raw in params:
        grp = MOVED.get(name)
        if grp is None:
            pieces.append(raw)
            continue
        if grp in inserted:
            continue          # 只在其**首个**成员的位置上插一条组参数
        inserted.add(grp)
        p, cls, label, _doc, keys = GROUP_PARAM[grp]
        nl = re.match(r"\n*", raw).group(0)     # 沿用原片段的前导换行，保住空行节奏
        pieces.append(
            f"{nl}    // v2.5.3 · P0：{label}那一组（{len(keys)} 条）搬进 [{cls}]。\n"
            f"    // 理由见本类 KDoc「参数预算」一节：主构造器当时是 245 = 255 个 dex 槽用满。\n"
            f"    // 调用点由类体里的转发属性原样保住 —— `strings.xxx` 一行都不用改。\n"
            f"    val {p}: {cls}"
        )
    new_ctor = "data class Strings(" + join_chunks(pieces, block) + "\n)"

    # ---- 新转发属性（名字与类型照抄原声明，只把取值改指到组里）----
    fwd_lines = []
    for p, cls, label, _doc, keys in GROUPS:
        fwd_lines.append(f"    // ---------- 转发属性（v2.5.3 · P0）：{label} → [{cls}] ----------")
        fwd_lines.append(f"    // 与 v2.0.0 · HF1 的 [OfflineStrings] 同一套做法：搬家不改调用点。")
        for k in keys:
            raw = next(r for nm, r in params if nm == k)
            body = re.sub(r"^[\s\S]*?\bval\s+", "", raw).strip()   # 去掉注释与 val
            # 转发属性不能带默认值：`val x: T = 默认值 get() = …` 不是合法 Kotlin。
            # 默认值跟着**原声明**一起搬进了分组（`AboutStrings.aboutDesignSystemLabel
            # = "Design System"`），所以「漏填的语言回落默认值」这条行为原样保留。
            if "=" in body:
                body = body.split("=", 1)[0].strip()
            fwd_lines.append(f"    val {body} get() = {p}.{k}")
        fwd_lines.append("")
    fwd_block = "\n".join(fwd_lines).rstrip() + "\n"

    # 类体：`data class Strings(...)` 之后是 ` {`，再往后才是成员。
    brace_idx = text.index("{", close_idx)
    body_close = text.index("\n}", brace_idx)
    body = text[brace_idx + 1:body_close]
    tail = text[body_close:]
    new_head = text[:text.index("data class Strings(")] + new_ctor + " {"
    new_text = new_head + body.rstrip("\n") + "\n\n" + fwd_block + tail

    # ---- 追加分组 data class（同层级搬家 ⇒ 片段原样） ----
    group_defs = []
    for p, cls, label, doc, keys in GROUPS:
        group_defs.append(f"""
/**
 * {doc}
 *
 * ## 为什么又是一个嵌套组
 *
 * `Strings` 的主构造参数在 v2.5.2 时是 **245**，即
 * `this(1) + 245 + ceil(245/32)=8 个默认值 mask + DefaultConstructorMarker(1) = 255` ——
 * **正好用满 dex 单方法 255 个参数寄存器**（v2.0.0 · HF1 与 v2.3.0 各因此崩过一次：
 * 编译照过、真机启动抛 `ClassFormatError`）。本组把 {len(keys)} 条从主构造器搬出来，
 * 用 **1 个**组参数换掉 {len(keys)} 个 ⇒ 净腾出 **{len(keys) - 1}** 个槽位。
 *
 * 老调用点（`strings.xxx`）由 [Strings] 类体里的转发属性保住，一条都不用改；
 * 新代码可以直接写 `strings.{p}.xxx`。
 *
 * 参数数量监控见 `StringsConstructorBudgetTest`。
 */""")
        group_defs.append(f"data class {cls}(")
        inner = ",".join(next(r for nm, r in params if nm == k) for k in keys)
        group_defs.append(re.sub(r"^\n+", "\n", inner))
        group_defs.append(")\n")
    new_text = new_text.rstrip("\n") + "\n" + "\n".join(group_defs)

    return new_text, len(names) - len(MOVED) + len(GROUPS), len(MOVED)


def transform_locale(var_name):
    path = os.path.join(I18N, LOCALE_FILES[var_name])
    text = read(path)
    m = re.search(r"^val %s\s*=\s*Strings\(" % re.escape(var_name), text, re.M)
    open_idx = m.end() - 1
    close_idx = match_paren(text, open_idx)
    block = text[open_idx + 1:close_idx]
    spans = split_spans(block)

    entries, order = {}, []
    for raw, _a, _b in spans:
        cleaned = re.sub(r"^\s*(?://[^\n]*\n\s*)*", "", raw)   # 跳过前导注释行
        nm = re.match(r"\s*(\w+)\s*=", cleaned)
        if not nm:
            continue
        entries[nm.group(1)] = raw
        order.append(nm.group(1))

    pieces, inserted = [], set()
    for key in order:
        grp = MOVED.get(key)
        if grp is None:
            pieces.append(entries[key])       # 保留的实参原样透传
            continue
        if grp in inserted:
            continue
        inserted.add(grp)
        p, cls, _label, _doc, keys = GROUP_PARAM[grp]
        present = [k for k in keys if k in entries]
        inner_parts = []
        for idx, k in enumerate(present):
            raw_k = entries[k]
            sep = "" if idx == 0 else (
                ",\n\n" if len(re.match(r"\n*", raw_k).group(0)) >= 2 else ",\n")
            inner_parts.append(sep + norm_block(raw_k, 8))
        inner = "".join(inner_parts)
        nl = re.match(r"\n*", entries[key]).group(0)
        pieces.append(f"{nl}    {p} = {cls}(\n{inner}\n    )")

    new_text = (text[:open_idx + 1] + join_chunks(pieces, block) + "\n)" + text[close_idx + 1:])
    return new_text, len(order) - sum(1 for k in order if k in MOVED) + len(GROUPS)


def main():
    apply = "--apply" in sys.argv
    new_kt, n_outer, n_moved = transform_strings_kt()
    print(f"Strings.kt: 移动 {n_moved} 个参数 → 主构造器 245 → {n_outer}")
    print(f"  dex 槽位: 255 → {1 + n_outer + (n_outer + 31) // 32 + 1}")
    for _p, cls, _l, _d, keys in GROUPS:
        print(f"  {cls:20s} {len(keys):3d} 参数")
    if apply:
        write(STRINGS_KT, new_kt)
    for var in LOCALE_FILES:
        new_loc, n = transform_locale(var)
        print(f"  {LOCALE_FILES[var]:10s} 顶层实参 → {n}")
        if apply:
            write(os.path.join(I18N, LOCALE_FILES[var]), new_loc)
    if not apply:
        print("\n（预览模式；加 --apply 落盘）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
