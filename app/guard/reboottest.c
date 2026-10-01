#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/syscall.h>
int main(void){
    /* LINUX_REBOOT_MAGIC1 / MAGIC2 / CMD_RESTART */
    fprintf(stderr, "[reboottest] 准备直接调 reboot() syscall\n");
    long r = syscall(__NR_reboot, 0xfee1deadUL, 672274793UL, 0x01234567UL, NULL);
    fprintf(stderr, "[reboottest] reboot() 返回 %ld  errno=%s\n", r, strerror(errno));
    return 0;
}
