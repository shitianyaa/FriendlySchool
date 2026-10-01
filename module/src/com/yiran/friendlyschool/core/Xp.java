package com.yiran.friendlyschool.core;

import android.util.Log;

import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 唯一的新 API 工具层：libxposed 现代 API（API 102）没有任何 XposedHelpers 等价物，
 * 本模块需要的反射查找、hook 包装、句柄登记都收在这一处。
 *
 * 设计约束（对应仓库纪律）：
 *   1) 只在这一层碰 io.github.libxposed.api，target 只调用这里的静态方法；
 *   2) findClassIfExists / findMethod 找不到时**不打印**，由调用点按原来的文案出声
 *      （保证「静默失效是头号敌人」这条落在有业务语义的地方，也保证日志行可逐字比对）；
 *   3) 每个 hook 句柄都登记，热重载时能一次性卸干净（API 102 的 onHotReloaded 用）。
 */
public final class Xp {

    /** 全模块唯一的日志 tag。 */
    public static final String TAG = "FriendlySchool";

    private static volatile XposedModule MODULE;

    private static final List<HookHandle> HOOKS =
            Collections.synchronizedList(new ArrayList<HookHandle>());

    private Xp() {
    }

    /** 入口类在**每次回调开头**都要绑一次：热重载换代后不能依赖 onModuleLoaded 被重放。 */
    public static void bind(XposedModule module) {
        MODULE = module;
    }

    private static XposedModule module() {
        XposedModule m = MODULE;
        if (m == null) {
            throw new IllegalStateException("Xp 未绑定模块实例（入口回调开头应先调用 Xp.bind）");
        }
        return m;
    }

    // ------------------------------------------------------------------ 日志

    /**
     * 统一日志出口。绑不上模块实例时退到 stderr —— 不允许整条链路静默。
     */
    public static void log(String msg) {
        try {
            module().log(Log.INFO, TAG, msg);
        } catch (Throwable t) {
            System.err.println(TAG + " " + msg);
        }
    }

    // ------------------------------------------------------------ 类 / 方法查找

    /**
     * 加载类但**不初始化**（{@code initialize=false}）—— 与 legacy {@code XposedHelpers.findClass} 逐字一致。
     *
     * 真机取证：framework.dex 里 legacy `XposedHelpers.findClass` 委派的 `Ls;.b` 方法，
     * 其指令是 `const/4 v0, #int 0` + `Class.forName(String, boolean, ClassLoader)`，
     * 即第二个参数就是 false。
     *
     * 这里写成 true 会真出事（2026-10-01 真机事故）：强制初始化易盾加固包里的
     * `com.yunma.baseextend.repos.ADRepos` 会连带提前跑 `BaseSimpleRepos.<clinit>`，
     * 那个静态块在“App 环境未就绪”时抛错，错误被 ART 缓存，
     * 随后 App 自己在 `BaseExtendApplication.setEnvHost` 里拿到 `NoClassDefFoundError`，
     * 开屏前就崩了。改回 false 后行为与 legacy 一致（类何时初始化由 App 自己决定）。
     */
    public static Class<?> findClass(String name, ClassLoader cl) throws ClassNotFoundException {
        if (name == null) {
            throw new ClassNotFoundException("class name is null");
        }
        return Class.forName(name, false, cl);
    }

    /** 找不到返回 null（与 legacy XposedHelpers.findClassIfExists 一致），调用点负责出声。 */
    public static Class<?> findClassIfExists(String name, ClassLoader cl) {
        try {
            return findClass(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按名字 + 参数类型找方法：先本类声明的方法，再沿父类链。
     * 找不到返回 null（调用点出声）；参数类型不符也算找不到。
     */
    public static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        if (cls == null || name == null) {
            return null;
        }
        Class<?>[] wanted = params == null ? new Class<?>[0] : params;
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, wanted);
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ hook

    public static HookHandle hook(Method method, Hooker hooker) {
        return hook(method, ExceptionMode.DEFAULT, hooker);
    }

    /** 主动拒绝调用时用 PASSTHROUGH，让拒绝异常到达调用方而非回落原方法。 */
    public static HookHandle hook(Method method, ExceptionMode exceptionMode, Hooker hooker) {
        HookHandle handle = module().hook(method).setExceptionMode(exceptionMode).intercept(hooker);
        HOOKS.add(handle);
        return handle;
    }

    /** 带 id 的 hook：同 id 在同一个方法上会原子替换（API 102）。 */
    public static HookHandle hook(Method method, String id, Hooker hooker) {
        HookHandle handle = module().hook(method).setId(id).intercept(hooker);
        HOOKS.add(handle);
        return handle;
    }

    /**
     * 挂上这个类里**同名的每个已声明方法**（与 legacy XposedBridge.hookAllMethods 同语义，
     * 只在本类声明的方法里找，不往父类走）。返回的列表长度 = 实际挂载数，调用点用它做自检。
     */
    public static List<HookHandle> hookAllNamed(Class<?> cls, String methodName, Hooker hooker) {
        List<HookHandle> out = new ArrayList<HookHandle>();
        if (cls == null || methodName == null) {
            return out;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                out.add(hook(m, hooker));
            }
        }
        return out;
    }

    public static List<HookHandle> hookAllConstructors(Class<?> cls, Hooker hooker) {
        List<HookHandle> out = new ArrayList<HookHandle>();
        if (cls == null) {
            return out;
        }
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            HookHandle handle = module().hook(c).intercept(hooker);
            HOOKS.add(handle);
            out.add(handle);
        }
        return out;
    }

    // ------------------------------------------------------------ 调用 / 读字段

    /** 反射调用（含父类链，按参数类型匹配），异常按调用方可见的方式抛出。 */
    public static Object callMethod(Object thisObject, String name, Object... args) throws Throwable {
        if (thisObject == null) {
            throw new NullPointerException("thisObject == null");
        }
        Object[] actual = args == null ? new Object[0] : args;
        Method m = findMethodForArgs(thisObject.getClass(), name, actual);
        if (m == null) {
            throw new NoSuchMethodException(thisObject.getClass().getName()
                    + "#" + name + "(" + actual.length + " args) 没找到");
        }
        m.setAccessible(true);
        try {
            return m.invoke(thisObject, actual);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause == null ? e : cause;
        }
    }

    /** 读静态字段；字段不存在时出声并返回 null（调用点多半在拦截链路里，不能抛）。 */
    public static Object getStaticObjectField(Class<?> cls, String name) {
        if (cls == null || name == null) {
            return null;
        }
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(null);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (Throwable t) {
                log("static field FAILED: " + cls.getName() + "#" + name + " : " + t);
                return null;
            }
        }
        log("static field MISSING: " + cls.getName() + "#" + name);
        return null;
    }

    // ------------------------------------------------------------ 句柄登记（热重载用）

    public static List<HookHandle> handles() {
        synchronized (HOOKS) {
            return new ArrayList<HookHandle>(HOOKS);
        }
    }

    public static int hookCount() {
        return HOOKS.size();
    }

    /** 把本代挂上的 hook 全卸掉（热重载换代前调用），并清空登记。 */
    public static void unhookAll() {
        List<HookHandle> copy = handles();
        HOOKS.clear();
        for (HookHandle h : copy) {
            try {
                h.unhook();
            } catch (Throwable t) {
                log("unhook FAILED: " + t);
            }
        }
    }

    /**
     * 从运行期取“能看见目标 App 类”的 ClassLoader。
     *
     * 热重载换代时这是唯一的可靠来源：旧代里通常只挂着几条**系统类**的 hook
     * （Activity#onCreate、Application#attach/onCreate），它们的 ClassLoader 是 boot（null），
     * 拿它去 findClass 就全是 ClassNotFoundException。
     * （2026-10-01 真机实测：易校园换代后 210 个 hook 全部 ClassNotFoundException。）
     *
     * 实现：ActivityThread.currentApplication() → Application.getClassLoader()；
     * 拿不到（非 App 进程、隐藏 API 变动、桌面 stub 环境）返回 null，
     * 由调用点记录日志并退回旧句柄方案。
     */
    public static ClassLoader appClassLoaderFromRuntime() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread", false, null);
            Method current = at.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            if (app instanceof android.content.Context) {
                return ((android.content.Context) app).getClassLoader();
            }
        } catch (Throwable t) {
            // 正常降级路径：拿不到就返回 null
        }
        return null;
    }

    // ------------------------------------------------------------------ 内部

    private static Method findMethodForArgs(Class<?> cls, String name, Object[] args) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != args.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < pt.length; i++) {
                    if (args[i] == null) {
                        continue;
                    }
                    if (!box(pt[i]).isInstance(args[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return m;
                }
            }
        }
        return null;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }
}
