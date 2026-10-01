import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import io.github.libxposed.api.XposedInterface;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 假框架实现 —— 让 core 层的语义能在桌面 JVM 上验证。
 *
 * 与真框架的一致性（刻意对齐的几处）：
 *   - Chain.getArgs() 返回**不可变** List（真 API 也是不可变的，改参数只能走 proceed(newArgs)）；
 *   - 不调用 proceed() 就不会执行原方法；
 *   - proceed() 才会落到原方法，返回原方法的返回值。
 *
 * 与真框架的差异（测试不要依赖这些）：
 *   - 不做优先级排序，后注册的先执行；
 *   - 不实现 remote prefs / remote files / invoker / class initializer hook；
 *   - DEFAULT 按本模块的 module.prop 解析为 PROTECTIVE。
 */
public class FakeFramework implements XposedInterface {

    private final Map<Executable, List<Hooker>> chains = new LinkedHashMap<Executable, List<Hooker>>();
    private final List<String> logs = new ArrayList<String>();

    // ------------------------------------------------------------ 日志（供断言）

    public List<String> logs() {
        return logs;
    }

    public void clearLogs() {
        logs.clear();
    }

    public String lastLog() {
        return logs.isEmpty() ? null : logs.get(logs.size() - 1);
    }

    public boolean loggedContains(String needle) {
        for (String s : logs) {
            if (s != null && s.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public int hookCount() {
        int n = 0;
        for (List<Hooker> l : chains.values()) {
            n += l.size();
        }
        return n;
    }

    // ------------------------------------------------------------ 模拟调用

    /** 模拟一次方法调用：走完整条链，最后一个 proceed() 落到原方法。 */
    public Object invoke(Method method, Object thisObject, Object... args) throws Throwable {
        return new Frame(method, thisObject, args == null ? new Object[0] : args, 0).proceed();
    }

    public Object invoke(Method method, Object thisObject) throws Throwable {
        return invoke(method, thisObject, new Object[0]);
    }

    // ------------------------------------------------------------ XposedInterface

    @Override
    public String getFrameworkName() {
        return "FakeFramework";
    }

    @Override
    public String getFrameworkVersion() {
        return "test";
    }

    @Override
    public long getFrameworkVersionCode() {
        return 1L;
    }

    @Override
    public long getFrameworkProperties() {
        return 0L;
    }

    @Override
    public HookBuilder hook(Executable origin) {
        return new Builder(origin);
    }

    @Override
    public HookBuilder hookClassInitializer(Class<?> origin) {
        throw new UnsupportedOperationException("hookClassInitializer 未在假框架里实现");
    }

    @Override
    public boolean deoptimize(Executable executable) {
        return false;
    }

    @Override
    public Invoker<?, Method> getInvoker(Method method) {
        throw new UnsupportedOperationException("getInvoker 未在假框架里实现");
    }

    @Override
    public <T> CtorInvoker<T> getInvoker(Constructor<T> constructor) {
        throw new UnsupportedOperationException("getInvoker 未在假框架里实现");
    }

    @Override
    public void log(int priority, String tag, String msg) {
        logs.add((tag == null ? "" : tag + " ") + msg);
    }

    @Override
    public void log(int priority, String tag, String msg, Throwable tr) {
        logs.add((tag == null ? "" : tag + " ") + msg + " | " + tr);
    }

    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        throw new UnsupportedOperationException("getModuleApplicationInfo 未在假框架里实现");
    }

    @Override
    public SharedPreferences getRemotePreferences(String group) {
        throw new UnsupportedOperationException("getRemotePreferences 未在假框架里实现");
    }

    @Override
    public String[] listRemoteFiles() {
        return new String[0];
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException {
        throw new FileNotFoundException(name);
    }

    // ------------------------------------------------------------ 内部实现

    private class Builder implements HookBuilder {
        private final Executable origin;
        private ExceptionMode exceptionMode = ExceptionMode.PROTECTIVE;

        Builder(Executable origin) {
            this.origin = origin;
        }

        @Override
        public HookBuilder setPriority(int priority) {
            return this;
        }

        @Override
        public HookBuilder setExceptionMode(ExceptionMode mode) {
            exceptionMode = mode == ExceptionMode.DEFAULT ? ExceptionMode.PROTECTIVE : mode;
            return this;
        }

        @Override
        public HookBuilder setId(String id) {
            return this;
        }

        @Override
        public HookHandle intercept(Hooker hooker) {
            List<Hooker> list = chains.get(origin);
            if (list == null) {
                list = new ArrayList<Hooker>();
                chains.put(origin, list);
            }
            RegisteredHook registered = new RegisteredHook(hooker, exceptionMode);
            list.add(0, registered);
            return new Handle(origin, registered);
        }
    }

    private static class RegisteredHook implements Hooker {
        private final Hooker delegate;
        private final ExceptionMode mode;

        RegisteredHook(Hooker delegate, ExceptionMode mode) {
            this.delegate = delegate;
            this.mode = mode;
        }

        @Override
        public Object intercept(Chain chain) throws Throwable {
            return delegate.intercept(chain);
        }
    }

    private class Handle implements HookHandle {
        private final Executable origin;
        private Hooker hooker;

        Handle(Executable origin, Hooker hooker) {
            this.origin = origin;
            this.hooker = hooker;
        }

        @Override
        public Executable getExecutable() {
            return origin;
        }

        @Override
        public void unhook() {
            List<Hooker> list = chains.get(origin);
            if (list != null) {
                list.remove(hooker);
                if (list.isEmpty()) {
                    chains.remove(origin);
                }
            }
        }

        @Override
        public String getId() {
            return "fake";
        }

        @Override
        public HookHandle replaceHook(Hooker newHooker) {
            newHooker = new RegisteredHook(newHooker, ((RegisteredHook) hooker).mode);
            List<Hooker> list = chains.get(origin);
            if (list != null) {
                int i = list.indexOf(hooker);
                if (i >= 0) {
                    list.set(i, newHooker);
                }
            }
            hooker = newHooker;
            return this;
        }
    }

    private class Frame implements Chain {
        private final Method method;
        private final Object thisObject;
        private final Object[] args;
        private final int index;
        private boolean proceeded;
        private Object proceededResult;
        private Throwable proceededThrowable;

        Frame(Method method, Object thisObject, Object[] args, int index) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args;
            this.index = index;
        }

        @Override
        public Executable getExecutable() {
            return method;
        }

        @Override
        public Object getThisObject() {
            return thisObject;
        }

        @Override
        public List<Object> getArgs() {
            return Collections.unmodifiableList(Arrays.asList(args));
        }

        @Override
        public Object getArg(int i) {
            return args[i];
        }

        @Override
        public Object proceed() throws Throwable {
            return recordProceed(thisObject, args);
        }

        @Override
        public Object proceed(Object[] newArgs) throws Throwable {
            return recordProceed(thisObject, newArgs);
        }

        @Override
        public Object proceedWith(Object newThis) throws Throwable {
            return recordProceed(newThis, args);
        }

        @Override
        public Object proceedWith(Object newThis, Object[] newArgs) throws Throwable {
            return recordProceed(newThis, newArgs);
        }

        private Object recordProceed(Object newThis, Object[] a) throws Throwable {
            proceeded = true;
            try {
                proceededResult = new Frame(method, newThis, a, index).step(a);
                return proceededResult;
            } catch (Throwable t) {
                proceededThrowable = t;
                throw t;
            }
        }

        private Object step(Object[] a) throws Throwable {
            List<Hooker> list = chains.get(method);
            if (list == null || index >= list.size()) {
                // android.jar 里的方法体都是 "Stub!"（一调就抛），而 core 层恰恰要挂
                // Application.attach / onCreate 这类系统方法。所以终端遇到 android.* 就不真调，
                // 直接返回 null —— 这样「不 proceed 就不执行原方法」「after-hook 读到 null 也不炸」
                // 这些语义仍可在桌面上验证。真机上这些方法当然是真的执行（由 T10 冷启动日志核实）。
                if (isAndroidStub(method.getDeclaringClass())) {
                    return null;
                }
                method.setAccessible(true);
                try {
                    return method.invoke(thisObject, a);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            RegisteredHook registered = (RegisteredHook) list.get(index);
            Frame next = new Frame(method, thisObject, a, index + 1);
            try {
                return registered.intercept(next);
            } catch (Throwable t) {
                if (registered.mode == ExceptionMode.PASSTHROUGH) {
                    throw t;
                }
                if (!next.proceeded) {
                    return next.proceed();
                }
                if (next.proceededThrowable != null) {
                    throw next.proceededThrowable;
                }
                return next.proceededResult;
            }
        }

        private boolean isAndroidStub(Class<?> c) {
            return c.getName().startsWith("android.");
        }
    }
}
