package com.yiran.friendlyschool.targets;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.yiran.friendlyschool.core.HookContext;
import com.yiran.friendlyschool.core.SchoolTargetBase;
import com.yiran.friendlyschool.core.Xp;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 趣智校园 6.5.30（680）：广告配置读侧关闭，校园服务、账号及支付配置保持原样。
 * 网易加固在 attach/onCreate 的解密时机不同，成功安装的方法单独登记，后一次只补缺项。
 */
public final class QuZhiCampusTarget extends SchoolTargetBase {

    private static final String TARGET_PKG = "com.klcxkj.zqxy";
    private static final String PREFIX = "com.klcxkj.zqxy.";
    private static final String SPLASH = PREFIX + "ui.splash.SplashActivity";
    private static final String TOPON_SPLASH = PREFIX + "ui.splash.TopOnSplashActivity";
    private static final String[] HOME_AD_VIEWS = {
            "home_task_adv_layout", "home_activity_rv", "home_meal_layout",
            "home_title_jg_adv_layout", "home_title_jt_adv_layout",
            "home_tian_mu_layout", "home_hz_parent_layout"
    };

    private final Set<Method> installed = new HashSet<Method>();

    @Override
    public String shortName() {
        return "趣智校园";
    }

    @Override
    public String packageName() {
        return TARGET_PKG;
    }

    @Override
    public void install(HookContext ctx) {
        ctx.onAppReady(this::installHooks);
    }

    private synchronized void installHooks(ClassLoader cl) {
        installConfig(cl);
        installSplash(cl);
        installTopOnSplash(cl);
        installHome(cl);
    }

    private void installConfig(ClassLoader cl) {
        try {
            Class<?> config = Xp.findClass("xm.j", cl);
            Class<?> project = Xp.findClass(PREFIX + "databean.ProjectInfo", cl);
            // t 只读 advertise_type；s/u 只读首页活动与横幅。不要清空整个配置中心，
            // 同类的 z(Context, ProjectInfo) 返回真实设备服务菜单，必须原样保留。
            for (String name : new String[] {"t", "s", "u"}) {
                Method method = pendingMethod(config, name, List.class, Context.class, project);
                if (method == null) {
                    continue;
                }
                Xp.hook(method, chain -> {
                    logOnce("hit: 广告配置 " + name + " -> emptyList");
                    return Collections.emptyList();
                });
                installed.add(method);
                log("hook installed: 广告配置 xm.j#" + name);
            }
        } catch (Throwable t) {
            log("hook FAILED: 广告配置（Application.onCreate 时补装）: " + t);
        }
    }

    private void installSplash(ClassLoader cl) {
        try {
            Class<?> splash = Xp.findClass(SPLASH, cl);
            // h 启动广告 Activity，i 请求启动插屏。两者都处于广告阶段；不替换
            // onCreate，不碰隐私同意状态，交回原 Handler 的消息 1 完成登录/主页分流。
            for (String name : new String[] {"h", "i"}) {
                Method method = pendingMethod(splash, name, void.class);
                if (method == null) {
                    continue;
                }
                Xp.hook(method, chain -> {
                    Object value = Xp.getObjectField(chain.getThisObject(), "h");
                    if (!(value instanceof Handler)) {
                        log("splash FAILED: 原生分流 Handler 不可用，保留原流程");
                        return chain.proceed();
                    }
                    Handler handler = (Handler) value;
                    handler.removeMessages(2);
                    if (!handler.hasMessages(1) && !handler.sendEmptyMessage(1)) {
                        log("splash FAILED: 原生分流消息投递失败，保留原流程");
                        return chain.proceed();
                    }
                    logOnce("hit: 启动广告阶段 " + name + " -> 原生登录/主页分流");
                    return null;
                });
                installed.add(method);
                log("hook installed: SplashActivity#" + name);
            }
        } catch (Throwable t) {
            log("hook FAILED: 启动广告分流（Application.onCreate 时补装）: " + t);
        }
    }

    private void installTopOnSplash(ClassLoader cl) {
        try {
            Class<?> manager = Xp.findClass("bl.h", cl);
            Method method = pendingMethod(manager, "E", void.class, Activity.class, ViewGroup.class);
            if (method == null) {
                return;
            }
            // E 的正常无广告回调最终调用 TopOnSplashActivity.f()；在加载 SDK 广告前
            // 走同一退场方法，覆盖直接进入广告 Activity 的路径，不伪造加载/奖励成功。
            Xp.hook(method, chain -> {
                Object value = chain.getArg(0);
                if (!(value instanceof Activity) || !TOPON_SPLASH.equals(value.getClass().getName())) {
                    return chain.proceed();
                }
                Activity activity = (Activity) value;
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    Xp.callMethod(activity, "f");
                    logOnce("hit: TopOn 开屏 -> 原生无广告退场");
                }
                return null;
            });
            installed.add(method);
            log("hook installed: TopOn 开屏加载 bl.h#E");
        } catch (Throwable t) {
            log("hook FAILED: TopOn 开屏（Application.onCreate 时补装）: " + t);
        }
    }

    private void installHome(ClassLoader cl) {
        try {
            Class<?> home = Xp.findClass(PREFIX + "fragment.HomeFragment", cl);
            for (String name : new String[] {"initView", "onResume"}) {
                Method method = pendingMethod(home, name, void.class);
                if (method == null) {
                    continue;
                }
                Xp.hook(method, chain -> {
                    Object result = chain.proceed();
                    try {
                        hideHomeAds(chain.getThisObject());
                    } catch (Throwable t) {
                        log("home cleanup FAILED: " + t);
                    }
                    return result;
                });
                installed.add(method);
                log("hook installed: 首页广告容器 HomeFragment#" + name);
            }
        } catch (Throwable t) {
            log("hook FAILED: 首页广告容器（Application.onCreate 时补装）: " + t);
        }
    }

    private void hideHomeAds(Object fragment) {
        // initView 在 onCreateView 返回前执行，此时 Fragment.getView() 可能仍为 null。
        Object value = Xp.getObjectField(fragment, "w");
        if (!(value instanceof View)) {
            return;
        }
        View root = (View) value;
        int placeholderId = root.getResources().getIdentifier("home_banner_iv", "id", TARGET_PKG);
        int bannerId = root.getResources().getIdentifier("home_banner_view", "id", TARGET_PKG);
        View placeholder = placeholderId == 0 ? null : root.findViewById(placeholderId);
        View banner = bannerId == 0 ? null : root.findViewById(bannerId);
        // 横幅的 FrameLayout 有固定高度。只在两个广告子视图共用专属父容器时
        // 收掉父容器，避免空槽；不能顺着父链隐藏整片校园服务菜单。
        if (placeholder != null && banner != null
                && placeholder.getParent() == banner.getParent()
                && banner.getParent() instanceof FrameLayout
                && ((FrameLayout) banner.getParent()).getChildCount() == 2) {
            ((FrameLayout) banner.getParent()).setVisibility(View.GONE);
        } else {
            hideViewById(root, "home_banner_iv");
            hideViewById(root, "home_banner_view");
            logOnce("home banner: 专属父容器未匹配，仅隐藏广告子视图");
        }
        for (String name : HOME_AD_VIEWS) {
            hideViewById(root, name);
        }
        logOnce("hit: 首页广告/返现容器已收起，设备菜单和消息中心保留");
    }

    private Method pendingMethod(Class<?> cls, String name, Class<?> resultType, Class<?>... params) {
        Method method = Xp.findMethod(cls, name, params);
        if (method == null || method.getReturnType() != resultType) {
            log("hook FAILED: " + cls.getName() + "#" + name + " 签名未匹配");
            return null;
        }
        return installed.contains(method) ? null : method;
    }
}
