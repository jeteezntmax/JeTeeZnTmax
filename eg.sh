#!/system/bin/sh
# ============================================================
#  Extreme GT 运行时  ·  KSU 系统工具箱内嵌版
# ------------------------------------------------------------
#  来源：Extreme GT vAB-1.3.0（作者 嘟嘟ski & AB，二改版）
#  原模块是纯 shell 模块，这里把它开机要做的动作收成一个可调用的脚本，
#  并把每一步拆成可单独开关的项，配置放 /data/adb/ksu_toolbox/eg.txt
#
#  它做四件事：
#    1. XML 覆盖 —— 把模块里预生成的去温控 XML bind-mount 回真实路径
#    2. emul_temp —— 只对一份手工挑选的 zone 白名单写 29.5℃（不是全写！）
#       非 OnePlus SM8650 机型则改为停掉 oplus ORMS 服务
#    3. GPU 满档 —— max_pwrlevel / max_gpu_clk 锁死
#    4. horae testmode + 触摸进程 renice
#
#  用法：eg.sh list | apply | clear | bootcheck
# ============================================================
DIR=/data/adb/ksu_toolbox
CONF=$DIR/eg.txt
ARM=$DIR/.eg_armed
CRASH=$DIR/eg_crash.log
MODDIR=${0%/*}/..

mkdir -p "$DIR" 2>/dev/null

load() {
    enabled=0; xml=0; emul=0; gpu=0; touch=0; horae=0   # 默认全关
    [ -f "$CONF" ] || return 0
    while IFS='=' read -r k v; do
        case "$k" in
            enabled) enabled=$v ;;
            xml)     xml=$v ;;
            emul)    emul=$v ;;
            gpu)     gpu=$v ;;
            touch)   touch=$v ;;
            horae)   horae=$v ;;
        esac
    done < "$CONF"
}

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1" >> "$CRASH"; }

# ---------- 1. XML 覆盖 ----------
mount_file() {
    src="$1"; dst="$2"
    [ -f "$src" ] || return 0
    [ -e "$dst" ] || return 0
    grep -q " $dst " /proc/mounts 2>/dev/null && return 0
    chcon --reference "$dst" "$src" 2>/dev/null
    mount --bind "$src" "$dst" 2>/dev/null
}

walk_mount() {
    for f in "$MODDIR$1"/*; do
        [ -e "$f" ] || continue
        rel=${f#"$MODDIR"}
        if [ -f "$f" ]; then
            mount_file "$f" "$rel"
        elif [ -d "$f" ]; then
            walk_mount "$rel"
        fi
    done
}

# 欧加 anyfs 的 upperdir 预拷贝（原 post-fs-data.sh 的逻辑）
ANYFS_UPPER="/dev/anyfs/upper"

precopy_to_anyfs() {
    [ -d "$ANYFS_UPPER" ] || return 0
    [ -d "$MODDIR$1" ] || return 0
    for f in "$MODDIR$1"/*; do
        [ -e "$f" ] || continue
        sub=${f##*/}
        tgt="$1/$sub"
        if [ -f "$f" ]; then
            d=$(dirname "$tgt")
            top=$(echo "$d" | cut -d'/' -f1-3)
            if [ -d "$ANYFS_UPPER$top" ]; then
                mkdir -p "$ANYFS_UPPER$d"
                cp -f "$f" "$ANYFS_UPPER$tgt"
                chcon --reference "$tgt" "$ANYFS_UPPER$tgt" 2>/dev/null
            fi
        elif [ -d "$f" ]; then
            precopy_to_anyfs "$tgt"
        fi
    done
}

do_xml() {
    precopy_to_anyfs "/odm"
    precopy_to_anyfs "/my_product"
    n=0
    for root in /odm /my_product /vendor /system/vendor /product /system; do
        [ -d "$MODDIR$root" ] || continue
        walk_mount "$root"
    done
    # 统计已经挂上去的
    for f in $(find "$MODDIR" -type f -name '*.xml' -o -type f -name '*.json' -o -type f -name '*.txt' 2>/dev/null); do
        rel=${f#"$MODDIR"}
        case "$rel" in /odm/*|/my_product/*|/vendor/*|/system/*|/product/*) ;; *) continue ;; esac
        grep -q " $rel " /proc/mounts 2>/dev/null && n=$((n + 1))
    done
    echo "xml_mounted=$n"
}

# ---------- 2. emul_temp 白名单 ----------
EG_ZONES="rear-tof-therm cam-flash-therm batt-therm usb-therm wlan-therm xo-therm oplus_thermal_ipa"
EG_TEMP=29500

do_emul() {
    n=0
    for z in /sys/class/thermal/thermal_zone*; do
        [ -f "$z/temp" ] || continue
        [ -w "$z/emul_temp" ] || continue
        ty=""; IFS= read -r ty 2>/dev/null < "$z/type"
        ok=0
        for k in $EG_ZONES; do
            [ "$ty" = "$k" ] && { ok=1; break; }
        done
        case "$ty" in shell*) ok=1 ;; esac
        [ "$ok" = "1" ] || continue
        echo $EG_TEMP > "$z/emul_temp" 2>/dev/null && n=$((n + 1))
    done
    echo "emul_written=$n"
}

do_emul_clear() {
    n=0
    for z in /sys/class/thermal/thermal_zone*; do
        [ -w "$z/emul_temp" ] || continue
        ty=""; IFS= read -r ty 2>/dev/null < "$z/type"
        ok=0
        for k in $EG_ZONES; do
            [ "$ty" = "$k" ] && { ok=1; break; }
        done
        case "$ty" in shell*) ok=1 ;; esac
        [ "$ok" = "1" ] || continue
        echo 0 > "$z/emul_temp" 2>/dev/null && n=$((n + 1))
    done
    echo "emul_cleared=$n"
}

# ---------- 3. GPU 满档 ----------
lock_val() {
    for f in $(find "$2" 2>/dev/null); do
        [ -e "$f" ] || continue
        rp=$(realpath "$f" 2>/dev/null)
        [ -n "$rp" ] && f=$rp
        umount "$f" 2>/dev/null
        chmod +w "$f" 2>/dev/null
        echo "$1" > "$f" 2>/dev/null
        chmod -w "$f" 2>/dev/null
        restorecon -R -F "$f" >/dev/null 2>&1
    done
}

do_gpu() {
    n=0
    for spec in "0:/sys/class/kgsl/kgsl-3d0/max_pwrlevel" \
                "2147483647:/sys/class/kgsl/kgsl-3d0/max_gpu_clk" \
                "2147483647:/sys/class/kgsl/kgsl-3d0/max_clock_mhz"; do
        v=${spec%%:*}; p=${spec#*:}
        [ -e "$p" ] || continue
        chmod +w "$p" 2>/dev/null
        echo "$v" > "$p" 2>/dev/null && n=$((n + 1))
        chmod -w "$p" 2>/dev/null
    done
    echo "gpu_nodes=$n"
}

# ---------- 4. 触摸 / horae ----------
do_touch() {
    n=0
    for p in $(pidof vendor-oplus-hardware-touch-V2-service) $(pidof touchDaemon); do
        [ -n "$p" ] && renice -n -19 -p "$p" >/dev/null 2>&1 && n=$((n + 1))
    done
    echo "touch_renice=$n"
}

do_horae() {
    dumpsys horae testmode >/dev/null 2>&1
    for i in 0 1 2; do
        echo "$i 29500" > /proc/shell-temp 2>/dev/null
    done
    echo "horae=ok"
}

do_orms() {
    stop vendor.oplus.ormsHalService-aidl-default >/dev/null 2>&1
    echo "orms=stopped"
}

# ---------- 设备判定 ----------
dev_match() {
    m=$(getprop ro.product.odm.manufacturer)
    [ -z "$m" ] && m=$(getprop ro.product.manufacturer)
    s=$(getprop ro.soc.model | tr 'a-z' 'A-Z')
    c=$(getprop ro.build.display.id | cut -d '.' -f 4 | cut -d '(' -f 1)
    case "$c" in ''|*[!0-9]*) c=0 ;; esac
    if [ "$m" = "OnePlus" ] && [ "$s" = "SM8650" ] && [ "$c" -lt 700 ] 2>/dev/null; then
        echo 1
    else
        echo 0
    fi
}

# ---------- 分阶段应用 ----------
# early：XML 覆盖 + GPU 锁频 + 触摸 renice（service 一开始就跑，越早越好）
apply_early() {
    load
    echo "enabled=$enabled"
    [ "$enabled" = "1" ] || return 0
    [ "$xml" = "1" ] && do_xml
    [ "$gpu" = "1" ] && do_gpu
    [ "$touch" = "1" ] && do_touch
}

# late：温度伪装 + horae（要等系统起来、进程都在）
apply_late() {
    load
    echo "enabled=$enabled"
    [ "$enabled" = "1" ] || return 0
    if [ "$emul" = "1" ]; then
        if [ "$(dev_match)" = "1" ]; then
            printf 'eg' > "$ARM"          # 60 秒后系统还活着才撤掉
            do_emul
            ( sleep 60; rm -f "$ARM" ) >/dev/null 2>&1 &
        else
            do_orms
        fi
    fi
    [ "$horae" = "1" ] && do_horae
}

apply_all() { apply_early; apply_late; }

clear_all() {
    rm -f "$ARM" 2>/dev/null
    do_emul_clear
}

# ---------- 开机自检 ----------
bootcheck() {
    if [ -f "$ARM" ]; then
        rm -f "$ARM"
        log "上次写 emul_temp 后系统异常重启 —— 本次跳过温度伪装，只做 XML/GPU 部分"
        sed -i 's/^emul=.*/emul=0/' "$CONF" 2>/dev/null
        echo "crash=1"
    else
        echo "crash=0"
    fi
}

# ---------- 状态 ----------
status() {
    load
    echo "eg_enabled=$enabled"
    echo "eg_xml=$xml"
    echo "eg_emul=$emul"
    echo "eg_gpu=$gpu"
    echo "eg_touch=$touch"
    echo "eg_horae=$horae"
    echo "eg_match=$(dev_match)"
    echo "eg_manufacturer=$(getprop ro.product.odm.manufacturer)"
    echo "eg_soc=$(getprop ro.soc.model)"
    echo "eg_cos=$(getprop ro.build.display.id)"
    n=0
    for z in /sys/class/thermal/thermal_zone*; do
        [ -w "$z/emul_temp" ] || continue
        ty=""; IFS= read -r ty 2>/dev/null < "$z/type"
        for k in $EG_ZONES; do
            [ "$ty" = "$k" ] && { n=$((n + 1)); break; }
        done
        case "$ty" in shell*) n=$((n + 1)) ;; esac
    done
    echo "eg_zone_hit=$n"
    # 已覆盖的配置文件数（find 只跑一次，别每 4 秒扫两遍）
    LIST=$DIR/.eglist
    find "$MODDIR" \( -name '*.xml' -o -name '*.json' -o -name '*.txt' \) -type f 2>/dev/null \
        | sed "s#^$MODDIR##" | grep -E '^/(odm|my_product|vendor|system|product)/' > "$LIST" 2>/dev/null
    c=$(wc -l < "$LIST" 2>/dev/null | tr -d ' ')
    case "$c" in ''|*[!0-9]*) c=0 ;; esac
    echo "eg_files=$c"
    m=0
    while IFS= read -r rel; do
        grep -q " $rel " /proc/mounts 2>/dev/null && m=$((m + 1))
    done < "$LIST"
    echo "eg_mounted=$m"
    rm -f "$LIST"
    if [ -f "$CRASH" ]; then
        echo "eg_log=$(tail -n 2 "$CRASH" 2>/dev/null | tr '\n' '|')"
    else
        echo "eg_log="
    fi
}

case "$1" in
    early)     apply_early ;;
    late)      apply_late ;;
    apply)     apply_all ;;
    clear)     clear_all ;;
    bootcheck) bootcheck ;;
    status)    status ;;
    *)
        echo "usage: $0 early|late|apply|clear|bootcheck|status"
        exit 1
        ;;
esac
