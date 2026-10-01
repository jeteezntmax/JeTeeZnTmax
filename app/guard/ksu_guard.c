// ============================================================
//  ksu_guard — 受保护的执行
//  把一个来路不明的程序放在 ptrace 底下跑，在危险 syscall
//  【执行之前】把它拦下来。
//
//  为什么是 ptrace 而不是 hook：
//    这类样本基本都用 raw syscall（syscall(__NR_openat, ...)）
//    绕开 libc，所以 LD_PRELOAD 那套完全无效。ptrace 在内核
//    停点上看得到，它没法绕。
//
//  用法：
//    ksu_guard -- /path/to/suspicious [args...]      拦截并杀死
//    ksu_guard -w -- /path/to/suspicious             只记录，不杀（先跑这个）
//    ksu_guard -a -- ...                             放行 mount/umount2
//    ksu_guard -l /x.log -w -- ...                   指定日志
//
//  注意：不保证 100%。目标如果有反调试（TracerPid 检测、
//  PR_SET_DUMPABLE=0）能识破并改变行为。真恶意的东西请先在
//  备用机上跑。
// ============================================================
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <time.h>
#include <signal.h>
static void on_alarm(int s) { (void)s; _exit(3); }
#include <dirent.h>
#include <limits.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <sys/types.h>

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

/* aarch64 通用寄存器组 */
struct arm_pt_regs { unsigned long long regs[31], sp, pc, pstate; };

#if defined(__x86_64__)
#define NR_ptrace       101
#define NR_prctl        157
#else
#define NR_ptrace       117
#define NR_prctl        167
#endif

/* --- 关心的 syscall 编号（按架构）--- */
#if defined(__x86_64__)
#define NR_openat       257
#define NR_mknodat      259
#define NR_mknod        133
#define NR_write        1
#define NR_pwrite64     18
#define NR_writev       20
#define NR_reboot       169
#define NR_kexec_load   246
#define NR_init_module  175
#define NR_finit_module 313
#define NR_delete_module 176
#define NR_swapon       167
#define NR_swapoff      168
#define NR_unlinkat     263
#define NR_mount        165
#define NR_umount2      166
#define NR_truncate     76
#define NR_faccessat    269
#define NR_newfstatat   262
#define NR_readlinkat   267
#define NR_mkdirat      258
#define NR_symlinkat    266
#define NR_linkat       265
#define NR_renameat     264
#define NR_renameat2    316
#define NR_fchmodat     268
#define NR_utimensat    280
#define NR_statx        332
#define NR_read         0
#define NR_pread64      17
#define NR_readv        19
#define NR_ioctl        16
#define NR_getdents64   217
#define NR_execve       59
#define NR_sendmsg      46
#define NR_connect      42
#define NR_clone        56
#define NR_clone3       435
#define NR_sendto       44
#define NR_sendfile     40
#define NR_splice       275
#define NR_vmsplice     278
#define NR_tee          276
#define NR_copy_file_range 326
#define NR_io_uring_setup 425
#define NR_io_uring_enter 426
#define NR_io_uring_register 427
#else  /* aarch64 */
#define NR_openat       56
#define NR_mknodat      33
#define NR_mknod        297
#define NR_write        64
#define NR_pwrite64     68
#define NR_writev       66
#define NR_reboot       142
#define NR_kexec_load   104
#define NR_init_module  105
#define NR_finit_module 273
#define NR_delete_module 106
#define NR_swapon       224
#define NR_swapoff      225
#define NR_unlinkat     35
#define NR_mount        40
#define NR_umount2      39
#define NR_truncate     45
#define NR_faccessat    48
#define NR_newfstatat   79
#define NR_readlinkat   78
#define NR_mkdirat      34
#define NR_symlinkat    36
#define NR_linkat       37
#define NR_renameat     38
#define NR_renameat2    276
#define NR_fchmodat     53
#define NR_utimensat    88
#define NR_statx        291
#define NR_read         63
#define NR_pread64      67
#define NR_readv        65
#define NR_ioctl        29
#define NR_getdents64   61
#define NR_execve       221
#define NR_sendmsg      211
#define NR_connect      203
#define NR_clone        220
#define NR_clone3       435
#define NR_sendto       206
#define NR_sendfile     71
#define NR_splice       275
#define NR_vmsplice     75
#define NR_tee          77
#define NR_copy_file_range 285
#define NR_io_uring_setup 425
#define NR_io_uring_enter 426
#define NR_io_uring_register 427
#endif

static int  opt_kill  = 1;   /* 1=拦截并杀死  0=只记录 */
static int  opt_allow_mount = 0;
static int  opt_block_reads = 1;   /* 默认：连读也拦 */
static FILE *logf = NULL;
static pid_t child = -1;
static int  hit_count = 0;
static long g_antidebug = 0;

/* 每个被跟踪进程/线程各自记 entry/exit —— 共用一个标志一 fork 就错位 */
#define STMAX 512
static struct { pid_t pid; int in; } g_st[STMAX];
static int *st_for(pid_t p) {
    int free_i = -1;
    for (int i = 0; i < STMAX; i++) {
        if (g_st[i].pid == p) return &g_st[i].in;
        if (free_i < 0 && g_st[i].pid == 0) free_i = i;
    }
    if (free_i < 0) { for (int i = 0; i < STMAX; i++) if (g_st[i].pid == 0) { free_i = i; break; } }
    if (free_i < 0) free_i = 0;
    g_st[free_i].pid = p; g_st[free_i].in = 0;
    return &g_st[free_i].in;
}
static void st_drop(pid_t p) { for (int i = 0; i < STMAX; i++) if (g_st[i].pid == p) { g_st[i].pid = 0; g_st[i].in = 0; } }

/* dd 这种一块一块读写的，同一条会刷几十万次 —— 日志会爆、fflush 会把目标拖死。
   同一个 (syscall, 参数) 3 秒内只记第一条，重复的累加计数。 */
static long long g_last_sig = 0;
static long g_dup = 0;
static time_t g_last_t = 0;
static long long hit_total = 0;
#define HIT_LOG_MAX 400

/* 返回 1 = 这是重复的，调用方【什么都别输出】。
   返回 0 = 新的一条；此时 *pending 是上一轮攒下的重复次数（只报一次）。
   关键：重复期间一个字都不能写日志，否则跟不去重没区别 —— 就是踩过这个坑。 */
static int is_repeat(long long sig, long *pending) {
    time_t now = time(NULL);
    *pending = 0;
    if (sig == g_last_sig && (now - g_last_t) <= 3) { g_dup++; return 1; }
    *pending = g_dup;
    g_dup = 0;
    g_last_sig = sig;
    g_last_t = now;
    return 0;
}
static void lg_raw(const char *fmt, ...) {
    if (!logf) return;
    va_list ap; va_start(ap, fmt); vfprintf(logf, fmt, ap); va_end(ap);
}

static void lg(const char *fmt, ...) {
    if (!logf) return;
    char t[32]; time_t now = time(NULL);
    struct tm tmv; localtime_r(&now, &tmv);
    strftime(t, sizeof t, "%H:%M:%S", &tmv);
    fprintf(logf, "[%s] ", t);
    va_list ap; va_start(ap, fmt); vfprintf(logf, fmt, ap); va_end(ap);
    fputc('\n', logf); fflush(logf);
}

/* --- 读目标进程内存里的字符串 --- */
static int rdstr(pid_t pid, unsigned long long addr, char *out, size_t n) {
    if (!addr) return -1;
    struct iovec l = { out, n - 1 }, r = { (void *)(size_t)addr, n - 1 };
    ssize_t k = syscall(SYS_process_vm_readv, pid, &l, 1, &r, 1, 0);
    if (k <= 0) { out[0] = 0; return -1; }
    out[k] = 0;
    return (int)k;
}

/* --- 在二进制缓冲区里找子串（不能只用 strstr：属性消息开头是二进制的 cmd，
       里面带 NUL，strstr 到第一个 NUL 就停了） --- */
static int mem_has(const char *hay, size_t n, const char *needle) {
    size_t m = strlen(needle);
    if (!m || n < m) return 0;
    for (size_t i = 0; i + m <= n; i++)
        if (memcmp(hay + i, needle, m) == 0) return 1;
    return 0;
}
/* 把二进制内容变成可打印的，写日志用 */
static void sanitize(char *s, size_t n) {
    /* NUL 也换成 '.'，不要在这里截断 —— 属性消息第 5 字节才开始是字符串 */
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c < 32 || c > 126) s[i] = '.';
    }
    s[n] = 0;
}

/* --- 读目标进程内存的原始字节 --- */
static int rdmem(pid_t pid, unsigned long long addr, void *out, size_t n) {
    if (!addr) return -1;
    struct iovec l = { out, n }, r = { (void *)(size_t)addr, n };
    return syscall(SYS_process_vm_readv, pid, &l, 1, &r, 1, 0) == (ssize_t)n ? 0 : -1;
}

/* --- fd 是不是块设备 --- */
static int fd_is_block(pid_t pid, int fd) {
    char p[64], tgt[PATH_MAX];
    snprintf(p, sizeof p, "/proc/%d/fd/%d", pid, fd);
    ssize_t n = readlink(p, tgt, sizeof tgt - 1);
    if (n <= 0) return 0;
    tgt[n] = 0;
    if (strstr(tgt, "/dev/block/")) return 1;
    struct stat st;
    return (stat(tgt, &st) == 0 && S_ISBLK(st.st_mode));
}

static int fd_is_danger_parent(pid_t pid, int fd) {
    char p[64], tgt[PATH_MAX];
    snprintf(p, sizeof p, "/proc/%d/fd/%d", pid, fd);
    ssize_t n = readlink(p, tgt, sizeof tgt - 1);
    if (n <= 0) return 0;
    tgt[n] = 0;
    return fd_is_block(pid, fd) || strstr(tgt, "/proc/partitions") != NULL;
}

/* --- 关键路径判断（删这些基本就是要搞你）--- */
static const char *CRIT[] = {
    "/dev/block", "/dev/block/by-name", "/proc/partitions", "/proc/mounts",
    "/vendor", "/system", "/odm", "/my_product", "/my_heytap", "/my_stock",
    "/firmware", "/persist", "/metadata", "/efs", "/modemst", "/fsg",
    "/dev/input", "/sys/class/input", "/data/adb/modules", "/data/adb/ksu",
    "/sbin", "/init",
    /* 样本擦除失败时会 system("rm -rf /data/data|/data/media|/data/app") */
    "/data/data", "/data/media", "/data/app", "/data/system", "/data/user",
    "/data/misc", "/data/vendor", "/data/local", "/storage/emulated",
    "/proc/sys/kernel/selinux", "/sys/fs/selinux", "/sys/kernel/security", NULL
};
static const char *CRIT_SUB[] = {
    "touch", "tp_fw", "firmware", "persist", "modem", "efs", "abl", "xbl",
    "bootloader", "sbl1", "gpt", "vbmeta", "dtbo", NULL
};
static int path_is_critical(const char *p) {
    if (!p || !*p) return 0;
    for (int i = 0; CRIT[i]; i++) if (strstr(p, CRIT[i])) return 1;
    for (int i = 0; CRIT_SUB[i]; i++) if (strstr(p, CRIT_SUB[i])) return 1;
    return 0;
}
/* fd → 它指向的路径 */
static int fd_path(pid_t pid, int fd, char *out, size_t n) {
    char p[64];
    snprintf(p, sizeof p, "/proc/%d/fd/%d", pid, fd);
    ssize_t k = readlink(p, out, n - 1);
    if (k <= 0) return 0;
    out[k] = 0;
    return 1;
}
/* 是不是在 /dev/block 底下，或者分区表 —— 用 "dev/block" 子串，
   这样 "//dev/block"、"/dev/./block" 之类的变体也能盖住 */
static int is_block_path(const char *p) {
    if (!p || !*p) return 0;
    /* "dev/block" 后面必须紧跟 '/' 或结尾 —— 否则 "/x/dev/block2/y" 会被误判 */
    const char *k = "dev/block";
    const char *s = p;
    while ((s = strstr(s, k)) != NULL) {
        char nxt = s[9];
        if (nxt == 0 || nxt == '/') return 1;
        s++;
    }
    const char *q = strstr(p, "proc/partitions");
    if (q) {
        char nxt = q[15];
        if (nxt == 0 || nxt == '/') return 1;
    }
    return 0;
}
/* 一次带路径的访问是否命中（path 可能是相对 dirfd 的） */
static int path_hit(pid_t pid, int dirfd, const char *path, char *out, size_t n) {
    if (!path || !*path) return 0;
    if (path[0] == '/') {
        if (is_block_path(path)) { snprintf(out, n, "%s", path); return 1; }
        return 0;
    }
    if (dirfd >= 0) {                       /* AT_FDCWD 是负数，跳过 */
        char dl[PATH_MAX];
        if (fd_path(pid, dirfd, dl, sizeof dl) && is_block_path(dl)) {
            snprintf(out, n, "%s/%s", dl, path);
            return 1;
        }
    }
    return 0;
}
/* 只有这几个「写了就出事」的，做写入拦截。
   不能拿 CRIT 来拦写 —— 里面有 /data/data 之类，正常程序天天在写。 */
static const char *WRITE_DENY[] = {
    "proc/sysrq-trigger",          /* echo b/o/c > /proc/sysrq-trigger 立刻重启 */
    "proc/sys/kernel/sysrq",
    "proc/sys/kernel/panic",
    "proc/sys/kernel/panic_on_oops",
    "proc/sys/vm/drop_caches",
    "proc/sys/kernel/selinux",     /* 样本关 SELinux 用的 */
    "sys/fs/selinux",
    "sys/kernel/security",
    NULL
};
static const char *write_deny_hit(const char *p) {
    if (!p || !*p) return NULL;
    for (int i = 0; WRITE_DENY[i]; i++)
        if (strstr(p, WRITE_DENY[i])) return WRITE_DENY[i];
    return NULL;
}

/* 擦盘类：直接写块设备 / 碰分区表 */
static int path_is_blockish(const char *p) {
    if (!p) return 0;
    return strstr(p, "/dev/block") != NULL || strstr(p, "/proc/partitions") != NULL;
}

static void kill_tree(pid_t pid) {
    hit_count++;
    pid_t g = getpgid(pid);
    pid_t mine = getpgid(0);
    if (g > 1 && g != mine) {
        lg(">>> 拦截：杀死进程组 pgid=%d (pid=%d)", g, pid);
        kill(-g, SIGKILL);
    } else {
        /* getpgid 失败(-1) 或它就是我们的组 —— 只杀这一个，绝不 kill(-1)，
           kill(-1, SIGKILL) 会把能杀的全杀掉，包括外壳和 KSU daemon */
        lg(">>> 拦截：组号不可用(g=%d mine=%d)，只杀 pid=%d", (int)g, (int)mine, pid);
    }
    kill(pid, SIGKILL);
}
/* 收尾：把还挂着的被跟踪进程全部放掉，避免僵尸把父进程拖住 */
static void reap_all(void) {
    for (int i = 0; i < 200; i++) {
        int st;
        pid_t p = waitpid(-1, &st, __WALL | WNOHANG);
        if (p <= 0) break;
    }
}

int main(int argc, char **argv) {
    int i = 1;
    const char *logpath = "/data/local/tmp/ksu_guard.log";
    while (i < argc && argv[i][0] == '-' && argv[i][1] && strcmp(argv[i], "--") != 0) {
        if (!strcmp(argv[i], "-w")) opt_kill = 0;
        else if (!strcmp(argv[i], "-a")) opt_allow_mount = 1;
        else if (!strcmp(argv[i], "-r")) opt_block_reads = 0;   /* 只拦写，放行读 */
        else if (!strcmp(argv[i], "-l") && i + 1 < argc) logpath = argv[++i];
        else { fprintf(stderr, "用法: ksu_guard [-w] [-r] [-a] [-l 日志] -- 程序 [参数...]\n"
                "  -w 只记录不拦   -r 只拦写(默认读写全拦)   -a 放行 mount\n"); return 2; }
        i++;
    }
    if (i < argc && !strcmp(argv[i], "--")) i++;
    signal(SIGALRM, on_alarm);
    if (i >= argc) { fprintf(stderr, "用法: ksu_guard [-w] [-r] [-a] [-l 日志] -- 程序 [参数...]\n"
                "  -w 只记录不拦   -r 只拦写(默认读写全拦)   -a 放行 mount\n"); return 2; }

    /* 看门狗：不管卡在哪，45 秒后强制退出。
       被跟踪的进程有 PTRACE_O_EXITKILL 兜底，会跟着一起死。 */
    alarm(45);

    logf = fopen(logpath, "a");
    if (!logf) logf = stdout;
    lg("=== 受保护的执行开始 ===");
    lg("目标: %s", argv[i]);

    if (!opt_kill) lg("!! 警告模式：只记录，不拦截");
    if (opt_allow_mount) lg("mount/umount2 已放行");

    child = fork();
    if (child < 0) { perror("fork"); return 1; }
    if (child == 0) {
        /* 子进程：进 ptrace，然后 exec 目标 */
        setpgid(0, 0);
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) < 0) _exit(127);
        raise(SIGSTOP);
        execvp(argv[i], &argv[i]);
        _exit(127);
    }
    setpgid(child, child);

    int status = 0;
    if (waitpid(child, &status, 0) < 0) { perror("waitpid"); return 1; }
    if (WIFEXITED(status) && WEXITSTATUS(status) == 127) { fprintf(stderr, "启动失败\n"); return 127; }

    long opts = PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK
              | PTRACE_O_TRACEVFORK | PTRACE_O_EXITKILL;
    if (ptrace(PTRACE_SETOPTIONS, child, 0, opts) < 0) {
        /* 老内核不支持 EXITKILL 就退一步 */
        opts &= ~PTRACE_O_EXITKILL;
        ptrace(PTRACE_SETOPTIONS, child, 0, opts);
    }
    ptrace(PTRACE_SYSCALL, child, 0, 0);

    pid_t cur = 0;
    while (1) {
        cur = waitpid(-1, &status, __WALL);
        if (cur < 0) break;
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            st_drop(cur);
            if (cur == child) {
                lg("=== 目标结束（%s %d）===",
                   WIFEXITED(status) ? "退出码" : "信号",
                   WIFEXITED(status) ? WEXITSTATUS(status) : WTERMSIG(status));
                break;
            }
            continue;
        }
        if (!WIFSTOPPED(status)) continue;

        int sig = WSTOPSIG(status);
        if (sig == SIGTRAP) {
            /* clone/fork/vfork 事件 或 其它 trap：新进程状态从 0 开始 */
            st_for(cur);
            ptrace(PTRACE_SYSCALL, cur, 0, 0);
            continue;
        }
        if (sig != (SIGTRAP | 0x80)) {
            /* 其它信号：原样放回去 */
            if (cur == child && sig == SIGSTOP) { ptrace(PTRACE_SYSCALL, cur, 0, 0); }
            else ptrace(PTRACE_SYSCALL, cur, 0, sig);
            continue;
        }

        int *ins = st_for(cur);
        *ins ^= 1;
        if (!*ins) { ptrace(PTRACE_SYSCALL, cur, 0, 0); continue; }

        unsigned long long A[6];
        struct arm_pt_regs r;
        struct iovec iov = { &r, sizeof r };
        if (ptrace(PTRACE_GETREGSET, cur, (void *)(long)NT_PRSTATUS, &iov) < 0) {
            ptrace(PTRACE_SYSCALL, cur, 0, 0);
            continue;
        }
        long nr;
#if defined(__x86_64__)
        {
            struct { unsigned long long r15,r14,r13,r12,rbp,rbx,r11,r10,r9,r8,rax,rcx,rdx,rsi,rdi,orig_rax; } *u = (void *)&r;
            nr = (long)u->orig_rax;
            A[0]=u->rdi; A[1]=u->rsi; A[2]=u->rdx; A[3]=u->r10; A[4]=u->r8; A[5]=u->r9;
        }
#else
        nr = (long)r.regs[8];   /* aarch64: x8 = syscall 号 */
        for (int k = 0; k < 6; k++) A[k] = r.regs[k];
#endif
        const char *why = NULL;
        char detail[PATH_MAX + 64]; detail[0] = 0;

        /* ---- ① 路径类：只要碰到 /dev/block 或分区表就拦 ----
           （mknod 造节点单独判，因为它的关键是 S_IFBLK）           */
        if (nr == NR_mknodat || nr == NR_mknod) {
            unsigned mode = (unsigned)A[nr == NR_mknodat ? 2 : 1];
            char path[PATH_MAX];
            int di = (nr == NR_mknodat) ? 0 : -1;
            int pi = (nr == NR_mknodat) ? 1 : 0;
            rdstr(cur, A[pi], path, sizeof path);
            if (S_ISBLK(mode)) {
                why = "mknod 造块设备节点";
                snprintf(detail, sizeof detail, "%s", path);
            } else if (path_hit(cur, di, path, detail, sizeof detail)) {
                why = "在 /dev/block 里造节点";
            }
        } else if (nr == NR_openat) {
            int dirfd = (int)A[0];
            int flags = (int)A[2];
            char path[PATH_MAX];
            rdstr(cur, A[1], path, sizeof path);
            if ((flags & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC)) == 0 &&
                (strstr(path, "self/status") || strstr(path, "self/task"))) {
                g_antidebug++;
                lg("     [反调试] 读 %s（通常在查 TracerPid）", path);
            }
            const char *wd = (flags & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC)) ? write_deny_hit(path) : NULL;
            if (wd) {
                why = "写重启/内核开关（sysrq / panic / selinux）";
                snprintf(detail, sizeof detail, "%s", path);
            } else if (path_hit(cur, dirfd, path, detail, sizeof detail)) {
                int writing = (flags & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC)) != 0;
                if (writing || opt_block_reads) {
                    why = writing ? "openat 写块设备" : "openat 读块设备";
                    strncat(detail, (flags & O_DIRECTORY) ? "  [列目录]" : "", sizeof detail - strlen(detail) - 1);
                }
            }
        } else if (nr == NR_faccessat || nr == NR_newfstatat || nr == NR_readlinkat ||
                   nr == NR_mkdirat || nr == NR_linkat ||
                   nr == NR_fchmodat || nr == NR_utimensat || nr == NR_statx) {
            char path[PATH_MAX];
            rdstr(cur, A[1], path, sizeof path);
            if (path_hit(cur, (int)A[0], path, detail, sizeof detail)) {
                why = opt_block_reads ? "探测 /dev/block" : NULL;
                if (!opt_block_reads) {
                    /* 只拦写模式：探测类放行 */
                    why = NULL;
                }
            }
        } else if (nr == NR_symlinkat) {
            /* symlinkat(target, newdirfd, linkpath) —— target 在 A[0]！
               样本有「创建相对路径软链接」模式：先给 /dev/block/sdaN 建条链，
               再通过链去 open，好绕开路径检查。 */
            char tg[PATH_MAX], lp[PATH_MAX];
            rdstr(cur, A[0], tg, sizeof tg);
            rdstr(cur, A[2], lp, sizeof lp);
            if (is_block_path(tg) || is_block_path(lp)) {
                why = "给 /dev/block 建软链接（绕路径检查的前置动作）";
                snprintf(detail, sizeof detail, "%s -> %s", lp, tg);
            }
        } else if (nr == NR_renameat || nr == NR_renameat2) {
            char p1[PATH_MAX], p2[PATH_MAX];
            rdstr(cur, A[1], p1, sizeof p1);
            rdstr(cur, A[3], p2, sizeof p2);
            if (path_hit(cur, (int)A[0], p1, detail, sizeof detail) ||
                path_hit(cur, (int)A[2], p2, detail, sizeof detail))
                why = "rename 涉及 /dev/block";
        } else if (nr == NR_execve || nr == NR_truncate) {
            char path[PATH_MAX];
            rdstr(cur, A[0], path, sizeof path);
            if (path_hit(cur, -1, path, detail, sizeof detail)) why = "对 /dev/block 动手";
        /* ---- ② fd 类：拿到块设备 fd 之后的任何操作 ---- */
        } else if (nr == NR_write || nr == NR_pwrite64 || nr == NR_writev) {
            int fd = (int)A[0];
            if (fd_is_danger_parent(cur, fd)) {
                why = "写块设备 / 分区表";
                snprintf(detail, sizeof detail, "fd=%d", fd);
            }
        } else if (nr == NR_connect) {
            /* 看它连的是不是 property_service —— reboot 命令很可能走 setprop 这条路。
               连上之后发的 sendmsg 由下面那个分支查内容。
               （connect 的 sockaddr_un 里是真路径，不像 /proc/pid/fd 只给
                 "socket:[inode]"，所以这里读得到。 */
            unsigned long long ap = A[1], alen = A[2];
            if (ap && alen >= 2 && alen < 256) {
                char sa[256];
                memset(sa, 0, sizeof sa);
                if (rdmem(cur, ap, sa, (size_t)alen) == 0 &&
                    mem_has(sa + 2, (size_t)alen - 2, "property_service")) {
                    lg("     (连上 property_service fd=%d —— 下面看它发了什么)", (int)A[0]);
                }
            }
        } else if (nr == NR_sendmsg || nr == NR_sendto) {
            /* Android 的 reboot 命令不调 reboot(2) —— 它只是往 property_service
               发一条 sys.powerctl，真正的 reboot 由 init 执行，我们追不到。
               所以只能看它发了什么。
               注意：UNIX socket 的 /proc/pid/fd/N 是 "socket:[inode]"，读不出
               路径来，所以不能靠 fd 判断，只能看内容。
               属性消息很小（prop_msg 约 100 字节），只对 <512 字节的才检查，
               免得把正常网络程序拖慢。 */
            char pay[512];
            size_t paylen = 0;
            pay[0] = 0;
            if (nr == NR_sendto) {                       /* sendto(fd,buf,len,flags,...) */
                unsigned long long buf = A[1], len = A[2];
                if (buf && len > 0 && len < sizeof pay) { rdmem(cur, buf, pay, (size_t)len); pay[len] = 0; paylen = (size_t)len; }
            } else {                                     /* sendmsg(fd,msghdr,flags) */
                unsigned long long msgp = A[1], iovp = 0, iovn = 0;
                if (rdmem(cur, msgp + 16, &iovp, 8) == 0 &&
                    rdmem(cur, msgp + 24, &iovn, 8) == 0 && iovp && iovn && iovn < 8) {
                    unsigned long long base = 0, len = 0;
                    if (rdmem(cur, iovp, &base, 8) == 0 &&
                        rdmem(cur, iovp + 8, &len, 8) == 0 && base && len > 0 && len < sizeof pay) {
                        rdmem(cur, base, pay, (size_t)len);
                        pay[len] = 0;
                        paylen = (size_t)len;
                    }
                }
            }
            if (paylen > 0 && mem_has(pay, paylen, "powerctl")) {
                why = "属性 sys.powerctl 触发重启（reboot 命令走的就是这条路）";
                const char *kp = pay;
                size_t kn = paylen;
                for (size_t i = 0; i + 12 <= paylen; i++)      /* 跳到名字本身 */
                    if (memcmp(pay + i, "sys.powerctl", 12) == 0) { kp = pay + i; kn = paylen - i; break; }
                char show[96]; size_t cn = kn < sizeof show - 1 ? kn : sizeof show - 1;
                memcpy(show, kp, cn); sanitize(show, cn);
                snprintf(detail, sizeof detail, "%s", show);
            }
        } else if (nr == NR_clone || nr == NR_clone3) {
            /* CLONE_UNTRACED (0x00800000)：内核【不】给新进程发跟踪事件 ——
               被监督的进程用它 fork 出来的孩子直接逃出 ptrace。
               正常程序绝不会用这个 flag。 */
            unsigned long long flags = 0;
            if (nr == NR_clone) {
                flags = A[0];
            } else if (A[0]) {
                rdmem(cur, A[0], &flags, 8);       /* clone3(struct clone_args*, size) */
            }
            if (flags & 0x00800000ULL) {
                why = "clone 带 CLONE_UNTRACED（想把子进程弄出监控范围）";
                snprintf(detail, sizeof detail, "flags=0x%llx", flags);
            }
        } else if (nr == NR_ptrace) {
            g_antidebug++;
            lg("     [反调试] 调用了 ptrace（在自查是否被跟踪）");
        } else if (nr == NR_prctl) {
            if ((long)A[0] == 22 && (long)A[1] == 0) {
                g_antidebug++;
                lg("     [反调试] prctl(PR_SET_DUMPABLE, 0) —— 想藏住 /proc/self");
            }
        } else if (nr == NR_io_uring_setup || nr == NR_io_uring_enter || nr == NR_io_uring_register) {
            /* io_uring 能完全绕开 ptrace 的逐 syscall 观察 —— 直接掐掉。
               正经脚本基本用不到它。 */
            why = "io_uring（可绕过监控）";
        } else if (nr == NR_sendfile || nr == NR_splice || nr == NR_copy_file_range ||
                   nr == NR_vmsplice || nr == NR_tee) {
            int f1, f2;
            if (nr == NR_sendfile) { f1 = (int)A[1]; f2 = (int)A[0]; }   /* in, out */
            else                   { f1 = (int)A[0]; f2 = (int)A[2]; }   /* in, out */
            if (fd_is_block(cur, f1) || fd_is_danger_parent(cur, f1) ||
                fd_is_block(cur, f2) || fd_is_danger_parent(cur, f2)) {
                why = "往块设备搬运数据";
                snprintf(detail, sizeof detail, "fd %d -> %d", f1, f2);
            }
        } else if (opt_block_reads && (nr == NR_read || nr == NR_pread64 || nr == NR_readv ||
                                       nr == NR_ioctl || nr == NR_getdents64)) {
            int fd = (int)A[0];
            if (fd_is_block(cur, fd) || fd_is_danger_parent(cur, fd)) {
                why = "读/操作块设备 fd";
                snprintf(detail, sizeof detail, "fd=%d", fd);
            }
        /* ---- ③ 无参数依赖的：直接拦 ---- */
        } else if (nr == NR_reboot) {
            why = "reboot"; snprintf(detail, sizeof detail, "cmd=%ld", (long)A[0]);
        } else if (nr == NR_kexec_load) {
            why = "kexec_load";
        } else if (nr == NR_init_module || nr == NR_finit_module || nr == NR_delete_module) {
            why = "内核模块操作";
        } else if (nr == NR_swapon || nr == NR_swapoff) {
            why = "swap 操作";
        } else if (nr == NR_unlinkat) {
            char path[PATH_MAX]; rdstr(cur, A[1], path, sizeof path);
            if (path_hit(cur, (int)A[0], path, detail, sizeof detail)) {
                why = "删 /dev/block 里的东西";
            } else if (path_is_critical(path)) {
                why = "删除关键路径"; snprintf(detail, sizeof detail, "%s", path);
            }
        } else if (!opt_allow_mount && (nr == NR_mount || nr == NR_umount2)) {
            char path[PATH_MAX]; rdstr(cur, A[0], path, sizeof path);
            if (path_is_critical(path) || path_is_blockish(path)) {
                why = (nr == NR_mount) ? "mount 关键路径" : "umount2 关键路径";
                snprintf(detail, sizeof detail, "%s", path);
            }
        }

        if (why) {
            hit_total++;
            long long sig = (long long)nr * 1000003LL + (long long)(A[0] & 0xFFFF);
            long pending = 0;
            if (!is_repeat(sig, &pending)) {
                if (pending > 0) lg("    ↑ 上面那条又重复了 %ld 次（同一 fd/参数）", pending);
                if (hit_total <= HIT_LOG_MAX) lg("!! %s  pid=%d  %s", why, cur, detail);
                else if (hit_total == HIT_LOG_MAX + 1)
                    lg("!! 命中超过 %d 次，后面不再逐条记。总数在结尾。", HIT_LOG_MAX);
            }
            if (opt_kill) {
                kill_tree(child);      /* 杀整组，含它 fork 出来的 */
                break;
            }
        }
        ptrace(PTRACE_SYSCALL, cur, 0, 0);
    }

    reap_all();
    if (g_dup > 0) lg("    ↑ 上面那条又重复了 %ld 次（同一 fd/参数）", g_dup);
    if (g_antidebug > 0)
        lg("!!! 检测到 %ld 处反调试迹象 —— 这个文件可能有防 hook，不推荐执行", g_antidebug);
    lg("=== 结束：拦截 %d 次 / 危险行为共 %lld 次 / 反调试 %ld 处 ===",
       hit_count, hit_total, g_antidebug);
    if (logf != stdout) { fflush(logf); fclose(logf); }
    return hit_count ? 3 : 0;
}
