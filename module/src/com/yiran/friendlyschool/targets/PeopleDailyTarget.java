package com.yiran.friendlyschool.targets;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.yiran.friendlyschool.core.HookContext;
import com.yiran.friendlyschool.core.SchoolTargetBase;
import com.yiran.friendlyschool.core.Xp;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 人民日报（com.peopledailychina.activity）纯净化 Target。
 *
 * 目标形态与逆向事实（2026-10-27 审计）：
 * 1. 爱加密企业版加固（静态 classes.dex 仅 13KB/4个壳类，运行时动态加载 12 个 DEX，约 53MB）；
 * 2. 0 引入第三方联盟广告 SDK（穿山甲/优量汇/快手等均无），完全由人民网/人民日报自研自营分发；
 * 3. 开屏广告链路：
 *    AppMainActivity.G0() -> SplashHelper.F(boolean) -> SplashHelper.c0()。
 *    在 c0() 中读取 SharedPreferences "launch" 中的 "splash_cache_type"（通过 sa.p#G0()）。
 *    若返回 0（非 1 非 2），直接触发 AppMainActivity#g0() 开启主页 ViewPager 与导航栏，
 *    跳过 activity_splash 布局、倒计时（d0/Handler）与 AliyunRenderView 视频播放器，复用官方原生直达通道。
 *    （注：放行 tb.e#c 启动引导数据请求，确保底部导航栏与首页首刷数据正常拉取）。
 * 4. 信息流与频道广告链路：
 *    基于阿里 mPaaS 组件流渲染机制（AbsSectionParser -> ChannelSectionParser）。
 *    NewsSectionParserHelper 注册了 8 类商业广告，契约 Key 均包含 "pd_ad_"：
 *    customStyle$pd_ad_long_title_land, customStyle$pd_ad_long_land, customStyle$pd_ad_name_card,
 *    customStyle$pd_ad_video_exhibition, customStyle$pd_ad_three_image, customStyle$pd_ad_small_image,
 *    customStyle$pd_ad_big_image, customStyle$pd_ad_video_card。
 *    在 AbsSectionParser#parseLayoutManager(String) 与 parseSection 处过滤包含 "pd_ad_" 的卡片，
 *    返回 null 使其静默不插入列表，无空白占位槽。
 *
 * 时序说明：
 * 宿主在 Application.attachBaseContext 后才完成真实 DEX 解密，因此统一走 ctx.onAppReady，
 * 分别维护独立的锁存状态，确保幂等且不错失延迟加载的类。
 */
public class PeopleDailyTarget extends SchoolTargetBase {

    private static final String TARGET_PKG = "com.peopledailychina.activity";
    private static final String NAME = "人民日报";

    private static volatile boolean splashInstalled = false;
    private static volatile boolean feedInstalled = false;
    private static volatile boolean autoSignInInstalled = false;

    // 目标类名（版本锚点 7.4.3.1）
    private static final String CLS_SP_UTILS = "sa.p";
    private static final String CLS_SECTION_PARSER = "com.people.component.comp.parser.AbsSectionParser";
    private static final String CLS_BASE_ACTIVITY = "com.people.common.base.BaseActivity";
    private static final String CLS_MAIN_ACTIVITY = "com.peopledailychina.activity.activity.AppMainActivity";
    private static final String CLS_PD_UTILS = "com.people.common.util.PDUtils";
    private static final String CLS_USER_POINT_VM = "com.people.personalcenter.vm.incentive.UserPointViewModel";
    private static final String CLS_SIGN_IN_LISTENER = "com.people.personalcenter.vm.incentive.IPointSignInListener";

    // in-flight 名额：0 = 空闲，非 0 = 持有者代号（看门狗与回调靠代号判断归属，防止误放新一轮的锁）
    private static final AtomicLong seqGen = new AtomicLong(0L);
    private static final AtomicLong inflightSeq = new AtomicLong(0L);
    private static volatile String lastSignDateWithAccount = "";
    /** 失败重试冷却（10 分钟，持久化到 prefs，跨冷启动生效） */
    private static final long RETRY_COOLDOWN_MS = 10 * 60 * 1000L;
    /** 回执看门狗：LiveData 观察者被提前摘除时，兜底释放 in-flight 名额 */
    private static final long INFLIGHT_TIMEOUT_MS = 45 * 1000L;

    // 登录态 / 账号身份方法（首次解析后缓存，避免每次 onResume 重复反射查找）
    private static volatile Method mIsLogin = null;
    private static volatile Method mUserId = null;
    private static volatile Method mUserToken = null;

    @Override
    public String shortName() {
        return NAME;
    }

    @Override
    public String packageName() {
        return TARGET_PKG;
    }

    @Override
    public void install(final HookContext ctx) {
        // 爱加密壳加载后会吞掉本进程的 INFO/WARN 日志（实测），只有 ERROR 能出 logcat。
        // 不开镜像的话，本目标的所有 hook installed / FAILED / hit 行都是静默的。
        Xp.enableLogcatMirror();
        log("install 被调用 pkg=" + ctx.packageName);

        ctx.onAppReady(new HookContext.AppReady() {
            @Override
            public void run(ClassLoader cl) {
                installHooks(cl);
            }
        });
    }

    private synchronized void installHooks(ClassLoader cl) {
        if (splashInstalled && feedInstalled && autoSignInInstalled) {
            return;
        }

        if (!splashInstalled) {
            splashInstalled = installSplashHooks(cl);
        }
        if (!feedInstalled) {
            feedInstalled = installFeedHooks(cl);
        }
        if (!autoSignInInstalled) {
            autoSignInInstalled = installAutoSignInHook(cl);
        }

        if (splashInstalled && feedInstalled && autoSignInInstalled) {
            log("Hook 全部安装完成: 开屏直通=true, 信息流裁剪=true, 自动签到=true");
        } else {
            log("Hook 安装状态: 开屏直通=" + splashInstalled + ", 信息流裁剪=" + feedInstalled + ", 自动签到=" + autoSignInInstalled);
        }
    }

    /**
     * 开屏广告：微创覆写开屏缓存读取，复用 AppMainActivity.g0() 原生通道直达首页。
     * （放行启动引导请求，保障底部导航栏与首页首刷数据正常落地）。
     */
    private boolean installSplashHooks(ClassLoader cl) {
        boolean ok = false;

        // 开屏缓存类型置零 -> 促使 SplashHelper#c0() 走 this.k.g0() 直达主页
        try {
            Class<?> spUtilsClass = Xp.findClassIfExists(CLS_SP_UTILS, cl);
            if (spUtilsClass != null) {
                Method mG0 = Xp.findMethod(spUtilsClass, "G0");
                if (mG0 != null) {
                    Xp.hook(mG0, chain -> {
                        logOnce("hit: 开屏广告缓存置零 (sa.p#G0 -> 0) -> 直通主页");
                        return 0;
                    });
                    log("hook installed: 开屏直通 sa.p#G0");
                    ok = true;
                } else {
                    log("hook FAILED: sa.p#G0 方法未找到");
                }
            } else {
                log("class MISSING: sa.p (开屏配置)");
            }
        } catch (Throwable t) {
            log("hook FAILED: sa.p#G0 : " + t);
        }

        return ok;
    }

    /**
     * 信息流与频道广告：在组件解析器 AbsSectionParser 处拦截 customStyle$pd_ad_*，
     * 阻止生成广告 Section 与 LayoutManager，列表零插入、无空白占位。
     */
    private boolean installFeedHooks(ClassLoader cl) {
        boolean ok = false;
        try {
            Class<?> parserClass = Xp.findClassIfExists(CLS_SECTION_PARSER, cl);
            if (parserClass != null) {
                boolean layoutHooked = false;
                boolean sectionHooked = false;

                // 1. parseLayoutManager(String str)
                Method mParseLayout = Xp.findMethod(parserClass, "parseLayoutManager", String.class);
                if (mParseLayout != null) {
                    Xp.hook(mParseLayout, chain -> {
                        String key = (String) chain.getArg(0);
                        if (key != null && key.contains("pd_ad_")) {
                            logOnce("hit: 过滤信息流广告布局管理器 -> " + key);
                            return null;
                        }
                        return chain.proceed();
                    });
                    log("hook installed: 信息流组件布局过滤 AbsSectionParser#parseLayoutManager(String)");
                    layoutHooked = true;
                } else {
                    log("hook FAILED: AbsSectionParser#parseLayoutManager(String) 未找到");
                }

                // 2. parseSection(AbsGroup absGroup, CompBean compBean)
                Class<?> absGroupClass = Xp.findClassIfExists("com.people.component.comp.layoutdata.AbsGroup", cl);
                Class<?> compBeanClass = Xp.findClassIfExists("com.people.entity.custom.comp.CompBean", cl);
                if (absGroupClass != null && compBeanClass != null) {
                    Method mParseSection = Xp.findMethod(parserClass, "parseSection", absGroupClass, compBeanClass);
                    Method mGetCompStyle = Xp.findMethod(compBeanClass, "getCompStyle");
                    if (mParseSection != null && mGetCompStyle != null) {
                        Xp.hook(mParseSection, chain -> {
                            Object compBean = chain.getArg(1);
                            if (compBean != null) {
                                try {
                                    Object style = mGetCompStyle.invoke(compBean);
                                    if (style instanceof String) {
                                        String styleStr = (String) style;
                                        if (styleStr.contains("pd_ad_")) {
                                            logOnce("hit: 过滤信息流广告数据项 -> " + styleStr);
                                            return null;
                                        }
                                    }
                                } catch (Throwable t) {
                                    logOnce("hit: parseSection getCompStyle 调用异常: " + t);
                                }
                            }
                            return chain.proceed();
                        });
                        log("hook installed: 信息流组件数据项过滤 AbsSectionParser#parseSection");
                        sectionHooked = true;
                    } else {
                        if (mParseSection == null) {
                            log("hook FAILED: AbsSectionParser#parseSection 方法未找到");
                        }
                        if (mGetCompStyle == null) {
                            log("hook FAILED: CompBean#getCompStyle 方法未找到");
                        }
                    }
                } else {
                    log("class MISSING: AbsGroup/CompBean (" + absGroupClass + "/" + compBeanClass + ") -> 跳过 parseSection 过滤");
                }

                if (layoutHooked && sectionHooked) {
                    log("hook installed: 信息流广告过滤全部就绪 (layoutManager + section)");
                    ok = true;
                } else {
                    log("hook PARTIAL: 信息流广告过滤未全就绪 (layout=" + layoutHooked + ", section=" + sectionHooked + ")");
                }
            } else {
                log("class MISSING: AbsSectionParser (信息流组件解析器)");
            }
        } catch (Throwable t) {
            log("hook FAILED: 信息流广告过滤 : " + t);
        }
        return ok;
    }

    /**
     * 自动签到：在 AppMainActivity 进入 onResume 且首屏加载就绪后，延时发起静默签到。
     * （内置登录态检查与每日单次频控持久化，避免未登录或重复请求打扰）。
     */
    private boolean installAutoSignInHook(ClassLoader cl) {
        boolean ok = false;
        try {
            Class<?> baseActivityClass = Xp.findClassIfExists(CLS_BASE_ACTIVITY, cl);
            if (baseActivityClass != null) {
                Method mOnResume = Xp.findMethod(baseActivityClass, "onResume");
                if (mOnResume != null) {
                    Xp.hook(mOnResume, chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Activity && CLS_MAIN_ACTIVITY.equals(self.getClass().getName())) {
                            scheduleAutoSignIn((Activity) self, cl);
                        }
                        return result;
                    });
                    log("hook installed: 首页加载自动签到 BaseActivity#onResume (AppMainActivity)");
                    ok = true;
                } else {
                    log("hook FAILED: BaseActivity#onResume 方法未找到");
                }
            } else {
                log("class MISSING: BaseActivity (活动基类)");
            }
        } catch (Throwable t) {
            log("hook FAILED: 自动签到挂钩异常 : " + t);
        }
        return ok;
    }

    /** 幂等解析并缓存登录态与账号身份方法（只做一次）。 */
    private void resolveAuthMethods(ClassLoader cl) {
        if (mIsLogin == null) {
            try {
                Class<?> pdUtilsClass = Xp.findClassIfExists(CLS_PD_UTILS, cl);
                if (pdUtilsClass != null) {
                    mIsLogin = Xp.findMethod(pdUtilsClass, "isLogin");
                }
            } catch (Throwable ignored) {
            }
        }
        if (mUserId == null || mUserToken == null) {
            try {
                Class<?> spClass = Xp.findClassIfExists(CLS_SP_UTILS, cl);
                if (spClass != null) {
                    // sa.p#W0 -> rmrb_config/user_id（稳定账号标识）
                    // sa.p#d1 -> rmrb_config/user_token（可能轮换，仅作兜底）
                    mUserId = Xp.findMethod(spClass, "W0");
                    mUserToken = Xp.findMethod(spClass, "d1");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Fail-Closed：只有明确返回 Boolean.TRUE 才判定已登录；任何异常/缺失一律当未登录。 */
    private boolean isUserLoggedIn(ClassLoader cl) {
        resolveAuthMethods(cl);
        if (mIsLogin == null) {
            logOnce("自动签到跳过：PDUtils#isLogin 不可用（按未登录处理）");
            return false;
        }
        try {
            return Boolean.TRUE.equals(mIsLogin.invoke(null));
        } catch (Throwable t) {
            logOnce("自动签到跳过：isLogin 调用异常 " + t);
            return false;
        }
    }

    /**
     * 账号身份后缀：优先 user_id（稳定），退化到 user_token 哈希。
     * 用稳定标识做键，避免 token 轮换把每日去重打穿。
     */
    private String getAccountKeySuffix(ClassLoader cl) {
        resolveAuthMethods(cl);
        try {
            if (mUserId != null) {
                Object uid = mUserId.invoke(null);
                if (uid instanceof String && !((String) uid).isEmpty()) {
                    return "_" + Integer.toHexString(uid.hashCode());
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mUserToken != null) {
                Object token = mUserToken.invoke(null);
                if (token instanceof String && !((String) token).isEmpty()) {
                    return "_" + Integer.toHexString(token.hashCode());
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 只有持有者才能释放，避免旧看门狗误放新一轮的锁。 */
    private static void releaseInflight(long seq) {
        inflightSeq.compareAndSet(seq, 0L);
    }

    private void scheduleAutoSignIn(final Activity activity, final ClassLoader cl) {
        // 1. Fail-Closed 登录态检查：只有确认已登录才继续调度
        if (!isUserLoggedIn(cl)) {
            return;
        }

        final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        final String accountSuffix = getAccountKeySuffix(cl);
        final String memoryKey = today + accountSuffix;
        final String prefKey = "pd_last_sign_date" + accountSuffix;
        final String attemptKey = "pd_last_attempt_ts" + accountSuffix;

        // 2. 内存快速去重
        if (memoryKey.equals(lastSignDateWithAccount)) {
            return;
        }

        final SharedPreferences sp;
        try {
            sp = activity.getSharedPreferences("friendlyschool_prefs", Context.MODE_PRIVATE);
        } catch (Throwable t) {
            logOnce("自动签到跳过：拿不到 SharedPreferences " + t);
            return;
        }

        // 3. 持久化去重（同账号同一天只签一次，跨冷启动生效）
        try {
            if (today.equals(sp.getString(prefKey, ""))) {
                lastSignDateWithAccount = memoryKey;
                logOnce("自动签到跳过：今日（" + today + "）已完成签到");
                return;
            }
        } catch (Throwable ignored) {
        }

        // 4. 失败重试冷却（10 分钟，持久化，防止弱网时每次冷启动都重试）
        try {
            long lastAttempt = sp.getLong(attemptKey, 0L);
            if (lastAttempt > 0 && System.currentTimeMillis() - lastAttempt < RETRY_COOLDOWN_MS) {
                return;
            }
        } catch (Throwable ignored) {
        }

        // 5. 抢占 in-flight 名额（0 = 空闲）
        final long seq = seqGen.incrementAndGet();
        if (!inflightSeq.compareAndSet(0L, seq)) {
            return;
        }

        // 延时 2 秒在主线程运行，避开冷启动首屏与首页首刷网络高峰
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // 延迟执行时二次复核：Activity 存活、仍处于登录态、且未被其他路径完成签到
            if (activity.isFinishing() || activity.isDestroyed()
                    || !isUserLoggedIn(cl)
                    || memoryKey.equals(lastSignDateWithAccount)) {
                releaseInflight(seq); // 复核未通过：不消耗冷却窗口
                return;
            }
            // 只有真正发包才写冷却时间戳
            try {
                sp.edit().putLong(attemptKey, System.currentTimeMillis()).apply();
            } catch (Throwable ignored) {
            }
            executeAutoSignIn(activity, cl, sp, today, memoryKey, prefKey, seq);
        }, 2000L);
    }

    private void executeAutoSignIn(final Activity activity, final ClassLoader cl,
                                  final SharedPreferences sp,
                                  final String today, final String memoryKey,
                                  final String prefKey, final long seq) {
        try {
            Class<?> vmClass = Xp.findClassIfExists(CLS_USER_POINT_VM, cl);
            Class<?> listenerClass = Xp.findClassIfExists(CLS_SIGN_IN_LISTENER, cl);
            if (vmClass == null || listenerClass == null) {
                log("自动签到阻断：UserPointViewModel 或 IPointSignInListener 类未找到");
                releaseInflight(seq);
                return;
            }

            final Object vm = vmClass.newInstance();

            // 注册动态代理监听器获取签到结果
            Object proxyListener = Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{listenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String mName = method.getName();

                            // 1. 处理 Object 基础方法，防止返回 null 在拆箱为基本类型时抛 NullPointerException
                            if ("hashCode".equals(mName)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(mName)) {
                                return (args != null && args.length > 0 && proxy == args[0]);
                            }
                            if ("toString".equals(mName)) {
                                return "PeopleDailySignInListenerProxy@" + Integer.toHexString(System.identityHashCode(proxy));
                            }

                            // 2. 处理业务回调（接口只有成功/失败两个终止回调）
                            try {
                                if ("onPointSignInSuccess".equals(mName) && args != null && args.length > 0) {
                                    lastSignDateWithAccount = memoryKey;
                                    try {
                                        sp.edit().putString(prefKey, today).apply();
                                    } catch (Throwable ignored) {
                                    }

                                    Object signInBean = args[0];
                                    String count = "0";
                                    boolean firstTime = false;
                                    try {
                                        Object c = Xp.callMethod(signInBean, "getSignCount");
                                        if (c != null) count = c.toString();
                                        Object f = Xp.callMethod(signInBean, "isFirstTime");
                                        if (f instanceof Boolean) firstTime = (Boolean) f;
                                    } catch (Throwable ignored) {
                                    }

                                    log("自动签到成功: 连续签到 " + count + " 天 (今日首次=" + firstTime + ")");
                                    if (firstTime) {
                                        final String finalCount = count;
                                        activity.runOnUiThread(() -> {
                                            try {
                                                Toast.makeText(
                                                        activity.getApplicationContext(),
                                                        "人民日报自动签到成功：已连续签到 " + finalCount + " 天",
                                                        Toast.LENGTH_SHORT
                                                ).show();
                                            } catch (Throwable ignored) {
                                            }
                                        });
                                    }
                                } else if ("onPointSignInFailed".equals(mName)) {
                                    logOnce("自动签到接口返回失败: " + (args != null && args.length > 0 ? args[0] : ""));
                                }
                            } finally {
                                // 异步回执到达，释放 in-flight 名额
                                releaseInflight(seq);
                            }
                            return null;
                        }
                    }
            );

            // 绑定监听器：只有成功绑定监听器后才允许发包，否则无法持久化去重
            boolean listenerBound = false;
            for (Method m : vmClass.getDeclaredMethods()) {
                if ("observePointSignInListener".equals(m.getName())) {
                    try {
                        m.setAccessible(true);
                        m.invoke(vm, activity, proxyListener);
                        listenerBound = true;
                        break;
                    } catch (Throwable t) {
                        log("observePointSignInListener 调用异常: " + t);
                    }
                }
            }

            if (!listenerBound) {
                log("自动签到阻断：未能成功绑定 IPointSignInListener，放弃发包");
                releaseInflight(seq);
                return;
            }

            // 发起签到
            Method pointSignInMethod = Xp.findMethod(vmClass, "pointSignIn");
            if (pointSignInMethod == null) {
                log("自动签到阻断：pointSignIn 方法未找到");
                releaseInflight(seq);
                return;
            }
            pointSignInMethod.invoke(vm);
            log("已触发自动签到 pointSignIn()");

            // 看门狗：宿主未回调（如 Activity 提前销毁导致 LiveData 观察者被摘除）时兜底释放
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (inflightSeq.get() == seq) {
                    releaseInflight(seq);
                    logOnce("自动签到：等待回执超时，兜底释放 in-flight 名额");
                }
            }, INFLIGHT_TIMEOUT_MS);
        } catch (Throwable t) {
            log("自动签到执行异常: " + t);
            releaseInflight(seq);
        }
    }
}
