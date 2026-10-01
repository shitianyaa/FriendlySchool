package com.yiran.friendlyschool.core;

/**
 * 统一日志出口（历史遗留的薄壳，保留是为了不动所有调用点）。
 *
 * 真正的出口是 {@link Xp#log}：tag 只有一个 {@value Xp#TAG}，目标名由
 * SchoolTargetBase 加在消息前缀里，所以一条日志长这样：
 *   FriendlySchool 易校园 | hook installed: Splash jump (toNext)
 *
 * 设备上读日志（这台机器 logcat 是空的，唯一信源是 LSPosed 的模块日志）：
 *   adb shell su -c 'grep FriendlySchool /data/adb/lspd/log/modules_*.log'
 */
public final class FsLog {

    public static final String TAG = Xp.TAG;

    private FsLog() {
    }

    public static void log(String msg) {
        Xp.log(msg);
    }
}
