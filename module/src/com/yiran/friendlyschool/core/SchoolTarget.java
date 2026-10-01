package com.yiran.friendlyschool.core;

/**
 * 一个被净化的校园 App。
 *
 * 新增一所学校 / 一个 App 的做法：
 *   1) 在 {@code com.yiran.friendlyschool.targets} 下写一个实现类（继承 SchoolTargetBase）；
 *   2) 在 {@link Targets#ALL} 里加一行；
 *   3) 在 {@code module/meta/META-INF/xposed/scope.list} 里加上它的包名
 *      （现代打包的静态作用域声明；构建脚本会核对代码与它一致）。
 * 构建脚本会把所有 .java 一起编译，不需要再改别的地方。
 *
 * 同时不需要为热重载另写清理代码：换代时框架会新建模块 classloader，
 * target 实例与静态标记都是新的（见 FriendlySchoolHook#onHotReloaded）。
 */
public interface SchoolTarget {

    /** 日志里的目标名，如“易校园”。 */
    String shortName();

    /** 目标 App 的包名，同时也是本模块的分发键。 */
    String packageName();

    /**
     * 在目标进程里安装本目标的 hook。
     *
     * 调用时机是 libxposed 现代 API 的 onPackageReady：
     * 「AppComponentFactory 建好 classloader、准备创建 Application 之前」——
     * 与 legacy 的 handleLoadPackage 同相位，早于目标 App 的 Application 被 attach。
     * 需要等 App 自己的类装载完再挂的 hook，用 {@link HookContext#onAppReady}。
     */
    void install(HookContext ctx);
}
