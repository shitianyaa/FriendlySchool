#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LSPosed Manager UI 小工具：dump / ls / find / tap（坐标来自 uiautomator 的 bounds）。"""
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
import uuid
import xml.etree.ElementTree as ET

ADB = "adb"


def sh(cmd):
    env = dict(os.environ, MSYS2_ARG_CONV_EXCL="*")
    r = subprocess.run(cmd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", env=env, timeout=30)
    if r.returncode:
        raise RuntimeError(f"命令失败（退出码 {r.returncode}）：{r.stderr or r.stdout}")
    return r.stdout + r.stderr


def dump():
    remote = f"/data/local/tmp/lsposed-ui-{uuid.uuid4().hex}.xml"
    output = sh([ADB, "shell", "uiautomator", "dump", remote])
    if "dumped to:" not in output or "ERROR:" in output:
        raise RuntimeError(f"获取 UI 失败：{output.strip()}")
    temp_root = Path("D:/tmp/codex/lsposed-ui")
    temp_root.mkdir(parents=True, exist_ok=True)
    try:
        with tempfile.TemporaryDirectory(dir=temp_root) as folder:
            local = Path(folder) / "ui.xml"
            sh([ADB, "pull", remote, str(local)])
            return local.read_text(encoding="utf-8")
    finally:
        sh([ADB, "shell", "rm", remote])


def parse(x):
    out = []
    for n in ET.fromstring(x).iter("node"):
        out.append({
            "text": n.get("text", ""),
            "desc": n.get("content-desc", ""),
            "bounds": n.get("bounds", ""),
            "class": n.get("class", "").split(".")[-1],
            "checkable": n.get("checkable", ""),
            "checked": n.get("checked", ""),
        })
    return out


def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        return None
    return (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2


def tap(bounds):
    point = center(bounds)
    if point is None:
        raise ValueError(f"无效 bounds：{bounds}")
    x, y = point
    sh([ADB, "shell", "input", "tap", str(x), str(y)])
    print("tap %d,%d  (%s)" % (x, y, bounds))


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "ls"
    if cmd == "tapbounds":
        tap(sys.argv[2])
        return
    if cmd == "back":
        sh([ADB, "shell", "input", "keyevent", "KEYCODE_BACK"])
        return
    if cmd not in ("ls", "find", "tap"):
        raise ValueError(f"未知命令：{cmd}")
    nodes = parse(dump())
    if cmd == "ls":
        for n in nodes:
            if n["text"] or n["desc"] or n["checkable"] == "true":
                print("%-46s | %-24s | %-24s | %s" %
                      (n["text"][:46], n["desc"][:24], n["bounds"], n["class"] +
                       (" checked=" + n["checked"] if n["checkable"] == "true" else "")))
    elif cmd == "find":
        want = sys.argv[2]
        for n in nodes:
            if want in n["text"] or want in n["desc"]:
                print("%-46s | %-24s | %s" % (n["text"][:46], n["bounds"], n["class"]))
    elif cmd == "tap":
        want = sys.argv[2]
        n = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        hits = [x for x in nodes if want in x["text"] or want in x["desc"]]
        if not hits:
            print("NOT FOUND: " + want)
            sys.exit(2)
        if n < 0 or n >= len(hits):
            raise ValueError(f"匹配序号越界：{n}，共 {len(hits)} 项")
        tap(hits[n]["bounds"])


if __name__ == "__main__":
    main()
