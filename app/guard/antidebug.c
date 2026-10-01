#define _GNU_SOURCE
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
int main(void){
    /* 经典反调试：读 /proc/self/status 找 TracerPid */
    int fd = open("/proc/self/status", O_RDONLY);
    char buf[4096]; int n = fd>=0 ? read(fd, buf, sizeof buf - 1) : 0;
    if(fd>=0) close(fd);
    if(n>0){ buf[n]=0; char *p = strstr(buf, "TracerPid:"); printf("[antidebug] %s\n", p ? p : "没找到"); }
    /* 再关掉 dumpable */
    prctl(PR_SET_DUMPABLE, 0);
    printf("[antidebug] 退出\n");
    return 0;
}
