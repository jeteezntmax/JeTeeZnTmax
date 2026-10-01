#!/system/bin/sh
# ============================================================
#  本地 HTTP 服务  ·  把 WebUI 挂到 127.0.0.1:<port>
# ------------------------------------------------------------
#  用 KernelSU 自带的 busybox httpd，只监听回环地址。
#  · 浏览器打开 http://127.0.0.1:<port>/ 就能看到界面
#  · 真实数据来自 /api/<任务名>.txt —— 由 bin/collect.sh 后台循环生成
#  · httpd 不做 CGI：它只能读静态文件，执行不了任何东西
#
#  用法：webui-server.sh start|stop|status|restart|token
# ============================================================
DIR=/data/adb/ksu_toolbox
WWW=$DIR/www
CONF=$DIR/webui.conf
TOKEN_FILE=$DIR/webui.token
PIDFILE=$DIR/webui.pid
LOG=$DIR/webui.log
MODDIR=${0%/*}/..

mkdir -p "$DIR"

bb() {
    for b in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox; do
        [ -x "$b" ] && { echo "$b"; return 0; }
    done
    command -v busybox 2>/dev/null
}

getport() {
    p=""
    [ -f "$CONF" ] && p=$(grep -m1 '^port=' "$CONF" 2>/dev/null | cut -d= -f2)
    case "$p" in ''|*[!0-9]*) p=8765 ;; esac
    echo "$p"
}

gen_token() {
    if [ ! -s "$TOKEN_FILE" ]; then
        T=$(od -An -N16 -tx1 < /dev/urandom 2>/dev/null | tr -d ' \n')
        [ -z "$T" ] && T=$(date +%s)$(date +%N)
        printf '%s' "$T" > "$TOKEN_FILE"
        chmod 600 "$TOKEN_FILE"
    fi
    cat "$TOKEN_FILE"
}

# busybox httpd 会守护化，pidof/pgrep 不一定抓得到 —— 直接扫 /proc
find_httpd() {
    for d in /proc/[0-9]*; do
        [ -r "$d/cmdline" ] || continue
        if tr '\0' ' ' < "$d/cmdline" 2>/dev/null | grep -q 'httpd -p 127.0.0.1'; then
            echo "${d#/proc/}"
            return 0
        fi
    done
    return 1
}

# 别用 wget 探活 —— 那会把 180KB 的首页整个拉一遍，
# 而 status 是被 collect.sh 每几秒调一次的。直接查内核的监听表。
probe_port() {
    P=$(getport)
    h=$(printf '%04X' "$P" 2>/dev/null)
    [ -n "$h" ] || return 1
    grep -qi ":$h " /proc/net/tcp 2>/dev/null
}

is_running() {
    if [ -s "$PIDFILE" ]; then
        p=$(cat "$PIDFILE" 2>/dev/null | tr -d ' \r\n')
        case "$p" in ''|*[!0-9]*) ;; *) kill -0 "$p" 2>/dev/null && return 0 ;; esac
    fi
    probe_port
}

stop_it() {
    for p in $(find_httpd); do
        kill "$p" 2>/dev/null
    done
    if [ -s "$PIDFILE" ]; then
        p=$(cat "$PIDFILE" | tr -d ' \r\n')
        case "$p" in ''|*[!0-9]*) ;; *) kill "$p" 2>/dev/null ;; esac
    fi
    sleep 1
    for p in $(find_httpd); do
        kill -9 "$p" 2>/dev/null
    done
    rm -f "$PIDFILE"
}

publish() {
    rm -rf "$WWW" 2>/dev/null
    mkdir -p "$WWW"
    cp -rf "$MODDIR/webroot/." "$WWW/" 2>/dev/null
    find "$WWW" -type d -exec chmod 755 {} \; 2>/dev/null
    find "$WWW" -type f -exec chmod 644 {} \; 2>/dev/null
    gen_token > /dev/null
}

collector_loop() {
    while true; do
        if [ -f "$DIR/webui.conf" ]; then
            e=$(grep -m1 '^enabled=' "$DIR/webui.conf" 2>/dev/null | cut -d= -f2)
            [ "$e" = "1" ] || { sleep 20; continue; }
        else
            break
        fi
        sh "$MODDIR/bin/collect.sh" "$WWW/api" >/dev/null 2>&1
        P=$(getport)
        if [ -d "$WWW/api" ]; then
            T=$(date +%s)
            printf '{"ok":1,"t":%s}\n' "$T" > "$WWW/api/_alive.json" 2>/dev/null
        fi
        sleep 6
    done
}

start_it() {
    BB=$(bb)
    if [ -z "$BB" ] || [ ! -x "$BB" ]; then
        echo "error=no-busybox"
        return 1
    fi
    if ! $BB httpd --help >/dev/null 2>&1; then
        echo "error=busybox-no-httpd"
        return 1
    fi
    stop_it
    publish
    # 先把快照生成一次，再去起服务
    sh "$MODDIR/bin/collect.sh" "$WWW/api" >/dev/null 2>&1
    ( collector_loop ) >/dev/null 2>&1 &
    P=$(getport)
    printf '[%s] start on 127.0.0.1:%s\n' "$(date '+%F %T')" "$P" >> "$LOG"
    $BB httpd -p "127.0.0.1:$P" -h "$WWW" >> "$LOG" 2>&1
    sleep 1
    pid=$(find_httpd 2>/dev/null | head -n 1)
    [ -z "$pid" ] && pid=$($BB pidof httpd 2>/dev/null | awk '{print $1}')
    if [ -n "$pid" ]; then printf '%s' "$pid" > "$PIDFILE"; fi
    if is_running; then
        echo "started=1"
        echo "port=$P"
        echo "pid=${pid:-?}"
    else
        echo "error=not-running"
        tail -n 3 "$LOG" 2>/dev/null
        return 1
    fi
}

status_it() {
    P=$(getport)
    echo "http_port=$P"
    echo "http_token=$(cat "$TOKEN_FILE" 2>/dev/null | tr -d ' \r\n')"
    if is_running; then
        echo "http_running=1"
        echo "http_pid=$(cat "$PIDFILE" 2>/dev/null | tr -d ' \r\n')"
    else
        echo "http_running=0"
        echo "http_pid="
    fi
    BB=$(bb)
    if [ -n "$BB" ] && [ -x "$BB" ] && $BB httpd --help >/dev/null 2>&1; then
        echo "http_busybox=1"
    else
        echo "http_busybox=0"
    fi
    echo "http_root=$WWW"
}

case "$1" in
    start|restart) start_it ;;
    stop)    stop_it; echo "stopped=1" ;;
    status)  status_it ;;
    token)   gen_token; echo ;;
    publish) publish; echo "published=1" ;;
    *)
        echo "usage: $0 start|stop|restart|status|token|publish"
        exit 1
        ;;
esac
