package com.yiran.friendlyschool.targets;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * adapter 构造器形状校验（纯 JVM，无 Android 依赖，可离线单测）。
 *
 * 判定：类的某个构造器参数里同时出现 fmClassName 指名的 FragmentManager
 * 与 java.util.List（含子类型）即视为形状相符。FragmentManager 按**类名字符串**
 * 比对而非 Class 身份——历史上 findAndHookMethod 的 #exact 签名匹配在该 adapter
 * 上报过 NoSuchMethodError，跨 ClassLoader 的 Class 身份不可依赖。
 *
 * 反射枚举整体 catch Throwable：被同名换人的类其构造器参数可能引用未装载类，
 * getDeclaredConstructors() 会抛 NoClassDefFoundError（MobGuard 教训），
 * 校验器绝不能把调用方的安装链炸穿，失败按不匹配返回、由调用点出声。
 */
public final class AdapterCtorShape {

    private AdapterCtorShape() {
    }

    /** c 的某构造器参数同时含名为 fmClassName 的类型与 List（含子类型）→ true。 */
    public static boolean hasFmListCtor(Class<?> c, String fmClassName) {
        if (c == null || fmClassName == null) {
            return false;
        }
        try {
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                boolean fm = false;
                boolean list = false;
                for (Class<?> p : ctor.getParameterTypes()) {
                    if (fmClassName.equals(p.getName())) {
                        fm = true;
                    } else if (List.class.isAssignableFrom(p)) {
                        list = true;
                    }
                }
                if (fm && list) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }
}
