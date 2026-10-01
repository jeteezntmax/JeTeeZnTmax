#!/system/bin/sh
# ============================================================
#  数据快照生成器  ·  给浏览器模式的 WebUI 用
# ------------------------------------------------------------
#  WebView 里有 ksu.exec，能直接跑 root 命令拿数据。
#  浏览器里没有那个桥 —— 所以这里由一个后台循环，把页面需要的
#  那几组只读命令跑一遍，结果写成纯文本放到 /api/<任务名>.txt。
#  页面去 fetch 这些文件就行。
#
#  任务定义（命令原文）由页面自己在 WebView 里写进 tasks.txt，
#  所以这里不需要重复维护命令，改页面就行了。
#
#  用法：collect.sh <输出目录>
# ============================================================
OUT="$1"
[ -n "$OUT" ] || exit 1
DIR=/data/adb/ksu_toolbox
TASKS=$DIR/tasks.txt
mkdir -p "$OUT" 2>/dev/null

[ -s "$TASKS" ] || { echo "no-tasks" > "$OUT/_status.txt"; exit 0; }

{
    printf 'ok=1\n'
    printf 'at=%s\n' "$(date '+%F %T')"
    printf 'count=%s\n' "$(grep -c '=' "$TASKS" 2>/dev/null)"
} > "$OUT/_status.txt"

while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    name=${line%%=*}
    enc=${line#*=}
    case "$name" in ''|*[!A-Za-z0-9_]*) continue ;; esac
    [ -n "$enc" ] || continue
    cmd=$(printf '%s' "$enc" | base64 -d 2>/dev/null)
    [ -n "$cmd" ] || continue
    sh -c "$cmd" > "$OUT/$name.txt" 2>/dev/null
done < "$TASKS"

exit 0
