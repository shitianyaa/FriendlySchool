package com.yiran.friendlyschool.targets;

import com.yiran.friendlyschool.core.HookContext;
import com.yiran.friendlyschool.core.SchoolTargetBase;
import com.yiran.friendlyschool.core.Xp;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * 酷安：在列表消费前过滤 sponsor 卡片，不把这些输入实体交给广告处理方法。
 * 返回列表再过滤一次，覆盖原方法新生成的广告；普通实体与业务方法保持原样。
 * 开屏与广告 SDK 的其余 Hook 仅作限量观测，不代表已禁用这些广告位。
 * 混淆方法名用 Unicode 转义，运行期签名见 CoolApk/work/device/main_inventory.txt。
 */
public class CoolApkTarget extends SchoolTargetBase {

    private static final String TARGET_PKG = "com.coolapk.market";
    private static final String NAME = "酷安";

    /** 每个方法最多记多少条，防止高频方法刷爆模块日志。 */
    private static final int MAX_CALLS_PER_METHOD = 12;

    // ---- 混淆方法名（unicode 转义字面量）----
    /** Ϳ */ private static final String M_037F = "\u037f";
    /** Ԩ */ private static final String M_0528 = "\u0528";
    /** Ԫ */ private static final String M_052A = "\u052a";
    /** ԫ */ private static final String M_052B = "\u052b";
    /** Ԭ */ private static final String M_052C = "\u052c";
    /** ԭ */ private static final String M_052D = "\u052d";
    /** Ԯ */ private static final String M_052E = "\u052e";
    /** ֈ */ private static final String M_0588 = "\u0588";
    /** ֏ */ private static final String M_058F = "\u058f";
    /** ׯ */ private static final String M_05EF = "\u05ef";
    /** ؠ */ private static final String M_0620 = "\u0620";
    /** ހ */ private static final String M_0780 = "\u0780";
    /** ށ */ private static final String M_0781 = "\u0781";
    /** ނ */ private static final String M_0782 = "\u0782";

    // ---- 目标类名 ----
    private static final String C_SPLASH_ACT = "com.coolapk.market.view.splash.SplashAdActivity";
    /**
     * AnyThink(Mintegral) 自带的开屏广告 Activity，与酷安自有开屏并列。
     * 两者都声明在酷安清单里且都未导出，只用于展示开屏广告。
     */
    private static final String C_AT_SPLASH_ACT =
            "com.anythink.core.common.inner.ui.activity.ATMixSplashActivity";
    private static final String C_SPLASH_LOADER = "com.coolapk.market.view.splash.SplashAdLoader";
    private static final String C_SDK_UTILS = "com.coolapk.market.view.ad.SdkManagerUtils";
    private static final String C_SCOPE_MGR = "com.coolapk.market.view.ad.scope.ScopeAdManager";
    private static final String C_LOADER_FACTORY = "com.coolapk.market.view.ad.scope.AdLoaderFactory";
    private static final String C_ENTITY_AD = "com.coolapk.market.view.ad.EntityAdHelper";
    private static final String C_INTERSTITIAL = "com.coolapk.market.view.cardlist.EntityInterstitialAdHelper";
    private static final String C_DELAY_AD = "com.coolapk.market.view.ad.EntityDelayLoadADHelper";

    private static final String C_ADS_MODEL = "com.coolapk.market.model.Ads";

    private static final Map<String, Integer> CALLS =
            Collections.synchronizedMap(new HashMap<String, Integer>());

    /** 广告实体类，用于判断列表里有没有广告。 */
    private static volatile Class<?> ADS_CLASS;

    /** attach 时可能尚未解密，onCreate 只重试未安装的方法。 */
    private final Set<Method> installedMethods = new HashSet<Method>();

    /** onCreate 可能来自公共父类；共享 Hook 只结束明确登记的实际类。 */
    private final Set<Class<?>> blockedActivities =
            Collections.synchronizedSet(new HashSet<Class<?>>());

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
        log("install 被调用（sponsor 输入/输出过滤，其余广告链路观测）pkg=" + ctx.packageName);
        ctx.onAppReady(new HookContext.AppReady() {
            @Override
            public void run(ClassLoader cl) {
                observe(cl);
            }
        });
    }

    // ------------------------------------------------------------ 观测层安装

    private synchronized void observe(ClassLoader cl) {
        ADS_CLASS = Xp.findClassIfExists(C_ADS_MODEL, cl);
        log("=== 观测层安装开始 === Ads 实体类=" + (ADS_CLASS == null ? "未找到" : ADS_CLASS.getName()));

        log("--- 开屏 ---");
        log("--- 开屏（只断开广告浮层，不碰 SDK 初始化）---");
        blockActivity(cl, C_SPLASH_ACT, "开屏Activity");
        blockActivity(cl, C_AT_SPLASH_ACT, "AnyThink开屏Activity");
        obs(cl, C_SPLASH_LOADER, M_058F, "SplashAdLoader.֏");
        obs(cl, C_SPLASH_LOADER, M_05EF, "SplashAdLoader.ׯ");
        obs(cl, C_SPLASH_LOADER, M_0620, "SplashAdLoader.ؠ");
        obs(cl, C_SPLASH_LOADER, M_0780, "SplashAdLoader.ހ");
        obs(cl, C_SPLASH_LOADER, M_0781, "SplashAdLoader.ށ");
        obs(cl, C_SPLASH_LOADER, M_0782, "SplashAdLoader.ނ");

        log("--- 调度 / SDK 开关 ---");
        obs(cl, C_SDK_UTILS, M_052A, "SdkManagerUtils.Ԫ");
        obs(cl, C_SDK_UTILS, M_052B, "SdkManagerUtils.ԫ");
        obs(cl, C_SCOPE_MGR, M_052E, "ScopeAdManager.Ԯ");
        obs(cl, C_SCOPE_MGR, M_058F, "ScopeAdManager.֏");
        obs(cl, C_SCOPE_MGR, M_05EF, "ScopeAdManager.ׯ");
        obs(cl, C_LOADER_FACTORY, M_0528, "AdLoaderFactory.Ԩ");

        log("--- 列表处理（治本层：过滤 sponsor）---");
        filterSponsors(cl, C_ENTITY_AD, M_037F, "EntityAdHelper.Ϳ");
        filterSponsors(cl, C_INTERSTITIAL, M_037F, "EntityInterstitialAdHelper.Ϳ");

        log("--- 广告插入（日志证明：广告卡是 arg0 直接传入，不过列表）---");
        obs(cl, C_DELAY_AD, M_05EF, "EntityDelayLoadADHelper.ׯ");
        obs(cl, C_DELAY_AD, M_0620, "EntityDelayLoadADHelper.ؠ");
        obs(cl, C_DELAY_AD, M_0588, "EntityDelayLoadADHelper.ֈ");
        obs(cl, C_DELAY_AD, M_058F, "EntityDelayLoadADHelper.֏");
        obs(cl, C_DELAY_AD, M_0781, "EntityDelayLoadADHelper.ށ");
        noopSponsor(cl, C_DELAY_AD, M_0780, "EntityDelayLoadADHelper.ހ");

        log("--- UI 层插入 ---");
        obs(cl, C_DELAY_AD, M_0528, "EntityDelayLoadADHelper.Ԩ");
        obs(cl, C_DELAY_AD, M_052D, "EntityDelayLoadADHelper.ԭ");
        obs(cl, C_DELAY_AD, M_052E, "EntityDelayLoadADHelper.Ԯ");
        noopSponsor(cl, C_DELAY_AD, M_052C, "EntityDelayLoadADHelper.Ԭ");

        log("=== 观测层安装结束 ===");
    }

    /**
     * 给某个类的所有同名重载挂 pass-through 观测。
     *
     * 行为保证：{@code proceed()} 的结果原样返回；记录过程中的任何异常都吞掉。
     * 仅观察参数和返回值；原方法异常继续交给调用方。
     */
    private void obs(final ClassLoader cl, final String className, final String methodName, final String label) {
        try {
            Class<?> cls = Xp.findClassIfExists(className, cl);
            if (cls == null) {
                log("obs MISS: " + label + " —— 类不存在");
                return;
            }
            int count = 0;
            for (Method method : cls.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                count++;
                if (installedMethods.contains(method)) {
                    continue;
                }
                Xp.hook(method, chain -> {
                    boolean record = false;
                    String in = null;
                    try {
                        record = reserveCall(label);
                        if (record) {
                            in = summarizeArgs(chain.getArgs());
                        }
                    } catch (Throwable ignored) {
                    }
                    Object result = chain.proceed();
                    if (record) {
                        try {
                            logCall(label, in, summarize(result));
                        } catch (Throwable ignored) {
                        }
                    }
                    return result;
                });
                installedMethods.add(method);
            }
            if (count == 0) {
                log("obs MISS: " + label + " —— 方法不存在（名字或签名变了）");
            } else {
                log("obs OK: " + label + " (" + count + " 个重载)");
            }
        } catch (Throwable t) {
            log("obs FAILED: " + label + " : " + t);
        }
    }

    /**
     * 只在「要插入的确实是广告卡」时断开插入，其余原样放行。
     *
     * <h3>为什么不直接 noop 整个方法</h3>
     * 无差别 noop 会连正常内容一起断，且列表里可能留下占位（表现为空白行）。
     * 所以判据放在实参上：第一个参数是 sponsor 实体才拦，其它调用原样 proceed。
     *
     * <h3>为什么拦这一层</h3>
     * 真机日志（2026-10-06 11:47）表明：广告卡是作为<b>第一个实参直接传进 ހ / Ԭ 的</b>，
     * 并不经过 {@code EntityAdHelper.Ϳ} 的列表 —— 所以过滤列表挡不住它，
     * 必须在插入这一步断。
     *
     * <h3>真机验证（2026-10-06 11:54）</h3>
     * 信息流滚动时实际命中并断开：
     * <pre>
     *   ހ [已拦截] in{...sponsorCard-feedList-headline-2,
     *       List(n=25, t=[EntityCard|EntityCard|EntityCard|Feed])} -> blocked
     *   ހ [已拦截] in{...sponsorCard-feedList-huati.23257-0,
     *       List(n=22, t=[EntityCard|EntityCard|Feed|Feed])} -> blocked
     *   Ԭ [已拦截] in{...sponsorCard-feedList-huati.23257-0-2, ...} -> blocked
     * </pre>
     * 这几次的列表里<b>没有任何 HolderItem 占位</b>，所以断开插入不会留白。
     *
     * <h3>仍待核验</h3>
     * 回复列表（{@code feedDetailReplySponsorCard}）的列表由 HolderItem 组成，
     * 那一路本次未命中，断开后是否留白仍需真机肉眼核验。
     */
    private synchronized void noopSponsor(final ClassLoader cl, final String className,
                                          final String methodName, final String label) {
        try {
            Class<?> cls = Xp.findClassIfExists(className, cl);
            if (cls == null) {
                log("noopSponsor MISS: " + label + " —— 类不存在");
                return;
            }
            int count = 0;
            for (Method method : cls.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                count++;
                if (installedMethods.contains(method)) {
                    continue;
                }
                Xp.hook(method, chain -> {
                    Object first = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    boolean sponsor = false;
                    try {
                        sponsor = isSponsor(first);
                    } catch (Throwable ignored) {
                    }
                    if (sponsor) {
                        try {
                            if (reserveCall(label + " [已拦截]")) {
                                logCall(label + " [已拦截]", summarizeArgs(chain.getArgs()), "blocked");
                            }
                        } catch (Throwable ignored) {
                        }
                        return null;
                    }
                    return chain.proceed();
                });
                installedMethods.add(method);
            }
            if (count == 0) {
                log("noopSponsor MISS: " + label + " —— 方法不存在");
            } else {
                log("noopSponsor OK: " + label + " (" + count + " 个重载) —— 仅 sponsor 卡断开插入");
            }
        } catch (Throwable t) {
            log("noopSponsor FAILED: " + label + " : " + t);
        }
    }

    /**
     * 让某个 Activity 的 onCreate 直接结束，不创建界面。
     *
     * <h3>为什么只拦这一层</h3>
     * 两个开屏 Activity 只用于展示开屏广告：
     * <ul>
     *   <li>{@code SplashAdActivity} 是叠在 MainActivity 之上的浮层
     *       （{@code pm dump} 里 {@code taskRootClass} 仍是 MainActivity，
     *       显示约 3 秒后 PAUSED/STOPPED），不是 App 的启动入口；</li>
     *   <li>{@code ATMixSplashActivity} 同理，是 AnyThink 的开屏广告页。</li>
     * </ul>
     * 所以直接 finish 不会影响 App 正常启动。
     *
     * <h3>为什么不拦 SDK 初始化</h3>
     * {@code SdkManagerUtils.initSplashAdSdk(ctx, "GM_SPLASH")} 是 SDK 初始化中枢，
     * 本仓已有两次教训（易校园 {@code initThird}、GYBK {@code initAnyThinkSDK}）
     * —— no-op 初始化中枢会连带其它功能一起坏掉。这里只断显示。
     *
     * <h3>必须先 proceed 再 finish</h3>
     * 曾经试过「先 finish 再 return null（不 proceed）」，真机直接抛
     * {@code SuperNotCalledException: Activity ... did not call through to super.onCreate()}：
     * 框架在 onStart/onResume 会校验 onCreate 是否调用过 super，
     * 跳过原方法就等于跳过 {@code super.onCreate()}。所以顺序必须是
     * {@code proceed()} → {@code finish()}：框架初始化完成，界面还没绘制就被结束。
     */
    private synchronized void blockActivity(final ClassLoader cl, final String className, final String label) {
        try {
            Class<?> cls = Xp.findClassIfExists(className, cl);
            if (cls == null) {
                log("blockActivity MISS: " + label + " —— 类不存在");
                return;
            }
            Method onCreate = Xp.findMethod(cls, "onCreate", android.os.Bundle.class);
            if (onCreate == null) {
                log("blockActivity MISS: " + label + " —— onCreate(Bundle) 不存在");
                return;
            }
            // 两个目标可能共享同一个父方法，必须在方法去重前登记各自的类。
            blockedActivities.add(cls);
            if (installedMethods.contains(onCreate)) {
                return;
            }
            Xp.hook(onCreate, chain -> {
                try {
                    return chain.proceed();
                } finally {
                    // 放 finally：即使原 onCreate 自己抛异常（例如缺 extra），
                    // finish 仍然会执行，拦截不会因为业务异常而静默失效。
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof android.app.Activity
                                && blockedActivities.contains(self.getClass())) {
                            ((android.app.Activity) self).finish();
                            if (reserveCall(label + " [已拦截]")) {
                                logCall(label + " [已拦截]", "onCreate " + self.getClass().getName(), "finished");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            installedMethods.add(onCreate);
            log("blockActivity OK: " + label + " —— onCreate 后立即 finish");
        } catch (Throwable t) {
            log("blockActivity FAILED: " + label + " : " + t);
        }
    }

    /**
     * 真机证据：只过滤返回列表后，三张 sponsor 仍进入 ހ / Ԭ。
     * 先过滤输入，再过滤输出；内部广告回调仍可能运行，展示效果需独立核验。
     * 只匹配已测绘的 (List, boolean) 签名，不改变无关重载。
     */
    private synchronized void filterSponsors(final ClassLoader cl, final String className,
                                             final String methodName, final String label) {
        try {
            Class<?> cls = Xp.findClassIfExists(className, cl);
            if (cls == null) {
                log("filter MISS: " + label + " —— 类不存在");
                return;
            }
            Method method = Xp.findMethod(cls, methodName, List.class, boolean.class);
            if (method == null || !List.class.isAssignableFrom(method.getReturnType())) {
                log("filter MISS: " + label + " —— (List, boolean) -> List 签名不存在");
                return;
            }
            if (installedMethods.contains(method)) {
                return;
            }
            Xp.hook(method, chain -> {
                Object[] args = chain.getArgs().toArray();
                try {
                    if (args[0] instanceof List) {
                        args[0] = withoutSponsors((List<?>) args[0], label + " input");
                    }
                } catch (Throwable t) {
                    log("filter input error in " + label + ": " + t);
                }
                // 原方法异常必须原样抛出，不能捕获后再次 proceed。
                Object result = chain.proceed(args);
                try {
                    if (result instanceof List) {
                        return withoutSponsors((List<?>) result, label + " output");
                    }
                } catch (Throwable t) {
                    log("filter output error in " + label + ": " + t);
                }
                return result;
            });
            installedMethods.add(method);
            log("filter OK: " + label + " (List, boolean) -> List");
        } catch (Throwable t) {
            log("filter FAILED: " + label + " : " + t);
        }
    }

    /** 无广告时保留对象身份，不修改调用方持有的原列表。 */
    private List<?> withoutSponsors(List<?> input, String label) {
        java.util.ArrayList<Object> keep = null;
        int index = 0;
        for (Object item : input) {
            if (isSponsor(item)) {
                if (keep == null) {
                    keep = new java.util.ArrayList<Object>(input.size());
                    keep.addAll(input.subList(0, index));
                }
            } else if (keep != null) {
                keep.add(item);
            }
            index++;
        }
        if (keep == null) {
            return input;
        }
        logOnce("filtered " + (input.size() - keep.size()) + " sponsor(s) in " + label
                + " (" + input.size() + " -> " + keep.size() + ")");
        return keep;
    }

    /** 广告判据：entityTemplate 或 entityId 含 sponsor（酷安把广告叫 sponsor）。 */
    private static boolean isSponsor(Object o) {
        if (o == null) {
            return false;
        }
        String t = stringProp(o, "getEntityTemplate");
        if (t != null && t.toLowerCase(Locale.ROOT).contains("sponsor")) {
            return true;
        }
        String id = stringProp(o, "getEntityId");
        return id != null && id.toLowerCase(Locale.ROOT).contains("sponsor");
    }

    /** 反射取一个 String 属性（带缓存），取不到返回 null。 */
    private static String stringProp(Object o, String method) {
        try {
            Class<?> c = o.getClass();
            Method m;
            synchronized (ENTITY_METHODS) {
                Map<String, Object> methods = ENTITY_METHODS.get(c);
                if (methods == null) {
                    methods = new HashMap<String, Object>();
                    ENTITY_METHODS.put(c, methods);
                }
                Object cached = methods.get(method);
                if (cached instanceof Method) {
                    m = (Method) cached;
                } else if (cached == Boolean.FALSE) {
                    return null;
                } else {
                    try {
                        m = c.getMethod(method);
                        m.setAccessible(true);
                        methods.put(method, m);
                    } catch (Throwable t) {
                        methods.put(method, Boolean.FALSE);
                        return null;
                    }
                }
            }
            Object v = m.invoke(o);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 摘要生成前预占额度；并发调用也不能越过上限。 */
    private static synchronized boolean reserveCall(String label) {
        Integer n = CALLS.get(label);
        int c = n == null ? 0 : n;
        if (c >= MAX_CALLS_PER_METHOD) {
            if (c == MAX_CALLS_PER_METHOD) {
                CALLS.put(label, c + 1);
                Xp.log("酷安 | hit " + label + " —— 已达 " + MAX_CALLS_PER_METHOD + " 条上限，后续不再记录");
            }
            return false;
        }
        CALLS.put(label, c + 1);
        return true;
    }

    private static void logCall(String label, String in, String out) {
        // ads>0 单独高亮：那一刻就是广告注入点
        String mark = (out != null && out.contains("ads=") && !out.contains("ads=0")) ? " ***含广告***" : "";
        Xp.log("酷安 | hit " + label + "  in{" + in + "}  ->{" + out + "}" + mark);
    }

    // ------------------------------------------------------------ 摘要（绝不抛）

    private static String summarizeArgs(List<?> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(summarize(args.get(i)));
        }
        return sb.toString();
    }

    private static String summarize(Object o) {
        try {
            if (o == null) {
                return "null";
            }
            if (o instanceof List) {
                List<?> list = (List<?>) o;
                int ads = 0;
                StringBuilder types = new StringBuilder();
                int shown = 0;
                for (Object e : list) {
                    if (isAds(e)) {
                        ads++;
                    }
                    if (shown < 4) {
                        if (shown > 0) {
                            types.append('|');
                        }
                        types.append(e == null ? "null" : e.getClass().getSimpleName());
                        shown++;
                    }
                }
                return "List(n=" + list.size() + ", ads=" + ads + ", t=[" + types + "])";
            }
            if (o instanceof String) {
                String s = (String) o;
                return "Str(" + (s.length() > 24 ? s.substring(0, 24) + "…" : s) + ")";
            }
            if (o instanceof Boolean || o instanceof Integer || o instanceof Long
                    || o instanceof Short || o instanceof Byte) {
                return String.valueOf(o);
            }
            if (o instanceof Map) {
                return "Map(n=" + ((Map<?, ?>) o).size() + ")";
            }
            String simple = o.getClass().getSimpleName();
            // 广告相关实体单独标出来
            if (isAds(o)) {
                return "Ads!";
            }
            // 列表里的广告不是 Ads 本体，而是被包成 EntityCard —— 把实体身份打出来
            String id = describeEntity(o);
            return id == null ? simple : (simple + id);
        } catch (Throwable t) {
            return "<summarize-failed:" + t.getClass().getSimpleName() + ">";
        }
    }

    /** 缓存过的 Method 查找，避免每条日志都做一次反射。 */
    private static final Map<Class<?>, Map<String, Object>> ENTITY_METHODS =
            new HashMap<Class<?>, Map<String, Object>>();

    /**
     * 试着把实体的身份打出来：{@code getEntityType()} / {@code getEntityTemplate()}
     * / {@code getEntityId()}。
     *
     * 目的：找「怎么识别一张卡是广告」的判据。酷安的实体卡片靠 entityType/template
     * 区分普通帖子、广告、推荐 —— 而列表里的广告不是 {@code Ads} 本体（实测：
     * 传给 {@code EntityDelayLoadADHelper.ހ} 的是 {@code AutoValue_EntityCard}，
     * entityType 为 “card”），所以必须靠别的属性区分。
     *
     * <b>不记录 {@code getTitle()}</b>：它是帖子/回复的用户内容，写进模块日志会被有日志
     * 读取权限者看到；识别卡片只需 type/template/id，与本条判据无关，故一并省去。
     */
    private static String describeEntity(Object o) {
        StringBuilder sb = new StringBuilder();
        appendProp(sb, o, "getEntityType");
        appendProp(sb, o, "getEntityTemplate");
        appendProp(sb, o, "getEntityId");
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendProp(StringBuilder sb, Object o, String method) {
        String value = stringProp(o, method);
        if (value != null) {
            sb.append('/').append(value.length() > 40 ? value.substring(0, 40) + "..." : value);
        }
    }

    private static boolean isAds(Object o) {
        Class<?> c = ADS_CLASS;
        try {
            return c != null && o != null && c.isInstance(o);
        } catch (Throwable t) {
            return false;
        }
    }
}
