<div align="center">

# FriendlySchool

**校园/常用 App 净化 · 去广告 · 灭活后台采集**

[![Powered by LSPosed](https://img.shields.io/badge/Powered_by-LSPosed-E87DA6?style=flat-square&logo=android&logoColor=white)](https://github.com/LSPosed/LSPosed)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square&logo=opensourceinitiative&logoColor=white)](LICENSE)
[![Min SDK](https://img.shields.io/badge/Min_SDK-26-brightgreen?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/about/versions)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/about/versions)
[![libxposed API](https://img.shields.io/badge/libxposed_API-102-blueviolet?style=flat-square&logo=openjdk&logoColor=white)](https://github.com/libxposed/api)

<!-- 本仓为源码仓；安装包由官方模块收录仓分发，故徽章指向该仓 -->
[![Release](https://img.shields.io/github/v/release/Xposed-Modules-Repo/io.github.shitianyaa.friendlyschool?style=flat-square&color=E87DA6&logo=github&logoColor=white)](https://github.com/Xposed-Modules-Repo/io.github.shitianyaa.friendlyschool/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/Xposed-Modules-Repo/io.github.shitianyaa.friendlyschool/total?style=flat-square&color=orange&logo=github&logoColor=white&label=Downloads)](https://github.com/Xposed-Modules-Repo/io.github.shitianyaa.friendlyschool/releases)
[![Telegram Channel](https://img.shields.io/badge/Telegram-Channel-2CA5E0?style=flat-square&logo=telegram&logoColor=white)](https://t.me/FriendlySchoolRelease)

</div>

---

## 简介

**FriendlySchool** 对下面几个 App 做**客户端侧净化**：拦掉广告与后台采集的出口、收起营销入口，
同时保留刷卡、充值、余额、付款码这些真实业务。

做法上以**拦出口**为主：被拦的广告接口返回「该广告位已关闭」这类成功空响应，让 App 走它自己既有的正常分支，
而不是把请求打断、把状态机掐死。

**安装**：[下载 APK](https://github.com/Xposed-Modules-Repo/io.github.shitianyaa.friendlyschool/releases/latest)（本仓只维护源码，安装包由官方模块收录仓分发）→ 装好 APK → 在 LSPosed 里启用本模块 → 重启目标 App（目标已在模块里声明，通常无需手动挑选）。

## 兼容与版本

- Android 8.0（API 26）及以上；使用支持 **libxposed API 102** 的 LSPosed 框架。
- 模块包名：`io.github.shitianyaa.friendlyschool`；当前版本：`2.3`（versionCode `6`）。
- 版本变更见 [更新日志](CHANGELOG.md)。
- **仅 LSPosed 入口**：不提供桌面入口，请在 LSPosed 管理器中启用并管理模块。
- 旧包名 `com.yiran.friendlyschool` 与本包可并存。迁移时先停用旧模块，再启用本模块并重启目标 App，避免重复 Hook。

兼容范围覆盖以下目标版本；其他版本尚未确认：

| 应用 | 已分析 / 验证的版本 |
|---|---|
| 易校园 | 7.7.8 |
| WakeUp 课程表 | 6.5.0（versionCode 540） |
| JMComic3 | 2.1.9 |
| 光影边框 | 3.4.5（versionCode 34500） |
| 酷安 | 16.6.4（versionCode 2609291） |

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

### 光影边框 · `com.dengziwl.bk`

（Flutter 应用，图片边框 / 水印编辑）

- **去广告**：会员 / 广告开关全部收敛在一个本地设置键上，模块把它固定为「已开通」——开屏广告、导出页原生广告等 29+ 处广告位一处断开；
- **拔掉一条远程代码下发通道**：该应用集成的某个广告 SDK 会用明文 HTTP 从裸 IP 拉配置与 AES 加密的 dex 并反射加载，且无任何完整性校验；模块直接拦断该配置请求（异步上报，不阻塞主流程）；

**边界**：应用内「开通会员」入口的文案读的是服务端返回的会员对象（经 Dart 原生网络层），模块只能改本地设置、改不了这处显示，所以该入口仍会显示原样（不影响去广告效果）。

### 酷安 · `com.coolapk.market`

- **信息流与回复列表去广告**：在列表处理前后过滤 `sponsor` 广告卡片，保留普通帖子与回复；
- **开屏去广告**：两条展示路径都覆盖 —— 独立开屏页（含第三方 SDK 的开屏页）起后立即结束；嵌在主界面内的开屏 Fragment 走宿主原生的结束通道收尾，不留白、不卡顿。

## ⚠️ 已知边界

- **目标 App 升级后可能失效**：升级会改类名/方法名，这是所有 Xposed 模块的共同宿命；日志里会明确写出「找不到」的行。
- **会移除部分非广告入口**：易校园的首页「课程表」入口、「今日课表」卡片与我的页三大积分卡片；WakeUp 的「我的」整页、三个底栏标签与「普通提醒」行。
- **WakeUp 免登录的代价**：课表只存本机 —— 云同步不可用，卸载 / 清数据 / 换设备会丢失（学校教务系统的导入页不受影响）。
- **WakeUp 外观设置跟设备不跟账号**：夜间 / 简洁模式存放在当前安装的应用数据里（模块自有键），不随账号切换；新键不存在时按当前账号的原值初始化，之后以本地值为准；清除应用数据或卸载后该设置丢失。
- **WakeUp 付费皮肤未验证**：皮肤样式数据与权限仍由服务端下发，本地放行 ≠ 服务端给数据，未购买皮肤的实际行为尚未确认。
- **易校园拦 shell 的副作用**：App 自带的网速探测会失败；易校园自己的设备指纹持久化不在本模块范围内。
- **JMComic3 会以你自己的账号发少量请求**：签到（读日历 → 提交 → 回读，最多 4 个请求，必要时重试一次）与一次有界的启动期状态采样（每 3 秒一次、最多 100 次，约 5 分钟后自停）。
- **有被服务端风控识别的可能**：改客户端行为总有这个风险，请自行评估。

## ⚖️ 免责声明

FriendlySchool 仅供 Android、LSPosed 相关技术研究、学习交流及个人设备使用。

本项目通过运行时 Hook 调整第三方应用的客户端行为，与相关应用、开发者、运营方及服务提供商不存在任何隶属、合作或官方关联关系。项目中提及的应用名称、商标及其他相关权利均归其各自权利人所有。

使用者应自行确认其使用行为符合所在地法律法规、第三方应用的用户协议及服务条款，并自行承担因应用升级、兼容性变化、账号限制、数据异常或其他原因产生的风险。

请勿将本项目用于违法、侵权、商业牟利或其他损害第三方合法权益的用途，并请尊重原应用开发者的劳动成果与正版服务。

## 💬 反馈与交流

- **Telegram 频道**：欢迎加入 [FriendlySchool Release 频道](https://t.me/FriendlySchoolRelease) 交流玩耍，获取第一手更新与发布资讯！
- **问题反馈**：如果在日常使用中遇到 Bug 或异常，欢迎提交 [Issue](https://github.com/shitianyaa/FriendlySchool/issues)；
- **新应用适配**：如果有想要支持或净化的校园 / 常用 App，非常欢迎提交 [Issue](https://github.com/shitianyaa/FriendlySchool/issues) 并附上应用名称、版本及相关功能诉求！

## 从源码构建

源码仓库：[shitianyaa/FriendlySchool](https://github.com/shitianyaa/FriendlySchool)。官方模块收录仓 [Xposed-Modules-Repo/io.github.shitianyaa.friendlyschool](https://github.com/Xposed-Modules-Repo/io.github.shitianyaa.friendlyschool) 用于说明与 APK Release，源码在个人仓库维护。

构建不依赖 Gradle，使用 Windows 上的 JDK 17 或以上、Android SDK Build Tools 34.0.0 与 Android 34 平台。脚本默认路径为 `D:\JAVA\bin`、`D:\AndroidSDK\build-tools\34.0.0` 和 `D:\AndroidSDK\platforms\android-34\android.jar`；可用 `JDK_W`、`BT_W`、`AJ_W` 环境变量覆盖。

在源码根目录执行：

```powershell
# PowerShell
.\build.ps1
```

```bash
# Windows Git Bash（需要 cygpath）
bash build.sh
```

构建产物为 `module/build/module.apk`，另复制到 `dist/FriendlySchool-LSPosed-v2.3.apk`；可用 `APPDIR` 环境变量指定交付目录。首次构建自动生成 `module/mod.keystore`，后续更新须复用同一密钥；密钥与构建产物均不入库。

官方模块仓库的 Release 标题使用 `2.3`，标签使用 `6-2.3`，随 Release 上传 APK 并填写更新说明。

## 📜 许可证

本项目采用 [**MIT**](LICENSE)。

`module/lib/api-102.jar` 是 [libxposed API](https://github.com/libxposed/api) 的编译桩，采用 [Apache-2.0](module/lib/LICENSE-libxposed.txt)，来源与哈希见 [依赖说明](module/lib/README.md)。它只用于编译期打桩，不会进入产物 dex。
