/*
 * 桌面测试夹具：与目标 App 的 utils.ScheduleSpUtils 同名同签名（FQCN 必须一致）。
 *
 * 只保留 V2–V5 需要的那四个方法。夹具自己持有状态，用来断言“钩子退回原方法”时
 * 确实执行了原方法（而不是静默返回默认值）。
 *
 * 桌面没有 Application Context，所以 localPrefs() 必然拿不到 —— 这正好覆盖
 * “偏好在取失败 → 退回原方法并出声”这条失败路径。
 */
package com.suda.yzune.wakeupschedule.utils;

class ScheduleSpUtils {

    /** 夹具状态：原方法读写的目标。 */
    boolean dark;
    boolean simple;

    boolean OooO0Oo() {
        return dark;
    }

    void OooO0oo(int v) {
        dark = v == 1;
    }

    boolean OooO0o() {
        return simple;
    }

    void OooO(boolean v) {
        simple = v;
    }
}
