#!/usr/bin/env bash
#
# FriendlySchool —— 桌面 JVM 断言测试
#
# 不需要真机/模拟器：module/test/FakeFramework.java 是 io.github.libxposed.api.XposedInterface
# 的假实现，hook().intercept() 会真的组链并真的调用原方法，所以 Xp / HookContext /
# SchoolTargetBase 的语义可以在桌面 JVM 上先验证一遍，再上真机。
#
# 参与编译的 production 源码由 sources.txt 白名单控制（迁移期间 legacy 与新 API 不能混编）。
set -euo pipefail
export MSYS2_ARG_CONV_EXCL='*'
w() { cygpath -w "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD="$(cd "$HERE/.." && pwd)"
# 编译产物放 module/build/ 下（那一层在 .gitignore 白名单外＝不入库）；
# 千万别放进 test/ —— test/ 是为了让测试源码入库才被放行的，产物落在那里会被一起提交。
OUT="$MOD/build/test-classes"

AJ_W="${AJ_W:-D:\\AndroidSDK\\platforms\\android-34\\android.jar}"
API_W="$MOD\\lib\\api-102.jar"
JDK_W="${JDK_W:-D:\\JAVA\\bin}"
JAVAC="$JDK_W\\javac.exe"
JAVA="$JDK_W\\java.exe"

for f in "$AJ_W" "$API_W" "$JAVAC" "$JAVA"; do
  [ -e "$f" ] || { echo "缺少依赖: $f" >&2; exit 1; }
done

SRCS=()
# 全模块源码（已全部迁到现代 API，所以整包编译 —— 这本身就是一道编译门禁）
while IFS= read -r -d '' f; do SRCS+=("$(w "$f")"); done < <(find "$MOD/src" -name '*.java' -print0)
# 额外白名单（sources.txt，一般不需要；留着给"暂时不能编进整包"的场景）
if [ -s "$HERE/sources.txt" ]; then
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    case "$line" in \#*) continue ;; esac
    f="$MOD/$line"
    [ -e "$f" ] || { echo "sources.txt 里的文件不存在: $line" >&2; exit 1; }
    SRCS+=("$(w "$f")")
  done < "$HERE/sources.txt"
fi
while IFS= read -r -d '' f; do SRCS+=("$(w "$f")"); done < <(find "$HERE" -maxdepth 1 -name '*.java' -print0)

if [ "${#SRCS[@]}" -eq 0 ]; then
  echo "没有可编译的源文件" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

echo "[1/2] javac（${#SRCS[@]} 个源文件）"
"$JAVAC" -J-Duser.language=en -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp "$AJ_W;$(w "$API_W")" -d "$(w "$OUT")" "${SRCS[@]}"

# 这台机器的 JVM 默认按本地代码页(CP936)写 stdout，工具链那头按 UTF-8 读会变乱码，显式钉住 UTF-8。
echo "[2/2] java TestMain"
"$JAVA" -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
  -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$AJ_W;$(w "$API_W");$(w "$OUT")" TestMain
