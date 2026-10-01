#!/bin/sh
# 交叉编译 ddtest 成 aarch64 静态 ELF（musl）
set -e
CC=aarch64-linux-gnu-gcc
MUSL=/opt/musl
$CC -O2 -static -nostdinc -isystem $MUSL/include -I$MUSL/include \
    -fno-stack-protector -fno-pie -no-pie \
    -o ddtest ddtest.c \
    -L$MUSL/lib -lc -nostartfiles $MUSL/lib/crt1.o
