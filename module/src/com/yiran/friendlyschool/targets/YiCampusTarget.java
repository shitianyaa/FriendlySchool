package com.yiran.friendlyschool.targets;

/*
 * 易校园（cn.com.yunma.school.app）target。
 *
 * 代码来自独立的 FuckYiCampus 模块（com.yiran.fuckyicampus，已随本次合并下线），
 * 逐行移植、hook 调用点未改；日志与 hook 工具的公共部分在 core.SchoolTargetBase，
 * Application.attach/onCreate 的双挂钩在 core.HookContext，分发在 core.Targets。
 * 移植来源与逐点 diff 由开发记录保存（未随本仓库发布）。
 */

import com.yiran.friendlyschool.core.HookContext;
import com.yiran.friendlyschool.core.SchoolTargetBase;
import com.yiran.friendlyschool.core.Xp;

import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedInterface.ExceptionMode;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.view.View;
import android.view.ViewGroup;

import java.io.FileNotFoundException;
import java.util.Arrays;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class YiCampusTarget extends SchoolTargetBase {

    private static final String TARGET_PKG = "cn.com.yunma.school.app";
    private static volatile boolean hooksInstalled = false;
    private static volatile boolean systemHooksInstalled = false;

    private static final String[] TRACKING_CALLERS = new String[] {
            "hzjizhun", "baihemob", "kuaishou", "weapon", "anythink", "bytedance",
            "byazt", "beizi", "sigmob", "octopus", "ubix", "mob", "sharesdk",
            "yfanads", "gameley", "domob", "yunma.third.advert", "qumeng", "adscope"
    };

    private static final String KW_COURSE = "\u8bfe\u7a0b\u8868";
    private static final String KW_STUDENT = "\u5b66\u751f\u4e13\u4eab";
    private static final String KW_ACTIVITY = "\u6d3b\u52a8\u901a\u77e5";

    // ------------------------------------------------------------ target 声明

    private static final String NAME = "易校园";

    @Override
    public String shortName() {
        return NAME;
    }

    @Override
    public String packageName() {
        return TARGET_PKG;
    }

    /**
     * 原 FuckYiCampus 的 handleLoadPackage 内容，只是去掉了包名门禁
     * （分发已经由 FriendlySchoolHook + Targets 做完）。
     */
    @Override
    public void install(final HookContext ctx) {
        installSystemHooks(ctx.classLoader);

        ctx.onAppReady(new HookContext.AppReady() {
            @Override
            public void run(ClassLoader appClassLoader) {
                installAllHooks(appClassLoader);
            }
        });
    }

    private synchronized void installSystemHooks(ClassLoader cl) {
        if (systemHooksInstalled) return;
        systemHooksInstalled = true;
        log("Installing core system defense hooks...");

        installShellDefense();
        installAssetDefense();
        installPrivacyDefense(cl);
        installClipboardDefense();
    }

    private void installShellDefense() {
        try {
            Hooker runtimeExecHook = chain -> {
                String cmdStr = "";
                if (!chain.getArgs().isEmpty()) {
                    Object first = chain.getArg(0);
                    if (first instanceof String) {
                        cmdStr = (String) first;
                    } else if (first instanceof String[]) {
                        cmdStr = Arrays.toString((String[]) first);
                    }
                }
                if (isDangerousCommand(cmdStr) || isCallerTracked()) {
                    log("BLOCKED malicious shell exec: " + cmdStr);
                    throw new SecurityException("FuckYiCampus: Blocked malicious shell execution");
                }
                return chain.proceed();
            };

            for (Method m : Runtime.class.getDeclaredMethods()) {
                if ("exec".equals(m.getName())) {
                    Xp.hook(m, ExceptionMode.PASSTHROUGH, runtimeExecHook);
                }
            }

            for (Method m : ProcessBuilder.class.getDeclaredMethods()) {
                if ("start".equals(m.getName())) {
                    Xp.hook(m, ExceptionMode.PASSTHROUGH, chain -> {
                        Object self = chain.getThisObject();
                        ProcessBuilder pb = (self instanceof ProcessBuilder) ? (ProcessBuilder) self : null;
                        List<String> command = pb != null ? pb.command() : null;
                        String cmdStr = command != null ? command.toString() : "";
                        if (isDangerousCommand(cmdStr) || isCallerTracked()) {
                            log("BLOCKED malicious ProcessBuilder.start: " + cmdStr);
                            throw new SecurityException("FuckYiCampus: Blocked malicious ProcessBuilder.start");
                        }
                        return chain.proceed();
                    });
                }
            }
            logOnce("hook installed: Shell & Exec defense");
        } catch (Throwable t) {
            log("installShellDefense FAILED: " + t);
        }
    }
    private void installAssetDefense() {
        try {
            Hooker assetHook = chain -> {
                if (!chain.getArgs().isEmpty() && chain.getArg(0) instanceof String) {
                    String name = (String) chain.getArg(0);
                    if (name.contains("curl/curl") || name.contains("packages/packages") || name.contains("qumeng")) {
                        log("BLOCKED malicious asset read: " + name);
                        throw new FileNotFoundException("Blocked by FuckYiCampus AssetShield: " + name);
                    }
                }
                return chain.proceed();
            };

            for (Method m : AssetManager.class.getDeclaredMethods()) {
                if ("open".equals(m.getName())) {
                    Xp.hook(m, ExceptionMode.PASSTHROUGH, assetHook);
                }
            }
            logOnce("hook installed: AssetShield (blocked curl/curl, packages/packages)");
        } catch (Throwable t) {
            log("installAssetDefense FAILED: " + t);
        }
    }
    private void installPrivacyDefense(ClassLoader cl) {
        try {
            Class<?> appPmCls = Xp.findClassIfExists("android.app.ApplicationPackageManager", cl);
            if (appPmCls != null) {
                Hooker getPkgsHook = chain -> {
                    Object result = chain.proceed();
                    if (isCallerTracked()) {
                        logOnce("PrivacyShield: BLOCKED getInstalledPackages for tracking SDK");
                        return new ArrayList<PackageInfo>();
                    }
                    return result;
                };

                for (Method m : appPmCls.getDeclaredMethods()) {
                    if ("getInstalledPackages".equals(m.getName())) {
                        Xp.hook(m, getPkgsHook);
                    } else if ("getInstalledApplications".equals(m.getName())) {
                        Xp.hook(m, chain -> {
                            Object result = chain.proceed();
                            if (isCallerTracked()) {
                                logOnce("PrivacyShield: BLOCKED getInstalledApplications for tracking SDK");
                                return new ArrayList<ApplicationInfo>();
                            }
                            return result;
                        });
                    }
                }
                logOnce("hook installed: PrivacyShield (App List Armor)");
            }
        } catch (Throwable t) {
            log("installPrivacyDefense FAILED: " + t);
        }
    }
    private void installClipboardDefense() {
        try {
            Hooker clipHook = chain -> {
                if (isCallerTracked()) {
                    logOnce("ClipboardGuard: BLOCKED addPrimaryClipChangedListener from tracking SDK");
                    return null;
                }
                return chain.proceed();
            };

            for (Method m : ClipboardManager.class.getDeclaredMethods()) {
                if ("addPrimaryClipChangedListener".equals(m.getName())) {
                    Xp.hook(m, clipHook);
                }
            }
            logOnce("hook installed: ClipboardGuard");
        } catch (Throwable t) {
            log("installClipboardDefense FAILED: " + t);
        }
    }
    private synchronized void installAllHooks(ClassLoader cl) {
        if (hooksInstalled || cl == null) {
            return;
        }

        Class<?> adReposCls = Xp.findClassIfExists("com.yunma.baseextend.repos.ADRepos", cl);
        if (adReposCls == null) {
            logOnce("classes not yet unpacked by YiDun, waiting for onCreate...");
            return;
        }

        hooksInstalled = true;
        log("YiDun payload detected! Installing clean & debloat hooks...");

        installBackdoorBlocker(cl);
        installAdInitBlocker(cl);
        installADReposBlocker(cl, adReposCls);
        installAdvertManagerBlocker(cl);
        installSplashBlocker(cl);
        installTimetableAdBlocker(cl);
        installKuaishouWeaponBlocker(cl);
        installTelemetryBlocker(cl);
        installMineFragmentCleaner(cl);
        installHomeFragmentCleaner(cl);
        installMessageFragmentCleaner(cl);

        log("All FuckYiCampus clean hooks installed successfully!");
    }

    private void installBackdoorBlocker(ClassLoader cl) {
        try {
            Class<?> curlUtilsCls = Xp.findClassIfExists("com.yunma.baseextend.curl.CurlUtils", cl);
            if (curlUtilsCls != null) {
                hookAllMethodsNoop(curlUtilsCls, "loadAndTestCurlBinaries", "CurlUtils#loadAndTestCurlBinaries blocked");
                hookAllMethodsNoop(curlUtilsCls, "execute", "CurlUtils#execute blocked");
                hookAllMethodsNoop(curlUtilsCls, "runCustomCommand", "CurlUtils#runCustomCommand blocked");
                hookAllMethodsNoop(curlUtilsCls, "run", "CurlUtils#run blocked");
            }

            Class<?> cpuUtilsCls = Xp.findClassIfExists("com.yunma.baseextend.curl.CpuUtils", cl);
            if (cpuUtilsCls != null) {
                for (Method m : cpuUtilsCls.getDeclaredMethods()) {
                    if (m.getReturnType() == String.class) {
                        Xp.hook(m, chain -> "unknown");
                    }
                }
                logOnce("hook installed: CpuUtils neutralized");
            }

            Class<?> jzShellCls = Xp.findClassIfExists("cn.hzjizhun.admin.util.ShellUtils", cl);
            if (jzShellCls != null) {
                for (Method m : jzShellCls.getDeclaredMethods()) {
                    if ("execCommand".equals(m.getName())) {
                        Xp.hook(m, chain -> {
                            log("BLOCKED JiZhun ShellUtils#execCommand");
                            return null;
                        });
                    }
                }
                logOnce("hook installed: JiZhun ShellUtils blocked");
            }

            Class<?> bhShellCls = Xp.findClassIfExists("com.baihemob.ad.util.ShellUtils", cl);
            if (bhShellCls != null) {
                for (Method m : bhShellCls.getDeclaredMethods()) {
                    if ("execCommand".equals(m.getName())) {
                        Xp.hook(m, chain -> {
                            log("BLOCKED BaiHeMob ShellUtils#execCommand");
                            return null;
                        });
                    }
                }
                logOnce("hook installed: BaiHeMob ShellUtils blocked");
            }

            Class<?> cmdActCls = Xp.findClassIfExists("com.yunma.baseextend.base.activity.command.CommandActivity", cl);
            if (cmdActCls != null) {
                Xp.hookAllNamed(cmdActCls, "onCreate", chain -> {
                    log("DESTROYING CommandActivity immediately on onCreate");
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        ((Activity) self).finish();
                    }
                    return null;
                });
                logOnce("hook installed: CommandActivity auto-destroy");
            }

            Class<?> cmdDlgCls = Xp.findClassIfExists("com.yunma.baseextend.widget.dialog.CommandDialog", cl);
            if (cmdDlgCls != null) {
                hookAllMethodsNoop(cmdDlgCls, "show", "CommandDialog#show blocked");
            }
        } catch (Throwable t) {
            log("installBackdoorBlocker FAILED: " + t);
        }
    }
    private void installAdInitBlocker(ClassLoader cl) {
        try {
            Class<?> appCls = Xp.findClassIfExists("com.yunma.baseextend.BaseExtendApplication", cl);
            if (appCls != null) {
                // 【重要】不要 no-op `initThird`：它是「初始化第三方 SDK」的入口，微信 SDK 也在其中。
                // 整体 no-op 会让 BaseExtendApplication.wxapi 永不初始化，从而让微信支付/小程序在
                // 第一次使用时抛 UninitializedPropertyAccessException 直接闪退（2026-10-01 定位）。
                // 广告专用入口仍照旧 no-op。
                hookAllMethodsNoop(appCls, "initThirdAd", "initThirdAd blocked");
                hookAllMethodsNoop(appCls, "initTopOn", "initTopOn blocked");
                hookAllMethodsNoop(appCls, "initJiZhun", "initJiZhun blocked");
                hookAllMethodsNoop(appCls, "initWhiteBox", "initWhiteBox blocked");
                hookAllMethodsNoop(appCls, "checkJiZhun", "checkJiZhun blocked");
                hookAllMethodsNoop(appCls, "checkWhiteBox", "checkWhiteBox blocked");

                Xp.hookAllNamed(appCls, "getHasSplashAd", chain -> Boolean.FALSE);
                Xp.hookAllNamed(appCls, "getStartingSplash", chain -> Boolean.FALSE);
            }

            String[] adMgrClasses = new String[] {
                    "cn.com.yunma.third.advert.AdInitUtil",
                    "cn.com.yunma.third.advert.AdYmManager",
                    "cn.com.yunma.third.advert.NonStaAdManager",
                    "cn.com.yunma.third.advert.jizhun.JZManager",
                    "cn.com.yunma.third.advert.promo.PromoManager",
                    "cn.com.yunma.third.advert.tobid.ToBidManager",
                    "cn.com.yunma.third.advert.topon.TopOnManager",
                    "cn.com.yunma.third.advert.whitebox.WhiteBoxManager",
                    "cn.com.yunma.third.advert.EcMallAdUtil",
                    "cn.hzjizhun.admin.JZAdSdk",
                    "com.baihemob.ad.BHAdSdk"
            };

            for (String clsName : adMgrClasses) {
                Class<?> c = Xp.findClassIfExists(clsName, cl);
                if (c != null) {
                    for (Method m : c.getDeclaredMethods()) {
                        try {
                            if (m.getReturnType() == void.class) {
                                Xp.hook(m, chain -> null);
                            } else if (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class) {
                                Xp.hook(m, chain -> Boolean.FALSE);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    logOnce("hook installed: Decapitated " + clsName);
                }
            }
        } catch (Throwable t) {
            log("AdInitBlocker FAILED: " + t);
        }
    }
    private void installADReposBlocker(ClassLoader cl, Class<?> adReposCls) {
        try {
            hookAllMethodsReturnEmptyMap(adReposCls, "getAdvertisingMap", "getAdvertisingMap -> emptyMap");
            hookAllMethodsReturnEmptyMap(adReposCls, "getAdvertisingMapNoCache", "getAdvertisingMapNoCache -> emptyMap");
            hookAllMethodsReturnEmptyMap(adReposCls, "getAdvertisingThird", "getAdvertisingThird -> emptyMap");
            // 开屏广告的请求链：拦是要拦（不真发请求），但必须留日志 ——
            // 命中与否直接决定开屏走官方出口还是走模块兜底（见 installSplashBlocker）。
            hookNoopLogged(adReposCls, "loadStartAd", "ADRepos#loadStartAd 请求被拦");
            hookNoopLogged(adReposCls, "startAd", "ADRepos#startAd 请求被拦");
            hookAllMethodsNoop(adReposCls, "postAd", "postAd blocked");
        } catch (Throwable t) {
            log("ADReposBlocker FAILED: " + t);
        }
    }

    /** 与 hookAllMethodsNoop 同语义（返回类型默认值），但命中时打日志。 */
    /** 与 hookAllMethodsNoop 同语义（返回类型默认值），但命中时打日志。 */
    private void hookNoopLogged(Class<?> clazz, String methodName, final String label) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            final Class<?> rt = m.getReturnType();
            Xp.hook(m, chain -> {
                logOnce("hit: " + label);
                return defaultValueOf(rt);
            });
        }
    }
    private static Object defaultValueOf(Class<?> t) {
        if (!t.isPrimitive() || t == void.class) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        return null;
    }

    private void installAdvertManagerBlocker(ClassLoader cl) {
        try {
            Class<?> mgrCls = Xp.findClassIfExists("com.yunma.baseextend.ad.AdvertManager", cl);
            if (mgrCls != null) {
                hookAllMethodsNoop(mgrCls, "showTopOnMarketingPop", "showTopOnMarketingPop blocked");
                hookNoopLogged(mgrCls, "showTopOnSplash", "AdvertManager#showTopOnSplash 请求被拦");
                hookAllMethodsNoop(mgrCls, "showTopOnBanner", "showTopOnBanner blocked");
                hookAllMethodsNoop(mgrCls, "showTopOnNative", "showTopOnNative blocked");
                hookAllMethodsNoop(mgrCls, "showRewardVideoAd", "showRewardVideoAd blocked");
                hookAllMethodsNoop(mgrCls, "loadBanner", "loadBanner blocked");
                hookAllMethodsNoop(mgrCls, "loadNative", "loadNative blocked");
                hookAllMethodsNoop(mgrCls, "renderNative", "renderNative blocked");
            }
        } catch (Throwable t) {
            log("AdvertManagerBlocker FAILED: " + t);
        }
    }

    /**
     * 开屏跳转延迟（毫秒）：SplashActivity.onCreate 返回后再等这么久就进主界面。
     *
     * 为什么需要这个出口：易校园的 SplashActivity 用 onCreate 里注册的 onPreDraw 监听器
     * 死等 ready 置位才放行首帧，而 ready 的置位点在广告决策回调上（有缓存广告 ->
     * showAdvert，没广告 -> showDefault，都在回调里）。本模块把广告链在“请求层”掐掉了
     * （见 installADReposBlocker / installAdvertManagerBlocker 的 loadStartAd /
     * showTopOnSplash），回调就再也不会来 —— 官方两条出口全灭，开屏永久卡住。
     * 2026-09-30 实测（探针）：isReady 被轮询 4068 次，
     * showAdvert / showDefault / initTimer / toNext 命中 0 次。
     *
     * 取值依据（同机实测时间线，易校园主进程）：
     *   进程启动 -> SplashActivity.onCreate 返回          ≈ 1.9s（易盾壳解密 + Application 初始化，App 自身开销）
     *   onCreate 返回 -> ADRepos#loadStartAd（App 请求开屏广告）≈ 同刻（差 ~9ms）
     * 也就是说 onCreate 一返回，App 自己的启动准备就已经走完，剩下的只有“广告”这一步，
     * 而这一步由本模块短路（请求被拦），所以这里不必再等：取 0，冷启动总耗时 = App 自身开销。
     * 想更保守就调大这个值 —— 它是唯一的性能旋钮。
     */
    private static final long SPLASH_JUMP_DELAY_MS = 0;

    /** 一次开屏只跳一次：谁跳的都算（官方出口或本模块兜底）。 */
    private static volatile boolean splashJumped = false;

    /** 开屏出口：把 activity 直接带进主界面（等价官方无广告主线 toNext(false)）。 */
    private void jumpFromSplash(Class<?> splashCls, Object activity, String via) {
        if (splashJumped) return;
        if (activity instanceof Activity) {
            Activity act = (Activity) activity;
            if (act.isFinishing() || act.isDestroyed()) return;
        }
        splashJumped = true;
        log("hit: Splash 跳转 (" + via + ") -> toNext(false)");
        try {
            invokeToNext(splashCls, activity);
        } catch (Throwable t) {
            splashJumped = false;
            log("SplashActivity#toNext failed (" + via + "): " + t);
        }
    }

    /**
     * 反射调 toNext。实测真实签名为 toNext(java.lang.Boolean)（对象型，
     * 探针方法表已确认）；保留基础类型形态做兜底，防目标升级/换混淆后形态变化。
     */
    private void invokeToNext(Class<?> splashCls, Object activity) throws Throwable {
        try {
            Method m = splashCls.getDeclaredMethod("toNext", Boolean.class);
            m.setAccessible(true);
            m.invoke(activity, Boolean.FALSE);
            return;
        } catch (NoSuchMethodException e) {
            log("toNext(Boolean) 不存在，退到基础类型形态");
        }
        Method m = splashCls.getDeclaredMethod("toNext", boolean.class);
        m.setAccessible(true);
        m.invoke(activity, Boolean.FALSE);
    }

    /** 通用出口是否已用掉（一个进程只跳一次启动页，防止中途出现的同名页被误跳）。 */
    private static volatile boolean genericSplashHandled = false;

    /** 通用出口是否装上了（自检行要报）。 */
    private static volatile boolean genericExitInstalled = false;

    /** 按类名那套出口是否已就绪。就绪时通用出口让位，只在"类名找不到"时才出手。 */
    private static volatile boolean splashSpecificPathReady = false;

    /**
     * 通用开屏出口：**不依赖类名**，挂 `Activity.onCreate` 的 after，
     * 凡是"启动页样"的 Activity 就按同一套逻辑跳走。
     *
     * 为什么需要它：下面 `installSplashBlocker` 的出口 A/B/C 全都建立在
     * `findClassIfExists("com.yunma.app.ui.start.SplashActivity")` 之上 ——
     * 目标 App 一升级，只要这个类**改名或挪包**，整套 hook 会全部落空，
     * 而"落空"本身是静默的（v1.0 卡死就是这么酿成的）。
     * 页面本身的形状不会变：名字里带 Splash、且自己声明了 `toNext(java.lang.Boolean)`，
     * 就认它是启动页 —— 这样即使类名被改，开屏也不会再卡死。
     */
    private void installGenericSplashExit() {
        try {
            Method m = Xp.findMethod(android.app.Activity.class, "onCreate",
                    android.os.Bundle.class);
            if (m == null) {
                throw new NoSuchMethodException("android.app.Activity#onCreate(Bundle)");
            }
            Xp.hook(m, chain -> {
                Object result = chain.proceed();
                if (genericSplashHandled || splashJumped) {
                    return result;
                }
                if (splashSpecificPathReady) {
                    return result;   // 按类名的出口已就绪 —— 不抢它的活，也不刷日志
                }
                final Object act = chain.getThisObject();
                if (act == null) {
                    return result;
                }
                final Class<?> cls = act.getClass();
                String simple = cls.getSimpleName();
                if (simple == null || !simple.toLowerCase().contains("splash")) {
                    return result;   // 只认"启动页样"
                }
                if (!declaresToNext(cls)) {
                    return result;   // 形状也要对得上
                }
                genericSplashHandled = true;
                log("通用出口：按类名找不到启动页，但按形状认出了 " + cls.getName()
                        + " —— 目标 App 可能改过名/挪过包，走通用出口");
                final Class<?> splashCls2 = cls;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        jumpFromSplash(splashCls2, act, "通用出口(不依赖类名)");
                    }
                });
                return result;
            });
            genericExitInstalled = true;
            logOnce("hook installed: Splash 通用出口 (Activity.onCreate 按形状识别)");
        } catch (Throwable t) {
            log("Splash 通用出口安装失败: " + t);
        }
    }
    private static boolean declaresToNext(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] pt = m.getParameterTypes();
            if (m.getName().equals("toNext") && pt.length == 1 && pt[0] == Boolean.class) return true;
        }
        return false;
    }

    private void installSplashBlocker(ClassLoader cl) {
        installGenericSplashExit();
        try {
            final Class<?> splashCls = Xp.findClassIfExists("com.yunma.app.ui.start.SplashActivity", cl);
            if (splashCls == null) {
                // 静默失效是这类模块最贵的 bug（v1.0 卡死就是它）：类找不到必须出声。
                log("Splash 出口自检：SplashActivity 类没找到（可能改名/挪包）——"
                        + "按类名的 A/B/C 三条出口全部没装上，本次只靠通用出口兜底");
                return;
            }
            // 出口自检：目标 App 升级后出口方法可能改名/消失，而“hook 装不上”是静默的
            // （v1.0 的教训）。这里把“实际找到哪几条出口”打进日志，排查时不必再上探针。
            Method[] splashMethods = splashCls.getDeclaredMethods();
            StringBuilder exits = new StringBuilder();
            for (String want : new String[]{"showAdvert", "showDefault", "toNext", "initTimer", "onCreate"}) {
                boolean found = false;
                for (Method m : splashMethods) {
                    if (m.getName().equals(want)) {
                        found = true;
                        break;
                    }
                }
                exits.append(want).append('=').append(found ? "有" : "缺").append(' ');
                if (!found) {
                    log("Splash 出口自检：" + want + " 不见了（目标 App 可能已升级/换混淆，需重新核对）");
                }
            }
            log("Splash 出口自检：" + exits.toString().trim() + "｜方法总数 " + splashMethods.length
                    + "｜通用出口=" + (genericExitInstalled ? "有" : "缺"));
            // 出口 A：有缓存广告 -> showAdvert(bean)。官方在此点跳转的时序已实测安全（0 秒进主界面）。
            int hookedA = Xp.hookAllNamed(splashCls, "showAdvert", chain -> {
                jumpFromSplash(splashCls, chain.getThisObject(), "官方 showAdvert");
                return null;
            }).size();
            // 出口 B：官方“无广告”兜底 -> showDefault(bean)，与 showAdvert 同相位，同样直接跳。
            int hookedB = Xp.hookAllNamed(splashCls, "showDefault", chain -> {
                jumpFromSplash(splashCls, chain.getThisObject(), "官方 showDefault");
                return null;
            }).size();
            // 装没装上必须可见：hookAllNamed 返回实际挂上的重载数，为 0 就是这扇门静默失效了。
            if (hookedA == 0) log("Splash 出口 A(showAdvert) 一个重载都没挂上！");
            if (hookedB == 0) log("Splash 出口 B(showDefault) 一个重载都没挂上！");
            // 观测：官方自己的 toNext（App 走自己的兜底时）也记一笔，避免重复跳转。
            Xp.hookAllNamed(splashCls, "toNext", chain -> {
                splashJumped = true;
                log("hit: 官方 toNext(" + (chain.getArgs().size() > 0 ? chain.getArg(0) : "?") + ")");
                return chain.proceed();
            });
            // 出口 C：onCreate 之后主动跳。官方两条出口都哑掉时（我们掐了广告请求链就会这样）
            // 全靠它 —— 开屏永不卡死，且不依赖任何广告回调。
            Method onCreate = Xp.findMethod(splashCls, "onCreate", android.os.Bundle.class);
            if (onCreate == null) {
                log("Splash 出口 C(onCreate) 没找到 —— 开屏兜底只剩通用出口");
            } else {
                Xp.hook(onCreate, chain -> {
                    Object result = chain.proceed();
                    splashJumped = false;
                    final Object act = chain.getThisObject();
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            jumpFromSplash(splashCls, act, "onCreate+" + SPLASH_JUMP_DELAY_MS + "ms");
                        }
                    }, SPLASH_JUMP_DELAY_MS);
                    return result;
                });
            }
            // initTimer 是 ready 放行之后的下游倒计时（不是兜底出口），不再 noop：
            // 正常情况下我们早在 showAdvert/showDefault 就跳走了，留着它只是少一处多余的干预。
            Xp.hookAllNamed(splashCls, "initTimer", chain -> {
                logOnce("hit: initTimer " + (chain.getArgs().size() > 0 ? chain.getArg(0) : "?") + "（未拦截）");
                return chain.proceed();
            });
            splashSpecificPathReady = true;
            logOnce("hook installed: Splash 出口 (showAdvert×" + hookedA + " / showDefault×" + hookedB
                    + " / onCreate+" + SPLASH_JUMP_DELAY_MS + "ms)");
        } catch (Throwable t) {
            log("SplashBlocker FAILED: " + t);
        }
    }
    private void installTimetableAdBlocker(ClassLoader cl) {
        try {
            Class<?> courseViewCls = Xp.findClassIfExists("com.yunma.timetable.widget.CourseView", cl);
            if (courseViewCls != null) {
                hookAllMethodsNoop(courseViewCls, "loadAd", "CourseView#loadAd blocked");
            }
            Class<?> tableMainCls = Xp.findClassIfExists("com.yunma.timetable.ui.TableMainFragment", cl);
            if (tableMainCls != null) {
                hookAllMethodsNoop(tableMainCls, "queryAd", "TableMainFragment#queryAd blocked");
            }
        } catch (Throwable t) {
            log("TimetableAdBlocker FAILED: " + t);
        }
    }

    private void installKuaishouWeaponBlocker(ClassLoader cl) {
        try {
            Class<?> weaponCls = Xp.findClassIfExists("com.kuaishou.weapon.p0.WeaponHI", cl);
            if (weaponCls != null) {
                hookAllMethodsNoop(weaponCls, "init", "WeaponHI#init blocked");
                hookAllMethodsNoop(weaponCls, "initWeapon", "WeaponHI#initWeapon blocked");
            }

            Class<?> dgCls = Xp.findClassIfExists("com.kuaishou.weapon.p0.dg", cl);
            if (dgCls != null) {
                for (Method m : dgCls.getDeclaredMethods()) {
                    Class<?> ret = m.getReturnType();
                    if (ret == String.class) {
                        Xp.hook(m, chain -> "");
                    } else if (ret == int.class || ret == Integer.class) {
                        Xp.hook(m, chain -> 0);
                    } else if (ret == boolean.class || ret == Boolean.class) {
                        Xp.hook(m, chain -> Boolean.FALSE);
                    } else if (ret == Set.class) {
                        Xp.hook(m, chain -> Collections.emptySet());
                    } else if (ret.getName().contains("JSONObject")) {
                        Xp.hook(m, chain -> new org.json.JSONObject());
                    } else {
                        Xp.hook(m, chain -> null);
                    }
                }
                logOnce("hook installed: Weapon dg methods noop");
            }
        } catch (Throwable t) {
            log("KuaishouWeaponBlocker FAILED: " + t);
        }
    }
    private void installTelemetryBlocker(final ClassLoader cl) {
        probeAndNeutralizeMobGuard(cl, "初期注入");
        neutralizeAllDeclaredMethods(cl, "com.mob.commons.MobProductCollector", "MobProductCollector");

        // 延迟重试：验证假设 A（时机问题）。在主线程延时 3.5 秒（此时壳与 SDK 初始化已充分完成）再次尝试挂载
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                probeAndNeutralizeMobGuard(cl, "主线程延时3.5s重试");
            }
        }, 3500);

        try {
            Class<?> adLogCls = Xp.findClassIfExists("com.yunma.baseextend.util.AdLogUtil", cl);
            if (adLogCls != null) {
                hookAllMethodsNoop(adLogCls, "postLog", "postLog blocked");
                hookAllMethodsNoop(adLogCls, "batchPost", "batchPost blocked");
                hookAllMethodsNoop(adLogCls, "createLog", "createLog blocked");
                hookAllMethodsNoop(adLogCls, "updateLog", "updateLog blocked");
            }
        } catch (Throwable t) {
            log("AdLogUtil blocker FAILED: " + t);
        }
    }

    private volatile boolean mobGuardBlocked = false;

    /**
     * 专用于 MobGuard 的安全灭活函数。
     *
     * 经过真机实测（2026-09-30），MobGuard 的方法表中声明了以 Lcom/mob/mgs/OnAppActiveListener;
     * 和 Lcom/mob/guard/OnAppActiveListener; 为参数的监听器方法。若使用 getDeclaredMethods()
     * 或 hookAllMethods() 枚举整表，ART 会强制预解析所有参数类型，因跨 loader/未装载必抛 NoClassDefFoundError。
     *
     * 正确解法：放弃整表枚举，使用 findAndHookMethod 精准 Hook 核心静态入口（如 getGuardId 等），
     * 彻底避开 ART 整表参数签名解析，实现稳定灭活。
     */
    private synchronized void probeAndNeutralizeMobGuard(ClassLoader cl, String phase) {
        if (mobGuardBlocked) {
            return;
        }
        try {
            Class<?> guardCls = Xp.findClassIfExists("com.mob.guard.MobGuard", cl);
            if (guardCls == null) {
                log("MobGuard [" + phase + "]：未找到 com.mob.guard.MobGuard 类");
                return;
            }

            int hookedCount = 0;

            // 1. getGuardId() -> String：置空阻断设备全局标识获取
            try {
                Method m = Xp.findMethod(guardCls, "getGuardId");
                if (m == null) {
                    throw new NoSuchMethodException("com.mob.guard.MobGuard#getGuardId");
                }
                Xp.hook(m, chain -> {
                    logOnce("hit: MobGuard#getGuardId blocked -> empty string");
                    return "";
                });
                hookedCount++;
            } catch (Throwable t) {
                log("MobGuard hook getGuardId 失败: " + t);
            }

            // 2. 尝试无参或基础类型入口（如常见 init / syncInit 等）
            String[] noParamMethods = new String[]{"syncInit", "init", "start", "pullUp"};
            for (final String mName : noParamMethods) {
                try {
                    Method m = Xp.findMethod(guardCls, mName);
                    if (m == null) {
                        continue;   // 方法不存在 —— 与 legacy 里 findAndHookMethod 抛 NoSuchMethodError 同义
                    }
                    Xp.hook(m, chain -> {
                        logOnce("hit: MobGuard#" + mName + " blocked -> DO_NOTHING");
                        return null;
                    });
                    hookedCount++;
                } catch (Throwable t) {
                    log("MobGuard hook " + mName + " 失败: " + t);
                }
            }

            // 3. 尝试带 Context 参数的初始化入口
            for (final String mName : new String[]{"init", "syncInit", "pullUp"}) {
                try {
                    Method m = Xp.findMethod(guardCls, mName, android.content.Context.class);
                    if (m == null) {
                        continue;
                    }
                    Xp.hook(m, chain -> {
                        logOnce("hit: MobGuard#" + mName + "(Context) blocked -> DO_NOTHING");
                        return null;
                    });
                    hookedCount++;
                } catch (Throwable t) {
                    log("MobGuard hook " + mName + "(Context) 失败: " + t);
                }
            }

            if (hookedCount > 0) {
                mobGuardBlocked = true;
                logOnce("hook installed: MobGuard blocked (" + hookedCount + " entry methods hooked) [" + phase + "]");
            } else {
                log("MobGuard [" + phase + "] 0 个方法挂上，需核对方法签名");
            }
        } catch (Throwable t) {
            log("MobGuard [" + phase + "] 安全灭活异常: " + t);
        }
    }
    private void neutralizeAllDeclaredMethods(ClassLoader cl, String className, String label) {
        Method[] methods;
        try {
            Class<?> cls = Xp.findClassIfExists(className, cl);
            if (cls == null) {
                return;
            }
            methods = cls.getDeclaredMethods();
        } catch (Throwable t) {
            log(className + " 方法表解析失败（缺依赖类），跳过: " + t);
            return;
        }
        int n = 0;
        for (Method m : methods) {
            try {
                Xp.hook(m, chain -> null);
                n++;
            } catch (Throwable ignored) {
            }
        }
        logOnce("hook installed: " + label + " blocked (" + n + " methods)");
    }
    private void installMineFragmentCleaner(ClassLoader cl) {
        try {
            Class<?> centerCls = Xp.findClassIfExists("com.yunma.center.ui.CenterFragment", cl);
            if (centerCls == null) {
                centerCls = Xp.findClassIfExists("com.yunma.app.ui.center.CenterFragment", cl);
            }

            if (centerCls != null) {
                hookAllMethodsNoop(centerCls, "pointInfo", "CenterFragment#pointInfo blocked");

                Hooker mineCleanHook = chain -> {
                    Object result = chain.proceed();
                    try {
                        View rootView = null;
                        if (!chain.getArgs().isEmpty() && chain.getArg(0) instanceof View) {
                            rootView = (View) chain.getArg(0);
                        } else if (chain.getThisObject() != null) {
                            Method m = chain.getThisObject().getClass().getMethod("getView");
                            rootView = (View) m.invoke(chain.getThisObject());
                        }
                        if (rootView != null) {
                            final View v = rootView;
                            v.post(new Runnable() {
                                @Override
                                public void run() {
                                    hideViewById(v, "cl_coin");
                                    hideViewById(v, "rl_sign");
                                    hideViewById(v, "rl_coin");
                                    hideViewById(v, "rl_gift");
                                    hideViewById(v, "banner");
                                }
                            });
                            logOnce("hit: CenterFragment -> cl_coin hidden");
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                };

                Xp.hookAllNamed(centerCls, "onViewCreated", mineCleanHook);
                Xp.hookAllNamed(centerCls, "onResume", mineCleanHook);
                logOnce("hook installed: CenterFragment cl_coin cleaner");
            }
        } catch (Throwable t) {
            log("MineFragmentCleaner FAILED: " + t);
        }
    }
    private void installHomeFragmentCleaner(ClassLoader cl) {
        try {
            final Class<?> homeCls = Xp.findClassIfExists("com.yunma.app.ui.home.HomeFragment", cl);
            if (homeCls != null) {
                hookAllMethodsNoop(homeCls, "todayCourseList", "HomeFragment#todayCourseList blocked");
                hookAllMethodsNoop(homeCls, "renderToday", "HomeFragment#renderToday blocked");
                hookAllMethodsNoop(homeCls, "renderBannerCenter", "HomeFragment#renderBannerCenter blocked");
                hookAllMethodsNoop(homeCls, "setTopAdStyle", "HomeFragment#setTopAdStyle blocked");
                hookAllMethodsNoop(homeCls, "handleHomeAd", "HomeFragment#handleHomeAd blocked");
                hookAllMethodsNoop(homeCls, "handlePop", "HomeFragment#handlePop blocked");

                Hooker initAppsHook = chain -> {
                    Object[] args = chain.getArgs().toArray();
                    try {
                        if (args.length > 0 && args[0] instanceof List) {
                            List<?> origList = (List<?>) args[0];
                            List<Object> cleanList = new ArrayList<Object>();
                            boolean filtered = false;
                            for (Object item : origList) {
                                if (item != null && item.toString().contains(KW_COURSE)) {
                                    log("HomeFragment#initAppsAdapter: FILTERED course item -> " + item);
                                    filtered = true;
                                } else {
                                    cleanList.add(item);
                                }
                            }
                            if (filtered) {
                                args[0] = cleanList;
                                log("HomeFragment#initAppsAdapter: cleaned, remaining: " + cleanList.size());
                            }
                        }
                    } catch (Throwable t) {
                        log("initAppsAdapter filter error: " + t);
                    }
                    return chain.proceed(args);
                };

                for (Method m : homeCls.getDeclaredMethods()) {
                    if ("initAppsAdapter".equals(m.getName()) || "access$initAppsAdapter".equals(m.getName())) {
                        Xp.hook(m, initAppsHook);
                        logOnce("hook installed: " + m.getName());
                    }
                }

                // legacy 这里是同一个 XC_MethodHook 的 before + after 两段，合并成一个 interceptor
                Hooker setUIDataHook = chain -> {
                    try {
                        Object fragment = chain.getThisObject();
                        if (fragment != null) {
                            Field areaAppsField = homeCls.getDeclaredField("areaApps");
                            areaAppsField.setAccessible(true);
                            Object val = areaAppsField.get(fragment);
                            if (val instanceof List) {
                                List<?> origList = (List<?>) val;
                                List<Object> cleanList = new ArrayList<Object>();
                                boolean filtered = false;
                                for (Object item : origList) {
                                    if (item != null && item.toString().contains(KW_COURSE)) {
                                        log("HomeFragment.areaApps: FILTERED course item -> " + item);
                                        filtered = true;
                                    } else {
                                        cleanList.add(item);
                                    }
                                }
                                if (filtered) {
                                    areaAppsField.set(fragment, cleanList);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    Object result = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self != null) {
                            Method getViewMethod = self.getClass().getMethod("getView");
                            View v = (View) getViewMethod.invoke(self);
                            if (v != null) {
                                adjustTopMenuConstraint(v);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                };
                for (Method m : homeCls.getDeclaredMethods()) {
                    if ("setUIData".equals(m.getName()) || "access$setUIData".equals(m.getName())) {
                        Xp.hook(m, setUIDataHook);
                    }
                }

                Hooker homeUiHook = chain -> {
                    Object result = chain.proceed();
                    try {
                        View rootView = null;
                        if (!chain.getArgs().isEmpty() && chain.getArg(0) instanceof View) {
                            rootView = (View) chain.getArg(0);
                        } else if (chain.getThisObject() != null) {
                            Method getViewMethod = chain.getThisObject().getClass().getMethod("getView");
                            rootView = (View) getViewMethod.invoke(chain.getThisObject());
                        }
                        if (rootView != null) {
                            final View v = rootView;
                            adjustTopMenuConstraint(v);
                            v.post(new Runnable() {
                                @Override
                                public void run() {
                                    hideViewById(v, "ll_today_course");
                                    hideViewById(v, "banner_center");
                                    hideViewById(v, "banner_bottom");
                                    hideViewById(v, "iv_top_ad");
                                    hideViewById(v, "rl_auth_mob");
                                    adjustTopMenuConstraint(v);
                                }
                            });
                        }
                    } catch (Throwable t) {
                        log("homeUiHook error: " + t);
                    }
                    return result;
                };

                for (Class<?> c = homeCls; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if ("onViewCreated".equals(m.getName()) || "onResume".equals(m.getName())) {
                            Xp.hook(m, homeUiHook);
                        }
                    }
                }
                logOnce("hook installed: HomeFragment cleaner");
            }

            Class<?> appReposCls = Xp.findClassIfExists("com.yunma.baseextend.repos.AppRepos", cl);
            if (appReposCls != null) {
                Hooker sortAppHook = chain -> {
                    Object[] args = chain.getArgs().toArray();
                    try {
                        if (args.length >= 3 && args[2] instanceof List) {
                            List<?> origList = (List<?>) args[2];
                            List<Object> cleanList = new ArrayList<Object>();
                            boolean filtered = false;
                            for (Object item : origList) {
                                if (item != null && item.toString().contains(KW_COURSE)) {
                                    log("AppRepos#sortApp: FILTERED course item -> " + item);
                                    filtered = true;
                                } else {
                                    cleanList.add(item);
                                }
                            }
                            if (filtered) {
                                args[2] = cleanList;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed(args);
                };

                for (Method m : appReposCls.getDeclaredMethods()) {
                    if ("sortApp".equals(m.getName()) || "access$sortApp".equals(m.getName())) {
                        Xp.hook(m, sortAppHook);
                        logOnce("hook installed: AppRepos#sortApp cleaner");
                    }
                }
            }

            Class<?> appGridAdapterCls = Xp.findClassIfExists("com.yunma.baseextend.adapter.AppGridAdapter", cl);
            if (appGridAdapterCls != null) {
                for (Method m : appGridAdapterCls.getDeclaredMethods()) {
                    if ("onBindViewHolder".equals(m.getName()) && m.getParameterTypes().length >= 3) {
                        Xp.hook(m, chain -> {
                            try {
                                Object itemBean = chain.getArg(2);
                                if (itemBean != null && itemBean.toString().contains(KW_COURSE)) {
                                    Object binding = chain.getArg(0);
                                    Method getRootMethod = binding.getClass().getMethod("getRoot");
                                    View root = (View) getRootMethod.invoke(binding);
                                    if (root != null) {
                                        root.setVisibility(View.GONE);
                                        ViewGroup.LayoutParams lp = root.getLayoutParams();
                                        if (lp != null) {
                                            lp.width = 0;
                                            lp.height = 0;
                                            root.setLayoutParams(lp);
                                        }
                                    }
                                    log("AppGridAdapter#onBindViewHolder: collapsed course itemView");
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                        logOnce("hook installed: AppGridAdapter#onBindViewHolder fallback");
                    }
                }
            }
        } catch (Throwable t) {
            log("HomeFragmentCleaner FAILED: " + t);
        }
    }
    private void installMessageFragmentCleaner(ClassLoader cl) {
        try {
            Class<?> msgCls = Xp.findClassIfExists("com.yunma.app.ui.message.MessageFragment", cl);
            if (msgCls != null) {
                hookAllMethodsNoop(msgCls, "loadBannerData", "MessageFragment#loadBannerData blocked");

                for (Method m : msgCls.getDeclaredMethods()) {
                    if ("bindItemData".equals(m.getName())) {
                        Xp.hook(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                Object bean = (chain.getArgs().size() > 1) ? chain.getArg(1) : null;
                                if (bean != null && isAdMessage(bean)) {
                                    Object holder = chain.getArg(0);
                                    Field itemViewField = holder.getClass().getField("itemView");
                                    View itemView = (View) itemViewField.get(holder);
                                    if (itemView != null) {
                                        itemView.setVisibility(View.GONE);
                                        ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                                        if (lp != null) {
                                            lp.height = 0;
                                            itemView.setLayoutParams(lp);
                                        }
                                    }
                                    log("MessageFragment#bindItemData: collapsed ad message -> " + bean);
                                }
                            } catch (Throwable t) {
                                log("MessageFragment#bindItemData error: " + t);
                            }
                            return result;
                        });
                        logOnce("hook installed: MessageFragment#bindItemData cleaner");
                    }
                }
            }

            Class<?> multiListCls = Xp.findClassIfExists("com.yunma.basemodule.mlist.BaseMultiListFragment", cl);
            if (multiListCls != null) {
                for (Method m : multiListCls.getDeclaredMethods()) {
                    if ("handleData".equals(m.getName()) && m.getParameterTypes().length >= 1) {
                        Xp.hook(m, chain -> {
                            Object[] args = chain.getArgs().toArray();
                            try {
                                if (args.length > 0 && args[0] instanceof List) {
                                    List<?> origList = (List<?>) args[0];
                                    List<Object> cleanList = new ArrayList<Object>();
                                    boolean filtered = false;
                                    for (Object item : origList) {
                                        if (item != null && isAdMessage(item)) {
                                            log("BaseMultiListFragment#handleData: FILTERED ad message -> " + item);
                                            filtered = true;
                                        } else {
                                            cleanList.add(item);
                                        }
                                    }
                                    if (filtered) {
                                        args[0] = cleanList;
                                        log("BaseMultiListFragment#handleData: cleaned, remaining: " + cleanList.size());
                                    }
                                }
                            } catch (Throwable t) {
                                log("handleData error: " + t);
                            }
                            return chain.proceed(args);
                        });
                        logOnce("hook installed: BaseMultiListFragment#handleData cleaner");
                    }
                }
            }
        } catch (Throwable t) {
            log("MessageFragmentCleaner FAILED: " + t);
        }
    }
    private boolean isAdMessage(Object item) {
        if (item == null) return false;
        String s = item.toString();
        if (s.contains(KW_STUDENT) || s.contains(KW_ACTIVITY)) {
            return true;
        }
        return false;
    }

    private void adjustTopMenuConstraint(final View root) {
        if (root == null) return;
        try {
            int inTitleId = root.getResources().getIdentifier("in_title", "id", root.getContext().getPackageName());
            int topMenuId = root.getResources().getIdentifier("ll_top_menu", "id", root.getContext().getPackageName());
            if (inTitleId != 0 && topMenuId != 0) {
                final View inTitle = root.findViewById(inTitleId);
                final View topMenu = root.findViewById(topMenuId);
                if (topMenu != null && inTitle != null) {
                    applyTopMenuConstraint(topMenu, inTitle);
                    topMenu.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override
                        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
                            applyTopMenuConstraint(topMenu, inTitle);
                        }
                    });
                }
            }
        } catch (Throwable t) {
            log("adjustTopMenuConstraint error: " + t);
        }
    }

    private void applyTopMenuConstraint(final View topMenu, final View inTitle) {
        if (topMenu == null || inTitle == null) return;
        if (topMenu.isInLayout()) {
            topMenu.post(new Runnable() {
                @Override
                public void run() {
                    applyTopMenuConstraint(topMenu, inTitle);
                }
            });
            return;
        }
        try {
            ViewGroup.LayoutParams lp = topMenu.getLayoutParams();
            if (lp != null) {
                Field topToBottomField = lp.getClass().getField("topToBottom");
                int currentVal = topToBottomField.getInt(lp);
                if (currentVal != inTitle.getId()) {
                    topToBottomField.setInt(lp, inTitle.getId());
                    try {
                        Field topToTopField = lp.getClass().getField("topToTop");
                        topToTopField.setInt(lp, -1);
                    } catch (Throwable ignored) {
                    }
                    topMenu.setLayoutParams(lp);
                    topMenu.requestLayout();
                    logOnce("applyTopMenuConstraint: attached topMenu to inTitle id " + inTitle.getId());
                }
            }
        } catch (Throwable t) {
            log("applyTopMenuConstraint error: " + t);
        }
    }

    private boolean isDangerousCommand(String cmd) {
        if (cmd == null || cmd.isEmpty()) return false;
        String low = cmd.toLowerCase();
        return low.contains("curl") || low.contains("/curl") ||
               low.contains("su") || low.contains("sh") ||
               low.contains("chmod");
    }

    private boolean isCallerTracked() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        if (stack == null) return false;
        for (StackTraceElement elem : stack) {
            String cname = elem.getClassName().toLowerCase();
            for (String tracked : TRACKING_CALLERS) {
                if (cname.contains(tracked)) {
                    return true;
                }
            }
        }
        return false;
    }

}
