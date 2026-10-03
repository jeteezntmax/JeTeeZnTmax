#!/bin/sh
# ============================================================
#  wiper-sim.sh — 模拟"格机"程序的行为（**只动临时目录**）
#
#  它照着真实样本那套写：dd 擦块设备、mknod 造节点绕着走、
#  删关键分区目录、最后 reboot。区别只有一个：所有路径都在
#  你传进来的临时目录底下，不会碰到真实分区。
#
#  用法: sh wiper-sim.sh <临时根目录>
#  预期: 在 guard 底下跑 → 第一个 dd 就被打断，后面的 echo 不该出现
# ============================================================
B=${1:?用法: sh wiper-sim.sh <临时根目录>}
BLK="$B/dev/block"
PERSIST="$B/persist"

echo "[*] 开始擦除（假目标，安全）"
# ① 典型样本的第一刀：dd 擦块设备
dd if=/dev/zero of="$BLK/sda" bs=1M count=8 2>/dev/null
echo "[*] dd 执行完了（如果被 guard 拦住，这行不会出现）"
# ② mknod 造块设备节点，绕过假 /dev
mknod "$B/fake_sda" b 8 0 2>/dev/null
echo "[*] mknod 执行完了"
# ③ 删关键路径
rm -rf "$PERSIST"
echo "[*] rm -rf persist 执行完了"
# ④ reboot（无效参数，安全；只是让 syscall 出现）
"$(dirname "$0")/reboot-probe" 2>/dev/null || true
echo "[!] 全部干完了 —— 这行出现说明【一个都没拦住】"
