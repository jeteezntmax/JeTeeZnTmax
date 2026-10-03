#!/bin/sh
# ============================================================
#  test-guard.sh — ksu_guard（受保护的执行）自测套件
#
#  全部测试都在 <工作目录> 下自带的一棵"假块设备树"里做，
#  **不碰任何真实分区**；reboot 用无效参数调，不会真重启。
#
#  用法:  sh test-guard.sh [工作目录]        （默认 /tmp/guard-test）
#  结果:  最后一张 PASS/FAIL 表；每项详细日志在 <工作目录>/logs/xx.log
#
#  期望：只要 ptrace 在这台机器上能用，绝大多数项应该是 PASS。
# ============================================================
W=${1:-/tmp/guard-test}
HERE=$(cd "$(dirname "$0")" && pwd)
# guard 和样本在哪：支持两种摆放 —— 独立的 kit 目录，或直接放在 app/guard 下
if [ -d "$HERE/guard" ]; then GD="$HERE/guard"; else GD="$HERE"; fi
if [ -d "$HERE/samples" ]; then SD="$HERE/samples"; else SD="$HERE"; fi
G="$GD/ksu_guard"
LOGS="$W/logs"
FAKE="$W/fake"
BLK="$FAKE/dev/block"
PASS=0; FAIL=0; SKIP=0

mkdir -p "$LOGS" "$BLK" "$FAKE/persist" "$W/bin" 2>/dev/null
[ -x "$G" ] || chmod 755 "$G" 2>/dev/null

# ── 小工具 ──
hdr(){ printf "\n=== %s ===\n" "$1"; }
ok(){ PASS=$((PASS+1)); printf "  [PASS] %s\n" "$1"; }
no(){ FAIL=$((FAIL+1)); printf "  [FAIL] %s   ← 看 %s\n" "$1" "$2"; }
sk(){ SKIP=$((SKIP+1)); printf "  [SKIP] %s\n" "$1"; }
md5(){ md5sum "$1" 2>/dev/null | cut -d' ' -f1; }
now(){ date +%s%N 2>/dev/null || echo $(($(date +%s) * 1000000000)); }
hits(){ H=$(grep -c '!!' "$1" 2>/dev/null); echo "${H:-0}"; }

# 一台能用的机器吗？
if [ ! -r /proc/self/status ]; then echo "这个环境没有 /proc，没法测"; exit 2; fi
if ! "$G" -l /dev/null -- true >/dev/null 2>&1; then
    echo "!! ksu_guard 跑不起来（ptrace 被禁 / 架构不对？）先手动试： $G -l /tmp/x.log -- true"; exit 2
fi

# ── 造假块设备树 ──
dd if=/dev/urandom of="$BLK/sda" bs=1M count=4 >/dev/null 2>&1 || dd if=/dev/zero of="$BLK/sda" bs=1M count=4 >/dev/null 2>&1
echo "calib" > "$FAKE/persist/calib.bin"
SDA_BEFORE=$(md5 "$BLK/sda")
echo "工作目录: $W   （假块设备: $BLK/sda，$(ls -l "$BLK/sda" | awk '{print $5}') 字节）"

# 编译两个样本小程序（没编译器就跳过相关项）
CC=""
for c in cc gcc clang aarch64-linux-gnu-gcc; do command -v $c >/dev/null 2>&1 && { CC=$c; break; }; done
if [ -n "$CC" ]; then
    $CC -O2 -o "$W/bin/dirfd-open" "$SD/dirfd-open.c" 2>/dev/null
    $CC -O2 -o "$W/bin/reboot-probe" "$SD/reboot-probe.c" 2>/dev/null
    cp "$W/bin/reboot-probe" "$SD/reboot-probe" 2>/dev/null
fi

# ============================================================
hdr "① 拦截模式 · mknod 造块设备节点"
# ============================================================
L="$LOGS/01-mknod.log"; rm -f "$L" "$W/fake_sda"
timeout 60 "$G" -l "$L" -- sh -c "mknod $W/fake_sda b 8 0" >/dev/null 2>&1; RC=$?
if [ "$RC" = "3" ] && [ ! -e "$W/fake_sda" ]; then ok "rc=3（打住了）且节点没生成"
else no "rc=$RC（期望 3）· 节点存在=$([ -e "$W/fake_sda" ] && echo 是 || echo 否)" "$L"; fi

# ============================================================
hdr "② 拦截模式 · dd 写假块设备（绝对路径）"
# ============================================================
L="$LOGS/02-ddwrite.log"; rm -f "$L"
timeout 60 "$G" -l "$L" -- dd if=/dev/zero of="$BLK/sda" bs=1M count=4 >/dev/null 2>&1; RC=$?
AFTER=$(md5 "$BLK/sda")
if [ "$RC" = "3" ] && [ "$AFTER" = "$SDA_BEFORE" ]; then ok "rc=3 且块设备内容一字未变"
else no "rc=$RC（期望 3）· 内容变了=$([ "$AFTER" = "$SDA_BEFORE" ] && echo 否 || echo 是)" "$L"; fi

# ============================================================
hdr "③ 拦截模式 · dd 读假块设备"
# ============================================================
L="$LOGS/03-ddread.log"; rm -f "$L"
timeout 60 "$G" -l "$L" -- dd if="$BLK/sda" of=/dev/null bs=512 count=64 >/dev/null 2>&1; RC=$?
[ "$RC" = "3" ] && ok "rc=3（默认读写全拦，读也打住）" || no "rc=$RC（期望 3）" "$L"

# ============================================================
hdr "④ -r 只拦写：读放行、写拦"
# ============================================================
L="$LOGS/04-r-read.log"; rm -f "$L"
timeout 60 "$G" -r -l "$L" -- dd if="$BLK/sda" of=/dev/null bs=512 count=64 >/dev/null 2>&1; RC1=$?
L2="$LOGS/04-r-write.log"; rm -f "$L2"
timeout 60 "$G" -r -l "$L2" -- dd if=/dev/zero of="$BLK/sda" bs=1k count=4 >/dev/null 2>&1; RC2=$?
if [ "$RC1" = "0" ] && [ "$RC2" = "3" ]; then ok "-r 下读放行(rc=0)、写仍被拦(rc=3)"
else no "读 rc=$RC1（期望 0）· 写 rc=$RC2（期望 3）" "$L2"; fi

# ============================================================
hdr "⑤ openat(目录fd, \"sda\") —— 样本最典型的手法"
# ============================================================
if [ -x "$W/bin/dirfd-open" ]; then
    L="$LOGS/05-dirfd-read.log"; rm -f "$L"
    timeout 60 "$G" -l "$L" -- "$W/bin/dirfd-open" "$BLK" sda r >/dev/null 2>&1; RC=$?
    [ "$RC" = "3" ] && ok "按目录fd 读块设备被拦（rc=3）—— 完整路径从不出现也拦得住" || no "rc=$RC（期望 3）" "$L"
    L="$LOGS/05b-dirfd-write.log"; rm -f "$L"
    timeout 60 "$G" -l "$L" -- "$W/bin/dirfd-open" "$BLK" sda w >/dev/null 2>&1; RC=$?
    [ "$RC" = "3" ] && ok "按目录fd 写块设备被拦（rc=3）" || no "rc=$RC（期望 3）" "$L"
else
    sk "没有编译器，跳过（先生成 $W/bin/dirfd-open）"
fi

# ============================================================
hdr "⑥ rm -rf 不该被拦（正常脚本也在用）"
# ============================================================
echo "x" > "$FAKE/persist/calib.bin"
L="$LOGS/06-rm.log"; rm -f "$L"
timeout 60 "$G" -l "$L" -- rm -rf "$FAKE/persist" >/dev/null 2>&1; RC=$?
H=$(hits "$L")
if [ "$RC" = "0" ] && [ "$H" = "0" ] && [ ! -d "$FAKE/persist" ]; then
    ok "rc=0、零命中，rm 正常跑完 —— 有意为之（安卓上 rm -rf 只动 /data，碰不到真分区）"
else
    no "rc=$RC（期望 0）· 命中 $H 条（期望 0）· 目录被删=$([ ! -d "$FAKE/persist" ] && echo 是 || echo 否)" "$L"
fi
mkdir -p "$FAKE/persist"; echo "x" > "$FAKE/persist/calib.bin"

# ============================================================
hdr "⑦ clone(CLONE_UNTRACED) 逃逸"
# ============================================================
if [ -x "$GD/clonetest" ]; then
    L="$LOGS/07-clone.log"; rm -f "$L"
    timeout 60 "$G" -l "$L" -- "$GD/clonetest" 1 >/dev/null 2>&1; RC=$?
    [ "$RC" = "3" ] && ok "rc=3（想把自己弄出监控范围，被掐）" || no "rc=$RC（期望 3）" "$L"
else
    sk "没有 guard/clonetest"
fi

# ============================================================
hdr "⑧ reboot(2)（无效参数，不会真重启）"
# ============================================================
if [ -x "$W/bin/reboot-probe" ]; then
    L="$LOGS/08-reboot.log"; rm -f "$L"
    timeout 60 "$G" -l "$L" -- "$W/bin/reboot-probe" >/dev/null 2>&1; RC=$?
    [ "$RC" = "3" ] && ok "rc=3（reboot 被拦）" || no "rc=$RC（期望 3）" "$L"
else
    sk "没有编译器，跳过"
fi

# ============================================================
hdr "⑨ 正常脚本不该误报"
# ============================================================
L="$LOGS/09-benign.log"; rm -f "$L"
timeout 60 "$G" -l "$L" -- sh "$SD/benign.sh" "$W" >/dev/null 2>&1; RC=$?
H=$(hits "$L")
if [ "$RC" = "0" ] && [ "$H" = "0" ]; then ok "rc=0 且零误报"
else no "rc=$RC（期望 0）· 命中 $H 条" "$L"; fi

# ============================================================
hdr "⑩ 观察模式：只记录、不杀"
# ============================================================
L="$LOGS/10-watch.log"; rm -f "$L"; cp "$BLK/sda" "$W/sda.bak"
timeout 60 "$G" -w -l "$L" -- sh "$SD/wiper-sim.sh" "$FAKE" >"$W/watch.out" 2>&1; RC=$?
H=$(hits "$L")
AFTER=$(md5 "$BLK/sda")
if [ "$H" -ge "2" ] && [ "$AFTER" != "$SDA_BEFORE" ]; then
    ok "记录到 $H 条危险行为，且没拦（块设备被写了）—— 观察模式就该是这样"
else
    no "命中 $H 条（期望 ≥2）· 块设备被写=$([ "$AFTER" != "$SDA_BEFORE" ] && echo 是 || echo 否)" "$L"
fi
cp "$W/sda.bak" "$BLK/sda" 2>/dev/null

# ============================================================
hdr "⑪ 大文件 dd：日志会不会刷屏 + 性能"
# ============================================================
L="$LOGS/11-bigdd.log"; rm -f "$L"
T0=$(now); timeout 120 "$G" -w -l "$L" -- dd if="$BLK/sda" of=/dev/null bs=512 count=8000 >/dev/null 2>&1; T1=$(now)
GUARD_MS=$(( (T1 - T0) / 1000000 ))
T2=$(now); dd if="$BLK/sda" of=/dev/null bs=512 count=8000 >/dev/null 2>&1; T3=$(now)
RAW_MS=$(( (T3 - T2) / 1000000 ))
LN=$(wc -l < "$L" 2>/dev/null || echo 0)
if [ "$LN" -le 60 ]; then ok "8000 次读，日志只有 $LN 行（有去重 + 限速 + 封顶）"
else no "日志 $LN 行（期望 ≤60，说明去重/限速没生效）" "$L"; fi
printf "         耗时：无 guard %s ms ｜ guard %s ms（这台机器 ptrace 的真实开销，看倍数别看绝对值）\n" "$RAW_MS" "$GUARD_MS"
grep -q '重复命中被折叠' "$L" && printf "         日志里有：%s\n" "$(grep '重复命中被折叠' "$L" | tail -1 | sed 's/^ *//')"

# 顺带：fd 缓存有没有用（现场编一个关掉缓存的版本来比）
BS="$GD/build-guard.sh"
if [ -f "$BS" ]; then
    sed 's/#define FDC_TTL_MS 300/#define FDC_TTL_MS 0/' "$GD/ksu_guard.c" > "$W/nocache.c" 2>/dev/null
    sh "$BS" "$W/nocache.c" "$W/ksu_guard-nocache" >/dev/null 2>&1
    if [ -x "$W/ksu_guard-nocache" ]; then
        L2="$LOGS/11b-nocache.log"; rm -f "$L2"
        T4=$(now); timeout 120 "$W/ksu_guard-nocache" -w -l "$L2" -- dd if="$BLK/sda" of=/dev/null bs=512 count=8000 >/dev/null 2>&1; T5=$(now)
        NC_MS=$(( (T5 - T4) / 1000000 ))
        printf "         对照：关掉 fd 缓存 %s ms ｜ 带缓存 %s ms（带缓存应该更快）\n" "$NC_MS" "$GUARD_MS"
    fi
fi

# ============================================================
hdr "⑫ 时限与心跳（-t 5，目标睡 30 秒）"
# ============================================================
L="$LOGS/12-timeout.log"; rm -f "$L"
T0=$(date +%s)
timeout 60 "$G" -w -t 5 -l "$L" -- sh -c 'sleep 30' >/dev/null 2>&1; RC=$?
SECS=$(( $(date +%s) - T0 ))
if [ "$RC" = "4" ] && [ "$SECS" -le 12 ] && grep -q '到时限' "$L"; then ok "$SECS 秒就收工（rc=4），日志写了「到时限」"
else no "rc=$RC（期望 4）· 用了 ${SECS}s · 有「到时限」=$(grep -q '到时限' "$L" && echo 是 || echo 否)" "$L"; fi
L="$LOGS/12b-beat.log"; rm -f "$L"
timeout 60 "$G" -w -t 20 -l "$L" -- sh -c 'sleep 8' >/dev/null 2>&1
B=$(grep -c '还在跑' "$L" 2>/dev/null || echo 0)
[ "$B" -ge 1 ] && ok "有 $B 条心跳（证明跟踪者没被卡死）" || no "没有心跳行（期望 ≥1）" "$L"

# ============================================================
hdr "⑬ 反调试识别（样本查 TracerPid）"
# ============================================================
if [ -x "$GD/antidebug" ]; then
    L="$LOGS/13-antidebug.log"; rm -f "$L"
    timeout 60 "$G" -w -l "$L" -- "$GD/antidebug" >/dev/null 2>&1
    grep -q '反调试' "$L" && ok "认出来了，日志里有「[反调试]」" || no "没认出来（期望日志含「[反调试]」）" "$L"
else
    sk "没有 guard/antidebug"
fi

# ============================================================
hdr "汇总"
printf "  PASS %s ｜ FAIL %s ｜ SKIP %s\n" "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" = "0" ] && printf "  结论：全部通过 —— guard 在这台机器上是有效的\n" \
                  || printf "  结论：有 %s 项没过，把对应 logs/*.log 发我\n" "$FAIL"
printf "  详细日志目录: %s\n" "$LOGS"
