#!/system/bin/sh
# ============================================================
#  KSU 系统工具箱 · 开机服务
#   只有一件事：按配置跑 Extreme GT
#     early —— XML 覆盖 / GPU 锁频 / 触摸 renice（越早越好）
#     late  —— 温度伪装 + horae（等系统起来）
#   崩溃自检：上回写完 emul_temp 系统就崩了的话，这次自动跳过温度伪装
# ============================================================
MODDIR=${0%/*}
E="$MODDIR/bin/eg.sh"

[ -f "$E" ] || exit 0

# 崩溃自检（纯文件操作）
sh "$E" bootcheck >/dev/null 2>&1

# 尽早：XML / GPU
sh "$E" early >/dev/null 2>&1

# 等系统起来再做温度相关
(
    i=0
    while [ "$i" -lt 240 ]; do
        [ "$(getprop sys.boot_completed)" = "1" ] && break
        sleep 5
        i=$((i + 5))
    done
    sleep 5
    sh "$E" late >/dev/null 2>&1

    # 本地 HTTP 服务（设置页里开的）
    W="$MODDIR/bin/webui-server.sh"
    if [ -f "$W" ]; then
        e=""
        [ -f /data/adb/ksu_toolbox/webui.conf ] && e=$(grep -m1 '^enabled=' /data/adb/ksu_toolbox/webui.conf 2>/dev/null | cut -d= -f2)
        [ "$e" = "1" ] && sh "$W" start >/dev/null 2>&1
    fi
) >/dev/null 2>&1 &
