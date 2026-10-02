#!/system/bin/sh
# ============================================================
#  充电控制节点探测
#
#  充电这块【各家完全不统一】：
#    欧加   /sys/class/oplus_chg/
#    高通   /sys/class/power_supply/battery/ + qcom-battery/
#    MTK    /sys/class/power_supply/mtk-battery/
#    小米   /sys/class/xm_power/
#    魅族   /sys/class/meizu/charger/
#    三星   /sys/class/power_supply/battery/ (名字又不一样)
#    ...
#  所以不能写死。这里把每项能力的候选全列出来，谁存在就报谁，
#  顺便标出【可写/只读】—— 只读的写进去也没用。
# ============================================================

show() {
    # $1 = 说明
    shift
    for p in "$@"; do
        [ -e "$p" ] || continue
        v=$(cat "$p" 2>/dev/null | head -1 | tr -d '\n')
        if [ -w "$p" ]; then w="可写"; else w="只读"; fi
        printf '  %-62s = %-14s [%s]\n' "$p" "${v:-?}" "$w"
    done
}

echo "═══ ① 停充 / 恢复充电 ═══"
show "停充开关" \
/sys/class/oplus_chg/battery/charging_enabled \
/sys/class/oplus_chg/battery/charge_control_limit \
/sys/class/oplus_chg/common/charger_exist \
/sys/class/power_supply/battery/input_suspend \
/sys/class/power_supply/battery/charging_enabled \
/sys/class/power_supply/battery/battery_charging_enabled \
/sys/class/power_supply/battery/mmi_charging_enable \
/sys/class/power_supply/battery/step_charging_enabled \
/sys/class/power_supply/battery/night_charging \
/sys/class/qcom-battery/input_suspend \
/sys/class/qcom-battery/night_charging \
/sys/class/cms_class/disable_charge \
/sys/class/mtk_charger/charging_enable \
/sys/class/power_supply/mtk-battery/charging_enabled \
/sys/class/smblib/batt_charging_enabled \
/sys/class/sec/charge_enable \
/sys/class/power_supply/battery/charge_disable

echo
echo "═══ ② 充电电流（改功率用这个）═══"
show "目标电流" \
/sys/class/oplus_chg/battery/charge_control_limit \
/sys/class/oplus_chg/battery/battery_cc \
/sys/class/oplus_chg/battery/constant_charge_current \
/sys/class/power_supply/battery/constant_charge_current \
/sys/class/power_supply/battery/constant_charge_current_max \
/sys/class/power_supply/main/constant_charge_current_max \
/sys/class/power_supply/battery/charge_control_limit \
/sys/class/power_supply/battery/charge_control_limit_max \
/sys/class/power_supply/battery/current_max \
/sys/class/power_supply/battery/input_current_limit \
/sys/class/power_supply/usb/current_max \
/sys/class/power_supply/usb/input_current_limit \
/sys/class/xm_power/charger/charger_thermal/wired_chg_curr \
/sys/class/power_supply/mtk-battery/ichg \
/sys/class/power_supply/mtk-battery/charging_current

echo
echo "═══ ③ USB 低速 / 快充 ═══"
show "USB 相关" \
/sys/class/oplus_chg/usb/fast_chg_type \
/sys/class/oplus_chg/usb/usb_status \
/sys/class/oplus_chg/usb/typec_svid \
/sys/class/power_supply/usb/voltage_max \
/sys/class/power_supply/usb/pd_active \
/sys/class/power_supply/usb/pd_allowed \
/sys/class/power_supply/usb/input_current_limit \
/sys/class/power_supply/usb/online \
/sys/class/power_supply/usb/hvdcp_opti \
/sys/class/power_supply/usb/real_type \
/sys/class/power_supply/usb/typec_mode \
/sys/class/power_supply/usb/speed

echo
echo "═══ ④ 读电量 / 电压 / 电流 ═══"
show "状态" \
/sys/class/power_supply/battery/capacity \
/sys/class/power_supply/battery/voltage_now \
/sys/class/power_supply/battery/current_now \
/sys/class/power_supply/battery/temp \
/sys/class/power_supply/battery/status \
/sys/class/oplus_chg/battery/battery_cc \
/sys/class/oplus_chg/usb/usb_current_now \
/sys/class/bms/current_now

echo
echo "═══ ⑤ 这台机器上到底有哪几套充电子系统 ═══"
for d in /sys/class/oplus_chg /sys/class/xm_power /sys/class/qcom-battery \
         /sys/class/meizu /sys/class/mtk_charger /sys/class/vivo_chg \
         /sys/class/huawei_chg /sys/class/sec /sys/class/smblib /sys/class/cms_class; do
    [ -d "$d" ] && echo "  有 $d"
done
echo "  power_supply 下的设备:"
ls /sys/class/power_supply/ 2>/dev/null | sed 's/^/    /'

echo
echo "═══ ⑥ 电池 uevent（信息最全的一条）═══"
cat /sys/class/power_supply/battery/uevent 2>/dev/null | sed 's/^/  /'
