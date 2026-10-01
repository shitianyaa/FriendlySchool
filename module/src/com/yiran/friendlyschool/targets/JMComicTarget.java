package com.yiran.friendlyschool.targets;

/*
 * JMComic3（禁漫）去广告 target。
 *
 * 与易校园 / WakeUp 两个 target 的根本差别：JMComic3 是 Capacitor 混合应用，
 * 广告不在 dex 里，而在 WebView 的 JS 层。
 *
 * 事实来源（不是推测）：APK 里 assets/public/static/js/*.js.map 带完整 sourcesContent，
 * 234 个应用源码文件可逐字还原。据此确认的广告数据流只有一条：
 *
 *   localStorage.adsList     -> GlobalContext.config.ads         -> AdComponent(全部广告位) / Banner
 *   localStorage.adsContent  -> GlobalContext.config.adsContent  -> HeaderAds / 开屏封面 / 详情页中部
 *   服务端 ad_content_all / advertise_all -> FETCH_*_ADS_THUNK -> setConfig(prev => ({...prev, ads}))
 *
 * 所以"没有广告数据"就等于"所有广告位无事可做"：每个消费点都是
 * `adsScript && ...` / `bannerList?.length > 0` 这类先判空再渲染的写法，
 * 空数据走的是 App 自己为"运营把该广告位关掉"准备的正常分支。
 *
 * 三条纪律：
 *   1. 拦"出口"不拦"请求"。本 target 不 no-op 任何 App 方法、不让任何请求失败；
 *      广告接口被拦时返回的是 `{"code":200,"data":""}` 这个**成功**响应，App 的
 *      successCallback / resolve 照常触发（它自己解密失败就会把 data 置空，是既有分支），
 *      状态机不会被掐断 —— 这正是易校园开屏卡死那个坑的反面。
 *   2. 禁止静默失效。hook 装上要出声、注入要出声、装不上也要出声。
 *   3. 伪造 ad_free（经明确选择的取舍，完整代价见 README）。
 *      目的：让 App 走它自己的「免广告会员」分支 —— FirstCover 在 adFreeStatus 为真时
 *      调 onNext(2) 而不是 onNext(1)，直接跳过只有广告内容、本身没有任何功能的
 *      SecondCover（coverOpen===3），不必对一页空白再点一次右上角的叉。
 *
 *      代价（已如实告知并经用户确认）：components/Member/CenterCard.tsx 的「狀態」一行
 *      读的是同一个字段 —— 仅当伪造还在生效（即下面的回退形态）才会显示成「超級JM人」。
 *      实现上**只注入 ad_free 这一个字段**，余额/到期/等级等其它字段一律保持服务端原值。
 *
 *      伪造只在**开屏封面未走完时**生效：App 自己在 ThreeCover 的「同意」里会把
 *      sessionStorage.state 置为 'true'，一旦置位就立刻回到真实值。
 *      这样「我的」页（渲染时现读 localStorage）显示的是**真实会员等级**，
 *      伪造窗口只剩启动那一小段 —— 也正是它唯一被需要的地方。
 *      setItem 一律存真实值，所以落盘数据不被污染。
 *
 * 副作用与残留（如实记录，见 README）：
 *   - SecondCover 被跳过；ThreeCover 的年龄确认按钮未动，仍需要点。
 *   - 封面阶段内（启动后几秒）访客的 AVS Cookie 会短暂变成 AVS=undefined，
 *     封面走完即恢复为原始的 Cookie:""。
 *   - 若 sessionStorage.state 因故一直未置位，伪造会一直生效，此时「我的」页
 *     会显示成「超級JM人」—— 这是本方案的回退形态。
 *   - 本模块外的其它 WebView（例如 App 内打开的外链页）不受影响。
 */

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.yiran.friendlyschool.core.HookContext;
import com.yiran.friendlyschool.core.SchoolTargetBase;
import com.yiran.friendlyschool.core.Xp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class JMComicTarget extends SchoolTargetBase {

    /** 在模块内再做一次包名收口；真正的门是 LSPosed 的 scope。 */
    private static final String TARGET_PKG = "com.a7m3p9xv.t6qk2z8.app";

    private static final String NAME = "JMComic3";

    /**
     * 注入的补丁脚本。
     *
     * 设计要点：
     *   - 幂等：用 window.__jmAdFree 守卫，onPageStarted / onPageFinished 都注入也不会重复装。
     *   - 覆盖读取与写入两侧：只清空 localStorage 不够 —— FETCH_*_ADS_THUNK 成功后是直接
     *     setConfig(...ads) 进 React state 的（不经过 localStorage），所以还要掐掉广告接口，
     *     否则 React 状态会被服务端重新填满。
     *   - 自诊断：把注入时刻的 document.readyState / 是否发现旧广告缓存
     *     写进 localStorage 的 __jmAdFree，使"脚本到底跑没跑、跑得早不早"在设备上可用
     *     文件系统证据回答（本机 logcat 是空的，需要一条不依赖 logcat 的通道）。
     *   - 不自造节奏：实测 onPageStarted 注入时 document.readyState 恒为 "loading"，
     *     即补丁稳定早于 React 首次读取 localStorage，所以"先清缓存"这一步就足够。
     *     因此**不做** location.reload() 之类的自我跳转（贴着 App 的节奏，别自己造节奏）。
     *     __jmAdFree 里记的 early / rs / stale 只作诊断，不驱动任何行为。
     */
    private static final String PATCH_JS =
            "(function(){try{var W=window;if(W.__jmAdFree){return}W.__jmAdFree=1;"
            + "var EMPTY_ADS='{}';"
            + "var EMPTY_COVER='{\"img\":{},\"link\":{}}';"
            + "var MI_ADFREE='{\"ad_free\":true}';"
            + "var RS=(document&&document.readyState)?document.readyState:'';"
            + "var EARLY=(RS==='loading');"
            + "var stale=false;"
            + "try{var a=W.localStorage.getItem('adsList');var c=W.localStorage.getItem('adsContent');"
            + "if(a&&a!==EMPTY_ADS){stale=true}if(c&&c!==EMPTY_COVER){stale=true}}catch(e){}"
            + "var harden=function(v){try{var o=JSON.parse(v);"
            + "if(o&&typeof o==='object'){o.ad_free=true;return JSON.stringify(o)}}catch(e){}"
            + "return MI_ADFREE};"
            + "var coversDone=function(){try{return !!W.sessionStorage.getItem('state')}catch(e){return false}};"
            + "try{var SP=W.Storage&&W.Storage.prototype;"
            + "if(SP&&!SP.__jmAdFree){var og=SP.getItem,os=SP.setItem;"
            + "SP.getItem=function(k){"
            + "if(k==='adsList'){return EMPTY_ADS}"
            + "if(k==='adsContent'){return EMPTY_COVER}"
            + "if(k==='memberInfo'){var v=og.call(this,k);"
            + "if(coversDone()){return v}return v?harden(v):MI_ADFREE}"
            + "return og.call(this,k)};"
            + "SP.setItem=function(k,v){"
            + "if(k==='adsList'){try{os.call(this,k,EMPTY_ADS)}catch(e){}return}"
            + "if(k==='adsContent'){try{os.call(this,k,EMPTY_COVER)}catch(e){}return}"
            + "return os.call(this,k,v)};"
            + "SP.__jmAdFree=1}}catch(e){}"
            + "try{W.localStorage.setItem('adsList',EMPTY_ADS);"
            + "W.localStorage.setItem('adsContent',EMPTY_COVER)}catch(e){}"
            + "try{var of=W.fetch;"
            + "if(typeof of==='function'&&!of.__jmAdFree){"
            + "var nf=function(input,init){"
            + "try{var u='';"
            + "if(typeof input==='string'){u=input}else if(input&&input.url){u=input.url}"
            + "if(u&&(u.indexOf('ad_content')>=0||u.indexOf('advertise')>=0)){"
            + "return Promise.resolve(new Response('{\"code\":200,\"data\":\"\"}',"
            + "{status:200,headers:{'Content-Type':'application/json'}}))}}catch(e){}"
            + "return of.apply(this,arguments)};"
            + "nf.__jmAdFree=1;W.fetch=nf}}catch(e){}"
            + "try{W.localStorage.setItem('__jmAdFree',JSON.stringify("
            + "{v:1,early:EARLY,rs:RS,stale:stale,t:Date.now()}))}catch(e){}"
            + "}catch(e){}})();";

    /**
     * 诊断脚本：把"当前文档里补丁状态 / 广告数据大小 / DOM 里真实存在的广告容器数量"回读出来。
     *
     * 为什么需要它：本机 logcat 是空的，而 WebView 的 localStorage 落在 leveldb 里、
     * 且会被 Snappy 压缩，出问题时直接读盘既读不到也读不准。用 evaluateJavascript 的回调把事实
     * 带回 Java 层再走 Xp.log，是一条不受这两者影响的通道。
     *
     * adIframes / adTags 是 DOM 层的事实：AdComponent 每渲染一个广告位就产生一个
     * title="frame-<adKey>" 的 iframe，adTag=true 时还会产生 id="<adKey>_tag" 的 "AD" 角标。
     * 两者都为 0 才算"界面上真的没有广告"。
     */
    private static final String DIAG_JS =
            "(function(){try{var l=window.localStorage;"
            + "var raw=l.getItem('adsList');var rc=l.getItem('adsContent');"
            + "var nk=0;try{nk=Object.keys(JSON.parse(raw||'{}')).length}catch(e){nk=-1}"
            + "var mi=null;try{mi=JSON.parse(l.getItem('memberInfo')||'null')}catch(e){}"
            + "var bt='';try{bt=(document.body&&document.body.innerText)||''}catch(e){}"
            + "var ti=bt.indexOf('JM\u4eba');"
            + "var tier=(ti>=0)?bt.slice(Math.max(0,ti-14),ti+4).replace(/\\s+/g,' '):'';"
            + "var ifr=document.querySelectorAll(\"iframe[title^='frame-']\").length;"
            + "var tg=document.querySelectorAll(\"span[id$='_tag']\").length;"
            + "var cd=false;try{cd=!!window.sessionStorage.getItem('state')}catch(e){}"            + "return JSON.stringify({patched:!!window.__jmAdFree,adsKeys:nk,"
            + "adFree:!!(mi&&mi.ad_free),jwt:!!l.getItem('jwttoken'),tier:tier,covers:cd,"
            + "adsListLen:(raw?raw.length:0),adsContentLen:(rc?rc.length:0),"
            + "adIframes:ifr,adTags:tg,url:String(location.href).slice(0,80)});"
            + "}catch(e){return JSON.stringify({err:String(e)})}})()";

    /** 采样：每 3s 一次，最多 100 次（约 5 分钟），只在结论变化时落日志（diagnose 用 logOnce）。 */
    private static final long SAMPLE_INTERVAL_MS = 3000L;
    private static final int SAMPLE_MAX = 100;

    // =======================================================================
    // 自动每日签到（v1.7）
    // =======================================================================

    /** 想要"不自动签到"就把它改成 false。刻意不做配置项：一个常量够用。 */
    private static final boolean AUTO_CHECK_IN = true;

    /**
     * 签到结果要不要弹 Toast。
     *
     * **只在本轮真的做了事（或明确失败）时才弹**，否则每次开 App 都会弹一个
     * 「已签到」，用户第一天就会来投诉：
     *   真签成（或服务端回旧签到保护的原话）-> 弹服务端自己的 msg
     *   领到全签奖励                        -> 弹（带前缀区分）
     *   回读校验失败                        -> 弹（失败必须可见）
     *   今天已签到 / 未登录 / 还没 apiUrl    -> **不弹**（只落模块日志）
     *   读日历就失败（多半是断网）          -> **不弹**（否则离线时每次启动都弹一下）
     *
     * 文案原则：能用服务端返回的 `msg` 就用它（那是 App 自己在 snackbar 里显示的同一句话），
     * 不自编一套；只有 msg 为空时才用固定兜底文案。
     *
     * **排队而非立即弹**（v1.9）：JMComic3 启动要穿过「测线路 → 3s 开屏(带❌) → 同意18岁」
     * 好几层封面（`Main.tsx` 的 coverOpen 1→6），在封面阶段弹会盖在封面页上、用户正在点 ❌/同意，
     * 基本看不到。所以弹窗先排队，等**App 自己的同意位** `sessionStorage.state === 'true'`
     * （`ThreeCover.tsx:79` / `FourCover.tsx:64` 写入）置位后再弹 —— 用 App 的信号，不自己造延时。
     */
    private static final boolean TOAST_CHECKIN = true;

    /**
     * 一次性调试开关（**v1.9 临时加的，验完必须改回 false 并重构**）。
     *
     * 用途：签到成功弹窗只在「当日真的签成」那一天出现，而验收当天早就签过了，
     * 所以拿不到真弹窗。开这个开关会在**封面走完之后**弹一条带【调试】字样的假弹窗，
     * 让你先把样式/长短/位置看一眼。真弹窗内容优先于它（排队时真内容会覆盖它）。
     *
     * 2026-10-01：弹窗样式已由用户目视确认、真弹窗也已实测到（日志 `toast -> …`），
     * 开关**已关闭**；代码与分支保留（不再触发），以后需要看样式时临时打开即可。
     */
    private static final boolean DEBUG_TOAST_ONCE = false;

    /**
     * 模块自己在 App localStorage 里占的诊断键（**App 从不读它**，已有 `__jmAdFree` 先例）。
     * 只用来记「失败弹窗哪天弹过」，以做到「一天只弹一次失败」——
     * 不写这个就只能做到「一进程一次」，重启 App 又会弹。
     */
    private static final String LS_CHECKIN = "__jmCheckin";

    /**
     * API 的共享密钥，逐字来自 App 自己的 assets/public/static/js（api/apiPaths.ts 的 token 字段）：
     *   Token    = md5(unix_ts + 这个串)
     *   回包 data = AES-256-ECB/PKCS7(base64)，密钥同上是同一个 md5 串的 UTF-8 字节
     * 它随 App 版本理论上可能变；变了本 target 的签到会以「解密失败/401」明确报错，不会静默。
     */
    private static final String API_SECRET = "185Hcomic3PAPP7R";

    /** 只进 Tokenparam 头（`"<ts>,<ver>"`）。取不到 App 版本号，先跟当前样本一致。 */
    private static final String APP_VERSION = "2.1.9";

    private static final int HTTP_TIMEOUT_MS = 15000;

    /**
     * 取会话：只读 localStorage，不改任何 App 状态。
     *
     * 为什么不把请求也写在 JS 里：签名要 md5、回包还要 AES-ECB 解密，而 webpack 没把
     * CryptoJS / md5 挂到 window 上，等于要在注入脚本里重造这两套；Java 侧有
     * MessageDigest 和 javax.crypto，反而一行都不用造。所以 JS 只负责"把会话捞出来"。
     */
    private static final String CHECKIN_JS =
            "(function(){try{var l=window.localStorage;var mi={};"
            + "try{mi=JSON.parse(l.getItem('memberInfo')||'{}')||{}}catch(e){}"
            + "var jw='';try{jw=JSON.parse(l.getItem('jwttoken')||'\"\"')||''}catch(e){}"
            + "var fd='';try{var o=JSON.parse(l.getItem('" + LS_CHECKIN + "')||'{}');fd=(o&&o.failDay)||''}catch(e){}"
            + "var cd=false;try{cd=!!window.sessionStorage.getItem('state')}catch(e){}"
            + "return JSON.stringify({apiUrl:l.getItem('apiUrl')||'',jwt:jw,lang:l.getItem('lang')||'TW',"
            + "s:mi.s||'',uid:String(mi.uid||''),name:String(mi.username||''),covers:cd,failDay:fd});"
            + "}catch(e){return JSON.stringify({err:String(e)})}})()";

    private volatile int samplesLeft = 0;
    private volatile boolean samplingChain = false;

    /** install 在 handleLoadPackage 里只调一次，这里再守一道，防止重复挂。 */
    private volatile boolean hooksInstalled = false;

    /** 签到每个进程只尝试一次（onPageFinished 会多次触发）。 */
    private final AtomicBoolean checkInStarted = new AtomicBoolean(false);

    /** 待弹内容：排队等「同意18岁」位置位后再弹；用 AtomicReference 避免两个线程重复弹。 */
    private final AtomicReference<String> pendingToast = new AtomicReference<String>();

    /** 封面是否已走完（`sessionStorage.state === 'true'`），或已走兜底路径。 */
    private volatile boolean coversDoneSeen = false;

    /**
     * 早做那次因「未登录 / 还没测出线路」而跳过时，置位表示「还欠一次重试」（仅一次）。
     * 触发点见 {@link #retryIfPending}：以 diag 回读报 `jwt=true` 为准，采样链结束再兜底。
     */
    private volatile boolean retryAfterCovers = false;

    /** 调试弹窗只弹一次。 */
    private volatile boolean debugToastShown = false;

    @Override
    public String shortName() {
        return NAME;
    }

    @Override
    public String packageName() {
        return TARGET_PKG;
    }

    /**
     * 挂 WebView 生命周期出口。
     *
     * 为什么挂 WebViewClient 而不是 Capacitor 自己的类：Capacitor 的类名被 R8 混淆成
     * com.getcapacitor.q 这种，随版本会变；而 com.getcapacitor.q#onPageStarted /
     * onPageFinished 都 **invoke-super**（已 static 确认：q.smali 第 114 行、第 33 行），
     * 所以挂基类 android.webkit.WebViewClient 一定能命中，且不受混淆影响。
     *
     * 注意 AndroidManifest 里 targetSdk 是 35，但 targetSdk 37 的设备上
     * onPageStarted(WebView, String, Bitmap) 仍是 WebViewClient 的公开签名，未变。
     */
    @Override
    public void install(HookContext ctx) {
        if (hooksInstalled) {
            return;
        }
        hooksInstalled = true;

        hookPageStarted();
        hookPageFinished();
    }

    private void hookPageStarted() {
        try {
            Method m = Xp.findMethod(WebViewClient.class, "onPageStarted",
                    WebView.class, String.class, Bitmap.class);
            if (m == null) {
                throw new NoSuchMethodException("WebViewClient#onPageStarted(WebView,String,Bitmap)");
            }
            Xp.hook(m, chain -> {
                Object result = chain.proceed();
                inject(asWebView(chain), "onPageStarted");
                return result;
            });
            log("hook installed: WebViewClient#onPageStarted");
        } catch (Throwable t) {
            // 装不上必须出声：这一条静默就等于整个 target 什么都没做。
            log("hook FAILED: WebViewClient#onPageStarted : " + t);
        }
    }

    private void hookPageFinished() {
        try {
            Method m = Xp.findMethod(WebViewClient.class, "onPageFinished",
                    WebView.class, String.class);
            if (m == null) {
                throw new NoSuchMethodException("WebViewClient#onPageFinished(WebView,String)");
            }
            Xp.hook(m, chain -> {
                Object result = chain.proceed();
                WebView wv = asWebView(chain);
                inject(wv, "onPageFinished");
                diagnose(wv);
                scheduleSampling(wv);
                autoCheckIn(wv);
                return result;
            });
            log("hook installed: WebViewClient#onPageFinished");
        } catch (Throwable t) {
            log("hook FAILED: WebViewClient#onPageFinished : " + t);
        }
    }

    private static WebView asWebView(io.github.libxposed.api.XposedInterface.Chain chain) {
        if (chain.getArgs().isEmpty()) {
            return null;
        }
        Object a0 = chain.getArg(0);
        return (a0 instanceof WebView) ? (WebView) a0 : null;
    }

    /**
     * 把补丁脚本送进页面。
     *
     * onPageStarted / onPageFinished 都在主线程回调，evaluateJavascript 要求主线程，所以直接调用。
     */
    private void inject(WebView wv, String where) {
        if (wv == null) {
            logOnce("inject skipped at " + where + ": 第 0 个参数不是 WebView");
            return;
        }
        try {
            wv.evaluateJavascript(PATCH_JS, null);
            logOnce("js injected at " + where);
        } catch (Throwable t) {
            log("js inject FAILED at " + where + " : " + t);
        }
    }

    /**
     * 回读页面事实并写进模块日志。
     *
     * 页面加载次数很少（本机实测一次启动 1–3 次），每次打一行不会刷屏；
     * 同一份结论只打一次（logOnce），避免重复状态把日志顶掉。
     */
    private void diagnose(final WebView wv) {
        if (wv == null) {
            return;
        }
        try {
            wv.evaluateJavascript(DIAG_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    logOnce("diag: " + value);
                    // 顺便用同一个回读结果判断「同意18岁」位是否已置位（不额外开通道）
                    JSONObject d = parseJsResult(value, "diag");
                    if (d != null && d.optBoolean("covers")) {
                        onCoversDone(wv, "diag 报 state 已置位");
                    }
                    // 重试时机以「登录真的回来了」为准，而不是「封面走完」为准：
                    // 实测 App 启动存在 clearAuth → 明文自动重登 的窗口（3~55s 不等），
                    // 封面走完时很可能还落在窗口里，那一刻重试会白废掉唯一一次机会 → 当次漏签。
                    if (d != null && d.optBoolean("jwt")) {
                        retryIfPending(wv, "diag 报 jwt=true（登录已回来）");
                    }
                }
            });
        } catch (Throwable t) {
            log("diag FAILED: " + t);
        }
    }

    /**
     * 有界采样。
     *
     * 为什么需要：JMComic3 是 SPA，react-router 站内跳转**不触发** onPageFinished，
     * 所以只看 onPageFinished 就永远看不到站内任何一页（详情页 / 阅读页 / 「我的」）的真实状态。
     * 这里在每次整页加载后开一条有界的采样链：每 3s 回读一次，最多 100 次
     * （约 5 分钟：每 3s × 100 次）后自行停下；因为 diagnose 内部用 logOnce，同一份结论只会落一行，
     * 所以不会刷屏（探针不得刷爆日志）。
     */
    private void scheduleSampling(final WebView wv) {
        if (wv == null) {
            return;
        }
        samplesLeft = SAMPLE_MAX;
        if (samplingChain) {
            return;
        }
        samplingChain = true;
        try {
            wv.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (samplesLeft > 0) {
                            samplesLeft--;
                            diagnose(wv);
                            wv.postDelayed(this, SAMPLE_INTERVAL_MS);
                        } else {
                            samplingChain = false;
                            // 兜底：采样链跑完还没等到同意位（用户一直停在封面 / 没点同意），
                            // 就把排队的弹窗按边界弹一次 —— 宁可弹得晚，也不能静默丢掉。
                            onCoversDone(wv, "采样链结束兜底");
                            retryIfPending(wv, "采样链结束兜底");
                        }
                    } catch (Throwable t) {
                        samplingChain = false;
                        log("sampling stopped: " + t);
                    }
                }
            }, SAMPLE_INTERVAL_MS);
        } catch (Throwable t) {
            samplingChain = false;
            log("scheduleSampling FAILED: " + t);
        }
    }

    // =======================================================================
    // 自动每日签到
    // =======================================================================

    /**
     * 在页面加载完成后，用 App 自己的会话去调 App 自己的签到接口。
     *
     * 三条边界，都是刻意的：
     *   1. **不改 App 任何状态**。不碰 localStorage、不 drive DOM、不 no-op App 的方法。
     *      只额外发两个 HTTP 请求（与 App 自身的请求互不相干，不参与它的状态机），
     *      所以没有"掐断 App 收尾"的风险（经验：贴着出口拦，不掐断 App 收尾）。
     *   2. **不猜今天**。先读 /daily 拿 daily_id 与当日格子；本地算出来的日期如果在月度
     *      日历里找不到对应条目（时区/跨月），**不据此跳过**，而是照样发一次签到请求 ——
     *      宁可多一次幂等请求，也不要因为本地时钟错位而静默不签到。
     *   3. **回读才算数**。服务端回包只是"主张"：最后一定再读一次 /daily，看到今天的格子
     *      真的变成 signed=true 才报 VERIFY_OK，否则报 VERIFY_FAIL（禁止静默失效）。
     */
    private void autoCheckIn(final WebView wv) {
        if (!AUTO_CHECK_IN) {
            logOnce("checkin: 已由 AUTO_CHECK_IN=false 关闭");
            return;
        }
        if (wv == null || !checkInStarted.compareAndSet(false, true)) {
            return;
        }
        harvestAndRun(wv, false);
    }

    /**
     * 取会话并开跑。isRetry=true 表示这是「封面走完后」的那次重试（仅一次）。
     *
     * 为什么要重试：实测发现 App 启动时存在一个「先 clearAuth 清掉过期 token →
     * 再用明文账号密码 saveAuthData 自动重登」的窗口（2026-10-01 验收期间实测到
     * diag 的 `jwt:false` 与模块的「未登录」同时出现，而 WAL 里 jwttoken 末次操作是 PUT）。
     * 而本实现只在 onPageFinished 取一次会话，所以有概率落进这个窗口而当次跳过 ——
     * 重试就是为了收窄这个窗口，而不是把「跳过」当正常结果。
     */
    private void harvestAndRun(final WebView wv, final boolean isRetry) {
        try {
            wv.evaluateJavascript(CHECKIN_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    JSONObject sess = parseJsResult(value, "checkin");
                    if (sess == null) {
                        return;
                    }
                    if (sess.has("err")) {
                        log("checkin: 会话读取异常 -> " + sess.optString("err"));
                        return;
                    }
                    if (isRetry) {
                        log("checkin: 重试取会话成功（covers=" + sess.optBoolean("covers") + "）");
                    }
                    // 调试开关：只在没有真内容排队时插一条样例，真弹窗内容优先
                    if (DEBUG_TOAST_ONCE && !debugToastShown && pendingToast.get() == null) {
                        debugToastShown = true;
                        log("checkin: DEBUG 样例弹窗已排队（DEBUG_TOAST_ONCE 临时打开）");
                        toast(wv, "【调试】签到提醒弹窗样式演示（验完请让 Agent 关掉此开关）");
                    }
                    if (sess.optBoolean("covers")) {
                        onCoversDone(wv, "取会话时 state 已置位");
                    }
                    runCheckIn(sess, wv, isRetry);
                }
            });
        } catch (Throwable t) {
            log("checkin: 注入取会话脚本失败: " + t);
        }
    }

    /** 真正干活的线程：网络 + 解密走后台线程（主线程做网络会直接崩）。 */
    private void runCheckIn(final JSONObject sess, final WebView wv, final boolean isRetry) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    checkInBody(sess, wv, isRetry);
                } catch (Throwable t) {
                    // 任何异常都要出声（一个异常带走一串 / 静默失效）
                    log("checkin: 异常中止 -> " + t);
                }
            }
        }, "FriendlySchool-CheckIn");
        th.setDaemon(true);
        th.start();
    }

    private void checkInBody(JSONObject sess, WebView wv, boolean isRetry) {
        String apiUrl = sess.optString("apiUrl");
        String jwt = sess.optString("jwt");
        String uid = sess.optString("uid");
        if (apiUrl.length() == 0) {
            log("checkin: 跳过 —— localStorage 里还没有 apiUrl（App 还没测出线路）"
                    + (isRetry ? "；重试后仍然没有" : "；等封面走完再试一次"));
            if (!isRetry) {
                retryAfterCovers = true;
            }
            return;
        }
        if (jwt.length() == 0 || uid.length() == 0) {
            log("checkin: 跳过 —— 未登录（无 jwttoken/uid）"
                    + (isRetry ? "；重试后仍未登录，本次作吧" : "；等封面走完再试一次"));
            if (!isRetry) {
                retryAfterCovers = true;
            }
            return;
        }
        if (isRetry) {
            log("checkin: 重试时已登录，继续签到");
        }

        String today = new SimpleDateFormat("dd", Locale.US).format(new Date());

        // 1) 读签到日历
        JSONObject r1 = api(sess, "daily?user_id=" + uid, null);
        JSONObject d1 = asObject(r1 == null ? null : r1.opt("data"));
        if (r1 == null || r1.optInt("code") != 200 || d1 == null) {
            log("checkin: 读取签到日历失败 -> " + brief(r1));
            return;
        }

        int dailyId = d1.optInt("daily_id");
        JSONObject entry = findDay(d1.optJSONArray("record"), today);
        boolean signed = entry != null && entry.optBoolean("signed");
        if (entry == null) {
            log("checkin: 本地日期 " + today + " 在月度日历里找不到条目（时区/跨月?），仍继续尝试签到");
        }

        boolean justSigned = false;
        String signedMsg = null;
        if (signed) {
            log("checkin: 今天（" + today + "）已签到，跳过");
        } else if (dailyId <= 0) {
            log("checkin: daily_id 缺失，放弃（不猜）");
            return;
        } else {
            JSONObject r2 = api(sess, "daily_chk", "user_id=" + uid + "&daily_id=" + dailyId);
            JSONObject d2 = asObject(r2 == null ? null : r2.opt("data"));
            String msg = d2 == null ? "" : d2.optString("msg", "");
            boolean dup = msg.contains("已經簽到") || msg.contains("已经签到");
            boolean ok = r2 != null && r2.optInt("code") == 200 && !dup;
            log("checkin: 签到回包 code=" + (r2 == null ? "?" : r2.optInt("code"))
                    + " dup=" + dup + " msg=" + msg);
            justSigned = ok;
            if (ok) {
                signedMsg = msg;
            }
        }

        // 2) 回读校验：服务端说的不算，格子变 true 才算
        JSONObject r3 = api(sess, "daily?user_id=" + uid, null);
        JSONObject d3 = asObject(r3 == null ? null : r3.opt("data"));
        JSONObject e3 = d3 == null ? null : findDay(d3.optJSONArray("record"), today);
        if (e3 != null && e3.optBoolean("signed")) {
            log("checkin: VERIFY_OK 已确认今天（" + today + "）签到，进度=" + d3.optString("currentProgress", "?"));
            if (justSigned) {
                // 弹窗文案：服务端返回的原文（如 `Jcoin:10 EXP:10`）本身看不出是什么，加前缀
                toast(wv, "签到成功：" + (signedMsg == null || signedMsg.length() == 0 ? "已完成" : signedMsg));
                if (allSigned(d3.optJSONArray("record"))) {
                    claimFullMonth(sess, wv);
                }
            }
        } else {
            log("checkin: VERIFY_FAIL —— 服务端未记录今天的签到！回读=" + brief(r3));
            // 失败弹窗一天只弹一次：靠模块自己的 localStorage 键记着（App 不读它）
            if (today.equals(sess.optString("failDay"))) {
                log("checkin: 失败弹窗今天（" + today + "）已弹过，不再重复");
            } else {
                toast(wv, "自动签到失败：服务端未记录（详见模块日志）");
                markFailDay(wv, today);
            }
        }
    }

    /** 全月签满时的"全签奖励"领取，条件与 App 里那个按钮一致（record 里每一天都 signed=true）。 */
    private void claimFullMonth(JSONObject sess, WebView wv) {
        String month = new SimpleDateFormat("M", Locale.US).format(new Date());
        JSONObject r = api(sess, "daily_list/filter", "data=" + month);
        JSONObject d = asObject(r == null ? null : r.opt("data"));
        String msg = d == null ? "" : d.optString("msg", "");
        log("checkin: 全签奖励 code=" + (r == null ? "?" : r.optInt("code"))
                + " status=" + (d == null ? "?" : d.optString("status"))
                + " msg=" + msg);
        // 只有服务端明确说 ok 才弹，避免把失败态误当成领奖成功
        if (d != null && "ok".equals(d.optString("status"))) {
            toast(wv, "全签奖励：" + (msg.length() == 0 ? "已领取" : msg));
        }
    }

    /**
     * 弹一条 Toast —— 但**先排队**，不在调用点直接弹。
     *
     * 为什么排队（v1.9 修正）：JMComic3 启动要穿过「测线路 → 3s 开屏(带❌) → 同意18岁」
     * 好几层封面，在封面阶段弹会盖在封面页上、用户正在点 ❌ 和同意，基本看不到。
     * 所以等 **App 自己的同意位** `sessionStorage.state === 'true'`（ThreeCover.tsx:79 /
     * FourCover.tsx:64 写入）置位后再弹 —— 用 App 的信号，不自己造延时。
     */
    private void toast(WebView wv, String text) {
        if (!TOAST_CHECKIN) {
            log("checkin: toast(已关) -> " + text);
            return;
        }
        pendingToast.set(text);
        log("checkin: toast 排队 -> " + text);
        flushToast(wv, coversDoneSeen);
    }

    /**
     * 真正弹出。
     *
     * Toast.show() 必须在有 Looper 的线程上（后台线程直调会抛
     * "Can't create handler inside thread that has not called Looper.prepare()"），
     * 所以用 WebView.post 回主线程。弹窗失败不能影响签到本身，所以单独 try/catch，
     * 失败也出声。无论弹不弹都会落一行日志 —— 本机 logcat 是空的，
     * 「弹了没」必须有文件证据。
     */
    private void flushToast(final WebView wv, boolean coversDone) {
        if (!TOAST_CHECKIN || wv == null || !coversDone) {
            return;
        }
        final String text = pendingToast.getAndSet(null);
        if (text == null) {
            return;
        }
        try {
            wv.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context ctx = wv.getContext();
                        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        log("checkin: toast 失败 -> " + t);
                    }
                }
            });
            log("checkin: toast -> " + text);
        } catch (Throwable t) {
            pendingToast.set(text);      // 派发失败就放回去，下一次 tick 还会试
            log("checkin: toast 派发失败 -> " + t);
        }
    }

    /**
     * 「同意18岁」位置位时（或采样链兜底）调：把排队的弹窗弹出去。
     *
     * 注意：**重试不在这里**（2026-10-01 改）—— 早做那次若跳过，重试以 diag 回读
     * 报 `jwt=true` 为准触发，见 {@link #retryIfPending}。
     */
    private void onCoversDone(final WebView wv, String why) {
        if (!coversDoneSeen) {
            coversDoneSeen = true;
            log("checkin: 封面已走完（" + why + "）");
        }
        flushToast(wv, true);
        // 重试**不在这里**做：以 diag 回读报 `jwt=true` 为准触发（见 diagnose），
        // 采样链跑完时再兜底一次（见 scheduleSampling）。
    }

    /**
     * 早做那次因「未登录 / 还没测出线路」而跳过时，重试一次取会话（**仅一次**，不是轮询）。
     *
     * 调用点两处，优先级从高到低：
     *   1. diag 回读报 `jwt:true` —— 登录真的回来了，立刻做；
     *   2. 采样链跑完（约 5 分钟）—— 兜底，宁可晚也不能静默不试。
     */
    private void retryIfPending(final WebView wv, String why) {
        if (!retryAfterCovers) {
            return;
        }
        retryAfterCovers = false;
        log("checkin: 按计划重试一次取会话（" + why + "）");
        harvestAndRun(wv, true);
    }

    /**
     * 记下「失败弹窗今天已弹过」。
     *
     * 写的是模块**自己**的 localStorage 键（{@link #LS_CHECKIN}，App 从不读它，
     * 与已有的 `__jmAdFree` 同一性质），不碰 App 的任何业务状态。
     * 不写这个就只能做到「一进程一次」，重启 App 又会弹。
     */
    private void markFailDay(final WebView wv, final String day) {
        if (wv == null) {
            return;
        }
        try {
            wv.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        wv.evaluateJavascript(
                                "try{localStorage.setItem('" + LS_CHECKIN + "',JSON.stringify({failDay:'"
                                        + day + "',t:Date.now()}))}catch(e){}", null);
                        log("checkin: 已记 " + LS_CHECKIN + ".failDay=" + day);
                    } catch (Throwable t) {
                        log("checkin: 写 " + LS_CHECKIN + " 失败 -> " + t);
                    }
                }
            });
        } catch (Throwable t) {
            log("checkin: 写 " + LS_CHECKIN + " 派发失败 -> " + t);
        }
    }

    // ------------------------------------------------------- 网络 / 加解密

    /**
     * 一次 API 调用。form 为 null 时是 GET，否则是表单 POST。
     *
     * 签名与解密口径逐字对齐 api/HttpUtil.ts：
     *   Tokenparam = "<ts>,<ver>"；Token = md5(ts + secret)；回包 data 用同一个 md5 串做 AES-256-ECB 密钥。
     */
    private JSONObject api(JSONObject sess, String pathAndQuery, String form) {
        HttpURLConnection c = null;
        try {
            String base = sess.optString("apiUrl");
            if (!base.endsWith("/")) {
                base = base + "/";
            }
            String lang = sess.optString("lang");
            if (lang.length() == 0) {
                lang = "TW";
            }
            long ts = System.currentTimeMillis() / 1000L;
            String key = md5Hex(ts + API_SECRET);

            URL url = new URL(base + pathAndQuery + (pathAndQuery.indexOf('?') >= 0 ? "&" : "?") + "lang=" + lang);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(HTTP_TIMEOUT_MS);
            c.setReadTimeout(HTTP_TIMEOUT_MS);
            c.setRequestProperty("Tokenparam", ts + "," + APP_VERSION);
            c.setRequestProperty("Token", key);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("User-Agent", UA);
            String jwt = sess.optString("jwt");
            if (jwt.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + jwt);
            }
            String avs = sess.optString("s");
            if (avs.length() > 0) {
                c.setRequestProperty("Cookie", "AVS=" + avs);
            }

            if (form != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = c.getOutputStream();
                os.write(form.getBytes("UTF-8"));
                os.flush();
                os.close();
            }

            int code = c.getResponseCode();
            // 400/401 的业务错误也带 JSON 回包，必须读正文，不能吞
            InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            String text = is == null ? "" : readAll(is);
            JSONObject o = new JSONObject(text);
            o.put("_http", code);

            Object data = o.opt("data");
            if (data instanceof String) {
                String dec = aesDecrypt((String) data, key);
                if (dec == null) {
                    o.put("_decrypt", false);
                    log("checkin: 回包解密失败（密钥/口径变了？）path=" + pathAndQuery);
                } else {
                    o.put("_decrypt", true);
                    JSONObject inner = null;
                    try {
                        inner = new JSONObject(dec);
                    } catch (Throwable ignored) {
                    }
                    o.put("data", inner != null ? inner : dec);
                }
            }
            return o;
        } catch (Throwable t) {
            log("checkin: 请求失败 path=" + pathAndQuery + " -> " + t);
            return null;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** AES-256-ECB/PKCS5(base64)。密钥 = md5 串的 32 个 UTF-8 字节（CryptoJS 传 WordArray 时不做 KDF）。 */
    private static String aesDecrypt(String b64, String keyHex) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyHex.getBytes("UTF-8"), "AES"));
            byte[] out = cipher.doFinal(Base64.decode(b64, Base64.DEFAULT));
            return new String(out, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String md5Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(32);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ------------------------------------------------------------ 小工具

    /** evaluateJavascript 的回调值是"被 JS 字符串字面量包住"的结果，先解一层再当 JSON 用。 */
    private JSONObject parseJsResult(String value, String label) {
        if (value == null || "null".equals(value) || value.length() == 0) {
            log(label + ": 页面返回空（脚本没跑或被 CSP 挡了）");
            return null;
        }
        String json = value;
        try {
            Object v = new JSONTokener(value).nextValue();
            if (v instanceof String) {
                json = (String) v;
            }
        } catch (Throwable ignored) {
        }
        try {
            return new JSONObject(json);
        } catch (Throwable t) {
            log(label + ": 结果不是 JSON -> " + value);
            return null;
        }
    }

    private static JSONObject asObject(Object o) {
        return (o instanceof JSONObject) ? (JSONObject) o : null;
    }

    /** 在 record（[[{date,signed,bonus}...]...]）里找指定日期的那一格。 */
    private static JSONObject findDay(JSONArray record, String dd) {
        if (record == null) {
            return null;
        }
        for (int i = 0; i < record.length(); i++) {
            JSONArray week = record.optJSONArray(i);
            if (week == null) {
                continue;
            }
            for (int j = 0; j < week.length(); j++) {
                JSONObject it = week.optJSONObject(j);
                if (it != null && dd.equals(it.optString("date"))) {
                    return it;
                }
            }
        }
        return null;
    }

    private static boolean allSigned(JSONArray record) {
        if (record == null || record.length() == 0) {
            return false;
        }
        int n = 0;
        for (int i = 0; i < record.length(); i++) {
            JSONArray week = record.optJSONArray(i);
            if (week == null) {
                continue;
            }
            for (int j = 0; j < week.length(); j++) {
                JSONObject it = week.optJSONObject(j);
                if (it == null) {
                    continue;
                }
                n++;
                if (!it.optBoolean("signed")) {
                    return false;
                }
            }
        }
        return n > 0;
    }

    /** 只取足够定位问题的信息，别把整个回包打日志。 */
    private static String brief(JSONObject o) {
        if (o == null) {
            return "null（请求异常，见上一行）";
        }
        return "http=" + o.opt("_http") + " code=" + o.opt("code")
                + " decrypt=" + o.opt("_decrypt") + " data=" + String.valueOf(o.opt("data")).substring(0, Math.min(120, String.valueOf(o.opt("data")).length()));
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
}
