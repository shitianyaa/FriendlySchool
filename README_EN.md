<div align="center">

# FriendlySchool

**De-bloating for campus & utility apps · ad removal · background-collection defusing**

[![Powered by LSPosed](https://img.shields.io/badge/Powered_by-LSPosed-E87DA6?style=flat-square&logo=android&logoColor=white)](https://github.com/LSPosed/LSPosed)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square&logo=opensourceinitiative&logoColor=white)](LICENSE)
[![Min SDK](https://img.shields.io/badge/Min_SDK-26-brightgreen?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/about/versions)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/about/versions)
[![libxposed API](https://img.shields.io/badge/libxposed_API-102-blueviolet?style=flat-square&logo=openjdk&logoColor=white)](https://github.com/libxposed/api)

<!-- Dynamic badges: uncomment after the public repo exists and its first release is published
[![Release](https://img.shields.io/github/v/release/shitianyaa/FriendlySchool?style=flat-square&color=green&logo=github&logoColor=white)](https://github.com/shitianyaa/FriendlySchool/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/shitianyaa/FriendlySchool/total?style=flat-square&color=orange&logo=github&logoColor=white&label=Downloads)](https://github.com/shitianyaa/FriendlySchool/releases)
-->

[简体中文](README.md) | English

</div>

---

## Introduction

**FriendlySchool** de-bloats the apps below **on the client side**: it cuts off ad and analytics exits and hides
marketing entries, while keeping real features — card swiping, top-up, balance and the payment QR code — working.

It prefers **intercepting the exit over the request**: blocked ad endpoints receive a successful-but-empty
response, so the app follows its own "this ad slot is disabled" branch instead of having its state machine broken.

**Install**: install the APK → enable the module in LSPosed → restart the target app (all three targets are
declared by the module, so you normally do not have to pick them manually).

## Compatibility & version

- Android 8.0 (API 26) or newer; an LSPosed framework supporting **libxposed API 102**.
- Module application ID: `io.github.shitianyaa.friendlyschool`; current version: `2.1` (versionCode `3`).
- The old package, `com.yiran.friendlyschool`, can coexist with this one. Disable the old module before enabling this module and restarting the target apps to avoid duplicate hooks.

Previous analysis and device-validation records cover these target versions. Compatibility with other versions has not been confirmed:

| App | Previously analyzed / validated version |
|---|---|
| YiCampus | 7.7.8 |
| WakeUp timetable | 6.5.0 (versionCode 540) |
| JMComic3 | 2.1.9 |

The APK under the new application ID completed regression testing on 2026-10-01 with Android 17 / LSPosed 2.2.0-it (7901, API 102): three airplane-mode cold starts per app, followed by online checks. No baseline hook methods were lost, no hook entrypoints were broken, and the installed APK matched both local copies by SHA-256. The user confirmed balance refresh, the payment QR code, timetable, ad removal / check-in and layout on the phone. User data was retained.

This validation also caught and fixed YiCampus' intentional denial exceptions being swallowed by protective mode. Shell / ProcessBuilder / AssetShield denial hooks now use `PASSTHROUGH`; other hooks retain protective mode.

## ✨ Supported apps

### 易校园 (YiCampus) · `cn.com.yunma.school.app`

This app ships a fairly heavy stack: a bundled 2.2 MB ELF `curl` release chain with a transparent command
activity, shell utilities inside its ad SDKs, a scan list covering 220 apps (uploaded to third-party domains),
clipboard snooping, and 15+ ad SDKs. What the module does:

- **Splash direct**: the splash activity goes straight through the app's own navigation instead of waiting for an ad callback;
- **Whole ad chain blocked**: the ad hub and every ad SDK's load/show paths are cut (splash, interstitial, banner, feed, marketing popups, notices);
- **Collection & command execution blocked**: installed-app scans, clipboard snooping, Mob collection, Kuaishou probing, plus curl / shell / the command activity;
- **Tidied UI**: the home timetable tile and "today's classes" card, and the check-in / points / gift cards on the profile page are hidden; the message centre keeps campus-card notifications only.

### WakeUp 课程表 (timetable) · `com.suda.yzune.wakeupschedule`

- **Ad removal**: splash, interstitial, store slot, home banner and popups are all disabled;
- **Paid flows removed**: course-selling / VIP / checkout entries and the VIP section are blocked;
- **No sign-in**: no login page, no sign-in card (see the trade-off below);
- **Tidied UI**: the bottom bar is trimmed to "日程︱课表", the top-bar gift icon and floating store are removed, and the "普通提醒" row is hidden.

### JMComic3 · `com.a7m3p9xv.t6qk2z8.app`

(an adult-content site's client)

- **Ad removal at the WebView layer**: the ad caches are emptied on both the read and write sides and the ad endpoints are blocked, so no slot has anything to show;
- **Splash**: only inside the window before the covers finish, the app is nudged into its own "ad-free member" branch to skip the ad-only cover (only that one display flag changes — balance, expiry and level keep their server values);
- **Automatic daily check-in**: uses your own account against the official endpoints, and reports the result with an in-app toast (`AUTO_CHECK_IN` disables it).

## ⚠️ Known limitations

- **A target app update can break the hooks**: class and method names change — the common fate of every Xposed module. The log states explicitly what could not be found.
- **Some non-ad entries are removed on purpose**: on YiCampus the home timetable tile, the "today's classes" card and the three points cards on the profile page; on WakeUp the whole profile page, three bottom tabs and the "普通提醒" row.
- **The cost of WakeUp's no-sign-in mode**: the timetable lives on this device only — no cloud sync, and it is lost after uninstalling, clearing data or switching devices (the school system's own import page is unaffected).
- **Blocking shell inside YiCampus has a side effect**: the app's own speed test fails; YiCampus' own device-fingerprint persistence is outside this module's scope.
- **JMComic3 sends a few requests with your own account**: the daily check-in (read calendar → submit → read back, at most 4 requests plus one retry) and a bounded startup probe (every 3 s, at most 100 times, stopping after about 5 minutes).
- **Server-side risk control may notice**: modifying a client always carries that risk — evaluate it yourself.

## Building from source

Source repository: [shitianyaa/FriendlySchool](https://github.com/shitianyaa/FriendlySchool). The official module listing repository holds descriptions and APK releases; source code is maintained in the personal repository.

The build uses JDK 17 or newer, Android SDK Build Tools 34.0.0 and the Android 34 platform on Windows, without Gradle. Default paths are `D:\JAVA\bin`, `D:\AndroidSDK\build-tools\34.0.0` and `D:\AndroidSDK\platforms\android-34\android.jar`; override them with the `JDK_W`, `BT_W` and `AJ_W` environment variables.

Run from the source root:

```powershell
# PowerShell
.\build.ps1
```

```bash
# Windows Git Bash (requires cygpath); run tests and build separately
bash module/test/run.sh
bash build.sh
```

The APK is written to `module/build/module.apk` and copied to `dist/FriendlySchool-LSPosed-v2.1.apk`. Set `APPDIR` to override the delivery directory. The first build generates `module/mod.keystore`; retain and reuse that key for subsequent updates. The key and build outputs are excluded from version control.

For the official module repository, use release title `2.1`, tag `3-2.1`, and attach the APK and changelog when publishing the release.

## 📜 License

Released under the [**MIT**](LICENSE) license.

`module/lib/api-102.jar` is the compile stub of [libxposed API](https://github.com/libxposed/api), licensed under [Apache-2.0](module/lib/LICENSE-libxposed.txt). See [dependency notes](module/lib/README.md) for its origin and hash. It is used at compile time only and never enters the dex.
