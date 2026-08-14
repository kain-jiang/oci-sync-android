#!/usr/bin/env bash
# 本地构建 APK(3.7G 小内存机器专用流程):
# 1. 先编译 Kotlin(会启动 kotlin daemon)
# 2. 杀掉 kotlin daemon(释放 ~768M,dex 阶段内存峰值高,避免 cgroup OOM)
# 3. 再跑 assembleDebug(dex/packaging 不需要 kotlin daemon)
# 大内存机器(CI 7G+)直接 ./gradlew assembleDebug 即可。
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew :app:compileDebugKotlin --no-daemon
# 拆字规避 pgrep 匹配到本脚本自身
pgrep -f "KotlinCompile""Daemon" | while read -r pid; do kill "$pid" 2>/dev/null || true; done
./gradlew :app:assembleDebug --no-daemon
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
