#!/bin/sh
# 正常脚本：guard 不该对它有半点反应（误报检查用）
B=${1:-/tmp}
echo "benign: 开始"
echo hello > "$B/benign-ok.txt"
cat "$B/benign-ok.txt"
ls "$B" | head -3
mkdir -p "$B/benign-dir" && rm -rf "$B/benign-dir"
echo "benign: 结束（正常）"
