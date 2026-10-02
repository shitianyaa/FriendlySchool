/*
 * 桌面测试夹具：与目标 App 的 aaa.utils.o00O0000 同名同签名（FQCN 必须一致）。
 *
 * V 组是按“包名 + 类名”反射找类的（Xp.findClass），桌面 JVM 上不存在真实目标类，
 * 所以用这个夹具顶替：夹具方法恒返回 false，钩子装上后应返回 true ——
 * “没装上”与“装上后又回滚了”都能由此区分。
 *
 * 类必须是包内可见且文件名无关（run.sh 用显式文件列表调 javac，不按目录推导包名）。
 */
package com.suda.yzune.wakeupschedule.aaa.utils;

class o00O0000 {

    /** 真机上是 isVip：未登录时恒 false。 */
    static boolean OooOOO0() {
        return false;
    }
}
