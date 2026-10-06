import android.app.Application;
import android.content.Context;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.yiran.friendlyschool.FriendlySchoolHook;
import com.yiran.friendlyschool.core.HookContext;
import com.yiran.friendlyschool.core.SchoolTargetBase;
import com.yiran.friendlyschool.core.Targets;
import com.yiran.friendlyschool.core.Xp;
import com.yiran.friendlyschool.targets.YiCampusTarget;
import com.yiran.friendlyschool.targets.WakeUpTarget;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 桌面 JVM 断言测试入口（不依赖 JUnit）。
 *
 * 用 {@link FakeFramework} 当假框架：它能真的组出 interceptor 链、真的调用原方法，
 * 所以「不 proceed = 原方法不执行」「proceed(newArgs) = 换参数调原方法」这类语义
 * 在桌面上就能验，而不必每次上真机试。
 */
public final class TestMain {

    private static int failed = 0;
    private static int passed = 0;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    public static void main(String[] args) throws Throwable {
        harnessTests();
        xpTests();
        hookContextTests();
        baseHelperTests();
        entryTests();
        assetDefenseTests();
        wakeUpVGroupTests();
        coolApkTests();
        System.out.println();
        System.out.println(failed == 0
                ? "ALL TESTS PASSED (" + passed + ")"
                : "TESTS FAILED: " + failed + " failed / " + (passed + failed) + " total");
        if (failed != 0) {
            System.exit(1);
        }
    }

    /** 脚手架自检：证明假框架能真的组链、真的调用原方法。 */
    private static void harnessTests() throws Throwable {
        System.out.println("[harness] FakeFramework 自身的语义");

        Method value = Sample.class.getDeclaredMethod("value");

        FakeFramework ff = new FakeFramework();
        ff.hook(value).intercept(chain -> 42);
        check("intercept 生效：返回值被 hooker 改掉",
                (Integer) ff.invoke(value, new Sample()) == 42);

        FakeFramework ff2 = new FakeFramework();
        ff2.hook(value).intercept(chain -> (Integer) chain.proceed() + 100);
        check("proceed() 调到原方法并把结果交回 hooker",
                (Integer) ff2.invoke(value, new Sample()) == 101);

        Method withArg = Sample.class.getDeclaredMethod("echo", String.class);
        FakeFramework ff3 = new FakeFramework();
        ff3.hook(withArg).intercept(chain -> {
            Object[] next = chain.getArgs().toArray();
            next[0] = "hooked";
            return chain.proceed(next);
        });
        check("proceed(newArgs) 用新参数调原方法",
                "hooked".equals(ff3.invoke(withArg, new Sample(), "original")));

        FakeFramework ff4 = new FakeFramework();
        ff4.hook(withArg).intercept(chain -> "swallowed");
        check("不 proceed 时原方法不执行",
                "swallowed".equals(ff4.invoke(withArg, new Sample(), "original")));

        FakeFramework ff5 = new FakeFramework();
        ff5.hook(withArg).intercept(chain -> { throw new SecurityException("denied"); });
        check("PROTECTIVE 吞掉 Hook 异常后继续原方法",
                "original".equals(ff5.invoke(withArg, new Sample(), "original")));

        FakeFramework ff6 = new FakeFramework();
        ff6.hook(withArg).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> { throw new SecurityException("denied"); });
        boolean denied = false;
        try {
            ff6.invoke(withArg, new Sample(), "original");
        } catch (SecurityException expected) {
            denied = true;
        }
        check("PASSTHROUGH 将拒绝异常交给调用方，不执行原方法", denied);

        Method counted = Sample.class.getDeclaredMethod("countedValue");
        Sample sample = new Sample();
        FakeFramework ff7 = new FakeFramework();
        ff7.hook(counted).intercept(chain -> {
            chain.proceed();
            throw new SecurityException("after proceed");
        });
        check("PROTECTIVE 在 proceed 后出错时保留原结果，原方法只执行一次",
                (Integer) ff7.invoke(counted, sample) == 1 && sample.calls == 1);

        Method originalFailure = Sample.class.getDeclaredMethod("originalFailure");
        FakeFramework ff8 = new FakeFramework();
        ff8.hook(originalFailure).intercept(chain -> chain.proceed());
        Sample failingSample = new Sample();
        boolean originalError = false;
        try {
            ff8.invoke(originalFailure, failingSample);
        } catch (IllegalStateException expected) {
            originalError = true;
        }
        check("PROTECTIVE 不吞原方法异常、不重复执行原方法",
                originalError && failingSample.calls == 1);
    }

    private static void assetDefenseTests() throws Throwable {
        System.out.println();
        System.out.println("[YiCampus] AssetShield 拒绝异常不得被保护模式吞掉");
        FakeFramework ff = new FakeFramework();
        TestModule tm = new TestModule();
        tm.attachFramework(ff, () -> {});
        Xp.bind(tm);
        Xp.unhookAll();
        Method install = YiCampusTarget.class.getDeclaredMethod("installAssetDefense");
        install.setAccessible(true);
        install.invoke(new YiCampusTarget());
        Method open = android.content.res.AssetManager.class.getDeclaredMethod("open", String.class);
        boolean rejected = false;
        try {
            ff.invoke(open, null, "curl/curl");
        } catch (java.io.FileNotFoundException expected) {
            rejected = true;
        }
        check("curl/curl 必须抛 FileNotFoundException，不能回落原方法", rejected);
        check("普通资源读取仍委派原方法", ff.invoke(open, null, "normal.txt") == null);
        Xp.unhookAll();
    }

    /**
     * HookContext 的桌面验证范围，以及为什么只有这么点：
     *   - 可验：装了几个 hook、装在哪个方法上、重复调用幂等、参数不是 Context 时不抛不误触发；
     *   - 不可验：真的用 Application 实例跑 attach/onCreate（android.jar 的构造器与方法体都是
     *     "Stub!"，new Application() 直接抛）—— 这条由 T10 的真机冷启动日志核实。
     */
    private static void hookContextTests() throws Throwable {
        System.out.println();
        System.out.println("[HookContext] App 就绪回调（结构 + 幂等 + 入参防御）");

        FakeFramework ff = new FakeFramework();
        TestModule tm = new TestModule();
        tm.attachFramework(ff, new Runnable() {
            @Override
            public void run() {
            }
        });
        Xp.bind(tm);
        Xp.unhookAll();

        final int[] calls = {0};
        HookContext.AppReady action = new HookContext.AppReady() {
            @Override
            public void run(ClassLoader cl) {
                calls[0]++;
            }
        };

        HookContext ctx = new HookContext("cn.com.yunma.school.app", "cn.com.yunma.school.app",
                TestMain.class.getClassLoader());
        check("构造后字段可见（包名/进程名/ClassLoader）",
                "cn.com.yunma.school.app".equals(ctx.packageName)
                        && "cn.com.yunma.school.app".equals(ctx.processName)
                        && ctx.classLoader == TestMain.class.getClassLoader());

        ff.clearLogs();
        ctx.onAppReady(action);

        // android-34 的公有 stub jar 里没有 Application#attach(Context)（它是 hidden API），
        // 真机 framework 里有（已用 lspctl hook-debug dump 取到签名核实）。所以桌面这里走的是
        // 「方法找不到 → 必须出声，且不整体失败」的分支。
        check("attach 在桌面 stub 里不存在 → 必须出声（不能静默）",
                ff.loggedContains("Application#attach"));
        check("attach 缺失时仍挂上 onCreate（不整体失败）", Xp.hookCount() == 1);

        StringBuilder names = new StringBuilder();
        for (io.github.libxposed.api.XposedInterface.HookHandle h : Xp.handles()) {
            names.append(h.getExecutable().getDeclaringClass().getName()).append('#')
                    .append(h.getExecutable().getName()).append(';');
        }
        check("挂在 android.app.Application#onCreate 上",
                names.toString().contains("android.app.Application#onCreate;"));

        ctx.onAppReady(action);
        check("重复 onAppReady 幂等（不重复挂）", Xp.hookCount() == 1);

        // 热重载语境：换代时进程里的 App 早就起来了，不会再触发 attach/onCreate，
        // 所以 onAppReady 必须立刻回调一次 —— 否则 App 侧那整批 hook 永远不会重装。
        // （2026-10-01 真机发现：易校园换代后只装了 16 个 hook，应到 ~210。）
        newFramework();
        final int[] hotCalls = {0};
        HookContext hot = new HookContext("cn.com.yunma.school.app", "proc",
                TestMain.class.getClassLoader(), true);
        hot.onAppReady(new HookContext.AppReady() {
            @Override
            public void run(ClassLoader cl) {
                hotCalls[0]++;
            }
        });
        check("热重载语境：onAppReady 立即回调一次", hotCalls[0] == 1);

        newFramework();
        final int[] coldCalls = {0};
        HookContext cold = new HookContext("cn.com.yunma.school.app", "proc",
                TestMain.class.getClassLoader());
        cold.onAppReady(new HookContext.AppReady() {
            @Override
            public void run(ClassLoader cl) {
                coldCalls[0]++;
            }
        });
        check("普通语境：onAppReady 不立即回调（等 Application.attach）", coldCalls[0] == 0);

        // 换代时拿“能看见 App 类”的 ClassLoader：优先运行期 Application（真机有，桌面 stub 没有）
        check("appClassLoaderFromRuntime 在桌面环境不抛且返回 null",
                Xp.appClassLoaderFromRuntime() == null);

        check("非 Context 入参取不到 ClassLoader（不抛）",
                HookContext.appClassLoader(new Object()) == null);
        check("null 入参取不到 ClassLoader（不抛）", HookContext.appClassLoader(null) == null);

        Method onCreate = Application.class.getDeclaredMethod("onCreate");
        boolean threw = false;
        try {
            ff.invoke(onCreate, null);
        } catch (Throwable t) {
            threw = true;
            System.out.println("        (抛出: " + t + ")");
        }
        check("onCreate 的 hook 遇到 null this 指针不抛异常", !threw);
        check("null this 指针不会误触发 App 就绪回调", calls[0] == 0);

        Xp.unhookAll();
    }

    /**
     * SchoolTargetBase 的共享 hook 工具族：三个 target 全靠这几个方法，所以先在这里验语义。
     */
    private static void baseHelperTests() throws Throwable {
        System.out.println();
        System.out.println("[SchoolTargetBase] 共享 hook 工具族");

        ClassLoader cl = TestMain.class.getClassLoader();
        String gates = Gates.class.getName();
        Method value = Sample.class.getDeclaredMethod("value");

        // hookFalse：门禁方法直接返回 false，原方法不执行
        FakeFramework ff = newFramework();
        ProbeTarget t = new ProbeTarget();
        Gates g1 = new Gates();
        t.callHookFalse(cl, gates, "gate", "测试门禁");
        check("hookFalse 门禁返回 false 且原方法不执行",
                Boolean.FALSE.equals(ff.invoke(Gates.class.getDeclaredMethod("gate"), g1))
                        && !g1.gateCalled);
        check("hookFalse 装了必须出声", ff.loggedContains("hook installed: 测试门禁"));

        // hookFalse：方法不存在必须出声（不能静默）
        ff.clearLogs();
        t.callHookFalse(cl, gates, "noSuchGate", "测试缺方法");
        check("hookFalse 方法不存在时必须出声", ff.loggedContains("hook FAILED: 测试缺方法"));

        // hookReplaceAllNamed：按返回类型自适应（boolean->true / int->0 / 其它->null）
        ff = newFramework();
        t = new ProbeTarget();
        t.callReplaceAllNamed(cl, gates, "gate", "测试门禁");
        t.callReplaceAllNamed(cl, gates, "num", "测试整数");
        t.callReplaceAllNamed(cl, gates, "text", "测试文本");
        check("hookReplaceAllNamed boolean -> true",
                Boolean.TRUE.equals(ff.invoke(Gates.class.getDeclaredMethod("gate"), new Gates())));
        check("hookReplaceAllNamed int -> 0",
                Integer.valueOf(0).equals(ff.invoke(Gates.class.getDeclaredMethod("num"), new Gates())));
        check("hookReplaceAllNamed 其它 -> null",
                ff.invoke(Gates.class.getDeclaredMethod("text"), new Gates()) == null);
        check("hookReplaceAllNamed 打印重载数", ff.loggedContains("hook installed: 测试门禁 " + gates + "#gate (1 overloads)"));

        // hookReplaceAllNamed：类找不到时静默 return（与 legacy 一致）
        ff.clearLogs();
        int before = Xp.hookCount();
        t.callReplaceAllNamed(cl, "no.such.Class", "whatever", "测试缺类");
        check("hookReplaceAllNamed 类找不到时不动声色也不报错",
                Xp.hookCount() == before && !ff.loggedContains("测试缺类"));

        // hookNoop：指定签名变空实现，原方法不执行
        ff = newFramework();
        t = new ProbeTarget();
        Gates g2 = new Gates();
        t.callHookNoop(cl, gates, "withArg", new Object[]{String.class}, "测试空实现");
        check("hookNoop 返回 null 且原方法不执行",
                ff.invoke(Gates.class.getDeclaredMethod("withArg", String.class), g2, "x") == null
                        && !g2.withArgCalled);

        // 回归：legacy 的 findAndHookMethod 允许把参数类型写成**类名字符串**（它会自己 findClass）。
        // WakeUp 的“首页弹窗”就是这么传的（"androidx.activity.ComponentActivity", "kotlin.jvm.functions.Function0"），
        // 而迁移时 hookNoop 里直接 (Class<?>) 强转 → ClassCastException → 那条 hook 静默地没装上。
        ff = newFramework();
        t = new ProbeTarget();
        Gates g2b = new Gates();
        t.callHookNoop(cl, gates, "withArg", new Object[]{"java.lang.String"}, "测试字符串签名");
        check("hookNoop 支持用类名字符串传参类型（legacy 语义）",
                ff.invoke(Gates.class.getDeclaredMethod("withArg", String.class), g2b, "x") == null
                        && !g2b.withArgCalled);

        // hookReplaceVoid：void 方法变空实现
        ff = newFramework();
        t = new ProbeTarget();
        Gates g3 = new Gates();
        t.callHookReplaceVoid(cl, gates, "doVoid", "测试 void");
        ff.invoke(Gates.class.getDeclaredMethod("doVoid"), g3);
        check("hookReplaceVoid 后 void 方法不执行", !g3.voidCalled);

        // hookAllMethodsNoop：同名重载全部替掉
        ff = newFramework();
        t = new ProbeTarget();
        Gates g4 = new Gates();
        t.callAllMethodsNoop(Gates.class, "twice", "测试重载");
        ff.invoke(Gates.class.getDeclaredMethod("twice"), g4);
        ff.invoke(Gates.class.getDeclaredMethod("twice", int.class), g4, 1);
        check("hookAllMethodsNoop 把同名重载全替掉", g4.twiceCalls == 0);
        check("hookAllMethodsNoop 装了必须出声", ff.loggedContains("hook installed: 测试重载"));

        // hookAllMethodsReturnEmptyMap：返回空 Map（不是 null，也不是失败响应）
        ff = newFramework();
        t = new ProbeTarget();
        t.callAllMethodsReturnEmptyMap(Gates.class, "empty", "测试空 Map");
        Object r = ff.invoke(Gates.class.getDeclaredMethod("empty"), new Gates());
        check("hookAllMethodsReturnEmptyMap 返回空 Map",
                r instanceof Map && ((Map<?, ?>) r).isEmpty());

        Xp.unhookAll();
    }

    private static FakeFramework newFramework() {
        FakeFramework ff = new FakeFramework();
        TestModule tm = new TestModule();
        tm.attachFramework(ff, new Runnable() {
            @Override
            public void run() {
            }
        });
        Xp.bind(tm);
        Xp.unhookAll();
        return ff;
    }

    /** 被 hook 的样本类：只记标志位，便于断言“原方法到底执行了没”。 */
    public static class Gates {
        public boolean gateCalled;
        public boolean withArgCalled;
        public boolean voidCalled;
        public int twiceCalls;

        public boolean gate() {
            gateCalled = true;
            return false;
        }

        public int num() {
            return 7;
        }

        public String text() {
            return "text";
        }

        public String withArg(String s) {
            withArgCalled = true;
            return s;
        }

        public void doVoid() {
            voidCalled = true;
        }

        public void twice() {
            twiceCalls++;
        }

        public void twice(int n) {
            twiceCalls++;
        }

        public Map<String, String> empty() {
            Map<String, String> m = new HashMap<String, String>();
            m.put("a", "b");
            return m;
        }
    }

    /** 把 protected 工具族暴露出来给测试用（生产代码里只有 target 子类会用）。 */
    public static class ProbeTarget extends SchoolTargetBase {
        @Override
        public String shortName() {
            return "探针";
        }

        @Override
        public String packageName() {
            return "test.probe";
        }

        @Override
        public void install(HookContext ctx) {
        }

        public void callHookFalse(ClassLoader cl, String cls, String m, String label) {
            hookFalse(cl, cls, m, label);
        }

        public void callHookNoop(ClassLoader cl, String cls, String m, Object[] params, String label) {
            hookNoop(cl, cls, m, params, label);
        }

        public void callHookReplaceVoid(ClassLoader cl, String cls, String m, String label) {
            hookReplaceVoid(cl, cls, m, label);
        }

        public void callReplaceAllNamed(ClassLoader cl, String cls, String m, String label) {
            hookReplaceAllNamed(cl, cls, m, label);
        }

        public void callAllMethodsNoop(Class<?> cls, String m, String label) {
            hookAllMethodsNoop(cls, m, label);
        }

        public void callAllMethodsReturnEmptyMap(Class<?> cls, String m, String label) {
            hookAllMethodsReturnEmptyMap(cls, m, label);
        }
    }

    /**
     * 入口类：路由 + 非目标包必须完全静默 + 热重载回调的基本形状。
     * 真正注入到目标 App 里的行为由真机验证（T10）。
     */
    private static void entryTests() throws Throwable {
        System.out.println();
        System.out.println("[入口] 路由与静默");

        check("路由表：四个目标都认",
                Targets.forPackage("cn.com.yunma.school.app") != null
                        && Targets.forPackage("com.suda.yzune.wakeupschedule") != null
                        && "易校园".equals(Targets.forPackage("cn.com.yunma.school.app").shortName())
                        && "WakeUp课程表".equals(Targets.forPackage("com.suda.yzune.wakeupschedule").shortName())
                        && "JMComic3".equals(Targets.forPackage("com.a7m3p9xv.t6qk2z8.app").shortName())
                        && "光影边框".equals(Targets.forPackage("com.dengziwl.bk").shortName()));
        check("路由表：非目标包返回 null",
                Targets.forPackage("com.example.other") == null
                        && Targets.forPackage(null) == null);

        FakeFramework ff = new FakeFramework();
        FriendlySchoolHook hook = new FriendlySchoolHook();
        hook.attachFramework(ff, new Runnable() {
            @Override
            public void run() {
            }
        });

        hook.onModuleLoaded(new FakeModuleLoadedParam("com.example.other"));
        check("onModuleLoaded 会发声（modul loaded 行）", ff.loggedContains("module loaded"));

        ff.clearLogs();
        Xp.unhookAll();
        hook.onPackageReady(new FakePackageReadyParam("com.example.other"));
        check("非目标包：不装任何 hook", Xp.hookCount() == 0);
        check("非目标包：不打任何日志（完全静默）", ff.logs().isEmpty());

        FakeHotReloadingParam hrParam = new FakeHotReloadingParam();
        check("onHotReloading 返回 true（允许换代）", hook.onHotReloading(hrParam));
        check("onHotReloading 把包名/进程名交给新代",
                "|com.example.other".equals(hrParam.saved));
    }

    public static class FakeModuleLoadedParam implements ModuleLoadedParam {
        private final String proc;

        FakeModuleLoadedParam(String proc) {
            this.proc = proc;
        }

        @Override
        public boolean isSystemServer() {
            return false;
        }

        @Override
        public String getProcessName() {
            return proc;
        }
    }

    public static class FakePackageReadyParam implements PackageReadyParam {
        private final String pkg;

        FakePackageReadyParam(String pkg) {
            this.pkg = pkg;
        }

        @Override
        public String getPackageName() {
            return pkg;
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            return null;
        }

        @Override
        public boolean isFirstPackage() {
            return true;
        }

        @Override
        public ClassLoader getDefaultClassLoader() {
            return TestMain.class.getClassLoader();
        }

        @Override
        public ClassLoader getClassLoader() {
            return TestMain.class.getClassLoader();
        }

        @Override
        public android.app.AppComponentFactory getAppComponentFactory() {
            return null;
        }
    }

    public static class FakeHotReloadingParam implements HotReloadingParam {
        public String saved;

        @Override
        public android.os.Bundle getExtras() {
            return null;
        }

        @Override
        public void setSavedInstanceState(Object outState) {
            saved = String.valueOf(outState);
        }
    }

    /** 静态初始化计数器容器（单独一个类，避免读计数时反过来初始化被测类）。 */
    public static class InitCounter {
        public static int count = 0;
    }

    /** 被测类：只要它被初始化，count 就会 +1。 */
    public static class ClinitProbe {
        static {
            InitCounter.count++;
        }

        public static void touch() {
        }
    }

    public static class Sample {
        public int calls;

        public int countedValue() {
            calls++;
            return 1;
        }

        public int originalFailure() {
            calls++;
            throw new IllegalStateException("original failure");
        }
        public static final String STATIC = "S";

        /** 供 Xp.getObjectField 测试的实例字段（父类侧）。 */
        public String parentField = "P";

        public int value() {
            return 1;
        }

        public String echo(String s) {
            return s;
        }

        public String parentMethod() {
            return "parent";
        }

        public String over(String s) {
            return s;
        }

        public String over(int i) {
            return String.valueOf(i);
        }
    }

    public static class Child extends Sample {
    }

    /** 真实的 XposedModule 子类：用 attachFramework 把假框架接上去，用来验证 Xp 工具层。 */
    /**
     * WakeUp V组（本地解锁外观功能）的桌面验证范围：
     *   - 可验：五条 hook 的成组安装与安装顺序（存储在先、V1 在后）、安装中途失败后的整体回滚
     *     （不残留 hook、V1 不生效）、偏好在取失败时退回原方法并出声、迁移判定，
     *     以及内存偏好下实际夜间读取 hook 的迁移与本地值优先；
     *   - 不可验：真实 SharedPreferences 的读写与跟进程持久化（桌面没有 Application Context），
     *     以及真机上 isVip / UserInfo 的实际取值 —— 这两条由真机日志核实。
     */
    private static void wakeUpVGroupTests() throws Throwable {
        System.out.println();
        System.out.println("[WakeUp] V组：成组安装、回滚与迁移判定");
        ClassLoader cl = TestMain.class.getClassLoader();
        Method install = WakeUpTarget.class.getDeclaredMethod("installAppearanceUnlock", ClassLoader.class);
        install.setAccessible(true);

        Class<?> vipCls = Class.forName("com.suda.yzune.wakeupschedule.aaa.utils.o00O0000", false, cl);
        Method isVip = vipCls.getDeclaredMethod("OooOOO0");
        Class<?> spuCls = Class.forName("com.suda.yzune.wakeupschedule.utils.ScheduleSpUtils", false, cl);
        Constructor<?> spuCtor = spuCls.getDeclaredConstructor();
        spuCtor.setAccessible(true);
        Method readDark = spuCls.getDeclaredMethod("OooO0Oo");
        Method writeSimple = spuCls.getDeclaredMethod("OooO", boolean.class);

        // 1) 正常路径：五条全装，且 V1 在存储读写之后
        FakeFramework ff = new FakeFramework();
        TestModule tm = new TestModule();
        tm.attachFramework(ff, new Runnable() {
            @Override
            public void run() {
            }
        });
        Xp.bind(tm);
        Xp.unhookAll();
        WakeUpTarget target = new WakeUpTarget();
        install.invoke(target, cl);
        check("V组：正常路径装五条 hook", ff.hookCount() == 5);
        String text = String.join("\n", ff.logs());
        int storageAt = text.indexOf("hook installed: V2-V5");
        int vipAt = text.indexOf("hook installed: V1");
        check("V组：安装顺序为存储读写在前、V1 在后", storageAt >= 0 && vipAt > storageAt);
        check("V组：V1 生效后 isVip -> true", Boolean.TRUE.equals(ff.invoke(isVip, null)));

        // 2) 桌面必然拿不到 Application Context：读写必须退回原方法并出声
        Object spu = spuCtor.newInstance();
        check("V组：偏好在取失败时读退回原方法", Boolean.FALSE.equals(ff.invoke(readDark, spu)));
        Object afterWrite = ff.invoke(writeSimple, spu, true);
        check("V组：偏好在取失败时写退回原方法", afterWrite == null
                && Boolean.TRUE.equals(ff.invoke(spuCls.getDeclaredMethod("OooO0o"), spu)));
        check("V组：退回原逻辑必须出声（可诊断）",
                ff.loggedContains("偏好读写退回原方法"));

        // 注入可用偏好，执行实际读取 hook，覆盖 boolean 原结果到 int 存储值的迁移。
        Map<String, Integer> stored = new HashMap<String, Integer>();
        android.content.SharedPreferences.Editor editor =
                (android.content.SharedPreferences.Editor) java.lang.reflect.Proxy.newProxyInstance(
                        cl, new Class<?>[] { android.content.SharedPreferences.Editor.class }, (proxy, method, args) -> {
                            if (method.getName().equals("putInt")) {
                                stored.put((String) args[0], (Integer) args[1]);
                                return proxy;
                            }
                            if (method.getName().equals("apply")) {
                                return null;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
        android.content.SharedPreferences prefs =
                (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                        cl, new Class<?>[] { android.content.SharedPreferences.class }, (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "contains": return stored.containsKey(args[0]);
                                case "getInt": return stored.getOrDefault(args[0], (Integer) args[1]);
                                case "edit": return editor;
                                default: throw new UnsupportedOperationException(method.getName());
                            }
                        });
        java.lang.reflect.Field prefsField = WakeUpTarget.class.getDeclaredField("localPrefs");
        prefsField.setAccessible(true);
        prefsField.set(target, prefs);
        Method writeDark = spuCls.getDeclaredMethod("OooO0oo", int.class);
        writeDark.setAccessible(true);
        for (boolean accountDark : new boolean[] { false, true }) {
            stored.clear();
            writeDark.invoke(spu, accountDark ? 1 : 0);
            Object migrated = ff.invoke(readDark, spu);
            check("V2实际迁移：账号 " + accountDark + " 转为本地 0/1 并保存",
                    Boolean.valueOf(accountDark).equals(migrated)
                            && Integer.valueOf(accountDark ? 1 : 0).equals(stored.get("dark_model_fs_local")));
            writeDark.invoke(spu, accountDark ? 0 : 1);
            check("V2实际读取：本地 " + accountDark + " 不被账号相反值覆盖",
                    Boolean.valueOf(accountDark).equals(ff.invoke(readDark, spu)));
        }
        Xp.unhookAll();

        // 3) 安装中途失败：整体回滚，不残留 hook，V1 不生效
        FakeFramework ff2 = new FakeFramework();
        TestModule tm2 = new TestModule();
        tm2.attachFramework(ff2, new Runnable() {
            @Override
            public void run() {
            }
        });
        Xp.bind(tm2);
        Xp.unhookAll();
        ff2.failHookAfter(2);
        install.invoke(new WakeUpTarget(), cl);
        check("V组：安装中途失败后无残留 hook", ff2.hookCount() == 0);
        check("V组：失败与撤销都记录在日志里",
                ff2.loggedContains("V组安装失败") && ff2.loggedContains("V组已撤销"));
        check("V组：安装失败不得留下“已放行会员判断”", Boolean.FALSE.equals(ff2.invoke(isVip, null)));
        Xp.unhookAll();

        // 4) 迁移判定（纯函数）
        Method rInt = WakeUpTarget.class.getDeclaredMethod(
                "resolveLocalInt", boolean.class, int.class, int.class);
        rInt.setAccessible(true);
        Method rBool = WakeUpTarget.class.getDeclaredMethod(
                "resolveLocalBool", boolean.class, boolean.class, boolean.class);
        rBool.setAccessible(true);
        check("V组迁移：本地键缺失时取账号原值",
                ((Integer) rInt.invoke(null, false, 0, 1)) == 1);
        check("V组迁移：本地键已存在时以本地值为准（显式 false 不被账号值覆盖）",
                ((Integer) rInt.invoke(null, true, 0, 1)) == 0);
        check("V组迁移：简洁模式同判定（缺失取账号 true / 已存在保本地 false）",
                ((Boolean) rBool.invoke(null, false, false, true))
                        && !((Boolean) rBool.invoke(null, true, false, true)));
    }

    private static void coolApkTests() throws Throwable {
        System.out.println("[CoolApk] 列表消费前过滤 sponsor");
        Xp.unhookAll();
        FakeFramework ff = new FakeFramework();
        TestModule tm = new TestModule();
        tm.attachFramework(ff, () -> {});
        Xp.bind(tm);
        com.yiran.friendlyschool.targets.CoolApkTarget target =
                new com.yiran.friendlyschool.targets.CoolApkTarget();
        Method install = target.getClass().getDeclaredMethod("filterSponsors",
                ClassLoader.class, String.class, String.class, String.class);
        install.setAccessible(true);
        install.invoke(target, TestMain.class.getClassLoader(), "CoolApkFixture", "process", "fixture");
        install.invoke(target, TestMain.class.getClassLoader(), "CoolApkFixture", "process", "fixture");
        check("CoolApk：重试不重复安装且只匹配列表签名", Xp.hookCount() == 1);
        Method process = CoolApkFixture.class.getMethod("process", java.util.List.class, boolean.class);
        CoolApkFixture fixture = new CoolApkFixture();
        Object normal = new CoolApkFixture.Entity("feed");
        Object sponsor = new CoolApkFixture.Entity("sponsorCard");
        java.util.List<Object> input = java.util.Arrays.asList(normal, sponsor, null);
        java.util.List<?> result = (java.util.List<?>) ff.invoke(process, fixture, input, false);
        check("CoolApk：原方法不能登记输入中的 sponsor", fixture.sponsorRegistrations == 0);
        check("CoolApk：普通实体、null 和顺序保留", result.size() == 2
                && result.get(0) == normal && result.get(1) == null);
        check("CoolApk：不修改调用方的原始列表", input.size() == 3 && input.get(1) == sponsor);
        check("CoolApk：原方法只执行一次", fixture.calls == 1);
        java.util.List<Object> clean = java.util.Collections.singletonList(normal);
        check("CoolApk：无广告时保留列表对象身份", ff.invoke(process, fixture, clean, true) == clean);
        java.util.List<?> empty = (java.util.List<?>) ff.invoke(process, fixture,
                java.util.Collections.singletonList(sponsor), false);
        check("CoolApk：全广告列表变为空列表，原方法不登记广告", empty.isEmpty()
                && fixture.sponsorRegistrations == 0);
        java.util.List<Object> uppercase = java.util.Collections.singletonList(
                new CoolApkFixture.Entity("FEED_DETAIL_REPLY_SPONSOR_CARD"));
        check("CoolApk：大写 sponsor 判据也被过滤",
                ((java.util.List<?>) ff.invoke(process, fixture, uppercase, false)).isEmpty());
        fixture.addSponsor = true;
        java.util.List<?> output = (java.util.List<?>) ff.invoke(process, fixture, clean, false);
        check("CoolApk：原方法新生成的返回广告也被过滤", output.size() == 1 && output.get(0) == normal);
        fixture.addSponsor = false;
        check("CoolApk：不影响无关重载", "plain".equals(ff.invoke(
                CoolApkFixture.class.getMethod("process", String.class), fixture, "plain")));
        fixture.fail = true;
        boolean threw = false;
        int before = fixture.calls;
        try {
            ff.invoke(process, fixture, clean, false);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        check("CoolApk：原方法异常可见且不重复执行", threw && fixture.calls == before + 1);
        Xp.unhookAll();

        Method observe = target.getClass().getDeclaredMethod("obs",
                ClassLoader.class, String.class, String.class, String.class);
        observe.setAccessible(true);
        observe.invoke(target, TestMain.class.getClassLoader(), "CoolApkFixture", "observe", "fixture-observe");
        Method observed = CoolApkFixture.class.getMethod("observe", java.util.List.class);
        CoolApkFixture.CountingList counting = new CoolApkFixture.CountingList();
        fixture.fail = false;
        before = fixture.calls;
        ff.clearLogs();
        for (int i = 0; i < 12; i++) {
            check("CoolApk：观测保留返回列表身份 " + i, ff.invoke(observed, fixture, counting) == counting);
        }
        check("CoolApk：额度内摘要读取输入与输出", counting.reads == 24000 && ff.logs().size() == 12);
        counting.reads = 0;
        ff.invoke(observed, fixture, counting);
        check("CoolApk：第13次不生成摘要，仅提示一次上限", counting.reads == 0
                && ff.logs().size() == 13 && ff.loggedContains("已达 12 条上限"));
        ff.invoke(observed, fixture, counting);
        check("CoolApk：额度耗尽后无摘要无新日志，原方法仍执行", counting.reads == 0
                && ff.logs().size() == 13 && fixture.calls == before + 14);
        fixture.fail = true;
        before = fixture.calls;
        threw = false;
        try {
            ff.invoke(observed, fixture, counting);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        check("CoolApk：额度耗尽后原异常可见且仅执行一次", threw && fixture.calls == before + 1
                && counting.reads == 0);
        Xp.unhookAll();

        // 同一份字节码由两个加载器独立定义，模拟加固 App 的同名实体类。
        String entityName = "CoolApkFixture$Entity";
        byte[] entityBytes;
        try (java.io.InputStream stream = TestMain.class.getResourceAsStream("/CoolApkFixture$Entity.class")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            entityBytes = bytes.toByteArray();
        }
        final byte[] classBytes = entityBytes;
        Class<?>[] entities = new Class<?>[2];
        for (int i = 0; i < entities.length; i++) {
            entities[i] = new ClassLoader(TestMain.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!name.equals(entityName)) {
                        return super.loadClass(name, resolve);
                    }
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        type = defineClass(name, classBytes, 0, classBytes.length);
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
            }.loadClass(entityName);
        }
        check("CoolApk：测试实体同名但属于不同 Class", entities[0] != entities[1]
                && entities[0].getName().equals(entities[1].getName()));
        Method withoutSponsors = target.getClass().getDeclaredMethod("withoutSponsors", java.util.List.class, String.class);
        withoutSponsors.setAccessible(true);
        for (Class<?> entity : entities) {
            Object ad = entity.getConstructor(String.class).newInstance("sponsorCard");
            Object feed = entity.getConstructor(String.class).newInstance("feed");
            java.util.List<?> filtered = (java.util.List<?>) withoutSponsors.invoke(target,
                    java.util.Arrays.asList(ad, feed), "multiloader");
            check("CoolApk：各加载器 sponsor 被过滤、普通实体保留", filtered.size() == 1 && filtered.get(0) == feed);
        }
    }

    public static class TestModule extends io.github.libxposed.api.XposedModule {
    }

    private static void xpTests() throws Throwable {
        System.out.println();
        System.out.println("[Xp] 工具层");

        ClassLoader cl = TestMain.class.getClassLoader();
        FakeFramework ff = new FakeFramework();
        TestModule tm = new TestModule();
        tm.attachFramework(ff, new Runnable() {
            @Override
            public void run() {
            }
        });
        Xp.bind(tm);

        // 连续断言里日志会累积，所以每条需要看日志的断言前先清空。
        ff.clearLogs();
        check("findClassIfExists 未命中返回 null 且不打印",
                Xp.findClassIfExists("no.such.Class", cl) == null && ff.logs().isEmpty());

        // 回归：legacy XposedHelpers.findClass 的实现是 Class.forName(name, **false**, cl)
        // （真机 framework.dex 里 Ls;.b 的 const/4 v0,#0 那条可查）。
        // 曾经写成 true，结果强制触发了易盾的 BaseSimpleRepos.<clinit> → 易校园在 onCreate 里
        // NoClassDefFoundError 崩溃。这条断言就是那次真机事故的摩子。
        check("findClassIfExists 不触发目标类静态初始化（legacy 语义）",
                Xp.findClassIfExists("TestMain$ClinitProbe", cl) != null
                        && InitCounter.count == 0);
        check("findClassIfExists 命中返回该 Class",
                Xp.findClassIfExists("com.yiran.friendlyschool.core.Xp", cl) == Xp.class);

        boolean threw = false;
        try {
            Xp.findClass("no.such.Class", cl);
        } catch (ClassNotFoundException e) {
            threw = true;
        }
        check("findClass 未命中抛 ClassNotFoundException", threw);

        check("findMethod 沿父类链找", Xp.findMethod(Child.class, "parentMethod") != null);

        // getObjectField：读实例字段（含父类链）。
        // 回归背景：有些类把成员暴露为 public 字段而非 getter（如 Flutter 的 MethodCall.method），
        // 用 getMethod() 读它会抛异常并被吞掉，表现为“拦截装了但从不命中”。
        ff.clearLogs();
        Sample s = new Child();
        check("getObjectField 沿父类链读实例字段", "P".equals(Xp.getObjectField(s, "parentField")));
        check("getObjectField 读取成功时不出声", !ff.loggedContains("field MISSING"));
        ff.clearLogs();
        check("getObjectField 未命中返回 null", Xp.getObjectField(s, "noSuchField") == null);
        check("getObjectField 未命中必须出声", ff.loggedContains("field MISSING"));
        check("getObjectField null 入参不抛", Xp.getObjectField(null, "any") == null);
        check("findMethod 未命中返回 null", Xp.findMethod(Child.class, "noSuchMethod") == null);
        check("findMethod 用签名区分重载",
                Xp.findMethod(Child.class, "over", String.class) != null
                        && Xp.findMethod(Child.class, "over", int.class) != null);

        ff.clearLogs();
        Xp.log("hello");
        check("log 走框架出口并带 TAG",
                ff.logs().size() == 1 && ff.logs().get(0).equals("FriendlySchool hello"));

        Method value = Sample.class.getDeclaredMethod("value");
        Xp.hook(value, chain -> 7);
        check("hook 登记句柄", Xp.hookCount() == 1);
        check("hook 真的挂上（假框架真的组链执行）",
                ((Integer) ff.invoke(value, new Sample())).intValue() == 7);

        check("hookAllNamed 返回实际挂载数",
                Xp.hookAllNamed(Sample.class, "echo", chain -> "replaced").size() == 1);
        check("hookAllNamed 只挂同名的每个重载",
                Xp.hookAllNamed(Sample.class, "over", chain -> null).size() == 2);
        check("hookAllNamed 未命中返回空集合（不是 null）",
                Xp.hookAllNamed(Sample.class, "noSuchMethod", chain -> null).isEmpty());
        check("hookAllConstructors 返回实际挂载数",
                Xp.hookAllConstructors(Sample.class, chain -> null).size() == 1);

        check("callMethod 按名字+参数类型反射调用",
                "abc".equals(Xp.callMethod(new Sample(), "echo", "abc")));
        check("callMethod 能调父类方法",
                "parent".equals(Xp.callMethod(new Child(), "parentMethod")));
        check("getStaticObjectField 读静态字段",
                "S".equals(Xp.getStaticObjectField(Sample.class, "STATIC")));
        check("findStaticObjectField 未命中返回 null",
                Xp.getStaticObjectField(Sample.class, "NO_SUCH") == null);

        int before = Xp.hookCount();
        Xp.unhookAll();
        check("unhookAll 清空登记并真的卸掉（" + before + " 个）",
                Xp.hookCount() == 0 && ff.hookCount() == 0);
    }
}
