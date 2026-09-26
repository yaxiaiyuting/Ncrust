#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
probe-strings.py — v2.5.3 · P0 探针：`Strings` 现状与拆分选型。

不猜、不引用二手结论：全部数据从 HEAD 的源码里数出来。

产出：
  1. `Strings` 主构造参数个数（两种解析法互证）+ dex 255 槽算式
  2. 每个嵌套组自身的参数个数
  3. 调用点分布：**按消费文件**（= 屏幕 / 组件）列出读了哪些 key、各读几次
  4. 高频 key / 只被读过一次的 key
  5. 8 个语言包里跨语言逐字相同的 key（拆分风险最低的那些）
  6. 候选拆分方案（按消费文件聚类）+ 每组规模

用法：
    python3 docs/verification/v2.5.3/probe-strings.py
"""

import os
import re
import sys
from collections import Counter, defaultdict

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SRC = os.path.join(REPO, "app", "src")
MAIN_JAVA = os.path.join(SRC, "main", "java", "com", "takahashirinta", "ncrust")
I18N = os.path.join(MAIN_JAVA, "ui", "i18n")
STRINGS_KT = os.path.join(I18N, "Strings.kt")

LOCALES = ["zhCN", "zhTW", "en", "jpJP", "jpMY", "koNK", "deDE", "ruRU"]
LOCALE_FILES = {
    "zhCN": "zh_CN.kt", "zhTW": "zh_TW.kt", "en": "en.kt", "jpJP": "jp_JP.kt",
    "jpMY": "jp_MY.kt", "koNK": "ko_NK.kt", "deDE": "de_DE.kt", "ruRU": "ru_RU.kt",
}


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def strip_strings_and_comments(code):
    """把字符串字面量内容与注释替换成等长占位，避免里面的括号/逗号骗过计数。

    ⚠️ **必须逐字符等长**：`match_paren` 拿清理后的文本找配对括号，
    却要用这个下标去切**原文**。长度一变，切出来的参数表就是错的
    （本探针第一版正是在这里把 245 个参数数成 109 个）。
    """
    out = list(code)
    i, n = 0, len(code)

    def blank(a, b):
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        ch = code[i]
        if ch == '"':
            if code.startswith('"""', i):
                j = code.find('"""', i + 3)
                j = n if j < 0 else j + 3
                blank(i, min(j, n))
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
            # 保留首尾引号，中间抹平（引号本身不参与括号计数，保留只为可读）
            blank(i + 1, min(j, n) - 1 if j <= n else n)
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


def split_top_level(block):
    """把参数/实参块按顶层逗号切开。

    三种会骗过朴素括号计数的形状都要处理：
      · 函数类型 `(String) -> String` —— `>` 前面是 `-`，不是泛型闭合；
      · 泛型 `Map<String, Int>` —— 里面的逗号不是顶层逗号；
      · 字符串字面量 / 注释里的括号与逗号（先剥掉）。
    """
    block = strip_strings_and_comments(block)
    out, depth, cur = [], 0, []
    i, n = 0, len(block)
    while i < n:
        ch = block[i]
        if ch == "-" and i + 1 < n and block[i + 1] == ">":
            cur.append("->")
            i += 2
            continue
        if ch == "<":
            prev = block[i - 1] if i > 0 else ""
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
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    if "".join(cur).strip():
        out.append("".join(cur))
    return out


def match_paren(text, open_idx):
    """从 `(` 的下标开始，返回配对 `)` 的下标（先剥字符串与注释）。"""
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


# ---------------------------------------------------------------- 1. 构造器
def parse_data_classes(text):
    """返回 {类名: {"params": [名字...], "line": 行号}}"""
    classes = {}
    for m in re.finditer(r"^data class (\w+)\(", text, re.M):
        name = m.group(1)
        i = match_paren(text, m.end() - 1)
        if i < 0:
            continue
        block = text[m.end():i]
        params, types = [], {}
        for raw in split_top_level(block):
            pm = re.search(r"val\s+(\w+)\s*:\s*([^=\n]+?)\s*(?:=|$)", raw, re.M)
            if pm:
                params.append(pm.group(1))
                types[pm.group(1)] = pm.group(2).strip()
        classes[name] = {"params": params, "types": types,
                         "line": text[:m.start()].count("\n") + 1}
    return classes


# ---------------------------------------------------------------- 2. 调用点
BIND_FROM_LOCAL = re.compile(r"\b(?:val|var)\s+(\w+)\s*=\s*LocalStrings\.current\b")
BIND_TYPED = re.compile(r"\b(\w+)\s*:\s*Strings\b")
BIND_ALIAS = re.compile(r"\b(?:val|var)\s+(\w+)\s*=\s*(\w+)\s*$", re.M)


def strings_idents_in(text):
    """该文件里被绑定为 `Strings` 的标识符集合（含一跳传递别名）。"""
    idents = set(BIND_FROM_LOCAL.findall(text))
    idents |= set(BIND_TYPED.findall(text))
    for m in BIND_ALIAS.finditer(text):
        if m.group(2) in idents:
            idents.add(m.group(1))
    return idents


def collect_call_sites(group_param_names):
    """统计 `stringsIdent.key` 与 `stringsIdent.group.field` 的读取。

    只统计接收者确实是本文件里绑定的 `Strings` 标识符的读取 ——
    否则 `song.source` / `list.clear()` 这类同名属性会大量误报。
    """
    sites = defaultdict(list)      # key -> [(相对路径, 行号)]
    per_file = defaultdict(set)    # 相对路径 -> {key}
    for root, _dirs, files in os.walk(SRC):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(root, fn)
            rel = os.path.relpath(path, REPO)
            if rel.startswith(os.path.relpath(I18N, REPO)):
                continue  # 定义处
            text = read(path)
            idents = strings_idents_in(text)
            if not idents:
                continue
            for no, line in enumerate(text.splitlines(), 1):
                code = strip_strings_and_comments(line)
                # ① 嵌套组：strings.queue.actionAddToNext
                for m in re.finditer(r"\b(\w+)\.(\w+)\.(\w+)\b", code):
                    if m.group(1) in idents and m.group(2) in group_param_names:
                        key = m.group(3)
                        sites[key].append((rel, no))
                        per_file[rel].add(key)
                # ② 一层：strings.tabHome
                for m in re.finditer(r"\b(\w+)\.(\w+)\b", code):
                    if m.group(1) in idents:
                        key = m.group(2)
                        sites[key].append((rel, no))
                        per_file[rel].add(key)
                # ③ 直接：LocalStrings.current.tabHome
                for m in re.finditer(r"LocalStrings\.current\.(\w+)", code):
                    sites[m.group(1)].append((rel, no))
                    per_file[rel].add(m.group(1))
    return sites, per_file


# ---------------------------------------------------------------- 3. 语言包
def parse_locale_values(text, var_name):
    """解析 `val <var> = Strings(...)` 的顶层实参 → {param: 源码文本}"""
    m = re.search(r"^val %s\s*=\s*Strings\(" % re.escape(var_name), text, re.M)
    if not m:
        return {}
    i = match_paren(text, m.end() - 1)
    if i < 0:
        return {}
    out = {}
    for raw in split_top_level(text[m.end():i]):
        # 去掉前导注释行 —— 参数前有 KDoc / // 是常态
        cleaned = re.sub(r"^\s*(?://[^\n]*\n\s*)*", "", raw)
        nm = re.match(r"\s*(\w+)\s*=", cleaned)
        if nm:
            out[nm.group(1)] = cleaned[nm.end():].strip().rstrip(",").strip()
    return out


def module_of(rel_path):
    """把消费文件归到一个「功能面」标签。"""
    p = rel_path.replace("\\", "/")
    base = os.path.basename(p)
    if p.endswith("MainActivity.kt"):
        return "MainActivity"
    for marker, tag in [
        ("/ui/screen/", "screen"), ("/ui/player/", "player"),
        ("/ui/components/", "components"), ("/ui/viewmodel/", "viewmodel"),
        ("/ui/theme/", "theme"), ("/ui/navigation/", "navigation"),
    ]:
        if marker in p:
            return f"{tag}:{base[:-3]}"
    return "other:" + base[:-3]


def main():
    text = read(STRINGS_KT)
    classes = parse_data_classes(text)
    outer = classes.get("Strings")
    if not outer:
        print("FATAL: 没解析到 Strings", file=sys.stderr)
        return 2

    n = len(outer["params"] or [])
    body = text[text.index("data class Strings("):]
    end = body.index("\n) {")
    depth1 = len(re.findall(r"^    val \w+\s*:", body[:end], re.M))
    masks = (n + 31) // 32
    slots = 1 + n + masks + 1

    print("=" * 78)
    print("1) Strings 主构造器现状")
    print("=" * 78)
    print(f"  解析法 A（顶层逗号切分后取 val）      : {n}")
    print(f"  解析法 B（深度为 1 的 `    val x:` 行）: {depth1}")
    print(f"  两法一致                               : {'是' if n == depth1 else '否 ← 需人工复核'}")
    print(f"  dex 槽位算式  : this(1) + N({n}) + mask ceil(N/32)={masks} + marker(1) = {slots}")
    print(f"  上限 255 / 余量 {255 - slots}"
          f"{'   ← 天花板：再加一个参数即真机启动崩' if slots == 255 else ''}")
    print()

    groups = {k: v for k, v in classes.items() if k != "Strings"}
    group_class_names = set(groups.keys())
    # ★ 关键：`strings.queue.actionAddToNext` 里的 `queue` 是**主构造参数名**，
    #   不是组类名 `QueueStrings`。两者都从主构造参数的类型里推出来。
    group_param_names = {p for p, t in outer["types"].items()
                         if t.split("<")[0].strip() in group_class_names}
    forwarding = {name: (group, field) for name, group, field in re.findall(
        r"^    val (\w+)[^=\n]*get\(\)\s*=\s*(\w+)\.(\w+)",
        text[text.index("\n) {"):], re.M)}
    print("=" * 78)
    print("2) 既有嵌套组自身规模")
    print("=" * 78)
    for name, info in sorted(groups.items(), key=lambda kv: -len(kv[1]["params"])):
        print(f"  {name:22s} {len(info['params']):3d} 参数   (Strings.kt:{info['line']})")
    all_group_params = set()
    for info in groups.values():
        all_group_params |= set(info["params"])
    print(f"  {'合计（去重）':22s} {len(all_group_params):3d} 参数")
    print()

    sites, per_file = collect_call_sites(group_param_names)
    # 词汇表 = 主构造参数 ∪ 嵌套组字段 ∪ 类体里的**转发属性**
    # （`val tagPlayable get() = tags.playable`）—— 调用点常常走转发属性，
    # 不把它们算进来会把大量真实调用点误判成「从未被读取」。
    known = set(outer["params"]) | all_group_params | set(forwarding.keys())
    hit = {k: v for k, v in sites.items() if k in known and v}
    # 「外层未被读取」只对**真参数**判定；组参数名本身不在调用点出现（出现的是它的字段），
    # 所以单独排除，否则会把 7 个组参数全部误报成死文案。
    unread_outer = [p for p in outer["params"]
                    if p not in hit and p not in group_param_names and p not in forwarding]
    unread_group = sorted(p for p in all_group_params if p not in hit)
    # 组参数名的可达性：只要组里任何一个字段被读过，就说明这个组参数在用。
    reachable_groups = {forwarding[k][0] for k in hit if k in forwarding}
    reachable_groups |= {p for p in group_param_names if p in hit}
    unread_outer += [p for p in group_param_names if p not in reachable_groups]

    print("=" * 78)
    print("3) 调用点分布（按消费文件）")
    print("=" * 78)
    print(f"  消费 Strings 的文件数      : {len(per_file)}")
    print(f"  被读取过的 key 总数        : {len(hit)} / {len(known)}")
    print(f"  嵌套组参数名（{len(group_param_names)}）: {sorted(group_param_names)}")
    print(f"  类体转发属性（{len(forwarding)}）: 调用点走 `strings.<转发名>` 的老写法")
    print(f"  · 外层真参数未被读取       : {len(unread_outer)}")
    if unread_outer:
        print("    " + ", ".join(sorted(unread_outer)))
    print(f"  · 嵌套组内未被读取         : {len(unread_group)}")
    if unread_group:
        for i in range(0, len(unread_group), 8):
            print("    " + ", ".join(unread_group[i:i + 8]))
    print()

    print("  --- 每个消费文件用了多少 key（降序）---")
    for rel, keys in sorted(per_file.items(), key=lambda kv: -len(kv[1]))[:30]:
        print(f"    {module_of(rel):34s} {len(keys):3d} keys   {rel}")

    print()
    print("  --- 按功能面汇总 ---")
    area = defaultdict(set)
    for rel, keys in per_file.items():
        area[module_of(rel).split(":")[0]].update(keys)
    for k, v in sorted(area.items(), key=lambda kv: -len(kv[1])):
        print(f"    {k:16s} {len(v):3d} keys")
    print()

    print("=" * 78)
    print("4) 高频 / 低频 key（按读取次数）")
    print("=" * 78)
    freq = sorted(hit.items(), key=lambda kv: -len(kv[1]))
    print("  --- 高频 top 30 ---")
    for key, refs in freq[:30]:
        print(f"    {key:32s} reads={len(refs):4d} files={len({r for r, _ in refs}):3d}")
    once = sorted(k for k, v in hit.items() if len(v) == 1)
    print(f"  --- 只被读过 1 次的 key: {len(once)} 个 ---")
    for i in range(0, len(once), 6):
        print("    " + ", ".join(once[i:i + 6]))
    print()

    print("=" * 78)
    print("5) 语言包一致性")
    print("=" * 78)
    values = {}
    for name in LOCALES:
        values[name] = parse_locale_values(read(os.path.join(I18N, LOCALE_FILES[name])), name)
    print("  各语言文件解析到的顶层实参个数:")
    for name in LOCALES:
        print(f"    {name:6s} {len(values[name]):3d}   (Strings 主构造参数 {n})")
    common = set(outer["params"])
    for name in LOCALES:
        common &= set(values[name].keys())
    print(f"  8 个语言文件都给的 key : {len(common)}")
    for name in LOCALES:
        miss = sorted(set(outer["params"]) - set(values[name].keys()))
        if miss:
            print(f"    ⚠ {name:6s} 缺 {len(miss)}: {miss}")
    same_all = [k for k in sorted(common)
                if len({values[name][k] for name in LOCALES}) == 1]
    print(f"  在所有 8 个语言里取值**逐字相同**的 key: {len(same_all)}")
    for i in range(0, len(same_all), 6):
        print("    " + ", ".join(same_all[i:i + 6]))
    # 抽样展示几个「逐字相同」的值，便于人工判断它们是不是「本就不该翻译」
    for k in same_all[:6]:
        print(f"      {k:28s} = {values['zhCN'][k][:60]}")
    print()

    print("=" * 78)
    print("6) 候选拆分方案")
    print("=" * 78)
    print("  方案 A（按功能面 = 消费文件聚类）")
    for tag, keys in sorted(area.items(), key=lambda kv: -len(kv[1])):
        print(f"    {tag:16s} {len(keys):3d} keys")
    print()
    unowned = sorted(k for k in known if k not in hit)
    print(f"  没有任何调用点的 key（拆分时可以留原位）: {len(unowned)}")
    print()

    print("=" * 78)
    print("7) 结论速览")
    print("=" * 78)
    print(f"  · Strings 主构造器 = {n} 参数 = {slots}/255 槽，余量 {255 - slots}")
    print(f"  · 嵌套组 {len(groups)} 个，最大 {max((len(v['params']) for v in groups.values()), default=0)} 参数")
    print(f"  · 消费 Strings 的文件 {len(per_file)} 个，跨 {len(area)} 个功能面")
    print(f"  · 8 语言逐字相同的 key: {len(same_all)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
