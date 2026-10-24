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
 * 唯一例外是下方的 V 组：本地把会员判断放行（夜间模式、简洁模式、皮肤预览在 6.5.0 里都是
 * 会员功能，不放行就没法用）。放行只作用于**本地展示与本地偏好**，服务端账号字段一律不修改。
 * 每一处 hook 的锚点都已在样本 6.5.0 (versionCode 540) 与 6.6.0 (550) 上静态确认过。
 * 6.6.0 的 R8 把混淆名整体重排（同名方法被换给别的实现），所以凡是必须落在
 * 混淆符号上的 hook 都改成了按稳定锚点动态发现，锚点稳定性从高到低：
 * 资源名（arsc 不被 R8 重排）> 可读类名/框架方法名 > 字符串常量 > 签名形状 > 混淆名（仅兜底）。
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
 *   L4 底部标签 adapter 全构造器 + List 过滤（6.6.0 为 o0OO00Oo.OooOOO0，6.5.0 为
 *      o0O0OOoo.OooOOO，双名回退；tab 构建在 ScheduleActivity.onCreate L2088-2131）
 *      → 剪掉“学习/表助手/我的”底部标签
 *   L5 mine.MineActivity                                                  → 堵死“我的”页的其它入口（简洁模式右上角那个图标开的就是它）
 *   L6/L9 顶栏图标改走**资源 id**：ScheduleFragment 生命周期收口（onCreateView/
 *      onViewCreated/onResume 三点重隐）+ getIdentifier("scheduleMine"/"scheduleIcon")
 *      + oo0O 访问器兜底（6.6.0 语义已核实；下版本换名只损失这一条兜底）
 *      + 礼包加载器按签名 (HomeSourceBanner, String)→void 动态发现并 no-op
 *        （6.6.0 实名 o00oOOo、650 实名 o00O0Ooo——不再按混淆方法名挂钩，
 *         不 no-op 它则服务端 banner 到达时图标会被 setVisibility(VISIBLE) 翻回）
 *      → 隐掉简洁模式右上角“我的”人形图标与顶栏礼包/营销图标
 *   L7 settings.ScheduleNotifyActivity                                    → 隐掉“普通提醒”（实为推送提醒 pushNotifyLayout，需登录）
 *   L8 widget.MainAiTitleTabView$OooO0OO.onApplyWindowInsets              → 把底栏调高（高度由这个 insets 监听器写死为 44dp+inset）
 *   V1 aaa.utils.o00O0000.OooOOO0()Z   （isVip，21 个调用点）            → 本地解锁会员门禁
 *   V2 utils.ScheduleSpUtils.OooO0Oo()Z （isDarkModel 读）               → 改读本地键（原键带 uid，未登录存不下来）
 *   V3 utils.ScheduleSpUtils.OooO0oo(I)V（dark_model 写）               → 改写本地键
 *   V4 utils.ScheduleSpUtils.OooO0o()Z  （isSimpleModel 读）             → 改读本地键
 *   V5 utils.ScheduleSpUtils.OooO(Z)V   （simple_model 写）              → 改写本地键
 *   C组 launchmanager 基类 Task 构造器挂钩（install() 的 attach 相位，早于任何任务构造）
 *      + 任务名字符串黑名单（App 自有语义，R8 不动字符串常量；660 实测 10 个全在）
 *      → 动态 no-op 命中任务的 run()：广告SDK/广告配置/OAID/设备指纹/埋点/远程日志/
 *        内部日志/支付/推送/DProtect 共 10 项（6.5.0 的 C1-C12 按混淆类名的挂钩全部退役）
 *   C3 com.kuaishou.weapon.p0.{dg, WeaponHI} 全方法置空（类名两版本一致）
 *   C7 com.fastad.* 四网盟 init（可读方法名）
 *   C8 com.kwad.sdk.api.KsAdSDK init/start（可读方法名）
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
 * V 组（V1）与上面第 1、2 条的关系：V1 只让“本地会员判断”返回真，不改 UserInfo 里的任何字段，
 * 也不让 isLogin 返回真 —— 服务端是否下发数据仍由服务端决定，本地不替它做决定。
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
     * L6：简洁模式右上角的“我的”人形图标（资源名 scheduleMine）。
     * 6.6.0 起不再按混淆方法名挂钩（6.5.0 的访问器 oo0O 在 6.6.0 虽语义未变，
     * 但同名换人是常态）：改为资源 id 隐藏 —— R8 不重排 arsc 资源名，
     * 两版本实测 scheduleMine 都在。可见 installScheduleTopIconHider。
     */
    private static final boolean HIDE_SCHEDULE_MINE_ICON = true;

    /**
     * L9：顶栏礼包/营销图标（资源名 scheduleIcon）。
     * 6.5.0 曾按访问器 o00OO0o / 数据装载 o00O0Ooo / 营销入口 o00oO0O0 按名挂钩；
     * 6.6.0 里 o00OO0o 这个名字已被混淆器换给了 scheduleAdd（功能按钮，绝不能再挂），
     * o00O0Ooo/o00oO0O0/o00O0OO0/o00O00e0 签名全变。改为：资源 id 隐藏 + 礼包加载器
     * 按签名 (HomeSourceBanner, String)→void 动态发现 no-op（不 no-op 它，
     * 服务端 banner 到达时图标会被 setVisibility(VISIBLE) 翻回）。
     * 浮窗商城（StoreLayout）广告由仍存活的 E3 门禁把守。
     */
    private static final boolean HIDE_SCHEDULE_GIFT_ICON = true;

    /** 顶栏图标的资源名（arsc 里的名字，R8 不重排；两版本实测均在）。 */
    private static final String MINE_RES_NAME = "scheduleMine";
    private static final String GIFT_RES_NAME = "scheduleIcon";

    /** 解析后的资源 id：-1 = 还没解析，0 = 解析过但 arsc 里没有（自检告警）。 */
    private volatile int mineIconId = -1;
    private volatile int giftIconId = -1;

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
     * V组：本地解锁外观功能（夜间模式 / 简洁模式 / 皮肤预览）。
     *
     * 这三样在 6.5.0 里都挂在同一个门禁后面：aaa.utils.o00O0000.OooOOO0()（isVip）。
     * 未登录时它恒为 false，于是：
     *   - ScheduleFragment.o00ooo0()（夜间开关）：未登录分支读的是“dark_model_<uid>”SP 键
     *     （未登录恒 false），而不是当前会话状态，所以日间→夜间能进、夜间→日间永远切不回来；
     *   - ScheduleFragment.oo0o0O0()（onResume 持久化）/ o00O0OO()（简洁模式保存）：
     *     未登录时不写 SP 还把会话状态清零，所以夜间/简洁一退出 App 就打回原形；
     *   - MainStyleFragment.o0Oo0oo()（返回键拦截）：预览皮肤后未登录会弹“开通VIP”
     *     CommonDialog，而它的“取消”回调才是清预览标志的出口 —— 该弹窗已被 L3 按文案拦掉，
     *     回调永远不执行，返回键被永久吃掉（皮肤设置页卡死的直接原因）。
     *
     * V1 把门禁本身放行：上面三处的登录分支会自然走通（模式切换、退出重进保持、
     * 皮肤返回不再卡死），比逐个改 UI 方法稳 —— 那样要同时对抗 21 个调用点里的每一个分支。
     *
     * V2–V5 是配套：夜间/简洁的持久化键是“dark_model_<uid>”“simple_model_<uid>”，
     * uid 取自登录信息，未登录为 null，App 自己的读写会直接跳过。把 ScheduleSpUtils 的
     * 四个读写方法重定向到模块自有键（fs_ 后缀、独立 SP 文件），未登录也能记住状态；
     * 将来登录真实账号也不会和真实 uid 键冲突。
     *
     * 边界（必须知道）：
     *   - 账号字段未修改：V1 只影响本地判断，UserInfo.vipStatus / vipEndTime / uid 保持服务端原值。
     *   - 存储语义：夜间/简洁存放在当前安装的目标 App 数据内（模块自有键 fs_wakeup_prefs），
     *     **不随账号切换**；新键不存在时按当前账号的原方法值初始化，已存在则以本地值为准
     *     （用户显式保存的 false 也以本地为准，不会被当成“尚未迁移”）；不覆盖 App 的账号键；
     *     清除目标 App 数据或卸载后本地设置丢失。
     *   - 付费皮肤：样式数据与权限仍受服务端限制，未购买皮肤的实际行为**待真机验证**。
     */
    private static final boolean UNLOCK_APPEARANCE = true;

    /** 模块自有 SP 文件与键：与 App 自己的“<功能>_<uid>”键空间隔离。 */
    private static final String FS_PREFS_FILE = "fs_wakeup_prefs";
    private static final String FS_KEY_DARK = "dark_model_fs_local";
    private static final String FS_KEY_SIMPLE = "simple_model_fs_local";

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

    // ------------------------------------------------------------ C组：launchmanager 任务拦截

    /** launchmanager 任务基类（框架类名 6.5.0/6.6.0 一致）。 */
    private static final String LAUNCH_TASK_BASE = "com.homework.launchmanager.task.Task";

    /**
     * C组任务名黑名单 —— 都是 App 自己写的任务标识字符串（6.5.0 各任务构造器
     * super("…") 逐一实读；R8 不动字符串常量，6.6.0 实测 10 个全在），
     * 不依赖任何混淆类名/方法名。
     */
    private static final String[] LAUNCH_TASK_KILL = new String[] {
            "AdvertisementSdkInitTask",   // C1  FastAd 广告聚合初始化
            "AdConfigTask",               // C2  广告配置拉取
            "OaidInitTask",               // C4  OAID 采集
            "DeviceIdTask",               // C9  设备唯一指纹
            "TrackerInitTask",            // C5  nlog.daxuesoutijiang.com 行为埋点
            "RLogInitTask",               // C6  远程日志上报
            "LoggerInitTask",             // C6  内部日志上报
            "PayTask",                    // C11 支付 SDK 初始化
            "PushInitDelayTask",          // C12 推送 SDK 延迟初始化
            "DpSdkTask",                  // C10 DProtect 风控/设备指纹
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

        // C组：launchmanager 任务拦截挂在这里（PackageReady/attach 相位）——
        // 任务实例的构造必然在 Application 初始化之后，即必然在本挂钩之后。
        installLaunchTaskKill(ctx.classLoader);

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

        // V组：本地解锁外观功能（夜间/简洁/皮肤）
        if (UNLOCK_APPEARANCE) {
            installAppearanceUnlock(cl);
        }

        // L6/L9 顶栏图标：资源 id + 生命周期收口 + 访问器兜底 + 礼包加载器签名发现
        if (HIDE_SCHEDULE_MINE_ICON || HIDE_SCHEDULE_GIFT_ICON) {
            installScheduleTopIconHider(cl);
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
     * C3/C7/C8：底层广告与反作弊子系统的直挂钩（类名/方法名可读且两版本一致）。
     *
     * 原 C1/C1b/C2/C4/C5/C6/C9/C10/C11/C12（按混淆类名挂钩的启动任务）已全部退役——
     * 6.6.0 里 o0O0Ooo.o00Ooo 这个类名甚至被混淆器换给了广告渲染回调类。统一改由
     * {@link #installLaunchTaskKill} 按任务名字符串拦截（见 {@link #install}），
     * 黑名单见 {@link #LAUNCH_TASK_KILL}。
     */
    private void installSubsystemHooks(ClassLoader cl) {
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
    }

    /**
     * C组任务拦截：launchmanager（com.homework.launchmanager，框架类名两版本一致）
     * 的每个启动任务都会把**任务名字符串**传进基类构造器——
     * Task 唯一构造器是 Task(String)，两条继承链（直接 extends Task / 经
     * OooO0o(String)→super(str)）都把名字原样传入（6.6.0 反编译实证）。
     * 所以挂 Task 的全部构造器，getArg(0) 就是任务名，proceed 前即可判定，
     * 不依赖任何混淆类名；命中黑名单再对该实例的具体类动态 no-op 其 run()。
     *
     * 挂在 install() 的 attach 相位：任务实例的构造必然在 Application 初始化之后，
     * 即必然晚于本挂钩；构造先于 run，拦截才成立。
     */
    private void installLaunchTaskKill(ClassLoader cl) {
        try {
            Class<?> taskCls = Xp.findClass(LAUNCH_TASK_BASE, cl);
            int ctorCount = Xp.hookAllConstructors(taskCls, chain -> {
                String taskName = null;
                try {
                    for (Object a : chain.getArgs().toArray()) {
                        if (a instanceof String && isKillTask((String) a)) {
                            taskName = (String) a;
                            break;
                        }
                    }
                    if (taskName == null) {
                        return chain.proceed();
                    }
                } catch (Throwable t) {
                    log("C组 task 检查 FAILED: " + t);
                    return chain.proceed();
                }
                // 先让构造完成（字段写入、内部状态就绪），再取实例挂 run()
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self != null) {
                        hookTaskRunNoop(taskName, self.getClass());
                    } else {
                        logOnce("C组 告警: 任务 " + taskName
                                + " 构造完成但拿不到实例（getThisObject=null），本条未拦截");
                    }
                } catch (Throwable t) {
                    log("C组 task kill FAILED (" + taskName + "): " + t);
                }
                return result;
            }).size();

            log("hook installed: C组 Task 构造器挂钩 " + ctorCount
                    + " 个，任务名黑名单 " + LAUNCH_TASK_KILL.length + " 项");
        } catch (Throwable t) {
            log("C组 task kill 挂钩 FAILED: " + t);
        }
    }

    /** 对具体任务类的无参 run() 挂 no-op；带 id（同 id 原子替换），重复构造不会重复挂。 */
    private void hookTaskRunNoop(String taskName, Class<?> taskClass) {
        try {
            for (java.lang.reflect.Method m : taskClass.getDeclaredMethods()) {
                if (!"run".equals(m.getName()) || m.getParameterTypes().length != 0) {
                    continue;
                }
                Xp.hook(m, "C-task-kill:" + taskName, chain -> {
                    logOnce("C组 task kill: " + taskName + " -> " + taskClass.getName() + "#run");
                    return null;
                });
                log("hook installed: C组 " + taskName + " -> " + taskClass.getName() + "#run no-op");
                return;
            }
            log("C组 告警: " + taskName + " (" + taskClass.getName()
                    + ") 没有声明无参 run()，未拦截");
        } catch (Throwable t) {
            log("C组 task kill FAILED (" + taskName + "): " + t);
        }
    }

    private boolean isKillTask(String name) {
        for (String s : LAUNCH_TASK_KILL) {
            if (s.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * L6/L9：顶栏图标净化（简洁模式右上角“我的”scheduleMine + 顶栏礼包/营销 scheduleIcon）。
     *
     * 6.6.0 起混淆名不可再依赖（o00OO0o 这个名字已被换给 scheduleAdd），方案全部落在稳定锚点：
     *
     * 1. 资源 id：getIdentifier 解析 scheduleMine/scheduleIcon（arsc 不被 R8 重排，
     *    两版本实测都在；解析结果缓存 + 自检日志，解析为 0 出声）。
     * 2. 生命周期收口：onCreateView 返回后隐藏；onViewCreated / onResume 重隐
     *    （覆盖简洁模式切换等导致的视图重建；若真机验证发现未覆盖的原地翻回路径再补）。
     * 3. oo0O 访问器兜底：6.6.0 语义已核实（scheduleMine 的 Lazy 访问器），
     *    after + post 设 GONE 赢 setVisibility 竞态；下版本换名时仅这条 FAILED。
     * 4. 礼包加载器按签名动态发现：扫 ScheduleFragment 声明方法里参数恰为
     *    (Bannerconf$HomeSourceBanner, String)、返回 void 的候选，全部 no-op。
     *    650/660 实测都恰有 1 个（650=o00O0Ooo / 660=o00oOOo）——它会在服务端 banner
     *    到达时 setVisibility(VISIBLE) 并装载 Lottie/zip，不 no-op 则图标翻回。
     *    候选数必须打日志：0 = FAILED，>1 = 告警人工核对。
     */
    private void installScheduleTopIconHider(ClassLoader cl) {
        final String cls = APP + "schedule.ScheduleFragment";
        try {
            Class<?> fragCls = Xp.findClass(cls, cl);

            // 1+2. 生命周期收口
            java.lang.reflect.Method cv = Xp.findMethod(fragCls, "onCreateView",
                    android.view.LayoutInflater.class, android.view.ViewGroup.class, Bundle.class);
            if (cv == null) {
                throw new NoSuchMethodException(cls + "#onCreateView(LayoutInflater,ViewGroup,Bundle)");
            }
            Xp.hook(cv, chain -> {
                Object result = chain.proceed();
                if (result instanceof android.view.View) {
                    hideTopIcons((android.view.View) result);
                }
                return result;
            });
            log("hook installed: L6/L9 onCreateView 收口");

            java.lang.reflect.Method vc = Xp.findMethod(fragCls, "onViewCreated",
                    android.view.View.class, Bundle.class);
            if (vc == null) {
                throw new NoSuchMethodException(cls + "#onViewCreated(View,Bundle)");
            }
            Xp.hook(vc, chain -> {
                Object result = chain.proceed();
                if (chain.getArg(0) instanceof android.view.View) {
                    hideTopIcons((android.view.View) chain.getArg(0));
                }
                return result;
            });
            log("hook installed: L6/L9 onViewCreated 重隐");

            java.lang.reflect.Method rs = Xp.findMethod(fragCls, "onResume");
            if (rs == null) {
                throw new NoSuchMethodException(cls + "#onResume()");
            }
            Xp.hook(rs, chain -> {
                Object result = chain.proceed();
                try {
                    Object root = Xp.callMethod(chain.getThisObject(), "getView");
                    if (root instanceof android.view.View) {
                        hideTopIcons((android.view.View) root);
                    }
                } catch (Throwable t) {
                    logOnce("L6/L9 onResume 重隐失败: " + t);
                }
                return result;
            });
            log("hook installed: L6/L9 onResume 重隐");
        } catch (Throwable t) {
            log("L6/L9 生命周期收口 FAILED: " + t);
        }

        // 3. oo0O 访问器兜底（失败不致命：资源 id 方案仍在）
        try {
            java.lang.reflect.Method mine = Xp.findMethod(Xp.findClass(cls, cl), "oo0O");
            if (mine == null) {
                log("L6 兜底: oo0O 访问器不存在（下版本换名了？）——资源 id 方案仍在，跳过此条");
            } else {
                Xp.hook(mine, chain -> {
                    Object result = chain.proceed();
                    if (result instanceof android.view.View) {
                        final android.view.View v = (android.view.View) result;
                        v.setVisibility(android.view.View.GONE);
                        v.post(new Runnable() {
                            @Override
                            public void run() {
                                v.setVisibility(android.view.View.GONE);
                            }
                        });
                        logOnce("L6 hit: oo0O 访问器兜底 -> GONE");
                    }
                    return result;
                });
                log("hook installed: L6 oo0O 访问器兜底");
            }
        } catch (Throwable t) {
            log("L6 oo0O 兜底 FAILED: " + t);
        }

        // 4. 礼包加载器：签名 (HomeSourceBanner, String) -> void 动态发现
        try {
            Class<?> fragCls = Xp.findClass(cls, cl);
            Class<?> bannerItem = Xp.findClass(APP + "aaa.model.Bannerconf$HomeSourceBanner", cl);
            int candidates = 0;
            StringBuilder names = new StringBuilder();
            for (java.lang.reflect.Method m : fragCls.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (m.getReturnType() != void.class || pt.length != 2
                        || pt[0] != bannerItem || pt[1] != String.class) {
                    continue;
                }
                final String methodName = m.getName();
                Xp.hook(m, chain -> {
                    logOnce("L9 hit: 礼包加载器 no-op（" + methodName + "）");
                    return null;
                });
                candidates++;
                names.append(methodName).append(' ');
            }
            if (candidates == 1) {
                log("hook installed: L9 礼包加载器动态发现（1 候选: " + names + "）");
            } else if (candidates == 0) {
                log("L9 hook FAILED: 签名 (HomeSourceBanner, String)→void 零候选——图标会被服务端 banner 翻回");
            } else {
                log("L9 告警: 礼包加载器签名命中 " + candidates + " 个候选（" + names
                        + "）已全部 no-op，需人工核对是否误伤");
            }
        } catch (Throwable t) {
            log("L9 礼包加载器发现 FAILED: " + t);
        }
    }

    /** 两个顶栏图标各按自己的开关隐藏。 */
    private void hideTopIcons(android.view.View root) {
        if (root == null) {
            return;
        }
        if (HIDE_SCHEDULE_MINE_ICON) {
            hideTopIconById(root, MINE_RES_NAME);
        }
        if (HIDE_SCHEDULE_GIFT_ICON) {
            hideTopIconById(root, GIFT_RES_NAME);
        }
    }

    /** 按资源名取缓存 id（没解析过则解析 + 自检日志），在 root 子树里 GONE。 */
    private void hideTopIconById(android.view.View root, String resName) {
        try {
            boolean isMine = MINE_RES_NAME.equals(resName);
            int id = isMine ? mineIconId : giftIconId;
            if (id <= 0) {
                id = root.getResources().getIdentifier(
                        resName, "id", root.getContext().getPackageName());
                if (isMine) {
                    mineIconId = id;
                } else {
                    giftIconId = id;
                }
                if (id == 0) {
                    logOnce("L6/L9 自检: 资源 id " + resName + " 解析为 0（资源名变了？）");
                    return;
                }
                log("L6/L9 自检: " + resName + " id=0x" + Integer.toHexString(id));
            }
            android.view.View v = root.findViewById(id);
            if (v != null && v.getVisibility() != android.view.View.GONE) {
                v.setVisibility(android.view.View.GONE);
                logOnce("L6/L9 hit: " + resName + " -> GONE");
            }
        } catch (Throwable t) {
            logOnce("L6/L9 FAILED (" + resName + "): " + t);
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

    // ------------------------------------------- V组：本地解锁外观功能

    /**
     * V组安装：先把 V1–V5 的方法**全部查齐**，再装；任何一项查不到就一条都不装。
     *
     * 为什么必须先查齐：V2/V4 是“读本地键”、V3/V5 是“写本地键”、V1 是门禁。只装上其中一半
     * （例如读接管了、写没装）就会停在“读数取自本地、写数落回账号键”的不配对状态，而且日志上
     * 完全看不出来 —— 这正是本轮修掉的缺陷。
     *
     * 安装顺序：存储读写（V2–V5）在前，V1 在后。V1 一旦生效，夜间/简洁/皮肤会立刻走“已登录”
     * 分支；此时若存储接管没装上，切换结果就写进没人读的账号键。任一步抛错则撤销本组本次已装的
     * 全部 hook 并逐条记录；其他广告/界面 hook 不受影响。
     */
    private void installAppearanceUnlock(ClassLoader cl) {
        final java.lang.reflect.Method mIsVip;
        final java.lang.reflect.Method mReadDark;
        final java.lang.reflect.Method mWriteDark;
        final java.lang.reflect.Method mReadSimple;
        final java.lang.reflect.Method mWriteSimple;
        try {
            Class<?> vipCls = Xp.findClass(APP + "aaa.utils.o00O0000", cl);
            Class<?> spuCls = Xp.findClass(APP + "utils.ScheduleSpUtils", cl);
            mIsVip = Xp.findMethod(vipCls, "OooOOO0");
            mReadDark = Xp.findMethod(spuCls, "OooO0Oo");
            mWriteDark = Xp.findMethod(spuCls, "OooO0oo", int.class);
            mReadSimple = Xp.findMethod(spuCls, "OooO0o");
            mWriteSimple = Xp.findMethod(spuCls, "OooO", boolean.class);
        } catch (Throwable t) {
            log("V组未安装：查方法失败，一条都不装（保持原逻辑）: " + t);
            return;
        }
        StringBuilder missing = new StringBuilder();
        if (mIsVip == null) {
            missing.append(" o00O0000#OooOOO0");
        }
        if (mReadDark == null) {
            missing.append(" ScheduleSpUtils#OooO0Oo");
        }
        if (mWriteDark == null) {
            missing.append(" ScheduleSpUtils#OooO0oo(int)");
        }
        if (mReadSimple == null) {
            missing.append(" ScheduleSpUtils#OooO0o");
        }
        if (mWriteSimple == null) {
            missing.append(" ScheduleSpUtils#OooO(boolean)");
        }
        if (missing.length() > 0) {
            log("V组未安装：缺少" + missing + "，一条都不装（保持原逻辑）");
            return;
        }

        final java.util.List<io.github.libxposed.api.XposedInterface.HookHandle> installed =
                new java.util.ArrayList<io.github.libxposed.api.XposedInterface.HookHandle>();
        try {
            // V2 夜间读：本地键在 → 用本地值；不在 → 取原方法（账号）值并落盘迁移
            installed.add(Xp.hook(mReadDark, chain -> {
                android.content.SharedPreferences prefs = localPrefs();
                if (prefs == null) {
                    logOnce("V2 偏好不可用，读退回原逻辑");
                    return chain.proceed();
                }
                boolean has;
                int local = 0;
                try {
                    has = prefs.contains(FS_KEY_DARK);
                    if (has) {
                        local = prefs.getInt(FS_KEY_DARK, 0);
                    }
                } catch (Throwable t) {
                    logOnce("V2 读取失败，退回原逻辑: " + t);
                    return chain.proceed();
                }
                int account = has ? 0 : ((Boolean) chain.proceed() ? 1 : 0);
                int value = resolveLocalInt(has, local, account);
                if (!has && !putInt(prefs, FS_KEY_DARK, value)) {
                    logOnce("V2 旧账号值迁移写入失败，下次读取重试");
                }
                logOnce("V2 hit: isDarkModel -> " + (value == 1) + (has ? "" : "（迁移自账号值）"));
                return value == 1;
            }));

            // V3 夜间写：只写本地键，不碰账号键
            installed.add(Xp.hook(mWriteDark, chain -> {
                int v = (Integer) chain.getArg(0);
                android.content.SharedPreferences prefs = localPrefs();
                if (prefs == null || !putInt(prefs, FS_KEY_DARK, v)) {
                    logOnce("V3 写入失败，写退回原逻辑（未登录用户可能无法保存）");
                    return chain.proceed();
                }
                logOnce("V3 hit: dark_model -> " + v);
                return null;
            }));

            // V4 简洁读：同 V2
            installed.add(Xp.hook(mReadSimple, chain -> {
                android.content.SharedPreferences prefs = localPrefs();
                if (prefs == null) {
                    logOnce("V4 偏好不可用，读退回原逻辑");
                    return chain.proceed();
                }
                boolean has;
                boolean local = false;
                try {
                    has = prefs.contains(FS_KEY_SIMPLE);
                    if (has) {
                        local = prefs.getBoolean(FS_KEY_SIMPLE, false);
                    }
                } catch (Throwable t) {
                    logOnce("V4 读取失败，退回原逻辑: " + t);
                    return chain.proceed();
                }
                boolean account = has ? false : (Boolean) chain.proceed();
                boolean value = resolveLocalBool(has, local, account);
                if (!has && !putBool(prefs, FS_KEY_SIMPLE, value)) {
                    logOnce("V4 旧账号值迁移写入失败，下次读取重试");
                }
                logOnce("V4 hit: isSimpleModel -> " + value + (has ? "" : "（迁移自账号值）"));
                return value;
            }));

            // V5 简洁写：同 V3
            installed.add(Xp.hook(mWriteSimple, chain -> {
                boolean v = (Boolean) chain.getArg(0);
                android.content.SharedPreferences prefs = localPrefs();
                if (prefs == null || !putBool(prefs, FS_KEY_SIMPLE, v)) {
                    logOnce("V5 写入失败，写退回原逻辑（未登录用户可能无法保存）");
                    return chain.proceed();
                }
                logOnce("V5 hit: simple_model -> " + v);
                return null;
            }));
            log("hook installed: V2-V5 ScheduleSpUtils 夜间/简洁读写 -> 本地键");

            // V1 最后装：本地放行会员判断
            installed.add(Xp.hook(mIsVip, chain -> {
                logOnce("V1 hit: isVip -> true");
                return Boolean.TRUE;
            }));
            log("hook installed: V1 会员门禁 o00O0000#OooOOO0 -> true");
        } catch (Throwable t) {
            log("V组安装失败，撤销本次已装的 " + installed.size() + " 条: " + t);
            for (int i = installed.size() - 1; i >= 0; i--) {
                // 撤销只解绑 ART 上的 hook；Xp 的句柄登记表仍留这条死句柄，
                // 热重载时再 unhook 一次是幂等的（FakeFramework 与 libxposed 均忽略）。
                try {
                    installed.get(i).unhook();
                    log("V组已撤销第 " + (i + 1) + " 条");
                } catch (Throwable u) {
                    log("V组撤消失败: " + u);
                }
            }
        }
    }

    /**
     * 拿 Application Context（ActivityThread.currentApplication()，boot classloader 反射，
     * 不碰目标 App 类）。拿不到返回 null —— 调用方必须退回原方法并出声，不得静默降级。
     */
    private static android.content.Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread", false, null);
            java.lang.reflect.Method current = at.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            return app instanceof android.content.Context ? (android.content.Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 模块自有的偏好对象：成功取得后缓存，失败一律返回 null 让调用方退回原方法。
     * **不缓存失败** —— 拿不到 Application Context 的时机是暂时的，下次调用要能重试。
     */
    private android.content.SharedPreferences localPrefs() {
        android.content.SharedPreferences cached = localPrefs;
        if (cached != null) {
            return cached;
        }
        android.content.Context c = appContext();
        if (c == null) {
            logOnce("V组：拿不到 Application Context，偏好读写退回原方法（未登录用户可能无法保存）");
            return null;
        }
        try {
            android.content.SharedPreferences p =
                    c.getSharedPreferences(FS_PREFS_FILE, Context.MODE_PRIVATE);
            if (p == null) {
                logOnce("V组：getSharedPreferences 返回 null，偏好读写退回原方法");
                return null;
            }
            localPrefs = p;
            return p;
        } catch (Throwable t) {
            logOnce("V组：偏好获取失败，退回原方法: " + t);
            return null;
        }
    }

    /** 写 int；失败返回 false，调用方据此退回原方法（不许“记录了命中却把写入丢掉”）。 */
    private boolean putInt(android.content.SharedPreferences prefs, String key, int value) {
        try {
            prefs.edit().putInt(key, value).apply();
            return true;
        } catch (Throwable t) {
            logOnce("V组：写 int 失败（" + key + "）: " + t);
            return false;
        }
    }

    /** 写 boolean；失败返回 false，语义同 putInt。 */
    private boolean putBool(android.content.SharedPreferences prefs, String key, boolean value) {
        try {
            prefs.edit().putBoolean(key, value).apply();
            return true;
        } catch (Throwable t) {
            logOnce("V组：写 boolean 失败（" + key + "）: " + t);
            return false;
        }
    }

    /**
     * 迁移判定（纯函数）：本地键存在就用本地值，不存在才取原方法（账号）值。
     * 桌面测试用反射直接调这两个方法（TestMain#wakeUpVGroupTests）。
     *
     * 存在性只能靠 contains 判定，不能靠值判定 —— 用户明确保存的 false/0 与“从未写过”
     * 在值上无法区分，把前者当成待迁移会覆盖用户的选择。
     */
    private static int resolveLocalInt(boolean hasLocalKey, int localValue, int accountValue) {
        return hasLocalKey ? localValue : accountValue;
    }

    private static boolean resolveLocalBool(boolean hasLocalKey, boolean localValue, boolean accountValue) {
        return hasLocalKey ? localValue : accountValue;
    }

    /** 只在成功取得后赋值；失败保持 null 以便下次重试。 */
    private volatile android.content.SharedPreferences localPrefs;

    // ------------------------------------------- 第二阶段：剪标签（由 TRIM_LEARN_TABS 控制，当前启用）

    private void installTabTrim(ClassLoader cl) {
        // adapter 双名回退：6.6.0 起标签构建搬进 ScheduleActivity.onCreate
        // （new o0OO00Oo.OooOOO0(fm, list)），6.5.0 为 o0O0OOoo.OooOOO。
        // 原生开关 o0O0o0O.o00oO0o#OooOOOo 在 6.6.0 类已消失，List 过滤本就覆盖
        // assistant，不再单独挂（6.5.0 上少一条双保险，裁剪结果不变）。
        String adapterName = "o0OO00Oo.OooOOO0";
        Class<?> adapter = Xp.findClassIfExists(adapterName, cl);
        if (adapter == null) {
            adapterName = "o0O0OOoo.OooOOO";
            adapter = Xp.findClassIfExists(adapterName, cl);
        }
        if (adapter == null) {
            log("标签裁剪 hook FAILED: adapter 双名都找不到"
                    + "（o0OO00Oo.OooOOO0 / o0O0OOoo.OooOOO）");
            return;
        }
        // 之前用 findAndHookMethod(..., "<init>", FragmentManager, List) 会报
        // NoSuchMethodError(#exact)，尽管反编译出来的签名看起来完全一致。
        // 改用 Xp.hookAllConstructors：遍历 getDeclaredConstructors()，不做签名匹配，
        // 直接在 args 里找 List。同时把真实签名打出来，下次不必再猜。
        try {
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
