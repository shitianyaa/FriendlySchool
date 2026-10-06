#!/usr/bin/env python3
"""导出并比对 LSPosed 的"hook 清单"（框架侧视角），用来在目标 App 升级后发现 hook 悄悄消失。

原理：`lspctl hook-debug dump` 会列出**当前存活**的被 hook 进程里每个被 hook 的方法
（运行时真实签名 + 跳板地址 + EntryPoint/AccessFlags 完整性比对）。把它规范化成
"一行一个方法签名"的快照，之后 diff 就能看出新增/消失。

前置条件（缺一不可）：
  1. LSPosed 管理器里打开「开发者模式」与「Hook Debug」（管理器标注 Hook Debug 有性能开销，用完关掉）；
  2. 目标 App **必须在 Hook Debug 打开之后新起过进程** —— hook-debug 是进程 fork 时注入的，
     否则 dump 只会返回 `No process to dump`；
  3. 设备已 root（脚本用 `adb shell su -c`）。

用法：
  python hook_inventory.py                        # 默认易校园，存到 <仓库根>/Lessons/hook-inventory/
  python hook_inventory.py --pkg com.suda.yzune.wakeupschedule
  python hook_inventory.py --label after-update   # 归档名里带标记，便于人工区分
  python hook_inventory.py --no-diff              # 只存档不比对

产物：<outdir>/<包名>-<时间戳>[-标记].txt  —— 头部是汇总，`# methods` 之后是排序去重的方法签名。
每次运行都会自动与同包名**上一份**快照比对，打印消失/新增的方法（消失 = 需要人工核对）。
"""
import argparse
import datetime
import glob
import os
import re
import shutil
import subprocess
import sys

LSPCTL = "/data/adb/modules/zygisk_lsposed/lspctl"
DEFAULT_PKG = "cn.com.yunma.school.app"
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
DEFAULT_OUTDIR = os.path.join(REPO_ROOT, "Lessons", "hook-inventory")

METHOD_RE = re.compile(r"^\s+0x[0-9a-f]+\s+(\S.*)$")
PROC_RE = re.compile(r"^pid=(\d+)\s+uid=(\d+)\s+name=(\S+)")


def adb_available():
    return shutil.which("adb") is not None


def dump(pkg):
    """跑一次 lspctl hook-debug dump，返回 (原始文本, 错误说明)。"""
    cmd = ["adb", "shell", "su", "-c", f"{LSPCTL} hook-debug dump"]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=90)
    except subprocess.TimeoutExpired:
        return "", "dump 超时（90s）：设备无响应或进程过多"
    text = (out.stdout or "") + (out.stderr or "")
    if out.returncode:
        return "", f"dump 命令失败（退出码 {out.returncode}）：{text.strip()}"
    if "No process to dump" in text:
        return "", ("No process to dump —— hook-debug 只在进程 fork 时注入："
                    "请先在 Hook Debug 打开的前提下重启目标 App，再跑本脚本")
    if "Permission denied" in text or "transact failed" in text:
        return "", "权限/开关问题：需要 root，且管理器里要打开「开发者模式」"
    if not text.strip():
        return "", "dump 无输出：检查 adb 连接、root、以及设备上 lspctl 是否存在"
    return text, None


def package_dump(text, pkg):
    """保留目标包及其冒号子进程，完整性检查也只能使用这个范围。"""
    selected = False
    lines = []
    for line in text.splitlines():
        match = PROC_RE.match(line)
        if match:
            name = match.group(3)
            selected = name == pkg or name.startswith(pkg + ":")
        if selected:
            lines.append(line)
    return "\n".join(lines)


def parse(text):
    """提取 进程 -> 方法签名列表。"""
    procs = {}
    cur = None
    for line in text.splitlines():
        m = PROC_RE.match(line)
        if m:
            cur = f"{m.group(3)} (pid={m.group(1)}, uid={m.group(2)})"
            procs.setdefault(cur, [])
            continue
        m = METHOD_RE.match(line)
        if m and cur:
            procs[cur].append(m.group(1).strip())
    return procs


def entry_point_broken(text):
    """入口点 eq=false 的方法名（= hook 没真正生效），以及 accessFlags eq=false 的条数（常态）。"""
    lines = text.splitlines()
    broken, flags_false = [], 0
    for i, line in enumerate(lines):
        if "realEntryPoint" in line and "eq=false" in line:
            for j in range(i, max(-1, i - 5), -1):
                m = METHOD_RE.match(lines[j])
                if m:
                    broken.append(m.group(1).strip())
                    break
        elif "realAccessFlags" in line and "eq=false" in line:
            flags_false += 1
    return broken, flags_false


def latest_snapshot(outdir, pkg):
    files = sorted(glob.glob(os.path.join(outdir, f"{pkg}-*.txt")))
    return files[-1] if files else None


def main():
    ap = argparse.ArgumentParser(description="导出/比对 LSPosed hook 清单")
    ap.add_argument("--pkg", default=DEFAULT_PKG, help=f"目标包名（默认 {DEFAULT_PKG}）")
    ap.add_argument("--outdir", default=DEFAULT_OUTDIR, help=f"存档目录（默认 {DEFAULT_OUTDIR}）")
    ap.add_argument("--label", default="", help="归档名里的标记，例如 after-update")
    ap.add_argument("--no-diff", action="store_true", help="只存档，不与上一份比对")
    ap.add_argument("--limit", type=int, default=400, help="控制台最多展示的方法数；快照始终保存完整清单")
    args = ap.parse_args()

    if not adb_available():
        sys.exit("找不到 adb（PATH 里没有）")

    text, err = dump(args.pkg)
    if err:
        sys.exit(err)

    if args.limit < 1:
        ap.error("--limit 必须大于 0")
    text = package_dump(text, args.pkg)
    procs = parse(text)
    if not procs:
        sys.exit(f"dump 中没有目标进程 {args.pkg}：请确认目标 App 已启动且已启用 Hook Debug")

    total = sum(len(v) for v in procs.values())
    broken, flags_false = entry_point_broken(text)
    limit = args.limit

    def signatures():
        """所有进程的方法签名并集（App 升级后 hook 消失是全体性的，按并集比足够）。"""
        s = set()
        for v in procs.values():
            s.update(v)
        return s

    method_lines = sorted(signatures())

    os.makedirs(args.outdir, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    name = f"{args.pkg}-{stamp}" + (f"-{args.label}" if args.label else "") + ".txt"
    path = os.path.join(args.outdir, name)

    prev = None if args.no_diff else latest_snapshot(args.outdir, args.pkg)
    prev_methods = set()
    if prev:
        with open(prev, encoding="utf-8") as f:
            previous = f.read()
        prev_methods = {l.strip() for l in previous.splitlines()
                        if l.strip() and not l.startswith("#") and not l.startswith("pid=")}
        count = re.search(r"去重后: (\d+)", previous)
        if not count or int(count.group(1)) != len(prev_methods) or "# 进程范围: " + args.pkg not in previous:
            print("[skip] 上一份快照被截断或来自旧版未筛选进程，跳过比对，本次重建基线")
            prev = None

    with open(path, "x", encoding="utf-8", newline="\n") as f:
        f.write(f"# hook 清单快照\n")
        f.write(f"# 包名: {args.pkg}\n")
        f.write(f"# 进程范围: {args.pkg} 及其冒号子进程\n")
        f.write(f"# 时间: {datetime.datetime.now().isoformat(timespec='seconds')}\n")
        for p, ms in sorted(procs.items()):
            f.write(f"pid={p} 方法数={len(ms)}\n")
        f.write(f"# 合计方法数(含重复进程): {total}；去重后: {len(signatures())}\n")
        f.write(f"# 入口点 eq=false（= hook 未真正生效）: {len(broken)}\n")
        for b in broken:
            f.write(f"#   BROKEN {b}\n")
        f.write(f"# accessFlags eq=false（ART 优化常态，非问题）: {flags_false}\n")
        f.write(f"# methods（完整清单，共 {len(method_lines)} 条）\n")
        for m in method_lines:
            f.write(m + "\n")

    print(f"[ok] 快照已存: {path}")
    print(f"     进程 {len(procs)} 个；方法（去重）{len(signatures())} 条；入口点未生效 {len(broken)} 条；"
          f"accessFlags 差异 {flags_false} 条（常态）")
    if broken:
        print("     ⚠ 以下 hook 的入口点被破坏（= 没真正生效），需要人工核对：")
        for b in broken[:min(20, limit)]:
            print(f"       - {b}")

    if prev:
        cur = set(method_lines)
        gone, added = sorted(prev_methods - cur), sorted(cur - prev_methods)
        print(f"\n与上一份快照比对（{os.path.basename(prev)}）：消失 {len(gone)} 条，新增 {len(added)} 条")
        for g in gone[:min(30, limit)]:
            print(f"   - {g}")
        if len(gone) > min(30, limit):
            print(f"   … 其余 {len(gone) - min(30, limit)} 条请比对两份完整快照")
        for a in added[:min(15, limit)]:
            print(f"   + {a}")
    else:
        print("     （没有更早的同包名快照，本次作为基线）")


if __name__ == "__main__":
    main()
