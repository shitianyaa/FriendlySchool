#!/usr/bin/env bash
# FriendlySchool —— Linux 无 Gradle 构建（libxposed API 102）
# javac -> jar -> d8 -> aapt2 -> aapt add -> zipalign -> apksigner -> verify
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD="$ROOT/module"
OUT="$MOD/build"
API="$MOD/lib/api-102.jar"
KS="$MOD/mod.keystore"
META_DIR="$MOD/meta/META-INF/xposed"
MANIFEST="$MOD/AndroidManifest.xml"
APPDIR="${APPDIR:-$ROOT/dist}"
APK_NAME="FriendlySchool-LSPosed-v2.4.1.apk"

# Arch/CachyOS 的 SDK 默认安装在 /opt/android-sdk；其他位置可通过环境变量覆盖。
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
BT="${BT:-$SDK_ROOT/build-tools/34.0.0}"
AJ="${AJ:-$SDK_ROOT/platforms/android-34/android.jar}"
if [ -n "${JAVA_HOME:-}" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
  KEYTOOL="$JAVA_HOME/bin/keytool"
else
  JAVAC="javac"
  JAR="jar"
  KEYTOOL="keytool"
fi

# Build Tools 34 的旧 D8 无法处理 JDK 21 生成的无名参数元数据。
# 使用新版命令行工具中的 D8；也可用 D8 环境变量指定兼容版本的绝对路径。
D8="${D8:-$SDK_ROOT/cmdline-tools/latest/bin/d8}"
AAPT2="$BT/aapt2"
AAPT="$BT/aapt"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

fail() {
  echo "$*" >&2
  exit 1
}

for tool in "$D8" "$AAPT2" "$AAPT" "$ZIPALIGN" "$APKSIGNER" \
  "$JAVAC" "$JAR" "$KEYTOOL" unzip; do
  command -v "$tool" >/dev/null 2>&1 || fail "缺少可执行工具: $tool"
done
for file in "$AJ" "$API" "$MANIFEST" \
  "$META_DIR/java_init.list" "$META_DIR/module.prop" "$META_DIR/scope.list"; do
  [ -f "$file" ] || fail "缺少依赖文件: $file"
done

ENTRY_CLASS="$(tr -d ' \r\n' < "$META_DIR/java_init.list")"

echo "[0/7] 一致性检查：代码 TARGET_PKG <-> meta/META-INF/xposed/scope.list"
CODE_PKGS="$(grep -rhoE --include='*.java' 'TARGET_PKG = "[^"]+"' "$MOD/src" \
  | sed -E 's/.*"([^"]+)".*/\1/' | sort -u)"
SCOPE_PKGS="$(grep -vE '^\s*(#|$)' "$META_DIR/scope.list" | tr -d ' \r' | sort -u)"
if [ "$CODE_PKGS" != "$SCOPE_PKGS" ]; then
  printf '代码里的目标包名与静态作用域声明不一致：\n--- 代码 TARGET_PKG:\n%s\n--- scope.list:\n%s\n' \
    "$CODE_PKGS" "$SCOPE_PKGS" >&2
  exit 1
fi
printf '%s\n' "$CODE_PKGS"

echo "[0b/7] 入口类与 java_init.list 一致性检查"
grep -rF --include='*.java' "class ${ENTRY_CLASS##*.} extends XposedModule" "$MOD/src" >/dev/null \
  || fail "入口类 $ENTRY_CLASS 不是 XposedModule 子类，拒绝出包"
echo "        $ENTRY_CLASS extends XposedModule"

mapfile -d '' -t SRCS < <(find "$MOD/src" -type f -name '*.java' -print0 | sort -z)
[ "${#SRCS[@]}" -gt 0 ] || fail "没有找到 Java 源文件: $MOD/src"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/res"

echo "[1/7] javac -> class（${#SRCS[@]} 个源文件）"
"$JAVAC" -J-Duser.language=en -J-Duser.country=US \
  -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp "$AJ:$API" -d "$OUT/classes" "${SRCS[@]}"

echo "[2/7] jar（d8 不接受目录）"
"$JAR" cf "$OUT/classes.jar" -C "$OUT/classes" .

echo "[3/7] d8 -> classes.dex（api-102.jar 只作 --lib，不进 dex）"
"$D8" --min-api 26 --lib "$AJ" --lib "$API" --output "$OUT" "$OUT/classes.jar"

echo "[4/7] aapt2 compile 资源"
"$AAPT2" compile --dir "$MOD/res" -o "$OUT/res/res.zip"

echo "[5/7] aapt2 link -> base.apk"
"$AAPT2" link -o "$OUT/base.apk" -I "$AJ" \
  --manifest "$MANIFEST" --min-sdk-version 26 --target-sdk-version 28 \
  "$OUT/res/res.zip"

echo "[6/7] 加入 dex 与现代打包元数据（签名前写入 APK 根目录）"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT" && "$AAPT" add unsigned.apk classes.dex >/dev/null )
( cd "$MOD/meta" && "$AAPT" add "$OUT/unsigned.apk" \
  META-INF/xposed/module.prop META-INF/xposed/scope.list META-INF/xposed/java_init.list >/dev/null )

echo "[7/7] zipalign + 签名"
if [ ! -e "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias mod \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=friendlyschool, O=local"
  echo "      新建 keystore（之后重建复用）: $KS"
else
  echo "      复用已有 keystore: $KS"
fi
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android \
  --key-pass pass:android --v2-signing-enabled true \
  --out "$OUT/module.apk" "$OUT/aligned.apk"

echo
echo "=== 构建期验证 ==="
"$ZIPALIGN" -c -p 4 "$OUT/module.apk"
"$APKSIGNER" verify --print-certs "$OUT/module.apk"

echo "-- APK 根目录下的 META-INF/xposed/（现代打包三件套）："
APK_ENTRIES="$(unzip -Z1 "$OUT/module.apk")"
for want in META-INF/xposed/java_init.list META-INF/xposed/module.prop META-INF/xposed/scope.list; do
  grep -Fx "$want" <<< "$APK_ENTRIES" || fail "APK 缺少 $want，模块将无法生效"
done

if grep -Fx 'assets/xposed_init' <<< "$APK_ENTRIES" >/dev/null; then
  fail "APK 中不应存在 legacy 入口 assets/xposed_init"
fi
echo "   ok  没有 assets/xposed_init"

APK_INIT="$(unzip -p "$OUT/module.apk" META-INF/xposed/java_init.list | tr -d ' \r\n')"
[ "$APK_INIT" = "$ENTRY_CLASS" ] || fail "APK 的 java_init.list 与入口类不一致"

echo "-- APK 内 module.prop："
unzip -p "$OUT/module.apk" META-INF/xposed/module.prop
echo "-- APK 内 scope.list："
unzip -p "$OUT/module.apk" META-INF/xposed/scope.list

echo "-- dex 内含入口类："
# 必须读完整个流；grep -q 提前退出会使 unzip 在 pipefail 下因 SIGPIPE 误报失败。
if unzip -p "$OUT/module.apk" classes.dex | grep -aF "L${ENTRY_CLASS//./\/};" >/dev/null; then
  echo "   ok  L${ENTRY_CLASS//./\/};"
else
  fail "入口类不在 dex 里"
fi

echo "-- manifest："
"$AAPT2" dump xmltree "$OUT/module.apk" --file AndroidManifest.xml \
  | grep -E 'versionName|versionCode|minSdkVersion|description|xposed'

echo
echo "=== 产物 ==="
"$JAR" tf "$OUT/module.apk"
sha256sum "$OUT/module.apk"

mkdir -p "$APPDIR"
FINAL="$APPDIR/$APK_NAME"
cp "$OUT/module.apk" "$FINAL"
echo "已复制构建产物: $FINAL"
