// ============================================================
//  ddtest —— 用来验证 ksu_guard 能不能拦【原生 ELF】
//
//  和 shell 里的 dd 做的是同一件事：读一个块设备，写到文件。
//  区别是它不经 shell、不 exec 别的东西 —— 如果 guard 只会看
//  execve，这个就漏了。所以要拿它试。
//
//  路径可以用参数覆盖，先拿假文件试：
//    ddtest /tmp/dev/block/by-name/boot_a /tmp/out.img
//  默认才是真路径：
//    ddtest                     # /dev/block/by-name/boot_a -> /sdcard/boot_a.img
// ============================================================
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>

#ifndef O_LARGEFILE
#define O_LARGEFILE 0
#endif

static const char *DEF_SRC = "/dev/block/by-name/boot_a";
static const char *DEF_DST = "/sdcard/boot_a.img";

int main(int argc, char **argv) {
    const char *src = (argc > 1 && argv[1][0]) ? argv[1] : DEF_SRC;
    const char *dst = (argc > 2 && argv[2][0]) ? argv[2] : DEF_DST;

    fprintf(stderr, "[ddtest] %s  ->  %s\n", src, dst);

    int in = open(src, O_RDONLY | O_LARGEFILE);
    if (in < 0) {
        fprintf(stderr, "[ddtest] 打开源失败: %s\n", strerror(errno));
        return 1;
    }
    fprintf(stderr, "[ddtest] 源已打开 fd=%d\n", in);

    int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC | O_LARGEFILE, 0644);
    if (out < 0) {
        fprintf(stderr, "[ddtest] 打开目标失败: %s\n", strerror(errno));
        close(in);
        return 1;
    }

    static char buf[1024 * 1024];
    unsigned long long total = 0;
    int bad = 0;

    for (;;) {
        ssize_t n = read(in, buf, sizeof buf);
        if (n < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "[ddtest] 读失败: %s\n", strerror(errno));
            bad = 1;
            break;
        }
        if (n == 0) break;

        ssize_t w = 0;
        while (w < n) {
            ssize_t k = write(out, buf + w, (size_t)(n - w));
            if (k < 0) {
                if (errno == EINTR) continue;
                fprintf(stderr, "[ddtest] 写失败: %s\n", strerror(errno));
                bad = 1;
                break;
            }
            w += k;
        }
        if (bad) break;
        total += (unsigned long long)n;
    }

    close(in);
    close(out);
    fprintf(stderr, "[ddtest] 结束，共 %llu 字节%s\n", total, bad ? "（有错）" : "");
    return bad ? 2 : 0;
}
