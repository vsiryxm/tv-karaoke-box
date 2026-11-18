#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""单主干双 flavor 的源码剥离器（构建期工具，不打包进 APK）。

主线源码 src/com/xinmin/karaoke/KaraokeActivity.java 里，所有「直播模式」专属
代码都用成对的标记包起来：

    /* LIVE-BEGIN */
    ... 只有定制版才需要的字段 / 方法 / 分支 ...
    /* LIVE-END */

- `flavor.py strip <输入.java> <输出.java>`：删掉全部标记块，产出开源版源码。
  这样开源版在**编译之前**就没有直播代码，dex 里不会出现相关类、字符串或配置键。
- `flavor.py check <输入.java>`：只做标记配对与合法性检查，不写文件。

剥离后还会做一次静态自检：源码里不得残留任何直播专属词。构建脚本会调用它，
不通过就让编译失败，避免「以为隔离了其实漏了」。

注意：标记必须独占一行（允许前导空格），不允许嵌套。
"""
import io
import os
import sys

BEGIN = "/* LIVE-BEGIN */"
END = "/* LIVE-END */"

# 开源版源码里绝对不该出现的词。命中即说明标记漏包，或直播代码写到了块外。
BANNED = (
    "LIVE-BEGIN", "LIVE-END",
    "liveMode", "liveEnabled", "liveNotice", "live_start", "live_end",
    "warn_before", "programIdx", "programWait", "karaoke_program",
    "karaoke_openings", "opening_secs", "opening_hold", "openingOn",
    "openingSecs", "openingHold", "openings", "节目单", "报幕",
    "开播提醒", "中场休息", "下播预告", "下播提醒", "弹奏",
)


def strip_source(src_path, out_path=None, verify=True):
    with io.open(src_path, encoding="utf-8") as f:
        lines = f.read().splitlines()

    out, in_live, removed, pairs = [], False, 0, 0
    for no, line in enumerate(lines, 1):
        s = line.strip()
        if s == BEGIN:
            if in_live:
                raise SystemExit("第 %d 行：LIVE-BEGIN 嵌套了，不允许" % no)
            in_live = True
            pairs += 1
            continue
        if s == END:
            if not in_live:
                raise SystemExit("第 %d 行：LIVE-END 没有配对的 LIVE-BEGIN" % no)
            in_live = False
            continue
        if in_live:
            removed += 1
        else:
            out.append(line)
    if in_live:
        raise SystemExit("文件末尾仍在 LIVE 块内：缺少 LIVE-END")
    if pairs == 0:
        raise SystemExit("源码里一个 LIVE 块都没有，剥离器可能没被正确接入")

    text = "\n".join(out) + "\n"
    if verify:
        hits = [w for w in BANNED if w in text]
        if hits:
            raise SystemExit("开源版源码仍残留直播专属词：%s" % ", ".join(hits))

    if out_path:
        d = os.path.dirname(out_path)
        if d and not os.path.isdir(d):
            os.makedirs(d)
        with io.open(out_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    if verify:
        print("   flavor: 剥离 %d 个直播块 / %d 行，开源源码 %d 行，禁用词命中 0"
              % (pairs, removed, len(out)))
    else:
        print("   flavor: 标记配对检查通过（%d 块 / %d 行），完整源码 %d 行"
              % (pairs, removed, len(lines)))
    return text


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit("用法: flavor.py strip|check <输入.java> [输出.java]")
    mode, src = sys.argv[1], sys.argv[2]
    if mode == "strip":
        if len(sys.argv) < 4:
            raise SystemExit("strip 需要输出路径")
        strip_source(src, sys.argv[3])
    elif mode == "check":
        strip_source(src, None, verify=False)
    else:
        raise SystemExit("未知模式: " + mode)
