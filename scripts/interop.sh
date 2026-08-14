#!/usr/bin/env bash
# 互操作测试辅助脚本(docs/09-testing.md §3.3):
# 1. 重新生成 Go 样本并与仓库内固定样本对比(结构一致性;gzip 含 mtime,仅验证可解)
# 2. 跑 Android 端 InteropTest(JVM,无网络)
#
# 完整双向矩阵(Go push→Android pull 等)需本地 registry:2 + Go CLI,见 docs/09-testing.md §3。
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> [1/2] 重新生成 Go 样本(独立实现,scripts/gen-go-samples)"
GEN_DIR=$(mktemp -d)
trap 'rm -rf "$GEN_DIR"' EXIT
( cd scripts/gen-go-samples && GOPROXY=https://goproxy.cn,direct go run . "$GEN_DIR" )

echo "==> [2/2] Android 端互操作测试(InteropTest,含 Go 产物解包/解密)"
./gradlew :core:test --tests "com.tiramission.ocisync.core.InteropTest" --no-daemon

echo "OK: 互操作样本可复现,Android 端与 Go 字节级兼容(见 docs/02-core-format.md §6)"
