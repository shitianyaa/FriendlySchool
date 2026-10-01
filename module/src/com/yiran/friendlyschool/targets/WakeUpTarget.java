package com.yiran.friendlyschool.targets;

/*
 * WakeUp课程表（com.suda.yzune.wakeupschedule）target。
 *
 * 代码来自独立的 WakeUpClean 模块（com.yiran.wakeupclean，已随本次合并下线），
 * 逐行移植、hook 调用点未改；日志与 hook 工具的公共部分在 core.SchoolTargetBase，
 * Application.attach/onCreate 的双挂钩在 core.HookContext，分发在 core.Targets。
 * 移植来源与逐点 diff 由开发记录保存（未随本仓库发布）。
 */

import com.yiran.friendlyschool.core.HookContext;
import com.yiran.friendlyschool.core.SchoolTargetBase;
import com.yiran.friendlyschool.core.Xp;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.io.Serializable;
import java.lang.reflect.Field;

/**
 * WakeUp课程表 (com.suda.yzune.wakeupschedule) 净化模块。
 *
 * 设计原则：只关掉 App 自己的“要不要展示”决策，不替换数据、不伪造账号状态、不碰 SDK 内部。
 * 每一处 hook 的调用方都已在样本 6.5.0 (versionCode 540) 上静态确认过：
 *
 *   E1 aaa.utils.OooOOOO.OooO0OO()Z   ← 仅被 SplashActivity 调用          → 开屏广告
 *   E2 aaa.utils.OooOOOO.OooO00o()Z   ← 仅被 aaa.utils.OooOO0$OooO00o 调用 → 热启动插播广告
 *   E3 aaa.utils.OooOOOO.OooO0O0()Z   ← 仅被 widget.StoreLayout 调用      → 商城位广告
 *   E4 schedule.home.HomeBannerController.OooOo00()V   ← ScheduleActivity.onCreate 构造后调用 → 首页 banner
 *   E5 manage.HomeDialogHelper.OooOOO0(ComponentActivity, Function0)V     → 首页弹窗（服务端 Bannerconf.homePopupBanner）
 *   E6 Activity.onCreate + Intent extra "HybridParamsInfo".inputUrl       → 卖课/会员/收银台 H5 全部入口
 *   E7 同一个 Activity.onCreate 钩子里的类名判定 (mine.VipExclusiveActivity) → 会员专区
 *   L1 同一个 Activity.onCreate 钩子里的类名判定 (aaa.activity.login.*)      → 免登录（不弹登录页）
 *   L2 aaa.widget.MineNewUserLoginView.refreshLoginUI/refreshUnLoginUI      → 隐藏“我的”页登录卡片
 *   L3 com.dx.common.ui.dialog.CommonDialog.show + 文案匹配                → 会员/登录提示弹窗（单点覆盖全部，含课表页“简洁模式为VIP权益…去开通”）
 *   L4 o0O0OOoo.OooOOO 全构造器 + List 过滤                              → 剪掉“学习/表助手/我的”底部标签
 *   L5 mine.MineActivity                                                  → 堵死“我的”页的其它入口（简洁模式右上角那个图标开的就是它）
 *   L6 ScheduleFragment.oo0O()                                            → 把简洁模式右上角的“我的”图标也隐掉
 *   L7 settings.ScheduleNotifyActivity                                    → 隐掉“普通提醒”（实为推送提醒 pushNotifyLayout，需登录）
 *   L8 widget.MainAiTitleTabView$OooO0OO.onApplyWindowInsets              → 把底栏调高（高度由这个 insets 监听器写死为 44dp+inset）
 *
 * 明确不做的三件事，以及原因：
 *   - 不改 UserInfo.vipStatus / vipEndTime / isPermanentVip。这些字段来自服务端
 *     (HYWakeup_getuserinfoModel$Result)，本地改只会得到“显示会员但点进去没权限”的坏状态。
 *   - 不让 isLogin (aaa.utils.o00O0000.OooOO0) 返回 true。那是伪造会话：UserInfo 仍为 null，
 *     所有服务端接口照样失败，但界面以为自己已登录——比未登录更糟。
 *   - 不改 ShowLoginAction 的返回值。它给 H5 回的是 success 标志，回成功等于对 H5 诈称已登录，
 *     回失败语义不明；而会调它的那几个 H5 页（学习/AI/会员/课程）已经被 E6 拦掉了。
 *     这里只在 Activity 层拦住登录页本身，对调用方不做任何欺骗。
 *
 * 免登录的代价（必须知道）：不上作业帮账号 = 课表只存在本机。卸载、清数据、换设备都拿不回来，
 * 云同步用不了。课表本身的导入/增删课/提醒/小组件不依赖登录（已确认 SplashActivity 与
 * ScheduleActivity 都不引用登录页）。
 *
 * 作用范围：LSPosed Manager 里把 scope 只勾 com.suda.yzune.wakeupschedule。
 */
public class WakeUpTarget extends SchoolTargetBase {

    /** 在模块内再做一次包名收口；真正的门是 LSPosed 的 scope。 */
    private static final String TARGET_PKG = "com.suda.yzune.wakeupschedule";

    /** 每次 hook 命中都打日志，用来在设备上逐条确认行为确实变了。 */

    /** 应用自身类名前缀。 */
    private static final String APP = "com.suda.yzune.wakeupschedule.";

    /** 被拦截的 H5 路由片段（App 自身代码 + assets/router_v3.json 里的实测取值）。 */
    private static final String[] BLOCKED_URL_PARTS = new String[] {
            // 会员 / 收银台 / 续费 / 微信签约代扣  (router_v3.json 模块 vip-wakeup)
            "vip-wakeup", "vip.wakeup.fun",
            // 课程售卖与付费内容 (router_v3.json 模块 wakeup-core)
            "pages/buy/", "course-buy-dialog", "course-order-payment",
            "course-paid-qbank", "course-paid-video", "course-docs-preview",
            "course-my", "courseMaterial", "materialDetail",
            "studyHome", "studySpecialMaterial", "purchase-list",
            // 拉新返利
            "inviteRecord", "wakeupInvitation",
            // 搜题 / AI / 收藏
            "aiAssistant", "singleResult", "fullResult", "searchHistory", "myCollection",
            // 用户画像采集
            "personaInfoCollect",
            // 作业帮“更多”页
            "pages/more/index",
    };

    /** E7：会员专区页。按类名判断，不提前加载这个类。 */
    private static final String VipExclusive = APP + "mine.VipExclusiveActivity";

    /**
     * L5："我的"页的 Activity 宿主。
     * 剪掉底部标签后仍有两个入口能进来：简洁模式右上角的人形图标，以及外部 Intent。
     * 实测 activity 栈顶确实是 `com.suda.yzune.wakeupschedule/.mine.MineActivity`。
     * 课表设置/时间设置/课表管理都能从课表页的 ••• 菜单进，所以堵掉它不丢功能。
     */
    private static final boolean BLOCK_MINE_ACTIVITY = true;

    private static final String MineActivity = APP + "mine.MineActivity";

    /**
     * L6：简洁模式右上角的“我的”人形图标（id = scheduleMine）。
     * ScheduleFragment.oo0O() 是它的访问器，开关简洁模式的方法会调它。
     * 在访问器返回后用 post 设 GONE，让改动落在下一帧，盖过调用方随后的 setVisibility。
     */
    private static final boolean HIDE_SCHEDULE_MINE_ICON = true;

    /**
     * L9：顶栏礼包/营销图标（id = scheduleIcon，对应方法 o00OO0o）。
     * 点击后打开营销活动 H5（o0oOo0O0）。通过拦截访问器设 GONE、拦截数据装载
     * o00O0Ooo 为 DO_NOTHING、拦截营销入口 o00oO0O0 以及拦截模式切换 o00O0OO0/o00O00o0
     * 防止被重设为 VISIBLE，彻底清除顶部工具栏中的礼包图标，同时断掉浮窗商城与信息流广告。
     */
    private static final boolean HIDE_SCHEDULE_GIFT_ICON = true;

    /**
     * L7：上课提醒页里的“普通提醒”——它就是推送提醒（布局 id `pushNotifyLayout`，
     * 子开关 `switch_push_notify`），走服务端推送，所以处理器 o0000OO 里有 isLogin 门禁。
     * 不开作业帮账号就用不了，所以干脆把整行隐掉，而不是留一个点了没反应的开关。
     * 倒计时提醒（switch_notify / course_reminder）是本地提醒，保留不动。
     */
    private static final boolean HIDE_PUSH_NOTIFY_ROW = true;

    /**
     * L8：底栏高度。
     *
     * 耗时点在这里：底栏高度不是 layout 里的 wrap_content 决定的，而是
     * `MainAiTitleTabView$OooO0OO.onApplyWindowInsets` 写死的：
     *     lp.height = dp(0x2c = 44) + (ownsNavigationInsets ? 导航栏底部inset : 0)
     * 它在 insets 派发时（attach 之后）执行，所以先挂 setViewPager、
     * setMinimumHeight 或直接改 LayoutParams 都会被它盖回去（实测目标 192px、实际 132px）。
     *
     * 所以挂它本身，并在算目标值时把 inset 保留下来（绝对计算，
     * 重复触发也不会越加越大）。
     */
    private static final boolean RAISE_TAB_BAR = true;

    private static final int TAB_BAR_MIN_HEIGHT_DP = 64;

    /** App 自己写在 insets 监听器里的基础高度（dex 里的 0x2c）。 */
    private static final int TAB_BAR_ORIGINAL_DP = 44;

    /**
     * 免登录：不让作业帮账号登录页弹出来。
     * 只拦这三个精确类名——课表导入用的 schedule_import.LoginWebActivity（学校教务系统登录）
     * 名字里也有 Login，必须不能用子串匹配。
     * 实测全库只有 aaa.utils.o00O0000 引用这三个类，拦在 Activity 层可覆盖全部入口。
     */
    private static final boolean NO_LOGIN_PROMPT = true;

    private static final String[] LOGIN_ACTIVITIES = new String[] {
            APP + "aaa.activity.login.LoginActivity",
            APP + "aaa.activity.login.SYLoginActivity",
            APP + "aaa.activity.login.VerificationCodeLoginActivity",
    };

    /** 是否隐藏“我的”页里的登录/换绑卡片。 */
    private static final boolean HIDE_LOGIN_CARD = true;

    private static final String TOAST_LOGIN_TEXT = "已禁用登录";

    /**
     * L3：会员/登录提示弹窗统一拦截。
     *
     * App 里这类提示都走同一个弹窗框架 com.dx.common.ui.dialog.CommonDialog：
     * 实例构造好文案后再调 show()。所以把 show() 里读自己的 String 字段、按文案匹配，
     * 一个点就能盖掉：“我的”页的“请先登录”、下课提醒的“请先登录”、
     * 课表页“简洁模式为VIP权益…去开通”（ScheduleFragment.o0O0o）。
     *
     * 不能直接封 CommonDialog.show()：课表核心也在用它（AddCalendarActivity、
     * AddCourseFragment、EditTimeSettingActivity、清缓存确认…）。实测这些核心弹窗的文案是
     * “退出日程编辑”“上课时间未填写完整”“需要重新登录才可查看”等，与下面三个标记均不匹配。
     */
    private static final boolean SUPPRESS_MEMBER_PROMPTS = true;

    private static final String[] PROMPT_MARKERS = new String[] {
            "请先登录",     // 该功能需要登录后使用，请先登录
            "VIP权益",      // 简洁模式为VIP权益，开通VIP…
            "开通VIP",
    };

    private static final String TOAST_MEMBER_TEXT = "该功能已移除";

    /**
     * 保留清单——写在这里是为了让“别拦什么”变成可审查的代码，而不是注释里的口头承诺。
     * 小组件设置、学校/年级设置、协议与政策页、权限中心都是课表流程的一部分。
     */
    @SuppressWarnings("unused")
    private static final String[] KEPT_URL_PARTS = new String[] {
            "pages/widget/index", "schoolSetting", "agreement", "policy",
            "serviceProtocol", "permission-center", "user-info-export",
    };

    /** 拦到卖课/会员页时给一次 Toast，避免用户以为点坏了。 */
    private static final boolean TOAST_ON_BLOCK = true;

    private static final String TOAST_TEXT = "该功能已移除";

    /**
     * 剪标签之前曾经把这一项默认关掉，理由是“担心 ScheduleActivity.onCreate 里有硬编码的
     * setCurrentItem 索引”。已实测不成立：整个 ScheduleActivity 只有一处 setCurrentItem，
     * 参数是方法参数；索引由 adapter.OooO00o(tabId) 线性查找算出；
     * 且所有 o0000oO0(tabId) 调用点传的都是 "schedule"，没有流程跳向 learn/assistant/mine。
     */
    private static final boolean TRIM_LEARN_TABS = true;

    /**
     * 要从底部标签里剔掉的 Fragment：
     *   aaa.learn.Oooo000            学习（卖课 hub）
     *   aaa.learn.AssistantFragment  表助手
     *   aaa.fragment.MineFragment    我的
     *
     * “我的”页整页都是 Compose 顺序调用（没有 LazyColumn、没有可过滤的 item 列表，
     * 已查 MineFragment$OooO0OO 与 o0O0oO0o.o0000OO0）。里面的会员卡片 / 9.9元包年 /
     * 会员专属 / 我购买的课程 / 电脑端扫码 / 我的收藏 在 LSPosed 路线下没法单项剪除，
     * 只能整页去掉。
     * 去掉不丢功能：课表页（ScheduleFragment）自己就能进 SettingsActivity、
     * ScheduleSettingsActivity、ScheduleManageActivity、TimeSettingsActivity（已逐一核实）。
     * 想恢复“我的”只需从下面这个数组里删掉那一行。
     */
    private static final String[] REMOVED_TABS = new String[] {
            APP + "aaa.learn.Oooo000",
            APP + "aaa.learn.AssistantFragment",
            APP + "aaa.fragment.MineFragment",
    };

    // ------------------------------------------------------------ target 声明

    private static final String NAME = "WakeUp课程表";

    @Override
    public String shortName() {
        return NAME;
    }

    @Override
    public String packageName() {
        return TARGET_PKG;
    }

    /**
     * 原 WakeUpClean 的 handleLoadPackage 内容，只是去掉了包名门禁
     * （分发已经由 FriendlySchoolHook + Targets 做完）。
     */
    @Override
    public void install(final HookContext ctx) {
        // E6/E7/L1 的 Activity.onCreate 收口点必须在 App 起 Activity 之前挂好
        hookHybridBlocker();

        ctx.onAppReady(new HookContext.AppReady() {
            @Override
            public void run(ClassLoader appClassLoader) {
                // 与原实现一致：用 LoadPackageParam.classLoader
                installAppHooks(ctx.classLoader);
            }
        });
    }

    // ------------------------------------------------------- 广告门禁（E1-E3）

    /** 原来只从 attach 进来一次；合并后 attach/onCreate 两条路径都会回调，这里补幂等。 */
    private volatile boolean appHooksInstalled = false;

    private void installAppHooks(ClassLoader cl) {
        if (appHooksInstalled) {
            return;
        }
        appHooksInstalled = true;
        // E1 开屏广告：SplashActivity 检查 !OooO0OO() 时走它自带的 "not showAd,jumpMainPage" 分支。
        hookFalse(cl, APP + "aaa.utils.OooOOOO", "OooO0OO", "开屏广告");

        // E2 热启动插播广告：aaa.utils.OooOO0$OooO00o 用它决定是否拉起 ResumeSplashActivity。
        hookFalse(cl, APP + "aaa.utils.OooOOOO", "OooO00o", "插播广告");

        // E3 商城位广告：widget.StoreLayout 用它决定是否请求广告。
        hookFalse(cl, APP + "aaa.utils.OooOOOO", "OooO0O0", "商城位广告");

        // E4 首页 banner（服务端 Bannerconf.homeDiversionBanner / homeIconBanner）
        hookNoop(cl, APP + "schedule.home.HomeBannerController", "OooOo00",
                new Object[0], "首页 banner");

        // E5 首页弹窗（服务端 Bannerconf.homePopupBanner）
        hookNoop(cl, APP + "manage.HomeDialogHelper", "OooOOO0",
                new Object[] { "androidx.activity.ComponentActivity", "kotlin.jvm.functions.Function0" },
                "首页弹窗");

        // E7 会员专区：不单独 findAndHookMethod 加载 Activity 类（那会在 Application.attach 阶段
        // 提前装载 Compose 依赖），而是在 E6 的 Activity.onCreate 钩子里按类名判断。
        // L2 隐藏“我的”页登录卡片
        if (HIDE_LOGIN_CARD) {
            installLoginCardHider(cl);
        }

        // L3 会员/登录提示弹窗
        if (SUPPRESS_MEMBER_PROMPTS) {
            installPromptSuppressor(cl);
        }

        // L6 简洁模式右上角的“我的”图标
        if (HIDE_SCHEDULE_MINE_ICON) {
            installScheduleMineHider(cl);
        }

        // L9 顶栏礼包/营销图标
        if (HIDE_SCHEDULE_GIFT_ICON) {
            installScheduleGiftIconHider(cl);
        }

        // L8 底栏高度
        if (RAISE_TAB_BAR) {
            installTabBarHeight(cl);
        }

        if (TRIM_LEARN_TABS) {
            installTabTrim(cl);
        }

        // C组：底层广告子系统与隐私数据收集彻底灭活
        installSubsystemHooks(cl);
    }

    /**
     * C组：底层广告子系统与隐私信息收集彻底灭活。
     *
     * 1. C1: AdvertisementSdkInitTask -> run() no-op (FastAd 广告聚合核心不加载)
     *    双保险: o0O00000.OooO0O0 -> OooOOo() no-op
     * 2. C2: AdConfigTask -> run() no-op (不向远端请求广告策略)
     * 3. C3: WeaponHI (快手反作弊武器库) -> 所有 init() no-op (彻底消灭 netstat/mount/scrcpy 扫描及僵尸进程)
     * 4. C4: OaidInitTask -> run() no-op (不采集厂商硬件 OAID)
     * 5. C5: TrackerInitTask -> run() no-op (不向 nlog.daxuesoutijiang.com 上报用户行为埋点)
     * 6. C6: RLogInitTask & LoggerInitTask -> run() no-op (停止远程日志与内部日志上报)
     */
    private void installSubsystemHooks(ClassLoader cl) {
        // C1: AdvertisementSdkInitTask (广告 SDK 初始化根任务) 与 FastAd 广告核心单例
        hookReplaceVoid(cl, "o0O0Ooo.Oooo0", "run", "C1 广告SDK初始化任务");
        hookReplaceVoid(cl, "o0O00000.OooO0O0", "OooOOo", "C1 FastAd广告核心单例");

        // C2: AdConfigTask (广告配置拉取任务)
        hookReplaceVoid(cl, "o0O0OoOo.OooOo00", "run", "C2 广告配置拉取任务");

        // C3: 快手反作弊武器库与僵尸进程探测彻底阻断 (WeaponHI & dg)
        try {
            Class<?> dgCls = Xp.findClassIfExists("com.kuaishou.weapon.p0.dg", cl);
            if (dgCls != null) {
                for (java.lang.reflect.Method m : dgCls.getDeclaredMethods()) {
                    Class<?> ret = m.getReturnType();
                    if (ret == String.class) {
                        Xp.hook(m, chain -> "");
                    } else if (ret == int.class || ret == Integer.class) {
                        Xp.hook(m, chain -> 0);
                    } else if (ret == java.util.Set.class) {
                        Xp.hook(m, chain -> java.util.Collections.emptySet());
                    } else if (ret == org.json.JSONObject.class) {
                        Xp.hook(m, chain -> new org.json.JSONObject());
                    } else {
                        Xp.hook(m, chain -> null);
                    }
                }
                logOnce("hook installed: C3 快手武器库 dg#allMethods 探测置空");
            }
        } catch (Throwable t) {
            log("C3 dg hook FAILED: " + t);
        }

        try {
            Class<?> weaponCls = Xp.findClassIfExists("com.kuaishou.weapon.p0.WeaponHI", cl);
            if (weaponCls != null) {
                for (java.lang.reflect.Method m : weaponCls.getDeclaredMethods()) {
                    Class<?> ret = m.getReturnType();
                    if (ret == void.class) {
                        Xp.hook(m, chain -> null);
                    } else if (ret == boolean.class || ret == Boolean.class) {
                        Xp.hook(m, chain -> Boolean.FALSE);
                    } else if (ret == String.class) {
                        Xp.hook(m, chain -> "");
                    } else {
                        Xp.hook(m, chain -> null);
                    }
                }
                logOnce("hook installed: C3 快手武器库 WeaponHI 全部方法置空");
            }
        } catch (Throwable t) {
            log("C3 WeaponHI hook FAILED: " + t);
        }

        // C4: OaidInitTask (OAID 采集任务)
        hookReplaceVoid(cl, "o0O0OoOo.oo000o", "run", "C4 OAID采集任务");

        // C5: TrackerInitTask (用户行为埋点任务)
        hookReplaceVoid(cl, "o0O0Ooo.oo000o", "run", "C5 用户行为埋点任务");

        // C6: RLogInitTask & LoggerInitTask (日志上报任务)
        hookReplaceVoid(cl, "o0O0Ooo.o00Ooo", "run", "C6 RLog日志任务");
        hookReplaceVoid(cl, "o0O0OoOo.o00Oo0", "run", "C6 Logger日志任务");

        // C7: FastAd 四大网盟聚合子入口彻底灭活 (百度, 穿山甲, 快手, 优量汇)
        hookReplaceAllNamed(cl, "com.fastad.baidu.FastAdBDManager", "initBaiduSDK", "C7 百度广告初始化");
        hookReplaceAllNamed(cl, "com.fastad.csj.FastAdCsjManager", "initCsjSDK", "C7 穿山甲广告初始化");
        hookReplaceAllNamed(cl, "com.fastad.ks.FastAdKsManager", "initKsSdk", "C7 快手广告初始化");
        hookReplaceAllNamed(cl, "com.fastad.ylh.FastAdYlhManager", "initYlhSDK", "C7 优量汇广告初始化");

        // C8: 快手原生 KsAdSDK 灭活
        try {
            Class<?> ksSdkCls = Xp.findClassIfExists("com.kwad.sdk.api.KsAdSDK", cl);
            if (ksSdkCls != null) {
                for (java.lang.reflect.Method m : ksSdkCls.getDeclaredMethods()) {
                    if ("init".equals(m.getName())) {
                        Xp.hook(m, chain -> Boolean.TRUE);
                    } else if ("start".equals(m.getName())) {
                        Xp.hook(m, chain -> null);
                    }
                }
                logOnce("hook installed: C8 KsAdSDK init/start 置空");
            }
        } catch (Throwable t) {
            log("C8 KsAdSDK hook FAILED: " + t);
        }

        // C9: DeviceIdTask (设备唯一指纹采集任务)
        hookReplaceVoid(cl, "o0O0OoOo.Oooo0", "run", "C9 设备指纹采集任务");

        // C10: DpSdkTask (DProtect 风控/反爬/设备指纹 SDK)
        hookReplaceVoid(cl, "o0O0OoOo.o000oOoO", "run", "C10 DProtect风控指纹任务");

        // C11: PayTask (支付 SDK 初始化)
        hookReplaceVoid(cl, "o0O0OoOo.o00oO0o", "run", "C11 支付SDK任务");

        // C12: PushInitDelayTask (推送 SDK 延迟初始化)
        hookReplaceVoid(cl, "o0O0Ooo0.Oooo000", "run", "C12 推送初始化任务");
    }

    /**
     * L6：挂钩 ScheduleFragment.oo0O()（scheduleMine 的访问器）。
     * 返回后用 post 设 GONE：调用方拿到 view 还会自己 setVisibility，
     * 同步改会被覆盖，丢到下一条消息里执行就赢了。
     */
    private void installScheduleMineHider(ClassLoader cl) {
        final String cls = APP + "schedule.ScheduleFragment";
        try {
            java.lang.reflect.Method m = Xp.findMethod(Xp.findClass(cls, cl), "oo0O");
            if (m == null) {
                throw new NoSuchMethodException(cls + "#oo0O");
            }
            Xp.hook(m, chain -> {
                Object result = chain.proceed();
                final android.view.View v = (android.view.View) result;
                if (v == null) {
                    return result;
                }
                logOnce("L6 hit: 隐掉简洁模式的“我的”图标 (scheduleMine)");
                v.post(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(android.view.View.GONE);
                    }
                });
                return result;
            });
            logOnce("hook installed: L6 ScheduleFragment#oo0O");
        } catch (Throwable t) {
            log("L6 hook FAILED: " + t);
        }
    }

    /**
     * L9：彻底清除课表页顶栏礼包营销图标（scheduleIcon）。
     *
     * 1. 访问器 o00OO0o() 强制设 GONE + post 设 GONE（防御其它地方意外获取并设 VISIBLE）。
     * 2. 数据装载 o00O0Ooo(HomeSourceBanner, String) 直接 replace 为 DO_NOTHING（不下载图片、不解析 Lottie）。
     * 3. 页面横幅/营销总入口 o00oO0O0(ScheduleFragment, Bannerconf) 直接 replace 返回 Unit
     *    （礼包图标、浮窗商城 StoreLayout、信息流广告全部不装载）。
     * 4. 模式切换 o00O0OO0(ScheduleFragment, boolean) 与 o00O00o0(boolean) 在 afterHookedMethod 里
     *    确保 scheduleMine 与 scheduleIcon 始终保持 GONE。
     */
    private void installScheduleGiftIconHider(ClassLoader cl) {
        final String cls = APP + "schedule.ScheduleFragment";

        // 1. 访问器 o00OO0o() -> 设 GONE
        try {
            java.lang.reflect.Method m = Xp.findMethod(Xp.findClass(cls, cl), "o00OO0o");
            if (m == null) {
                throw new NoSuchMethodException(cls + "#o00OO0o");
            }
            Xp.hook(m, chain -> {
                Object result = chain.proceed();
                final android.view.View v = (android.view.View) result;
                if (v == null) {
                    return result;
                }
                v.setVisibility(android.view.View.GONE);
                v.post(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(android.view.View.GONE);
                    }
                });
                logOnce("L9 hit: scheduleIcon 设 GONE");
                return result;
            });
            logOnce("hook installed: L9 ScheduleFragment#o00OO0o");
        } catch (Throwable t) {
            log("L9 o00OO0o hook FAILED: " + t);
        }

        // 2. 礼包数据渲染 o00O0Ooo(HomeSourceBanner, String) -> no-op
        try {
            Class<?> bannerCls = Xp.findClass(
                    APP + "aaa.model.Bannerconf$HomeSourceBanner", cl);
            java.lang.reflect.Method m = Xp.findMethod(Xp.findClass(cls, cl), "o00O0Ooo",
                    bannerCls, String.class);
            if (m == null) {
                throw new NoSuchMethodException(cls + "#o00O0Ooo");
            }
            Xp.hook(m, chain -> null);
            logOnce("hook installed: L9 ScheduleFragment#o00O0Ooo (banner loader noop)");
        } catch (Throwable t) {
            log("L9 o00O0Ooo hook FAILED: " + t);
        }

        // 3. 课表页营销总入口 o00oO0O0(ScheduleFragment, Bannerconf) -> 返回 Unit
        try {
            Class<?> fragCls = Xp.findClass(cls, cl);
            Class<?> bannerConfCls = Xp.findClass(
                    APP + "aaa.model.Bannerconf", cl);
            Class<?> unitCls = Xp.findClass("kotlin.o0ooOOo", cl);
            final Object unit = Xp.getStaticObjectField(unitCls, "OooO00o");

            java.lang.reflect.Method m = Xp.findMethod(fragCls, "o00oO0O0",
                    fragCls, bannerConfCls);
            if (m == null) {
                throw new NoSuchMethodException(cls + "#o00oO0O0");
            }
            Xp.hook(m, chain -> {
                logOnce("L9 hit: o00oO0O0 拦截（礼包/商城/信息流广告）");
                return unit;
            });
            logOnce("hook installed: L9 ScheduleFragment#o00oO0O0 (bannerconf receiver)");
        } catch (Throwable t) {
            log("L9 o00oO0O0 hook FAILED: " + t);
        }

        // 4. 模式切换 o00O0OO0(ScheduleFragment, boolean)
        try {
            Class<?> fragCls = Xp.findClass(cls, cl);
            java.lang.reflect.Method m = Xp.findMethod(fragCls, "o00O0OO0",
                    fragCls, boolean.class);
            if (m == null) {
                throw new NoSuchMethodException(cls + "#o00O0OO0");
            }
            Xp.hook(m, chain -> {
                Object result = chain.proceed();
                hideTopButtons(chain.getArg(0));
                return result;
            });
            logOnce("hook installed: L9 ScheduleFragment#o00O0OO0");
        } catch (Throwable t) {
            log("L9 o00O0OO0 hook FAILED: " + t);
        }

        // 5. 模式切换 o00O00o0(boolean)
        try {
            java.lang.reflect.Method m = Xp.findMethod(Xp.findClass(cls, cl), "o00O00o0",
                    boolean.class);
            if (m == null) {
                throw new NoSuchMethodException(cls + "#o00O00o0");
            }
            Xp.hook(m, chain -> {
                Object result = chain.proceed();
                hideTopButtons(chain.getThisObject());
                return result;
            });
            logOnce("hook installed: L9 ScheduleFragment#o00O00o0");
        } catch (Throwable t) {
            log("L9 o00O00o0 hook FAILED: " + t);
        }
    }

    private void hideTopButtons(Object fragment) {
        if (fragment == null) {
            return;
        }
        try {
            final android.view.View mine = (android.view.View) Xp.callMethod(fragment, "oo0O");
            if (mine != null) {
                mine.setVisibility(android.view.View.GONE);
                mine.post(new Runnable() {
                    @Override
                    public void run() {
                        mine.setVisibility(android.view.View.GONE);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        try {
            final android.view.View icon = (android.view.View) Xp.callMethod(fragment, "o00OO0o");
            if (icon != null) {
                icon.setVisibility(android.view.View.GONE);
                icon.post(new Runnable() {
                    @Override
                    public void run() {
                        icon.setVisibility(android.view.View.GONE);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * L8：挂钩底栏的 insets 监听器，把 44dp 换成 TAB_BAR_MIN_HEIGHT_DP。
     * inset 用“当前高度 - 44dp”反推，然后算绝对值，所以重复触发安全。
     */
    private void installTabBarHeight(ClassLoader cl) {
        final String listenerCls = APP + "widget.MainAiTitleTabView$OooO0OO";
        try {
            Class<?> listener = Xp.findClass(listenerCls, cl);
            Class<?> viewCls = Xp.findClass("android.view.View", cl);
            Class<?> insetsCls = Xp.findClass("androidx.core.view.WindowInsetsCompat", cl);
            java.lang.reflect.Method m = Xp.findMethod(listener, "onApplyWindowInsets",
                    viewCls, insetsCls);
            if (m == null) {
                throw new NoSuchMethodException(listenerCls + "#onApplyWindowInsets");
            }
            Xp.hook(m, chain -> {
                Object result = chain.proceed();
                if (!(chain.getArg(0) instanceof android.view.View)) {
                    return result;
                }
                android.view.View v = (android.view.View) chain.getArg(0);
                android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp == null || lp.height <= 0) {
                    return result;
                }
                float density = v.getResources().getDisplayMetrics().density;
                int original = Math.round(TAB_BAR_ORIGINAL_DP * density);
                // 保住 app 自己的导航栏 inset 处理
                int inset = Math.max(0, lp.height - original);
                int target = Math.round(TAB_BAR_MIN_HEIGHT_DP * density) + inset;
                if (lp.height != target) {
                    lp.height = target;
                    v.setLayoutParams(lp);
                }
                // 只把高度改了的话，tab 文字会停在顶部的 44dp 里、下面留白。
                // 底栏内部是 ConstraintLayout（实测），在它里面把子视图 height 改成
                // MATCH_PARENT 语义会变成 MATCH_CONSTRAINT，需要拓扑约束配合，风险大。
                // 所以不动布局，只给 TextView 加一个垂直位移，把文字推到居中。
                v.post(new Runnable() {
                    @Override
                    public void run() {
                        int shift = (target - original) / 2;
                        int n = shiftTexts(v, shift);
                        logOnce("L8 子视图处理: 找到 " + n + " 个 TextView，位移 "
                                + shift + "px | 底栏=" + v.getHeight() + "px");
                    }
                });
                logOnce("L8 hit: 底栏 " + TAB_BAR_ORIGINAL_DP + "dp+inset(" + inset
                        + "px) -> " + TAB_BAR_MIN_HEIGHT_DP + "dp+inset = "
                        + lp.height + "px");
                return result;
            });
            logOnce("hook installed: L8 " + listenerCls + "#onApplyWindowInsets");
        } catch (Throwable t) {
            log("L8 hook FAILED: " + t);
        }
    }

    /**
     * 把底栏里的 TextView 往下移 shift 像素，使文字在加高后的条里垂直居中。
     * 用 translationY 而不是 LayoutParams：不参与布局计算，不会把 ConstraintLayout
     * 的约束拓扑弄乱，也不影响背景/marker 的 ImageView（它们不是 TextView）。
     * 返回碰到的 TextView 个数，供日志验证。
     */
    private int shiftTexts(android.view.View root, int shift) {
        if (shift == 0 || !(root instanceof android.view.ViewGroup)) {
            return 0;
        }
        int n = 0;
        android.view.ViewGroup g = (android.view.ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            android.view.View c = g.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                c.setTranslationY(shift);
                n++;
            }
            if (c instanceof android.view.ViewGroup) {
                n += shiftTexts(c, shift);
            }
        }
        return n;
    }

    /**
     * L7：上课提醒页隐掉“普通提醒”（推送提醒）那一行。
     * 资源 id 用 getIdentifier 反查，比硬编码 0x7f090830 更抗版本变动。
     */
    private void hidePushNotifyRow(Activity act) {
        try {
            int id = act.getResources().getIdentifier(
                    "pushNotifyLayout", "id", act.getPackageName());
            if (id == 0) {
                logOnce("L7: 找不到 pushNotifyLayout 资源");
                return;
            }
            android.view.View v = act.findViewById(id);
            if (v == null) {
                // 不再静默：上一版就是因为这里没日志，才看不出它其实没生效
                logOnce("L7: findViewById 没拿到 pushNotifyLayout（时机或布局变了）");
                return;
            }
            v.setVisibility(android.view.View.GONE);
            logOnce("L7 hit: 隐掉“普通提醒”（推送提醒）整行");
        } catch (Throwable t) {
            log("L7 FAILED: " + t);
        }
    }

    /**
     * L3：CommonDialog.show() 里按文案匹配。命中就不展示（setResult(null) 跳过原方法），
     * 调用方继续执行，不会因为弹窗被抽掉而卡住。
     *
     * 不去猜哪个字段是正文：直接扫这个实例（含父类）的所有 String 字段。
     * 字段数很少，开销可忽略，且不怕版本里字段改名。
     */
    private void installPromptSuppressor(ClassLoader cl) {
        try {
            Class<?> dialogCls = Xp.findClass("com.dx.common.ui.dialog.CommonDialog", cl);
            java.lang.reflect.Method m = Xp.findMethod(dialogCls, "show");
            if (m == null) {
                throw new NoSuchMethodException("com.dx.common.ui.dialog.CommonDialog#show");
            }
            Xp.hook(m, chain -> {
                Object self = chain.getThisObject();
                String hit = matchMarker(self);
                if (hit == null) {
                    return chain.proceed();
                }
                logOnce("L3 hit: 拦弹窗（命中 \"" + hit + "\"）");
                // 只对“请先登录”给提示；“VIP权益”类弹窗背后的开关
                // （简洁模式）本身还是会生效，弹“已移除”会误导。
                if (TOAST_ON_BLOCK && "请先登录".equals(hit)
                        && self instanceof android.app.Dialog) {
                    toast((android.app.Dialog) self, TOAST_MEMBER_TEXT);
                }
                return null;
            });
            log("hook installed: L3 会员/登录提示弹窗 CommonDialog#show");
        } catch (Throwable t) {
            log("L3 hook FAILED: " + t);
        }
    }

    /** 扫描对象及其父类上所有 String 字段，返回命中的标记；没命中返回 null。 */
    private String matchMarker(Object target) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                try {
                    if (f.getType() != String.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (!(v instanceof String)) {
                        continue;
                    }
                    String s = (String) v;
                    for (String marker : PROMPT_MARKERS) {
                        if (s.contains(marker)) {
                            return marker;
                        }
                    }
                } catch (Throwable ignored) {
                    // 单个字段读失败不影响其他字段
                }
            }
        }
        return null;
    }

    /** Toast 重载：给 Dialog 用。 */
    private void toast(final android.app.Dialog dialog, final String text) {
        try {
            final Context ctx = dialog.getContext();
            if (ctx == null) {
                return;
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Throwable ignored) {
            // 只是提示，失败不影响拦截
        }
    }

    // --------------------------------------------- 卖课/会员 H5 拦截（E6）

    /**
     * E6 + E7 + L1 共用一个收口点：Activity.onCreate。
     * E6：所有 Hybrid 页面都是 BaseCacheHybridActivity 的子类，params 通过 Intent extra
     *     "HybridParamsInfo" 传入（见 BaseCacheHybridActivity$OooOO0.OooO0OO / OooO00o）。
     *     只有带这个 extra 的 Intent 才可能是 H5 页面。
     * E7：会员专区按类名直接关掉，不额外加载它的类。
     * L1：作业帮登录页按类名直接关掉，不额外加载它们的类。
     */
    private void hookHybridBlocker() {
        try {
            java.lang.reflect.Method m = Xp.findMethod(Activity.class, "onCreate", Bundle.class);
            if (m == null) {
                throw new NoSuchMethodException("android.app.Activity#onCreate(Bundle)");
            }
            Xp.hook(m, chain -> {
                Object result = chain.proceed();
                Activity act = (Activity) chain.getThisObject();
                String cls = act.getClass().getName();

                if (VipExclusive.equals(cls)) {
                    logOnce("E7 hit: 会员专区 -> finish()");
                    act.finish();
                    return result;
                }

                if (BLOCK_MINE_ACTIVITY && MineActivity.equals(cls)) {
                    logOnce("L5 hit: 拦“我的”页 " + cls + " -> finish()");
                    if (TOAST_ON_BLOCK) {
                        toast(act, TOAST_MEMBER_TEXT);
                    }
                    act.finish();
                    return result;
                }

                if (HIDE_PUSH_NOTIFY_ROW
                        && (APP + "settings.ScheduleNotifyActivity").equals(cls)) {
                    // 时机关键：Activity.onCreate 的 after 钩子在 super.onCreate() 返回时
                    // 就触发，而子类的 setContentView 还没跑，此时 pushNotifyLayout
                    // 还不存在（findViewById 返回 null）。所以丢到 decor view 的
                    // 消息队列里，等这一轮 onCreate 跑完再隐。
                    final Activity target = act;
                    try {
                        act.getWindow().getDecorView().post(new Runnable() {
                            @Override
                            public void run() {
                                hidePushNotifyRow(target);
                            }
                        });
                    } catch (Throwable t) {
                        log("L7 投递失败: " + t);
                    }
                    // 不 return：这一页本身要继续正常显示，只隐它里面那一行
                }

                if (NO_LOGIN_PROMPT && isLoginActivity(cls)) {
                    logOnce("L1 hit: 拦登录页 " + cls + " -> finish()");
                    if (TOAST_ON_BLOCK) {
                        toast(act, TOAST_LOGIN_TEXT);
                    }
                    act.finish();
                    return result;
                }

                String url = hybridRoute(act.getIntent());
                if (url == null || !isBlocked(url)) {
                    return result;
                }
                logOnce("E6 hit: BLOCK " + cls + " -> " + url);
                if (TOAST_ON_BLOCK) {
                    toast(act, TOAST_TEXT);
                }
                act.finish();
                return result;
            });
            log("hook installed: E6/E7/L1 Activity.onCreate 收口点");
        } catch (Throwable t) {
            log("E6/E7/L1 hook FAILED: " + t);
        }
    }

    private boolean isLoginActivity(String cls) {
        for (String c : LOGIN_ACTIVITIES) {
            if (c.equals(cls)) {
                return true;
            }
        }
        return false;
    }

    /**
     * L2："我的"页顶部登录/换绑卡片。两个 refresh 方法都要挂，否则另一个会把可见性改回去。
     * 用 GONE 而不是移除：保留布局结构，不会影响同容器其他子 View。
     */
    private void installLoginCardHider(ClassLoader cl) {
        String cls = APP + "aaa.widget.MineNewUserLoginView";
        for (String m : new String[] { "refreshLoginUI", "refreshUnLoginUI" }) {
            try {
                Class<?> viewCls = Xp.findClass(cls, cl);
                java.lang.reflect.Method target = Xp.findMethod(viewCls, m);
                if (target == null) {
                    throw new NoSuchMethodException(cls + "#" + m);
                }
                Xp.hook(target, chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof android.view.View) {
                        ((android.view.View) self).setVisibility(android.view.View.GONE);
                        logOnce("L2 hit: 隐藏登录卡片 (" + m + ")");
                    }
                    return result;
                });
                log("hook installed: L2 登录卡片 " + m);
            } catch (Throwable t) {
                log("L2 hook FAILED: " + m + " : " + t);
            }
        }
    }

    /** 从 Intent 里取出混合页面的路由；拿不到就返回 null（不是 H5 页面）。 */
    private String hybridRoute(Intent intent) {
        if (intent == null) {
            return null;
        }
        Serializable info;
        try {
            info = intent.getSerializableExtra("HybridParamsInfo");
        } catch (Throwable t) {
            return null;
        }
        if (info == null) {
            return null;
        }
        // BaseHybridParamsInfo 上同时有 inputUrl / sourceUrl / mRouterScheme，按优先级取。
        String v = readString(info, "inputUrl");
        if (v == null) {
            v = readString(info, "sourceUrl");
        }
        if (v == null) {
            v = readString(info, "mRouterScheme");
        }
        return v;
    }

    private String readString(Object target, String fieldName) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                if (f.getType() != String.class) {
                    return null;
                }
                f.setAccessible(true);
                Object v = f.get(target);
                if (v instanceof String && !((String) v).isEmpty()) {
                    return (String) v;
                }
                return null;
            } catch (NoSuchFieldException ignored) {
                // 往父类继续找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private boolean isBlocked(String url) {
        for (String part : BLOCKED_URL_PARTS) {
            if (url.contains(part)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------- 第二阶段：剪标签（由 TRIM_LEARN_TABS 控制，当前启用）

    private void installTabTrim(ClassLoader cl) {
        // 原生开关：“表助手”标签（保留作为双保险）
        hookFalse(cl, "o0O0o0O.o00oO0o", "OooOOOo", "表助手标签");

        // 之前用 findAndHookMethod(..., "<init>", FragmentManager, List) 会报
        // NoSuchMethodError(#exact)，尽管反编译出来的签名看起来完全一致。
        // 改用 Xp.hookAllConstructors：遍历 getDeclaredConstructors()，不做签名匹配，
        // 直接在 args 里找 List。同时把真实签名打出来，下次不必再猜。
        final String adapterName = "o0O0OOoo.OooOOO";
        try {
            Class<?> adapter = Xp.findClass(adapterName, cl);
            for (java.lang.reflect.Constructor<?> c : adapter.getDeclaredConstructors()) {
                logOnce("  adapter ctor: " + java.util.Arrays.toString(c.getParameterTypes()));
            }
            Xp.hookAllConstructors(adapter, chain -> {
                java.util.List<Object> items = findListArg(chain.getArgs().toArray());
                if (items == null) {
                    logOnce("标签裁剪: 构造器 args 里没有 List: "
                            + java.util.Arrays.toString(chain.getArgs().toArray()));
                    return chain.proceed();
                }
                // 就地改这个 List（构造器拿到的是同一个对象），所以 proceed() 用原参数就够
                for (int i = items.size() - 1; i >= 0; i--) {
                    String fqcn = tabFragmentName(items.get(i));
                    if (fqcn != null && isRemovedTab(fqcn)) {
                        items.remove(i);
                        logOnce("tab removed: " + fqcn + " @index " + i);
                    }
                }
                logOnce("标签裁剪完成，剩余 " + items.size() + " 个");
                return chain.proceed();
            });
            logOnce("hook installed: 标签裁剪 " + adapterName + " (hookAllConstructors)");
        } catch (Throwable t) {
            log("标签裁剪 hook FAILED: " + t);
        }
    }

    private java.util.List<Object> findListArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> l = (java.util.List<Object>) a;
                return l;
            }
        }
        return null;
    }

    private boolean isRemovedTab(String fqcn) {
        for (String s : REMOVED_TABS) {
            if (s.equals(fqcn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标签项是 o0O0OOoo.OooOOOO，它的 OooO00o() 返回 Fragment。
     * 不用 Xp.callMethod（它要求名字可匹配唯一），直接反射找无参同名方法，更稳。
     */
    private String tabFragmentName(Object tabItem) {
        if (tabItem == null) {
            return null;
        }
        for (Class<?> c = tabItem.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("OooO00o") || m.getParameterTypes().length != 0) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object frag = m.invoke(tabItem);
                    return frag == null ? null : frag.getClass().getName();
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 工具

    private void toast(final Activity act, final String text) {
        try {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(act, text, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Throwable ignored) {
            // Toast 只是提示，失败不影响拦截本身
        }
    }

    /** 只打一次的消息，用于高频命中路径。 */

}
