#!/usr/bin/env bash
# FriendlySchool —— Linux 无 Gradle 构建（libxposed API 102）
# javac -> jar -> d8 -> aapt2 -> aapt add -> zipalign -> apksigner -> verify
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD="$ROOT/module"
OUT="$MOD/build"
LOCK_FILE="$MOD/.build-linux.lock"
API="$MOD/lib/api-102.jar"
KS="${KEYSTORE:-$MOD/mod.keystore}"
META_DIR="$MOD/meta/META-INF/xposed"
MANIFEST="$MOD/AndroidManifest.xml"
APPDIR="${APPDIR:-$ROOT/dist}"
APK_NAME="FriendlySchool-LSPosed-v2.4.1.apk"

fail() {
  echo "$*" >&2
  exit 1
}

# Arch/CachyOS 的 SDK 默认安装在 /opt/android-sdk；其他位置可通过环境变量覆盖。
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
if [ -z "${BT:-}" ] && [ ! -d "$SDK_ROOT/build-tools" ]; then
  fail "SDK 根目录无效（缺少 build-tools）: $SDK_ROOT；请检查 ANDROID_HOME / ANDROID_SDK_ROOT，或显式设置 BT"
fi
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

KEY_TMP_DIR=""
FINAL_TMP=""
cleanup() {
  if [ -n "$KEY_TMP_DIR" ]; then
    rm -rf -- "$KEY_TMP_DIR"
  fi
  if [ -n "$FINAL_TMP" ]; then
    rm -f -- "$FINAL_TMP"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

command -v "$D8" >/dev/null 2>&1 \
  || fail "缺少可执行 D8 工具: $D8；请安装 Android SDK Command-Line Tools 22.0，或通过 D8 指定兼容版本"
for tool in "$AAPT2" "$AAPT" "$ZIPALIGN" "$APKSIGNER" \
  "$JAVAC" "$JAR" "$KEYTOOL" unzip flock realpath stat; do
  command -v "$tool" >/dev/null 2>&1 || fail "缺少可执行工具: $tool"
done
for file in "$AJ" "$API" "$MANIFEST" \
  "$META_DIR/java_init.list" "$META_DIR/module.prop" "$META_DIR/scope.list"; do
  [ -f "$file" ] || fail "缺少依赖文件: $file"
done

# 密码通过环境变量交给工具，不放入命令行参数或使用隐式公开默认值。
[ -n "${KEYSTORE_PASSWORD:-}" ] || fail "请通过 KEYSTORE_PASSWORD 明确提供签名密码；已有密钥仍使用原密码"
KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
export KEYSTORE_PASSWORD KEY_PASSWORD
# 先检查物理路径（含符号链接），避免清理输出、打开锁文件或交付 APK 时破坏密钥。
KS_REAL="$(realpath -m -- "$KS")"
OUT_REAL="$(realpath -m -- "$OUT")"
case "$KS_REAL" in
  "$OUT_REAL"|"$OUT_REAL"/*)
    fail "签名密钥不能位于会被清理的构建输出目录内: $KS"
    ;;
esac
if [ "$KS_REAL" = "$(realpath -m -- "$APPDIR/$APK_NAME")" ] \
    || [ "$KS_REAL" = "$(realpath -m -- "$LOCK_FILE")" ]; then
  fail "签名密钥不能与交付 APK 或构建锁共用路径: $KS"
fi
[ ! -e "$KS" ] || [ -f "$KS" ] || fail "签名密钥不是普通文件: $KS"
if [ -n "${KEYSTORE:-}" ]; then
  [ -f "$KS" ] || fail "外部 KEYSTORE 必须已存在，不自动创建发布签名身份: $KS"
  KS_MODE="$(stat -Lc '%a' -- "$KS")"
  (( (8#$KS_MODE & 077) == 0 )) \
    || fail "外部 KEYSTORE 不应向组或其他用户开放权限，请收紧为 0600 或 0400: $KS"
elif [ ! -e "$KS" ]; then
  [ "${#KEYSTORE_PASSWORD}" -ge 12 ] || fail "新建本地密钥的 KEYSTORE_PASSWORD 至少需要 12 个字符"
  [ "$KEY_PASSWORD" = "$KEYSTORE_PASSWORD" ] \
    || fail "新建 PKCS12 密钥要求 KEY_PASSWORD 与 KEYSTORE_PASSWORD 相同"
fi
SIGNING_ARGS=(--ks "$KS" --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD)
if [ -n "${KEY_ALIAS:-}" ]; then
  SIGNING_ARGS+=(--ks-key-alias "$KEY_ALIAS")
fi

# 锁文件不能放进会被 rm -rf 的 OUT；保持同一 inode 才能覆盖整个构建周期。
exec {BUILD_LOCK_FD}>"$LOCK_FILE"
flock -n "$BUILD_LOCK_FD" || fail "已有构建正在运行，未清理输出: $LOCK_FILE"
if [ -z "${KEYSTORE:-}" ] && [ -f "$KS" ]; then
  chmod 600 -- "$KS"
fi

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
  [ -z "${KEYSTORE:-}" ] || fail "外部 KEYSTORE 已不可用，拒绝重新生成签名身份: $KS"
  # 临时目录与最终密钥位于同一文件系统；只有 keytool 成功后才发布密钥。
  # 不直接使用 mktemp 创建的空文件，keytool 会将其视为已存在的损坏 keystore。
  KEY_TMP_DIR="$(mktemp -d "$MOD/mod.keystore.tmp.XXXXXX")"
  (
    umask 077
    "$KEYTOOL" -genkeypair -keystore "$KEY_TMP_DIR/mod.keystore" -alias "${KEY_ALIAS:-mod}" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass:env KEYSTORE_PASSWORD -keypass:env KEY_PASSWORD -dname "CN=friendlyschool, O=local"
  )
  mv -- "$KEY_TMP_DIR/mod.keystore" "$KS"
  echo "      新建 keystore（之后重建复用）: $KS"
else
  echo "      复用已有 keystore: $KS"
fi
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$APKSIGNER" sign "${SIGNING_ARGS[@]}" --v2-signing-enabled true \
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
# 临时文件必须位于交付目录，确保最后的 rename 原子替换；失败时保留旧 APK。
FINAL_TMP="$(mktemp "$APPDIR/.${APK_NAME}.tmp.XXXXXX")"
cp --preserve=mode -- "$OUT/module.apk" "$FINAL_TMP"
mv -f -- "$FINAL_TMP" "$FINAL"
FINAL_TMP=""
echo "已复制构建产物: $FINAL"
