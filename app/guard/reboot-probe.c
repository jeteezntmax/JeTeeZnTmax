// ============================================================
//  reboot-probe — 安全地测"reboot(2) 有没有被拦"
//
//  默认用【无效参数】调 reboot(2)：内核只会返回 EINVAL，机器不会重启，
//  但 syscall 确实发生了 —— 足够验证 guard 拦不拦。
//
//  真机想测"真的重启请求"时加 -real（**只建议在拦截模式下用**，
//  观察模式不会杀，机器会真的重启）。
// ============================================================
#define _GNU_SOURCE
#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <sys/syscall.h>

#ifndef SYS_reboot
#define SYS_reboot 142      /* aarch64 */
#endif

int main(int argc, char **argv) {
    int real = (argc > 1 && strcmp(argv[1], "-real") == 0);
    long cmd = real ? 0x01234567L /* LINUX_REBOOT_CMD_RESTART */ : 0xdeadbeefL;
    printf("reboot-probe: 调 reboot(2) cmd=%#lx（%s）\n", cmd, real ? "真的重启命令！" : "无效参数，内核只会回 EINVAL");
    fflush(stdout);
    long r = syscall(SYS_reboot, cmd, 0x28121969L, 0x05121996L, NULL);
    printf("reboot-probe: 返回 %ld  errno=%s\n", r, strerror(errno));
    return 0;
}
