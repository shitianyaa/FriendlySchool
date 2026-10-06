package com.yiran.friendlyschool.targets;

import com.yiran.friendlyschool.core.HookContext;
import com.yiran.friendlyschool.core.SchoolTargetBase;
import com.yiran.friendlyschool.core.Xp;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CoolApk（酷安）target —— 阶段：<b>广告链路观测</b>（pass-through，零行为改变）。
 *
 * <h3>为什么先观测</h3>
 * 酷安是<b>网易易盾整体加固</b>包，静态拿不到「类↔方法」绑定；广告子系统的
 * 方法/字段面已由上一阶段测绘拿到（见 {@code CoolApk/work/device/main_inventory.txt}）。
 * 但「拦哪里」还没定 —— 按 {@code apk-reverse} 的 R4，<b>拦错层不会报错，只会静默无效</b>。
 * 所以本阶段只挂 <b>pass-through 日志</b>：原方法照常执行、返回值原样透传，
 * 只在旁边记录「谁在什么时候被调用、参数里有没有广告实体」。
 *
 * <h3>这一层物理上不可能弄坏 App</h3>
 * 每个 hook 都是 {@code chain.proceed()} 后原样返回，任何异常都被吞掉并落日志。
 * 观测完就按证据决定真正的拦截点（数据层过滤 / 插入层 no-op），而不是盲挂。
 *
 * <h3>判定关键：Ads 实体</h3>
 * 广告在列表里就是 {@code com.coolapk.market.model.Ads} 实例。所以每条列表类日志都会
 * 报告 {@code ads=N} —— <b>N>0 的那一刻就是广告注入点</b>。这也让验证不需要截图。
 *
 * <h3>混淆方法名</h3>
 * 全部用 unicode 转义（反斜杠 + u + 四位十六进制）写在字符串字面量里 —— 因为 {@code ֏}
 * 的码位 U+058F 是货币符号，根本不是合法 Java 标识符，不能当标识符写。
 * 统一转义同时也避免了对源码文件编码的依赖。
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

    private static volatile boolean observed = false;

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
        log("install 被调用（观测模式，pass-through 零行为改变）pkg=" + ctx.packageName);
        ctx.onAppReady(new HookContext.AppReady() {
            @Override
            public void run(ClassLoader cl) {
                observe(cl);
            }
        });
    }

    // ------------------------------------------------------------ 观测层安装

    private void observe(ClassLoader cl) {
        if (observed) {
            return;
        }
        observed = true;

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

        log("--- 广告插入 / 位置计算 ---");
        obs(cl, C_DELAY_AD, M_05EF, "EntityDelayLoadADHelper.ׯ");
        obs(cl, C_DELAY_AD, M_0620, "EntityDelayLoadADHelper.ؠ");
        obs(cl, C_DELAY_AD, M_0588, "EntityDelayLoadADHelper.ֈ");
        obs(cl, C_DELAY_AD, M_058F, "EntityDelayLoadADHelper.֏");
        obs(cl, C_DELAY_AD, M_0780, "EntityDelayLoadADHelper.ހ");        obs(cl, C_DELAY_AD, M_0781, "EntityDelayLoadADHelper.ށ");

        log("--- UI 层插入（找占位）---");
        obs(cl, C_DELAY_AD, M_0528, "EntityDelayLoadADHelper.Ԩ");
        obs(cl, C_DELAY_AD, M_052C, "EntityDelayLoadADHelper.Ԭ");
        obs(cl, C_DELAY_AD, M_052D, "EntityDelayLoadADHelper.ԭ");
        obs(cl, C_DELAY_AD, M_052E, "EntityDelayLoadADHelper.Ԯ");

        log("=== 观测层安装结束 ===");
    }

    /**
     * 给某个类的所有同名重载挂 pass-through 观测。
     *
     * 行为保证：{@code proceed()} 的结果原样返回；记录过程中的任何异常都吞掉。
     * 所以这一层不可能改变 App 行为，也不可能因为日志失败把 App 拖死。
     */
    private void obs(final ClassLoader cl, final String className, final String methodName, final String label) {
        try {
            Class<?> cls = Xp.findClassIfExists(className, cl);
            if (cls == null) {
                log("obs MISS: " + label + " —— 类不存在");
                return;
            }
            List<?> handles = Xp.hookAllNamed(cls, methodName, chain -> {
                Object result;
                String in = null;
                try {
                    in = summarizeArgs(chain.getArgs());
                } catch (Throwable ignored) {
                }
                result = chain.proceed();
                try {
                    logCall(label, in, summarize(result));
                } catch (Throwable ignored) {
                }
                return result;
            });
            if (handles.isEmpty()) {
                log("obs MISS: " + label + " —— 方法不存在（名字或签名变了）");
            } else {
                log("obs OK: " + label + " (" + handles.size() + " 个重载)");
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
    private void noop(final ClassLoader cl, final String className, final String methodName, final String label) {
        try {
            Class<?> cls = Xp.findClassIfExists(className, cl);
            if (cls == null) {
                log("noop MISS: " + label + " —— 类不存在");
                return;
            }
            List<?> handles = Xp.hookAllNamed(cls, methodName, chain -> {
                try {
                    logCall(label + " [已拦截]", summarizeArgs(chain.getArgs()), "blocked");
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
     * 在列表处理入口把广告（sponsor）实体剔掉。
     *
     * <h3>为什么这才是治本（对比拦 ހ 的失败）</h3>
     * 实测：拦 {@code EntityDelayLoadADHelper.ހ}（「替换」那一步）会留下占位，
     * 表现为帖子与回复之间空几行 —— 因为列表里**本来就带着广告位的占位项**
     * （传给 ހ 的列表里全是 {@code AutoValue_HolderItem}）。
     * 而 {@code Ϳ} 是列表处理的入口，在这里把 sponsor 实体从返回列表里剔除，
     * 列表里根本没这个元素 → 不可能有空白。
     *
     * <h3>判据（真机实测，2026-10-06）</h3>
     * 酷安把广告叫 <b>sponsor</b>。广告卡的
     * {@code getEntityTemplate()} / {@code getEntityId()} 里带 sponsor：
     * <pre>
     *   .../card/sponsorCard/sponsorCard-feedList-headline-2
     *   .../card/feedDetailReplySponsorCard/sponsor-feedList-feedDetail/FEED_DETAIL_REPLY_SPONSOR_CARD
     * </pre>
     * 而普通卡片不含。所以判据 = template 或 id 含 “sponsor”（不区分大小写）。
     *
     * <h3>安全边界</h3>
     * 只在确实剔掉了元素时才返回新列表（否则原样返回原对象）；
     * 新列表是 ArrayList，调用方只需要 List 接口；任何异常都吞掉并落日志，
     * 宁可漏拦也不能把列表处理搞挂。
     */
    private void filterSponsors(final ClassLoader cl, final String className, final String methodName, final String label) {
        try {
            Class<?> cls = Xp.findClassIfExists(className, cl);
            if (cls == null) {
                log("filter MISS: " + label + " —— 类不存在");
                return;
            }
            List<?> handles = Xp.hookAllNamed(cls, methodName, chain -> {
                Object result = chain.proceed();
                try {
                    if (result instanceof List) {
                        List<?> in = (List<?>) result;
                        java.util.ArrayList<Object> keep = new java.util.ArrayList<Object>(in.size());
                        int removed = 0;
                        for (Object e : in) {
                            if (isSponsor(e)) {
                                removed++;
                                continue;
                            }
                            keep.add(e);
                        }
                        if (removed > 0) {
                            logOnce("filtered " + removed + " sponsor(s) in " + label
                                    + " (" + in.size() + " -> " + keep.size() + ")");
                            return keep;
                        }
                    }
                } catch (Throwable t) {
                    log("filter error in " + label + ": " + t);
                }
                return result;
            });
            if (handles.isEmpty()) {
                log("filter MISS: " + label + " —— 方法不存在");
            } else {
                log("filter OK: " + label + " (" + handles.size() + " 个重载)");
            }
        } catch (Throwable t) {
            log("filter FAILED: " + label + " : " + t);
        }
    }

    /** 广告判据：entityTemplate 或 entityId 含 sponsor（酷安把广告叫 sponsor）。 */
    private static boolean isSponsor(Object o) {
        if (o == null) {
            return false;
        }
        String t = stringProp(o, "getEntityTemplate");
        if (t != null && t.toLowerCase().contains("sponsor")) {
            return true;
        }
        String id = stringProp(o, "getEntityId");
        return id != null && id.toLowerCase().contains("sponsor");
    }

    /** 反射取一个 String 属性（带缓存），取不到返回 null。 */
    private static String stringProp(Object o, String method) {
        try {
            Class<?> c = o.getClass();
            String key = c.getName() + "#" + method;
            Object cached = ENTITY_METHODS.get(key);
            Method m;
            if (cached instanceof Method) {
                m = (Method) cached;
            } else if (cached == Boolean.FALSE) {
                return null;
            } else {
                try {
                    m = c.getMethod(method);
                    ENTITY_METHODS.put(key, m);
                } catch (Throwable t) {
                    ENTITY_METHODS.put(key, Boolean.FALSE);
                    return null;
                }
            }
            Object v = m.invoke(o);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void logCall(String label, String in, String out) {        Integer n = CALLS.get(label);
        int c = n == null ? 0 : n;
        if (c >= MAX_CALLS_PER_METHOD) {
            if (c == MAX_CALLS_PER_METHOD) {
                CALLS.put(label, c + 1);
                Xp.log("酷安 | hit " + label + " —— 已达 " + MAX_CALLS_PER_METHOD + " 条上限，后续不再记录");
            }
            return;
        }
        CALLS.put(label, c + 1);
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
    private static final Map<String, Object> ENTITY_METHODS =
            Collections.synchronizedMap(new HashMap<String, Object>());

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
        try {
            Class<?> c = o.getClass();
            String key = c.getName() + "#" + method;
            Object cached = ENTITY_METHODS.get(key);
            Method m;
            if (cached instanceof Method) {
                m = (Method) cached;
            } else if (cached == Boolean.FALSE) {
                return;
            } else {
                try {
                    m = c.getMethod(method);
                    ENTITY_METHODS.put(key, m);
                } catch (Throwable t) {
                    ENTITY_METHODS.put(key, Boolean.FALSE);
                    return;
                }
            }
            Object v = m.invoke(o);
            if (v != null) {
                String s = String.valueOf(v);
                if (s.length() > 40) {
                    s = s.substring(0, 40) + "...";
                }
                sb.append('/').append(s);
            }
        } catch (Throwable ignored) {
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
