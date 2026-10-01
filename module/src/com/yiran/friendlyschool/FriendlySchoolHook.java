package com.yiran.friendlyschool;

import com.yiran.friendlyschool.core.FsLog;
import com.yiran.friendlyschool.core.HookContext;
import com.yiran.friendlyschool.core.SchoolTarget;
import com.yiran.friendlyschool.core.Targets;
import com.yiran.friendlyschool.core.Xp;

import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

import java.util.List;

/**
 * FriendlySchool 入口（{@code META-INF/xposed/java_init.list} 里登记的就是这个类）。
 *
 * 从 legacy 的 {@code IXposedHookLoadPackage} 迁到 libxposed 现代 API：
 *   legacy handleLoadPackage  -> 现代 onPackageReady
 *     （实测相位相同：AppComponentFactory 建好 classloader、准备创建 Application 之前）
 *   legacy 没有的东西        -> onHotReloading / onHotReloaded（API 102 的热重载）
 *
 * 职责还是两件：报一条"注入成功"的证据日志，按包名把进程交给对应的目标。
 * 除了目标包的进程，其它一律什么都不做。
 *
 * 不做 detach()：现代 API 里一个进程内每个被加载的包都会回调，而本模块的目标是
 * 三个互不相干的 App —— 一旦在"当前包不是目标"时 detach，就等于把这个进程后面
 * 的回调全掐了。所以只做 return。
 *
 * 每次回调开头都 {@link Xp#bind}：热重载换代后框架**不会**重放 onModuleLoaded，
 * 新代的实例必须自己把自己绑上，否则 Xp.log 会因为没有模块实例而退到 stderr。
 */
public class FriendlySchoolHook extends XposedModule {

    /** 当前进程名（onModuleLoaded 里拿到；用于日志与热重载重装）。 */
    private volatile String processName = "?";

    /** 最近一次接管的包名（热重载换代时用）。 */
    private volatile String lastPackage = "";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Xp.bind(this);
        processName = param.getProcessName();
        Xp.log("module loaded: " + processName + " | " + getFrameworkName()
                + " (" + getFrameworkVersionCode() + ") API " + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        Xp.bind(this);
        String pkg = param.getPackageName();
        SchoolTarget target = Targets.forPackage(pkg);
        if (target == null) {
            return;
        }
        lastPackage = pkg;

        // 这一行是注入证据：它在目标 App 任何代码之前打印。
        // 缺了它说明是 scope / 启用状态的问题，或者目标进程在改 scope 之后没重启 —— 不是 hook 写错。
        FsLog.log("injected: " + pkg + "/" + processName
                + " -> " + target.shortName()
                + " cl=" + shortCl(param.getClassLoader())
                + " [targets: " + Targets.summary() + "]");

        try {
            target.install(new HookContext(pkg, processName, param.getClassLoader()));
        } catch (Throwable t) {
            FsLog.log(target.shortName() + " | install FAILED: " + t);
        }
    }

    /**
     * 热重载第一步（在**旧**代码里跑）：报一条日志，把换代后要用的信息（包名/进程名，只有字符串）
     * 交给新代码，然后允许换代。
     */
    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        Xp.bind(this);
        Xp.log("hot reload: 准备换代（旧代码）pkg=" + lastPackage
                + " proc=" + processName + " oldHooks=" + Xp.hookCount());
        // 只放 classloader 无关的值：带旧 classloader 的对象会让换代失败（框架会拒收）
        param.setSavedInstanceState(lastPackage + "|" + processName);
        return true;
    }

    /**
     * 热重载第二步（在**新**代码里跑）：框架不会重放 onPackageReady，所以要自己把净化接回来。
     *
     * 拿 App ClassLoader 的唯一途径是旧句柄上的 {@code getExecutable().getDeclaringClass()}：
     * 那些方法属于目标 App，其 ClassLoader 在新代里依然可用（旧句柄本身还活着）。
     * 拿到之后：卸掉旧 hook → 用新代码重新 install。
     *
     * 拿不到就明确放弃并日志说明"需重启目标 App"，不静默。
     */
    @Override
    public void onHotReloaded(HotReloadedParam param) {
        Xp.bind(this);

        List<HookHandle> old = param.getOldHookHandles();
        // 优先运行期 Application（App 已经在跑，它手里就是 App 的 ClassLoader）；
        // 退而求其次才从旧句柄的 declaring class 摸，那经常只能摸到 boot loader（null）。
        ClassLoader appClassLoader = Xp.appClassLoaderFromRuntime();
        String clSource = "运行期 Application";
        if (appClassLoader != null) {
            Xp.log("hot reload: 从" + clSource + "取到 App ClassLoader " + clName(appClassLoader));
        } else {
            for (HookHandle h : old) {
                try {
                    ClassLoader cl = h.getExecutable().getDeclaringClass().getClassLoader();
                    if (cl != null) {
                        appClassLoader = cl;
                        clSource = "旧句柄";
                        break;
                    }
                } catch (Throwable t) {
                    Xp.log("hot reload: 读旧句柄失败: " + t);
                }
            }
            if (appClassLoader != null) {
                Xp.log("hot reload: 退而用旧句柄取到 App ClassLoader " + clName(appClassLoader));
            }
        }

        String saved = "";
        Object state = param.getSavedInstanceState();
        if (state instanceof String) {
            saved = (String) state;
        }
        String pkg = lastPackage;
        String proc = processName;
        int sep = saved.indexOf('|');
        if (sep > 0) {
            pkg = saved.substring(0, sep);
            proc = saved.substring(sep + 1);
        }
        processName = proc;
        if (pkg.length() == 0) {
            pkg = proc.indexOf(':') > 0 ? proc.substring(0, proc.indexOf(':')) : proc;
        }

        Xp.log("hot reload: 换代完成，旧 hooks=" + old.size() + " pkg=" + pkg
                + " proc=" + proc + " appClassLoader="
                + (appClassLoader != null ? "拿到(" + clSource + ":" + clName(appClassLoader) + ")" : "没拿到"));

        // 新代码自己这一代的登记表通常是空的，先清干净，再把旧代的句柄逐个卸掉
        Xp.unhookAll();
        for (HookHandle h : old) {
            try {
                h.unhook();
            } catch (Throwable t) {
                Xp.log("hot reload: 卸载旧 hook 失败: " + t);
            }
        }

        if (appClassLoader == null) {
            Xp.log("hot reload 放弃：拿不到 App ClassLoader，需重启目标 App 才能恢复净化");
            return;
        }
        SchoolTarget target = Targets.forPackage(pkg);
        if (target == null) {
            Xp.log("hot reload: " + pkg + " 不是本模块的目标，不重装");
            return;
        }
        lastPackage = pkg;
        try {
            // appAlreadyReady=true：换代时 App 已经在跑，onAppReady 要立刻回调一次
            // （否则 App 侧那批 hook 永远不会重装 —— 2026-10-01 真机实测到的静默降级）。
            target.install(new HookContext(pkg, proc, appClassLoader, true));
            Xp.log("hot reload: 已按新代码重新装好 " + target.shortName() + " 的 hook（新 hooks="
                    + Xp.hookCount() + "）");
        } catch (Throwable t) {
            Xp.log("hot reload: 重装 " + target.shortName() + " 失败: " + t);
        }
    }

    private static String clName(ClassLoader cl) {
        try {
            return cl == null ? "null" : cl.getClass().getSimpleName()
                    + "@" + Integer.toHexString(System.identityHashCode(cl));
        } catch (Throwable t) {
            return "<cl-threw>";
        }
    }

    private static String shortCl(ClassLoader cl) {
        try {
            return cl == null ? "null"
                    : cl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(cl));
        } catch (Throwable t) {
            return "<cl-threw>";
        }
    }
}
