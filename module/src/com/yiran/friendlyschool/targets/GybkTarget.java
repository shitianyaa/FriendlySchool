package com.yiran.friendlyschool.targets;

import com.yiran.friendlyschool.core.HookContext;
import com.yiran.friendlyschool.core.SchoolTargetBase;
import com.yiran.friendlyschool.core.Xp;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

/**
 * 光影边框（GYBK · com.dengziwl.bk）净化 target。
 *
 * 目标形态（2026-10-04 审计）：
 * Flutter 宿主 + 8 dex，无加固；会员判定与广告展示开关全部收敛在一条
 * 「SharedPreferences 键 vip_type（"PAID"/"FREE"）」上：
 *
 *   Dart: SpUtil.getString("vip_type")=="PAID"
 *       → MemberService.get:isVip / LifecycleManager._isVipUser /
 *         TakuAdsManage._isVip / TemplateExportOverlayCoordinator.get:isVipUser
 *       → 决定：开屏广告（LifecycleManager._showSplashAd）、导出页原生广告
 *         （canShowExportNativeAd/_checkExportNativeReady/VideoExportOverlay）等 29+ 处
 *
 * 而这个键在 Java 层的读写全部经过 Flutter 官方插件（跨版本稳定的公开 API）：
 *
 *   io.flutter.plugins.sharedpreferences.LegacySharedPreferencesPlugin
 *     - getAll(String, List)   → Dart 首次 getInstance 时整包缓存（含断网/首装场景）
 *     - setString(String, ...) → 服务端 /member/flutter/info 回写 vip_type 时落盘
 *   存储文件：shared_prefs/FlutterSharedPreferences.xml（类内常量 SHARED_PREFERENCES_NAME）。
 *
 * 所以 hook 这两个方法 = 一处断全局：
 *   getAll：把 Map 里的 vip_type 改成 "PAID"（Dart 缓存从源头就是会员）；
 *   setString：key 为 vip_type 时把值改成 "PAID" 再放行 —— 服务端每轮回写
 *   "FREE" 都会被就地改写，Dart 内存缓存随之更新，覆盖反而成了我们的帮手。
 *
 * 两处都只精确匹配键名 vip_type，其余键（登录态、模板配置等）原样透传，
 * 保证对 App 其它功能零影响（单变量可控）。
 *
 * 广告侧（组 2，只保留安全拦截）：
 *   com.fl.saas.config.utils.LoadConfigUtil#getRequestData() no-op ——
 *   拔掉「裸 IP http://39.107.124.86 明文上报 root/proxy/usb/debug 指纹 +
 *   无校验接收 AES dex 并 DexClassLoader 加载」的远程代码执行通道
 *   （REPORT §3.1，审计里唯一的"高危"）。这是异步上报，不阻塞主流程。
 *
 * **明确不做：不拦任何 SDK 初始化。** 首版还 no-op 了 TopOn 的 initAnyThinkSDK，
 * 结果首次启动点同意后卡在启动页（Dart 侧等初始化回调永远等不到）——
 * 与易校园 v1.0 开屏卡死同型。去广告靠会员判定（组 1）已足够，详见 installAdHooks 注释。
 *
 * 明确不动：
 *   - MyApplication.configureAdPrivacy()（开发者的合规代码，误杀反而放大采集面）；
 *   - 支付/登录/推送（alipay / wxapi / tauth / getui）—— 避免易校园 initThird
 *     整体 no-op 导致微信支付闪退的教训重演。
 *
 * 时序说明：LegacySharedPreferencesPlugin 在 Flutter 引擎注册插件时才装载，
 * 早于本 hook 安装的类加载时机不可控，因此统一走 ctx.onAppReady
 * （Application attach 之后）再找类，与 WakeUpTarget 同相位。
 */
public class GybkTarget extends SchoolTargetBase {

    private static final String TARGET_PKG = "com.dengziwl.bk";
    private static volatile boolean hooksInstalled = false;

    /**
     * 目标键：会员判定唯一事实源。
     *
     * **真机实测（2026-10-04，v2.2 首轮验收）**：Java 层（shared_preferences 插件）
     * 存的键名带 Flutter 前缀 —— 设备上 shared_prefs/FlutterSharedPreferences.xml
     * 里是 {@code flutter.vip_type}，不是 Dart 源码里看到的 {@code vip_type}。
     * Dart 侧 _getSharedPreferencesMap 读回来时会 substring(8) 去掉 "flutter." 前缀，
     * 所以 Dart 层看到的是无前缀名。
     *
     * 本 hook 挂在 **Java 层**，必须匹配带前缀的真名；同时保留无前缀形态做兼容
     * （防 Dart 侧版本差异 / 前缀变更）。首轮用无前缀名导致 getAll 只是往 Map 里
     * 新增了一个 Dart 侧读不到的空串键，会员判定完全没生效（见日志
     * "hit: getAll -> vip_type 重写 null -> PAID"）。
     */
    private static final String KEY_VIP_TYPE = "flutter.vip_type";
    private static final String KEY_VIP_TYPE_NO_PREFIX = "vip_type";
    /** 会员值（Dart 侧 MemberService.fetchMember 落盘的字面量）。 */
    private static final String VALUE_PAID = "PAID";

    /** hook 目标类名。 */
    private static final String CLS_SP_PLUGIN =
            "io.flutter.plugins.sharedpreferences.LegacySharedPreferencesPlugin";
    private static final String CLS_FL_CONFIG =
            "com.fl.saas.config.utils.LoadConfigUtil";

    private static final String NAME = "光影边框";

    // ------------------------------------------------------------ target 声明

    @Override
    public String shortName() {
        return NAME;
    }

    @Override
    public String packageName() {
        return TARGET_PKG;
    }

    // ------------------------------------------------------------ hook 安装

    @Override
    public void install(final HookContext ctx) {
        if (hooksInstalled) {
            return;
        }
        hooksInstalled = true;

        ctx.onAppReady(new HookContext.AppReady() {
            @Override
            public void run(ClassLoader cl) {
                installVipHooks(cl);
                installAdHooks(cl);
            }
        });
    }

    /**
     * 组 1 · 会员判定（主）：getAll 改返回 Map + setString 改写入值。
     *
     * 两个 hook 各自独立 try/catch：一个装不上不影响另一个 ——
     * getAll 是"读侧兜底"（断网/首装/服务端不可达时唯一生效的路径），
     * setString 是"写侧持续保障"（联网时服务端每轮回写的覆盖点），
     * 两者缺一会出现"断网无广告、联网广告回来"的半吊子形态，所以
     * 装不上必须各自出声。
     */
    private void installVipHooks(ClassLoader cl) {
        Class<?> plugin;
        try {
            plugin = Xp.findClass(CLS_SP_PLUGIN, cl);
        } catch (Throwable t) {
            log("hook FAILED: 会员判定 " + CLS_SP_PLUGIN + " 类不可达 : " + t);
            return;
        }

        // 1) getAll(String, List)：Dart 首次缓存整包读取 —— 返回前改 Map。
        //    按名字 hook 全部重载（2026-10-04 review：首版"循环只留最后一个"在多重载时会漏挂）。
        try {
            int count = 0;
            for (Method m : plugin.getDeclaredMethods()) {
                if (!"getAll".equals(m.getName())) {
                    continue;
                }
                Xp.hook(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (result instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<Object, Object> map = (Map<Object, Object>) result;
                            // 首次调用出声一次（无论是否需改写）：证明读侧通路是活的而不是静默失效。
                            logOnce("getAll 通路验证，当前 " + KEY_VIP_TYPE + "=" + map.get(KEY_VIP_TYPE));
                            // 复制 keySet 再改值：避免遍历中 put 触发 ConcurrentModificationException。
                            for (Object k : new ArrayList<Object>(map.keySet())) {
                                if (isVipKey(k)) {
                                    Object cur = map.get(k);
                                    if (!VALUE_PAID.equals(cur)) {
                                        logOnce("hit: getAll -> " + k + " 重写 " + cur + " -> " + VALUE_PAID);
                                    }
                                    map.put(k, VALUE_PAID);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        // 改写失败就算了，绝不能把异常抛回 Dart 侧（若插件将来返回不可变 Map，put 会抛）。
                        log("getAll 改写异常: " + t);
                    }
                    return result;
                });
                count++;
            }
            if (count == 0) {
                throw new NoSuchMethodException(CLS_SP_PLUGIN + "#getAll");
            }
            log("hook installed: 会员判定(读) " + CLS_SP_PLUGIN + "#getAll (" + count + " overloads)");
        } catch (Throwable t) {
            log("hook FAILED: 会员判定(读) " + CLS_SP_PLUGIN + "#getAll : " + t);
        }

        // 2) setString(String, String)：服务端回写覆盖点 —— 落盘前改值。
        try {
            int count = 0;
            for (Method m : plugin.getDeclaredMethods()) {
                if (!"setString".equals(m.getName()) || m.getParameterTypes().length != 2) {
                    continue;
                }
                Xp.hook(m, chain -> {
                    try {
                        Object[] args = chain.getArgs().toArray();
                        if (args.length == 2
                                && isVipKey(args[0])
                                && !VALUE_PAID.equals(args[1])) {
                            logOnce("hit: setString -> " + args[0] + " 改写 " + args[1] + " -> " + VALUE_PAID);
                            args[1] = VALUE_PAID;
                            return chain.proceed(args);
                        }
                    } catch (Throwable t) {
                        log("setString 改写异常: " + t);
                    }
                    return chain.proceed();
                });
                count++;
            }
            if (count == 0) {
                throw new NoSuchMethodException(CLS_SP_PLUGIN + "#setString(String,String)");
            }
            log("hook installed: 会员判定(写) " + CLS_SP_PLUGIN + "#setString (" + count + " overloads)");
        } catch (Throwable t) {
            log("hook FAILED: 会员判定(写) " + CLS_SP_PLUGIN + "#setString : " + t);
        }
    }

    /**
     * 组 2 · 广告 SDK（辅）：只保留 fl.saas 的配置请求拦截（拔 RCE 投毒通道）。
     *
     * **重要教训（2026-10-04 真机实测）：不要拦 SDK 初始化。**
     * 首版还 no-op 了 `com.anythink.flutter.init.ATAdInitManger#handleMethodCall` 的
     * `initAnyThinkSDK` 分支（并回 `Boolean.TRUE` 告知 Dart“已处理”），结果：
     * **首次启动点同意隐私政策后卡在启动页**（进程存活、CPU 0%、无 ANR、无崩溃）——
     * Dart 侧 `ATInit.initAnyThinkSDK` 在等初始化回调，而 SDK 实际从未初始化，
     * 回调永远不来，启动流程就挂在那里。
     *
     * 这与易校园 v1.0 的“开屏卡死”同型（拦了官方兑底回调依赖的广告请求 → 永远等不到），
     * 也与 JMComic3 定下的纪律一致：**拦出口不拦请求、不抬断 App 状态机**。
     *
     * 而去广告本身不靠它 —— 会员判定（组 1）已让 `isVipUser` 为真，广告位自然不展示
     * （用户实测“没有广告了”）。所以初始化拦截既非必要、又有害，已删除。
     */
    private void installAdHooks(ClassLoader cl) {
        // com.fl.saas LoadConfigUtil#getRequestData()
        // 注：这是**异步上报**（fire-and-forget），不阻塞主流程，no-op 不会卡状态机；
        // 且它是审计里唯一的“高危”（裸 IP 明文接收无校验 dex）通道，保留拦截。
        try {
            Class<?> cls = Xp.findClassIfExists(CLS_FL_CONFIG, cl);
            if (cls == null) {
                log("hook FAILED: fl.saas 配置 " + CLS_FL_CONFIG + " 类不存在");
            } else {
                int count = 0;
                for (Method m : cls.getDeclaredMethods()) {
                    if (!"getRequestData".equals(m.getName())) {
                        continue;
                    }
                    Xp.hook(m, chain -> {
                        logOnce("noop fl.saas getRequestData（裸 IP 配置/RCE 通道已断）");
                        return null;
                    });
                    count++;
                }
                if (count > 0) {
                    log("hook installed: fl.saas 配置 " + CLS_FL_CONFIG + "#getRequestData (" + count + " overloads)");
                } else {
                    log("hook FAILED: fl.saas 配置 " + CLS_FL_CONFIG + "#getRequestData 方法不存在");
                }
            }
        } catch (Throwable t) {
            log("hook FAILED: fl.saas 配置 " + CLS_FL_CONFIG + "#getRequestData : " + t);
        }
    }

    /**
     * 判断一个 SP 键是否为会员键。
     *
     * 同时接受带前缀（Java 层真名 flutter.vip_type）与无前缀（Dart 侧名字 vip_type）
     * 两种形态 —— 见 {@link #KEY_VIP_TYPE} 的实测说明。
     */
    private static boolean isVipKey(Object key) {
        return KEY_VIP_TYPE.equals(key) || KEY_VIP_TYPE_NO_PREFIX.equals(key);
    }
}

