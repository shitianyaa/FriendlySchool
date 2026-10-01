package com.yiran.friendlyschool.core;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Method;

/**
 * 一次进程注入的上下文。
 *
 * 合并前两个模块各自在 handleLoadPackage 里挂 Application.attach / Application.onCreate，
 * 现在这段逻辑只有一份，就是 {@link #onAppReady}。
 *
 * 与 legacy 版的差异只有一处：入参从 {@code XC_LoadPackage.LoadPackageParam} 换成了
 * 「包名 / 进程名 / ClassLoader」三元组 —— 现代 API 里没有 LoadPackageParam 这个等价物，
 * 这些东西都在 {@code PackageReadyParam} 上（见入口类 FriendlySchoolHook）。
 */
public final class HookContext {

    /** 目标 App 的包名。 */
    public final String packageName;

    /** 进程名（可能是 :remote 之类）。 */
    public final String processName;

    /** 这个进程的应用 ClassLoader（现代 API 里是 PackageReadyParam#getClassLoader）。 */
    public final ClassLoader classLoader;

    private volatile boolean appReadyHooked;

    /**
     * 换代语境标记：为 true 时进程里的 App **早就起来了**（热重载）——
     * 这时候 Application.attach / onCreate 永远不会再来一次，
     * 所以 {@link #onAppReady} 必须立刻回调一次，否则 App 侧那整批 hook 永远不会重装。
     */
    private final boolean appAlreadyReady;

    public HookContext(String packageName, String processName, ClassLoader classLoader) {
        this(packageName, processName, classLoader, false);
    }

    /**
     * @param appAlreadyReady 热重载换代时传 true（见字段说明）；普通注入传 false。
     */
    public HookContext(String packageName, String processName, ClassLoader classLoader,
                       boolean appAlreadyReady) {
        this.packageName = packageName;
        this.processName = processName;
        this.classLoader = classLoader;
        this.appAlreadyReady = appAlreadyReady;
    }

    /** 要等 App 自己装载完才能做的事。 */
    public interface AppReady {
        void run(ClassLoader appClassLoader);
    }

    /**
     * 在 Application 被 attach 之后、以及 onCreate 之后各回调一次。
     *
     * 两次是刻意的：被加固（易盾）的 App 在 attach 时业务类可能还没解密装载，
     * 所以目标自己的 install 方法必须自己保证幂等（例如用 hooksInstalled 标记）。
     *
     * 与 legacy 实现保持同样的取值：
     *   attach   -> Context.getClassLoader()（chain 的 0 号参数）
     *   onCreate -> Application.getClassLoader()（chain 的 this 指针）
     * 原方法先执行（proceed），回调在之后 —— 与 legacy 的 afterHookedMethod 同相位。
     */
    public void onAppReady(final AppReady action) {
        if (appReadyHooked) {
            return;
        }
        appReadyHooked = true;

        Method attach = Xp.findMethod(Application.class, "attach", Context.class);
        if (attach == null) {
            Xp.log("hook FAILED: Application#attach(Context) 没找到，onAppReady 只剩 onCreate 这条路");
        } else {
            Xp.hook(attach, chain -> {
                Object result = chain.proceed();
                try {
                    ClassLoader cl = appClassLoader(chain.getArg(0));
                    if (cl != null) {
                        action.run(cl);
                    }
                } catch (Throwable t) {
                    Xp.log("onAppReady(attach) 回调失败: " + t);
                }
                return result;
            });
        }

        Method onCreate = Xp.findMethod(Application.class, "onCreate");
        if (onCreate == null) {
            Xp.log("hook FAILED: Application#onCreate() 没找到，onAppReady 只剩 attach 这条路");
        } else {
            Xp.hook(onCreate, chain -> {
                Object result = chain.proceed();
                try {
                    ClassLoader cl = appClassLoader(chain.getThisObject());
                    if (cl != null) {
                        action.run(cl);
                    }
                } catch (Throwable t) {
                    Xp.log("onAppReady(onCreate) 回调失败: " + t);
                }
                return result;
            });
        }

        // 热重载换代：App 已经在跑，上面两个回调永远不会再触发 —— 立刻补一次，
        // 不然目标 App 会静默地退化成“只剩立即挂的那几个 hook”。
        if (appAlreadyReady) {
            try {
                Xp.log("onAppReady: 换代语境，立即补一次 App 就绪回调");
                action.run(classLoader);
            } catch (Throwable t) {
                Xp.log("onAppReady(立即回调) 失败: " + t);
            }
        }
    }

    /**
     * 从 attach/onCreate 的入参里取 ClassLoader。
     *
     * 只接受 Context（Application 也是 Context）；其它东西一律返回 null，由调用点跳过 ——
     * 这两个 hook 挂在系统方法上，入参要是哪天不对，绝不能因此把目标 App 拖死。
     */
    public static ClassLoader appClassLoader(Object contextOrApplication) {
        if (contextOrApplication instanceof Context) {
            return ((Context) contextOrApplication).getClassLoader();
        }
        return null;
    }
}
