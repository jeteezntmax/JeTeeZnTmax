#!/bin/sh
# 交叉编译 ksu_guard 成 aarch64 静态可执行文件（musl，跑在 Android 上）
# 依赖：aarch64-linux-gnu-gcc + musl aarch64（/opt/musl）
set -e
CC=aarch64-linux-gnu-gcc
MUSL=/opt/musl
$CC -O2 -static -nostdinc -isystem $MUSL/include -I$MUSL/include \
    -fno-stack-protector -fno-pie -no-pie \
    -o ksu_guard ksu_guard.c \
    -L$MUSL/lib -lc -nostartfiles $MUSL/lib/crt1.o
