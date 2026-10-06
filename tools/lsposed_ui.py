#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LSPosed Manager UI 小工具：dump / ls / find / tap（坐标来自 uiautomator 的 bounds）。"""
import os
import re
import subprocess
import sys

ADB = "adb"


def sh(cmd):
    env = dict(os.environ, MSYS2_ARG_CONV_EXCL="*")
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", env=env)
    return r.stdout + r.stderr


def dump():
    sh(ADB + " shell uiautomator dump /sdcard/ui.xml")
    sh(ADB + " pull /sdcard/ui.xml /tmp/ui.xml")
    return open("/tmp/ui.xml", encoding="utf-8").read()


def parse(x):
    out = []
    for n in re.findall(r"<node[^>]*>", x):
        t = re.search(r'text="([^"]*)"', n)
        d = re.search(r'content-desc="([^"]*)"', n)
        b = re.search(r'bounds="([^"]*)"', n)
        c = re.search(r'class="([^"]*)"', n)
        k = re.search(r'checkable="([^"]*)"', n)
        ck = re.search(r'checked="([^"]*)"', n)
        out.append({
            "text": t.group(1) if t else "",
            "desc": d.group(1) if d else "",
            "bounds": b.group(1) if b else "",
            "class": (c.group(1).split(".")[-1] if c else ""),
            "checkable": k.group(1) if k else "",
            "checked": ck.group(1) if ck else "",
        })
    return out


def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        return None
    return (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2


def tap(bounds):
    x, y = center(bounds)
    sh(ADB + " shell input tap %d %d" % (x, y))
    print("tap %d,%d  (%s)" % (x, y, bounds))


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "ls"
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
        tap(hits[min(n, len(hits) - 1)]["bounds"])
    elif cmd == "tapbounds":
        tap(sys.argv[2])
    elif cmd == "back":
        sh(ADB + " shell input keyevent KEYCODE_BACK")


main()
