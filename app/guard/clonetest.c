#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/wait.h>
static int child(void *a){ (void)a; _exit(0); }
int main(int argc, char **argv){
    int use_untraced = (argc > 1 && argv[1][0] == '1');
    char *st = malloc(1 << 20);
    unsigned long flags = 17;                 /* SIGCHLD */
    if (use_untraced) flags |= 0x00800000UL;  /* CLONE_UNTRACED */
    fprintf(stderr, "[clonetest] flags=0x%lx%s\n", flags, use_untraced ? "  (带 CLONE_UNTRACED)" : "");
    long pid = syscall(__NR_clone, flags, st + (1 << 20), 0, 0, 0);
    if (pid < 0) { perror("clone"); return 1; }
    if (pid == 0) { child(st); }
    waitpid((pid_t)pid, 0, 0);
    fprintf(stderr, "[clonetest] 完成\n");
    return 0;
}
