# module/meta —— 现代打包（libxposed）元数据的源头

这三个文件会被 `build.sh` / `build.ps1` 用 `aapt add` **原样塞进 APK 的 `META-INF/xposed/` 根目录**
（不是 `assets/`；LSPosed 只看 APK 根下的 `META-INF/xposed/`）。

| 文件 | 作用 |
|---|---|
| `java_init.list` | 入口类清单（每行一个全限定类名）。**LSPosed 判定"现代打包"就看这个文件在不在**（`daemon` 的 `ConfigFileManager` 先读它，读到即 `legacy=false`）。 |
| `module.prop` | 模块元数据（`java.util.Properties` 格式）。`minApiVersion` / `targetApiVersion` 必填。 |
| `scope.list` | 静态作用域声明（每行一个包名）。LSPosed 据此把目标 App 标成"推荐应用"。 |

## module.prop 各字段的取值理由

- `minApiVersion=101` / `targetApiVersion=102`：与真机框架上报的 `API 102` 对齐。
  **API 102 的行为变更里有一条很关键**：libxposed 模块不得再调用 legacy 的
  `de.robv.android.xposed` API —— 本模块因此把全部调用点迁成了 interceptor chain。
- `staticScope=true`：作用域固定为 `scope.list` 里那几个包，用户不该往外扩。
- `exceptionMode=protective`：hooker 抛异常时框架记日志并当没挂（不影响 App 稳定性）。
  Shell / ProcessBuilder / AssetShield 的主动拒绝 Hook 单独设为 `PASSTHROUGH`，
  让 `SecurityException` / `FileNotFoundException` 到达调用方；否则保护模式会吞掉拒绝并执行原方法。
- `autoHotReload=true`：更新模块 APK 时自动热重载（API 102 特性）。
  换代逻辑见 `FriendlySchoolHook#onHotReloading` / `#onHotReloaded`。

> **刻意不给 `module.prop` 写注释**：这份文件一旦解析失败，表现是"模块装了但静默不生效"，
> 属于本仓库最忌讳的失败形态。所以让它与官方示例逐字同形，说明写在本文档里。

## 与旧（legacy）打包的对应关系

| | 旧 | 新 |
|---|---|---|
| 入口清单 | `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| 元数据 | manifest 的 `xposedmodule` / `xposedminversion` / `xposeddescription` | `META-INF/xposed/module.prop` + manifest 的 `android:label` / `android:description` |
| 作用域 | `<meta-data name="xposedscope" android:resource="@array/..."/>`（string-array） | `META-INF/xposed/scope.list` |
| 入口类 API | `IXposedHookLoadPackage`（API 82） | `io.github.libxposed.api.XposedModule`（API ≥ 100） |
