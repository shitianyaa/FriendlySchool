#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把两个独立 LSPosed 模块的 hook 实现移植成 FriendlySchool 的 target 类。

!!! 一次性迁移快照，已经执行过，不要随手重跑 !!!
重跑会从两个老模块重新生成 targets/*.java，把合并之后手写的东西全部覆盖，
包括：文件头的来源注释、YiCampusTarget#installTelemetryBlocker 的逐 SDK try/catch 修复。
只有在“决定重做一遍合并”时才应该跑它。

只做机械变换，不改任何 hook 的语义：
  - package / 类名 / 入口方法改成 target 骨架
  - 删掉两份重复的私有工具方法（已上移到 SchoolTargetBase）
  - 删掉 log/logOnce（已上移到 SchoolTargetBase，且带目标前缀）
  - 删掉只被入口方法用的辅助（shortCl/verbose/VERBOSE/TAG/LOGGED_ONCE）
  - 剩下的 private static 方法改成实例方法（基类是实例方法）
"""
import re
import sys

SRC_YI = "D:/Project/SWUFE/FuckYiCampus/module/src/com/yiran/fuckyicampus/YiCampusCleanHook.java"
SRC_WAKE = "D:/Project/SWUFE/WakeUp/_analysis/debloat/module/src/com/yiran/wakeupclean/WakeUpCleanHook.java"
OUT_DIR = "D:/Project/SWUFE/FriendlySchool/module/src/com/yiran/friendlyschool/targets"

# 文件头（%s = 目标描述，%s/%s = 原模块名/包名），与手工加在生成文件里的那段保持一致
HEADER = """/*
 * %s target。
 *
 * 代码来自独立的 %s 模块（%s，已随本次合并下线），
 * 逐行移植、hook 调用点未改；日志与 hook 工具的公共部分在 core.SchoolTargetBase，
 * Application.attach/onCreate 的双挂钩在 core.HookContext，分发在 core.Targets。
 * 移植脚本与逐点 diff 记录：FriendlySchool/tools/port_targets.py、README §8 / §9.2。
 */
"""


def find_block_end(s, open_idx):
    i, depth, n = open_idx, 0, len(s)
    while i < n:
        c = s[i]
        if c == '/' and i + 1 < n and s[i + 1] == '/':
            j = s.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and i + 1 < n and s[i + 1] == '*':
            j = s.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"' or c == "'":
            q = c
            i += 1
            while i < n:
                if s[i] == '\\':
                    i += 2
                    continue
                if s[i] == q:
                    break
                i += 1
            i += 1
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise RuntimeError("unbalanced braces")


def cut(src, sig):
    """删除以 sig 开头、带 {} 体的整个方法块（含前面的注解行与后面一行空行）。"""
    idx = src.find(sig)
    if idx < 0:
        raise RuntimeError("signature not found: " + sig)
    brace = src.find('{', idx)
    semi = src.find(';', idx)
    if semi >= 0 and semi < brace:
        raise RuntimeError("no body for: " + sig)
    end = find_block_end(src, brace)
    line_start = src.rfind('\n', 0, idx) + 1
    while True:
        prev_end = line_start - 1
        if prev_end <= 0:
            break
        prev_start = src.rfind('\n', 0, prev_end) + 1
        if src[prev_start:prev_end].strip().startswith('@'):
            line_start = prev_start
        else:
            break
    after = end + 1
    if src[after:after + 1] == '\n':
        after += 1
    out = src[:line_start] + src[after:]
    print("  cut %-70s (%d bytes)" % (sig[:68], after - line_start))
    return out


def cut_lines(src, needle, label):
    idx = src.find(needle)
    if idx < 0:
        raise RuntimeError("line not found: " + label)
    start = src.rfind('\n', 0, idx) + 1
    end = src.find('\n', idx) + 1
    out = src[:start] + src[end:]
    print("  cut line %-66s" % label)
    return out


def tidy(src):
    """删块留下的多余空行收一下。"""
    return re.sub(r'\n{3,}', '\n\n', src)


def port_yi():
    src = open(SRC_YI, encoding="utf-8").read()
    print("[YiCampus]")

    entry = "    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) throws Throwable {"
    src = cut(src, entry)
    src = src.replace(entry, "")

    for sig in [
        "    private static void hookAllMethodsNoop(Class<?> clazz, String methodName, String label) {",
        "    private static void hookAllMethodsReturnEmptyMap(Class<?> clazz, String methodName, String label) {",
        "    private static void log(String msg) {",
        "    private static void logOnce(String msg) {",
        "    private static void hideViewById(View root, String resName) {",
    ]:
        src = cut(src, sig)

    src = cut_lines(src, 'private static final String TAG = "FuckYiCampus";', "TAG")
    src = cut_lines(src, "private static final Set<String> LOGGED_ONCE =", "LOGGED_ONCE")

    for old, new in [
        ("private static boolean isAdMessage(Object item) {", "private boolean isAdMessage(Object item) {"),
        ("private static void adjustTopMenuConstraint(final View root) {", "private void adjustTopMenuConstraint(final View root) {"),
        ("private static void applyTopMenuConstraint(final View topMenu, final View inTitle) {", "private void applyTopMenuConstraint(final View topMenu, final View inTitle) {"),
        ("private static boolean isDangerousCommand(String cmd) {", "private boolean isDangerousCommand(String cmd) {"),
        ("private static boolean isCallerTracked() {", "private boolean isCallerTracked() {"),
    ]:
        assert src.count(old) == 1, old
        src = src.replace(old, new)

    src = src.replace("package com.yiran.fuckyicampus;",
                      "package com.yiran.friendlyschool.targets;\n\n"
                      + HEADER % ("易校园（cn.com.yunma.school.app）",
                                  "FuckYiCampus", "com.yiran.fuckyicampus")
                      + "\nimport com.yiran.friendlyschool.core.HookContext;\n"
                        "import com.yiran.friendlyschool.core.SchoolTargetBase;\n")
    src = src.replace("public class YiCampusCleanHook implements IXposedHookLoadPackage {",
                      "public class YiCampusTarget extends SchoolTargetBase {")

    boiler = '''
    // ------------------------------------------------------------ target 声明

    private static final String NAME = "易校园";

    @Override
    public String shortName() {
        return NAME;
    }

    @Override
    public String packageName() {
        return TARGET_PKG;
    }

    /**
     * 原 FuckYiCampus 的 handleLoadPackage 内容，只是去掉了包名门禁
     * （分发已经由 FriendlySchoolHook + Targets 做完）。
     */
    @Override
    public void install(final HookContext ctx) {
        installSystemHooks(ctx.classLoader);

        ctx.onAppReady(new HookContext.AppReady() {
            @Override
            public void run(ClassLoader appClassLoader) {
                installAllHooks(appClassLoader);
            }
        });
    }

'''
    anchor = "    @Override\n"  # 已删除，改为插入到 installSystemHooks 之前
    anchor = "    private synchronized void installSystemHooks(ClassLoader cl) {"
    assert src.count(anchor) == 1
    src = src.replace(anchor, boiler.lstrip('\n') + anchor)

    src = tidy(src)
    open(OUT_DIR + "/YiCampusTarget.java", "w", encoding="utf-8", newline="\n").write(src)
    print("  -> YiCampusTarget.java (%d lines)" % src.count("\n"))


def port_wake():
    src = open(SRC_WAKE, encoding="utf-8").read()
    print("[WakeUp]")

    entry = "    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) throws Throwable {"
    src = cut(src, entry)

    for sig in [
        "    private void hookReplaceAllNamed(ClassLoader cl, String className, final String methodName, final String label) {",
        "    private void hookReplaceVoid(ClassLoader cl, String className, String method, final String label) {",
        "    private void hookFalse(ClassLoader cl, String className, String method, String label) {",
        "    private void hookNoop(ClassLoader cl, String className, String method,",
        "    private static void log(String msg) {",
        "    private static void logOnce(String msg) {",
        "    private static String shortCl(ClassLoader cl) {",
        "    private static boolean verbose() {",
    ]:
        src = cut(src, sig)

    src = cut_lines(src, 'private static final String TAG = "WakeUpClean";', "TAG")
    src = cut_lines(src, "private static final boolean VERBOSE = true;", "VERBOSE")
    src = cut_lines(src, "private static final java.util.Set<String> LOGGED_ONCE =", "LOGGED_ONCE")
    src = cut_lines(src, "    /** 同一个消息只打一次，否则首页 banner 这类高频 hook 会把日志刷满。 */", "LOGGED_ONCE comment")
    src = cut_lines(src, "java.util.Collections.synchronizedSet(new java.util.HashSet<String>());", "LOGGED_ONCE continuation")

    for old, new in [
        ("private static void hideTopButtons(Object fragment) {", "private void hideTopButtons(Object fragment) {"),
        ("private static int shiftTexts(android.view.View root, int shift) {", "private int shiftTexts(android.view.View root, int shift) {"),
        ("private static String matchMarker(Object target) {", "private String matchMarker(Object target) {"),
        ("private static void toast(final android.app.Dialog dialog, final String text) {", "private void toast(final android.app.Dialog dialog, final String text) {"),
        ("private static void toast(final Activity act, final String text) {", "private void toast(final Activity act, final String text) {"),
        ("private static boolean isLoginActivity(String cls) {", "private boolean isLoginActivity(String cls) {"),
        ("private static String hybridRoute(Intent intent) {", "private String hybridRoute(Intent intent) {"),
        ("private static String readString(Object target, String fieldName) {", "private String readString(Object target, String fieldName) {"),
        ("private static boolean isBlocked(String url) {", "private boolean isBlocked(String url) {"),
        ("private static java.util.List<Object> findListArg(Object[] args) {", "private java.util.List<Object> findListArg(Object[] args) {"),
        ("private static boolean isRemovedTab(String fqcn) {", "private boolean isRemovedTab(String fqcn) {"),
        ("private static String tabFragmentName(Object tabItem) {", "private String tabFragmentName(Object tabItem) {"),
    ]:
        assert src.count(old) == 1, old
        src = src.replace(old, new)

    # installAppHooks 原来是 handleLoadPackage 里只调一次；现在 attach/onCreate 两次回调都要进来，所以补幂等守卫
    old_head = "    private void installAppHooks(ClassLoader cl) {\n        // E1 开屏广告"
    assert src.count(old_head) == 1
    src = src.replace(old_head,
                      "    /** 原来只从 attach 进来一次；合并后 attach/onCreate 两条路径都会回调，这里补幂等。 */\n"
                      "    private volatile boolean appHooksInstalled = false;\n\n"
                      "    private void installAppHooks(ClassLoader cl) {\n"
                      "        if (appHooksInstalled) {\n"
                      "            return;\n"
                      "        }\n"
                      "        appHooksInstalled = true;\n"
                      "        // E1 开屏广告")

    src = cut_lines(src, "// ------------------------------------------------------------------ 入口", "WakeUp 入口 section header")

    src = src.replace("package com.yiran.wakeupclean;",
                      "package com.yiran.friendlyschool.targets;\n\n"
                      + HEADER % ("WakeUp课程表（com.suda.yzune.wakeupschedule）",
                                  "WakeUpClean", "com.yiran.wakeupclean")
                      + "\nimport com.yiran.friendlyschool.core.HookContext;\n"
                        "import com.yiran.friendlyschool.core.SchoolTargetBase;\n")
    src = src.replace("public class WakeUpCleanHook implements IXposedHookLoadPackage {",
                      "public class WakeUpTarget extends SchoolTargetBase {")

    boiler = '''    // ------------------------------------------------------------ target 声明

    private static final String NAME = "WakeUp课程表";

    @Override
    public String shortName() {
        return NAME;
    }

    @Override
    public String packageName() {
        return TARGET_PKG;
    }

    /**
     * 原 WakeUpClean 的 handleLoadPackage 内容，只是去掉了包名门禁
     * （分发已经由 FriendlySchoolHook + Targets 做完）。
     */
    @Override
    public void install(final HookContext ctx) {
        // E6/E7/L1 的 Activity.onCreate 收口点必须在 App 起 Activity 之前挂好
        hookHybridBlocker();

        ctx.onAppReady(new HookContext.AppReady() {
            @Override
            public void run(ClassLoader appClassLoader) {
                // 与原实现一致：用 LoadPackageParam.classLoader
                installAppHooks(ctx.classLoader);
            }
        });
    }

'''
    anchor = "    // ------------------------------------------------------- 广告门禁（E1-E3）"
    assert src.count(anchor) == 1
    src = src.replace(anchor, boiler + anchor)

    src = tidy(src)
    open(OUT_DIR + "/WakeUpTarget.java", "w", encoding="utf-8", newline="\n").write(src)
    print("  -> WakeUpTarget.java (%d lines)" % src.count("\n"))


port_yi()
port_wake()
print("done")
