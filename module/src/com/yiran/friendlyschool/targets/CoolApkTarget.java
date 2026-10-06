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
        obs(cl, C_SPLASH_ACT, "onCreate", "开屏Activity.onCreate");
        obs(cl, C_SPLASH_ACT, "onDestroy", "开屏Activity.onDestroy");
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
     * 把一个方法（全重载）换成 <b>no-op</b>：不调原方法、直接返回 null。
     *
     * <h3>为什么这里可以 no-op（而 SDK 初始化不行）</h3>
     * 本项目有两次硬教训：易校园 {@code initThird}、GYBK {@code initAnyThinkSDK}
     * —— 那两处是<b>初始化中枢</b>，no-op 会让调用方永远等不到回调，卡死启动流程。
     * 而 {@code EntityDelayLoadADHelper.ހ(Entity, List)} 是<b>纯插入动作</b>：
     * 把广告卡片放进展示列表，没有返回值、没有回调依赖（观测已确认它返回 void），
     * 不插就不显示，不会抬断任何状态机。
     *
     * <h3>证据（2026-10-06 真机观测，单变量）</h3>
     * 滚动信息流/回复列表时反复出现同一序列，共 4 次（对应 4 个广告位）：
     * <pre>
     *   ScopeAdManager.ׯ(bu5, 3000000) -> true          门禁通过
     *   ScopeAdManager.֏ / Ԯ(bu5, 3000, ...)           加载广告
     *   EntityDelayLoadADHelper.ހ(EntityCard, List)   ★ 插入
     * </pre>
     * 且位置计算器给出了插入位：{@code ؠ(FeedReply) -> 2}（回复列表第 2 条后，
     * 与用户截图里那条电商推广广告位置一致）、{@code ֈ(List) -> 13}（信息流）。
     */
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
     * <h3>未验证边界</h3>
     * 断开后原位是否留下空白，取决于列表里本来有没有占位项，需要真机肉眼核验。
     * 若出现空白，说明要连带移除占位项，而不是简单放行。
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

    private void noop(final ClassLoader cl, final String className, final String methodName, final String label) {
        try {
            Class<?> cls = Xp.findClassIfExists(className, cl);
            if (cls == null) {
                log("noop MISS: " + label + " —— 类不存在");
                return;
            }
            List<?> handles = Xp.hookAllNamed(cls, methodName, chain -> {
                try {
                    if (reserveCall(label + " [已拦截]")) {
                        logCall(label + " [已拦截]", summarizeArgs(chain.getArgs()), "blocked");
                    }
                } catch (Throwable ignored) {
                }
                return null;
            });
            if (handles.isEmpty()) {
                log("noop MISS: " + label + " —— 方法不存在");
            } else {
                log("noop OK: " + label + " (" + handles.size() + " 个重载) —— 广告插入已断开");
            }
        } catch (Throwable t) {
            log("noop FAILED: " + label + " : " + t);
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
     * / {@code getEntityId()} / {@code getTitle()}。
     *
     * 目的：找「怎么识别一张卡是广告」的判据。酷安的实体卡片靠 entityType/template
     * 区分普通帖子、广告、推荐 —— 而列表里的广告不是 {@code Ads} 本体（实测：
     * 传给 {@code EntityDelayLoadADHelper.ހ} 的是 {@code AutoValue_EntityCard}，
     * entityType 为 “card”），所以必须靠别的属性区分。
     */
    private static String describeEntity(Object o) {
        StringBuilder sb = new StringBuilder();
        appendProp(sb, o, "getEntityType");
        appendProp(sb, o, "getEntityTemplate");
        appendProp(sb, o, "getEntityId");
        appendProp(sb, o, "getTitle");
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
