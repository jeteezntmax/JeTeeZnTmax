// ============================================================
//  dirfd-open — 模拟"格机样本"最典型的手法：
//    用 openat(目录fd, "sda1") 打开块设备，完整路径从不出现，
//    所以基于路径匹配的 LSM / seccomp / 假 dev 沙箱都拦不住。
//    只有 ptrace 停在内核 syscall 入口、自己把 dirfd 解出来才看得到。
//
//  用法: dirfd-open <目录> <文件名> [r|w]
//     r = 只打开读（默认）   w = 打开写并写 4 字节
//  退出码: 0 正常; 2 打不开
// ============================================================
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "用法: %s <目录> <文件名> [r|w]\n", argv[0]); return 2; }
    int w = (argc > 3 && argv[3][0] == 'w');
    int d = open(argv[1], O_RDONLY | O_DIRECTORY);
    if (d < 0) { fprintf(stderr, "开目录失败: %s\n", strerror(errno)); return 2; }
    /* 注意：这里是相对路径 —— 完整路径在命令行里看不到 */
    int f = openat(d, argv[2], w ? (O_WRONLY) : O_RDONLY);
    if (f < 0) { fprintf(stderr, "openat 失败: %s\n", strerror(errno)); return 2; }
    if (w) {
        char b[4] = "XXXX";
        ssize_t k = write(f, b, 4);
        (void)k;
    } else {
        char b[8];
        ssize_t k = read(f, b, sizeof b);
        (void)k;
    }
    close(f); close(d);
    printf("dirfd-open: 读/写完成（如果没有被拦，说明拦漏了）\n");
    return 0;
}
