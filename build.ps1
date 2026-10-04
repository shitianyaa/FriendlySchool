# PowerShell 原生构建 FriendlySchool 校园净化 LSPosed 模块（libxposed 现代打包 · API 102）
# 严格与 build.sh 保持一致，支持一键在 Windows 环境下重新编译、构建与签名
$ErrorActionPreference = "Stop"

$BT = if ($env:BT_W) { $env:BT_W } else { "D:\AndroidSDK\build-tools\34.0.0" }
$AJ = if ($env:AJ_W) { $env:AJ_W } else { "D:\AndroidSDK\platforms\android-34\android.jar" }
$JDK = if ($env:JDK_W) { $env:JDK_W } else { "D:\JAVA\bin" }

$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
$MOD = Join-Path $SCRIPT_DIR "module"
$API = Join-Path $MOD "lib\api-102.jar"
$OUT = Join-Path $MOD "build"
$KS = Join-Path $MOD "mod.keystore"
$RES = Join-Path $MOD "res"
$META = Join-Path $MOD "meta"
$META_X = Join-Path $META "META-INF\xposed"
$MANIFEST = Join-Path $MOD "AndroidManifest.xml"

$APP_DIR = if ($env:APPDIR) { $env:APPDIR } else { Join-Path $SCRIPT_DIR "dist" }
$APK_NAME = "FriendlySchool-LSPosed-v2.2.apk"

$D8 = Join-Path $BT "d8.bat"
$AAPT2 = Join-Path $BT "aapt2.exe"
$AAPT = Join-Path $BT "aapt.exe"
$ZIPALIGN = Join-Path $BT "zipalign.exe"
$APKSIGNER = Join-Path $BT "apksigner.bat"
$JAVAC = Join-Path $JDK "javac.exe"
$JAR = Join-Path $JDK "jar.exe"
$KEYTOOL = Join-Path $JDK "keytool.exe"

foreach ($f in @($D8, $AAPT2, $AAPT, $ZIPALIGN, $APKSIGNER, $JAVAC, $JAR, $KEYTOOL, $AJ, $API,
                  (Join-Path $META_X "java_init.list"), (Join-Path $META_X "module.prop"),
                  (Join-Path $META_X "scope.list"))) {
    if (-not (Test-Path -LiteralPath $f)) {
        Write-Error "缺少依赖工具或文件: $f"
        exit 1
    }
}

$ENTRY_CLASS = (Get-Content -Path (Join-Path $META_X "java_init.list") -Raw).Trim()

Write-Host "[0/7] 一致性检查：代码 TARGET_PKG <-> meta/META-INF/xposed/scope.list" -ForegroundColor Cyan
$codePkgs = Get-ChildItem -Path (Join-Path $MOD "src") -Filter "*.java" -Recurse |
    Select-String -Pattern 'TARGET_PKG\s*=\s*"([^"]+)"' |
    ForEach-Object { $_.Matches.Groups[1].Value } |
    Sort-Object -Unique

$scopePkgs = Get-Content -Path (Join-Path $META_X "scope.list") |
    Where-Object { $_ -and -not $_.StartsWith("#") } |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -ne "" } |
    Sort-Object -Unique

$diff = Compare-Object -ReferenceObject $codePkgs -DifferenceObject $scopePkgs
if ($diff) {
    Write-Error "代码里的目标包名与静态作用域声明不一致！"
    exit 1
}
foreach ($pkg in $codePkgs) {
    Write-Host "        $pkg"
}

Write-Host "[0b/7] 入口类与 java_init.list 一致性检查" -ForegroundColor Cyan
$entrySimple = $ENTRY_CLASS.Split(".")[-1]
$entryOk = Get-ChildItem -Path (Join-Path $MOD "src") -Filter "$entrySimple.java" -Recurse |
    Select-String -Pattern "class\s+$entrySimple\s+extends\s+XposedModule"
if (-not $entryOk) {
    Write-Error "入口类 $ENTRY_CLASS 不是 XposedModule 子类 —— 现代打包下会被静默跳过，拒绝出包"
    exit 1
}
Write-Host "        $ENTRY_CLASS extends XposedModule"

if (Test-Path -LiteralPath $OUT) {
    Remove-Item -LiteralPath $OUT -Recurse -Force
}
New-Item -ItemType Directory -Path (Join-Path $OUT "classes") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $OUT "res") -Force | Out-Null

$srcFiles = Get-ChildItem -Path (Join-Path $MOD "src") -Filter "*.java" -Recurse |
    Select-Object -ExpandProperty FullName | Sort-Object

Write-Host "[1/7] javac -> class ($($srcFiles.Count) 个源文件)" -ForegroundColor Cyan
& $JAVAC -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$AJ;$API" -d "$OUT\classes" $srcFiles
if ($LASTEXITCODE -ne 0) { Write-Error "javac 编译失败"; exit 1 }

Write-Host "[2/7] jar" -ForegroundColor Cyan
& $JAR cf "$OUT\classes.jar" -C "$OUT\classes" .
if ($LASTEXITCODE -ne 0) { Write-Error "jar 打包失败"; exit 1 }

Write-Host "[3/7] d8 -> classes.dex（api-102.jar 只作 --lib，不进 dex）" -ForegroundColor Cyan
& $D8 --min-api 26 --lib $AJ --lib $API --output $OUT "$OUT\classes.jar"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath "$OUT\classes.dex")) {
    Write-Error "d8 构建 classes.dex 失败"
    exit 1
}

Write-Host "[4/7] aapt2 compile 资源（module_desc 字符串）" -ForegroundColor Cyan
& $AAPT2 compile --dir $RES -o "$OUT\res\res.zip"
if ($LASTEXITCODE -ne 0) { Write-Error "aapt2 compile 失败"; exit 1 }

Write-Host "[5/7] aapt2 link -> base.apk" -ForegroundColor Cyan
& $AAPT2 link -o "$OUT\base.apk" -I $AJ --manifest $MANIFEST --min-sdk-version 26 --target-sdk-version 28 "$OUT\res\res.zip"
if ($LASTEXITCODE -ne 0) { Write-Error "aapt2 link 失败"; exit 1 }

Write-Host "[6/7] 加入 dex 与现代打包元数据" -ForegroundColor Cyan
Copy-Item -LiteralPath "$OUT\base.apk" -Destination "$OUT\unsigned.apk" -Force
Push-Location $OUT
try {
    & $AAPT add unsigned.apk classes.dex | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Error "aapt add classes.dex 失败"; exit 1 }
} finally {
    Pop-Location
}
Push-Location $META
try {
    & $AAPT add "$OUT\unsigned.apk" META-INF/xposed/module.prop META-INF/xposed/scope.list META-INF/xposed/java_init.list | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Error "aapt add META-INF/xposed 失败"; exit 1 }
} finally {
    Pop-Location
}

Write-Host "[7/7] zipalign + 签名" -ForegroundColor Cyan
if (-not (Test-Path -LiteralPath $KS)) {
    & $KEYTOOL -genkeypair -keystore $KS -alias mod -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=friendlyschool, O=local" 2>$null
    Write-Host "      新建 keystore: $KS"
} else {
    Write-Host "      复用已有 keystore: $KS"
}

& $ZIPALIGN -f -p 4 "$OUT\unsigned.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { Write-Error "zipalign 失败"; exit 1 }
& $APKSIGNER sign --ks $KS --ks-pass pass:android --key-pass pass:android --v2-signing-enabled true --out "$OUT\module.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { Write-Error "apksigner 签名失败"; exit 1 }

Write-Host "`n=== 构建期验证 ===" -ForegroundColor Green
& $APKSIGNER verify --print-certs "$OUT\module.apk" | Select-Object -First 3

$apkEntries = & $JAR tf "$OUT\module.apk"
Write-Host "-- APK 根目录下的 META-INF/xposed/（现代打包三件套）："
$apkMeta = $apkEntries | Where-Object { $_ -like "META-INF/xposed/*" } | Sort-Object
$apkMeta | ForEach-Object { Write-Host "   $_" }
foreach ($want in @("META-INF/xposed/java_init.list", "META-INF/xposed/module.prop", "META-INF/xposed/scope.list")) {
    if ($apkMeta -notcontains $want) {
        Write-Error "缺少 $want ！现代打包下等于静默不生效"
        exit 1
    }
}

Write-Host "-- 确认没有回落 legacy 路径（assets/xposed_init 不该存在）："
if ($apkEntries -contains "assets/xposed_init") {
    Write-Error "APK 里还有 assets/xposed_init，会让『是不是现代打包』变得含糊"
    exit 1
}
Write-Host "   ok  没有 assets/xposed_init"

Write-Host "-- APK 内 java_init.list："
Write-Host "   $ENTRY_CLASS"

Write-Host "-- APK 内 module.prop："
Get-Content -LiteralPath (Join-Path $META_X "module.prop") | ForEach-Object { Write-Host "   $_" }

Write-Host "-- APK 内 scope.list："
Get-Content -LiteralPath (Join-Path $META_X "scope.list") | ForEach-Object { Write-Host "   $_" }

Write-Host "-- manifest（不该再有 xposed* meta-data，描述应指向资源）："
& $AAPT2 dump xmltree "$OUT\module.apk" --file AndroidManifest.xml |
    Select-String -Pattern "versionName|versionCode|minSdkVersion|description|xposed" |
    ForEach-Object { Write-Host "   $($_.Line.Trim())" }

Write-Host "`n=== 产物 ===" -ForegroundColor Green
Get-Item "$OUT\module.apk" | Format-List FullName, Length, LastWriteTime
$apkHash = (Get-FileHash -Path "$OUT\module.apk" -Algorithm SHA256).Hash
Write-Host "SHA-256: $apkHash"

if (-not (Test-Path -LiteralPath $APP_DIR)) {
    New-Item -ItemType Directory -Path $APP_DIR -Force | Out-Null
}
$FINAL_APK = Join-Path $APP_DIR $APK_NAME
Copy-Item -LiteralPath "$OUT\module.apk" -Destination $FINAL_APK -Force
Write-Host "已同步发布至交付目录: $FINAL_APK" -ForegroundColor Yellow
