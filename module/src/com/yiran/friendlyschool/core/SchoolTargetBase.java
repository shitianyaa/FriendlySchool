package com.yiran.friendlyschool.core;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 目标实现的公共部分：日志 + 一套共用的 hook 工具。
 *
 * 这些工具原来在两个模块里各写了一份（易校园的 hookAllMethodsNoop /
 * hookAllMethodsReturnEmptyMap，WakeUp 的 hookFalse / hookNoop / hookReplaceVoid /
 * hookReplaceAllNamed）。合并后只留这一份，方法名和日志文案都保持原样，
 * 这样各个目标的调用点和排查习惯不用改 —— 底层从 legacy 的 XC_MethodHook
 * 换成了 libxposed 现代 API 的 interceptor chain（见 {@link Xp}）。
 *
 * 全都是实例方法：日志前缀由 {@link #shortName()} 决定，不会再出现两个目标抢一个 tag 的情况。
 */
public abstract class SchoolTargetBase implements SchoolTarget {

    /** 同一目标的同一条消息只打一次，否则高频 hook（首页 banner 之类）会把日志刷满。 */
    private static final Set<String> LOGGED_ONCE =
            Collections.synchronizedSet(new HashSet<String>());

    @Override
    public abstract String shortName();

    @Override
    public abstract String packageName();

    // ------------------------------------------------------------------ 日志

    protected final void log(String msg) {
        // Xp.log 会自己加 "FriendlySchool " 前缀（框架侧再带上 tag），所以这里只拼目标名，
        // 最终设备日志行 = "FriendlySchool 易校园 | …"（与 legacy 逐字一致）。
        Xp.log(shortName() + " | " + msg);
    }

    protected final void logOnce(String msg) {
        if (LOGGED_ONCE.add(shortName() + "|" + msg)) {
            Xp.log(shortName() + " | " + msg);
        }
    }

    // ------------------------------------------------------- 共享 hook 工具

    /** 把类里所有同名方法替换成 DO_NOTHING（等价 legacy 的 XC_MethodReplacement.DO_NOTHING）。 */
    protected final void hookAllMethodsNoop(Class<?> clazz, String methodName, String label) {
        try {
            int count = 0;
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    Xp.hook(m, chain -> null);
                    count++;
                }
            }
            if (count > 0) {
                logOnce("hook installed: " + label);
            }
        } catch (Throwable t) {
            log("hook " + label + " FAILED: " + t);
        }
    }

    /** 把类里所有同名方法替换成返回空 Map。 */
    protected final void hookAllMethodsReturnEmptyMap(Class<?> clazz, String methodName, final String label) {
        try {
            int count = 0;
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    Xp.hook(m, chain -> {
                        logOnce("hit: " + label + " -> emptyMap");
                        return Collections.emptyMap();
                    });
                    count++;
                }
            }
            if (count > 0) {
                logOnce("hook installed: " + label);
            }
        } catch (Throwable t) {
            log("hook " + label + " FAILED: " + t);
        }
    }

    /** 让门禁方法直接返回 false（广告开关这类）。 */
    protected final void hookFalse(ClassLoader cl, String className, final String method, final String label) {
        try {
            Class<?> cls = Xp.findClass(className, cl);
            Method m = Xp.findMethod(cls, method);
            if (m == null) {
                throw new NoSuchMethodException(className + "#" + method);
            }
            Xp.hook(m, chain -> {
                logOnce("gate " + label + " -> false (" + method + ")");
                return Boolean.FALSE;
            });
            log("hook installed: " + label + " " + className + "#" + method);
        } catch (Throwable t) {
            log("hook FAILED: " + label + " " + className + "#" + method + " : " + t);
        }
    }

    /** 让指定签名的方法变成空实现（返回 null）。 */
    protected final void hookNoop(ClassLoader cl, String className, final String method,
                                  Object[] params, String label) {
        try {
            Class<?> cls = Xp.findClass(className, cl);
            Method m = Xp.findMethod(cls, method, params == null ? new Class<?>[0] : toClassArray(params, cl));
            if (m == null) {
                throw new NoSuchMethodException(className + "#" + method);
            }
            Xp.hook(m, chain -> {
                logOnce("noop " + label + " (" + method + ")");
                return null;
            });
            log("hook installed: " + label + " " + className + "#" + method);
        } catch (Throwable t) {
            log("hook FAILED: " + label + " " + className + "#" + method + " : " + t);
        }
    }

    /** void 方法替换成 DO_NOTHING。 */
    protected final void hookReplaceVoid(ClassLoader cl, String className, final String method, final String label) {
        try {
            Class<?> cls = Xp.findClass(className, cl);
            Method m = Xp.findMethod(cls, method);
            if (m == null) {
                throw new NoSuchMethodException(className + "#" + method);
            }
            Xp.hook(m, chain -> {
                logOnce(label + " -> DO_NOTHING (" + method + ")");
                return null;
            });
            log("hook installed: " + label + " " + className + "#" + method);
        } catch (Throwable t) {
            log("hook FAILED: " + label + " " + className + "#" + method + " : " + t);
        }
    }

    /** 按方法名替换所有重载，返回类型自适应。 */
    protected final void hookReplaceAllNamed(ClassLoader cl, String className, final String methodName, final String label) {
        try {
            Class<?> c = Xp.findClassIfExists(className, cl);
            if (c == null) {
                return;
            }
            int count = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    replaceByReturnType(m);
                    count++;
                }
            }
            if (count > 0) {
                log("hook installed: " + label + " " + className + "#" + methodName + " (" + count + " overloads)");
            }
        } catch (Throwable t) {
            log("hook FAILED: " + label + " " + className + "#" + methodName + " : " + t);
        }
    }

    /**
     * 按返回类型给一个"不要调原方法"的常量：boolean -> true、int -> 0、其它 -> null。
     * （等价 legacy 的 XC_MethodReplacement.returnConstant(true/0) / DO_NOTHING —— 只处理这三种，
     * 不要自作主张给 String/集合返回空值：那是行为变更，属于静默改语义。）
     */
    protected final void replaceByReturnType(Method m) {
        Class<?> ret = m.getReturnType();
        if (ret == boolean.class || ret == Boolean.class) {
            Xp.hook(m, chain -> Boolean.TRUE);
        } else if (ret == int.class || ret == Integer.class) {
            Xp.hook(m, chain -> 0);
        } else {
            Xp.hook(m, chain -> null);
        }
    }

    // ------------------------------------------------------------ 视图工具

    /** 按资源名找视图，置 GONE 并把高度压成 0（避免留下空槽位）。 */
    protected final void hideViewById(View root, String resName) {
        if (root == null || resName == null) {
            return;
        }
        try {
            int id = root.getResources().getIdentifier(resName, "id", root.getContext().getPackageName());
            if (id != 0) {
                View v = root.findViewById(id);
                if (v != null) {
                    v.setVisibility(View.GONE);
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    if (lp != null) {
                        lp.height = 0;
                        v.setLayoutParams(lp);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 参数类型数组：既接受 {@code Class}，也接受**类名字符串** —— legacy 的
     * {@code findAndHookMethod} 两种都吃（字符串由它自己 findClass 解出来），
     * 本模块 WakeUp 的"首页弹窗"就是写成字符串传的。
     *
     * 迁移时这里曾经只做 {@code (Class<?>) params[i]} 强转 —— 结果字符串形态直接抛
     * ClassCastException，那条 hook 静默地没装上（2026-10-01 真机发现）。
     * 解不出来的类名当错误上抛，由调用点的 try/catch 落成 hook FAILED 日志。
     */
    private static Class<?>[] toClassArray(Object[] params, ClassLoader cl) throws ClassNotFoundException {
        Class<?>[] out = new Class<?>[params.length];
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof Class) {
                out[i] = (Class<?>) p;
            } else if (p instanceof String) {
                out[i] = Xp.findClass((String) p, cl);
            } else {
                throw new IllegalArgumentException("参数类型既不是 Class 也不是 String: " + p);
            }
        }
        return out;
    }
}
