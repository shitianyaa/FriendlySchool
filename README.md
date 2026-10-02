<div align="center">

# FriendlySchool

**校园/常用 App 净化 · 去广告 · 灭活后台采集**

[![Powered by LSPosed](https://img.shields.io/badge/Powered_by-LSPosed-E87DA6?style=flat-square&logo=android&logoColor=white)](https://github.com/LSPosed/LSPosed)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square&logo=opensourceinitiative&logoColor=white)](LICENSE)
[![Min SDK](https://img.shields.io/badge/Min_SDK-26-brightgreen?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/about/versions)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/about/versions)
[![libxposed API](https://img.shields.io/badge/libxposed_API-102-blueviolet?style=flat-square&logo=openjdk&logoColor=white)](https://github.com/libxposed/api)

<!-- 动态徽章：公开仓建好且发出首个 Release 后再取消注释（在此之前 shields.io 只会显示 repo not found / no releases）
[![Release](https://img.shields.io/github/v/release/shitianyaa/FriendlySchool?style=flat-square&color=green&logo=github&logoColor=white)](https://github.com/shitianyaa/FriendlySchool/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/shitianyaa/FriendlySchool/total?style=flat-square&color=orange&logo=github&logoColor=white&label=Downloads)](https://github.com/shitianyaa/FriendlySchool/releases)
-->

</div>

---

## 简介

**FriendlySchool** 对下面几个 App 做**客户端侧净化**：拦掉广告与后台采集的出口、收起营销入口，
同时保留刷卡、充值、余额、付款码这些真实业务。

做法上以**拦出口**为主：被拦的广告接口返回「该广告位已关闭」这类成功空响应，让 App 走它自己既有的正常分支，
而不是把请求打断、把状态机掐死。

**安装**：装好 APK → 在 LSPosed 里启用本模块 → 重启目标 App（三个目标已在模块里声明，通常无需手动挑选）。

## 兼容与版本

- Android 8.0（API 26）及以上；使用支持 **libxposed API 102** 的 LSPosed 框架。
- 模块包名：`io.github.shitianyaa.friendlyschool`；当前版本：`2.1.1`（versionCode `4`）。
- 版本变更见 [更新日志](CHANGELOG.md)。
- **仅 LSPosed 入口**：不提供桌面入口，请在 LSPosed 管理器中启用并管理模块。
- 旧包名 `com.yiran.friendlyschool` 与本包可并存。迁移时先停用旧模块，再启用本模块并重启目标 App，避免重复 Hook。

此前研究与真机验证记录覆盖以下目标版本；其他版本尚未确认兼容：

| 应用 | 已分析 / 验证的版本 |
|---|---|
| 易校园 | 7.7.8 |
| WakeUp 课程表 | 6.5.0（versionCode 540） |
| JMComic3 | 2.1.9 |

2026-10-01 在 Android 17 / LSPosed 2.2.0-it（7901，API 102）上完成新包名 APK 的回归：三个 App 各 3 次飞行模式冷启动，再恢复联网检查；关键 Hook 无丢失、入口点失效数为 0，设备包与构建 / 交付副本哈希一致。用户在手机端确认余额刷新、付款码、课表、去广告 / 签到及排版正常；未清除用户数据。

本次同时修复易校园主动拒绝异常被框架保护模式吞掉的问题：Shell / ProcessBuilder / AssetShield 的拒绝 Hook 使用 `PASSTHROUGH`，其他 Hook 继续使用保护模式。

## ✨ 支持的应用

### 易校园 · `cn.com.yunma.school.app`

它自带的链路比较重：内置 2.2 MB 的 ELF `curl` 释放链与透明命令 Activity、广告 SDK 内嵌的 shell 工具、
面向 220 款应用的扫描名单（结果回传第三方域名）、剪贴板抓取，以及 15+ 家广告 SDK。模块的处理：

- **开屏直达**：开屏页起来后直接走官方跳转，不再等广告回调；
- **广告全链路拦截**：广告中枢与各广告 SDK 的加载/展示全断（开屏、插播、Banner、信息流、营销弹窗、公告位）；
- **后台采集与命令执行拦断**：应用列表扫描、剪贴板抓取、Mob 采集、快手探测，以及 curl / Shell / 命令 Activity；
- **界面精简**：首页「课程表」入口与「今日课表」卡片、我的页签到/积分/礼包卡片隐藏；消息中心只留一卡通类通知。

### WakeUp 课程表 · `com.suda.yzune.wakeupschedule`

- **去广告**：开屏、插播、商城位、首页 banner 与弹窗全部关闭；
- **关掉付费链路**：卖课 / 会员 / 收银台入口与会员专区全部拦断；
- **免登录**：不弹登录页、不显示登录卡片（**代价见下**）；
- **界面精简**：底栏裁到「日程︱课表」、清除顶栏礼包图标与浮窗商城、隐藏「普通提醒」行。
- **免登录也能解锁外观功能**：不登录账号时夜间 / 简洁模式照常来回切换并在重启后保持，皮肤预览返回不再卡住——做法是本地放行会员判断，服务端账号字段一律不修改；

### JMComic3 · `com.a7m3p9xv.t6qk2z8.app`

（禁漫，成人内容站点客户端）

- **WebView 层去广告**：清空广告缓存的读写两侧并拦掉广告接口，所有广告位无事可做；
- **开屏**：仅在封面未走完的窗口内，让 App 走它自己的「免广告会员」分支跳过纯广告封面（只改这一个显示标志，余额/到期/等级等其它字段保持服务端原值）；
- **自动每日签到**：用你自己的账号调官方接口签到，结果会在 App 内弹一条提示（`AUTO_CHECK_IN` 可关）。

## ⚠️ 已知边界

- **目标 App 升级后可能失效**：升级会改类名/方法名，这是所有 Xposed 模块的共同宿命；日志里会明确写出「找不到」的行。
- **会移除部分非广告入口**：易校园的首页「课程表」入口、「今日课表」卡片与我的页三大积分卡片；WakeUp 的「我的」整页、三个底栏标签与「普通提醒」行。
- **WakeUp 免登录的代价**：课表只存本机 —— 云同步不可用，卸载 / 清数据 / 换设备会丢失（学校教务系统的导入页不受影响）。
- **WakeUp 外观设置跟设备不跟账号**：夜间 / 简洁模式存放在当前安装的应用数据里（模块自有键），不随账号切换；新键不存在时按当前账号的原值初始化，之后以本地值为准；清除应用数据或卸载后该设置丢失。
- **WakeUp 付费皮肤未验证**：皮肤样式数据与权限仍由服务端下发，本地放行 ≠ 服务端给数据，未购买皮肤的实际行为尚未确认。
- **易校园拦 shell 的副作用**：App 自带的网速探测会失败；易校园自己的设备指纹持久化不在本模块范围内。
- **JMComic3 会以你自己的账号发少量请求**：签到（读日历 → 提交 → 回读，最多 4 个请求，必要时重试一次）与一次有界的启动期状态采样（每 3 秒一次、最多 100 次，约 5 分钟后自停）。
- **有被服务端风控识别的可能**：改客户端行为总有这个风险，请自行评估。

## 从源码构建

源码仓库：[shitianyaa/FriendlySchool](https://github.com/shitianyaa/FriendlySchool)。官方模块收录仓用于说明与 APK Release，源码在个人仓库维护。

构建不依赖 Gradle，使用 Windows 上的 JDK 17 或以上、Android SDK Build Tools 34.0.0 与 Android 34 平台。脚本默认路径为 `D:\JAVA\bin`、`D:\AndroidSDK\build-tools\34.0.0` 和 `D:\AndroidSDK\platforms\android-34\android.jar`；可用 `JDK_W`、`BT_W`、`AJ_W` 环境变量覆盖。

在源码根目录执行：

```powershell
# PowerShell
.\build.ps1
```

```bash
# Windows Git Bash（需要 cygpath）；测试和构建分别运行
bash module/test/run.sh
bash build.sh
```

构建产物为 `module/build/module.apk`，另复制到 `dist/FriendlySchool-LSPosed-v2.1.1.apk`；可用 `APPDIR` 环境变量指定交付目录。首次构建自动生成 `module/mod.keystore`，后续更新须复用同一密钥；密钥与构建产物均不入库。

官方模块仓库的 Release 标题使用 `2.1.1`，标签使用 `4-2.1.1`，随 Release 上传 APK 并填写更新说明。

## 📜 许可证

本项目采用 [**MIT**](LICENSE)。

`module/lib/api-102.jar` 是 [libxposed API](https://github.com/libxposed/api) 的编译桩，采用 [Apache-2.0](module/lib/LICENSE-libxposed.txt)，来源与哈希见 [依赖说明](module/lib/README.md)。它只用于编译期打桩，不会进入产物 dex。
