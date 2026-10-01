#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/uio.h>
struct prop_msg { unsigned cmd; char name[92]; char value[92]; };
int main(int argc, char **argv){
    const char *path = (argc>1) ? argv[1] : "/dev/socket/property_service";
    const char *name = (argc>2) ? argv[2] : "sys.powerctl";
    const char *val  = (argc>3) ? argv[3] : "reboot,";
    struct sockaddr_un a; memset(&a,0,sizeof a);
    a.sun_family = AF_UNIX; strncpy(a.sun_path, path, sizeof a.sun_path - 1);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if(connect(fd,(void*)&a,sizeof a) < 0){ perror("[proptest] connect"); return 1; }
    struct prop_msg m; memset(&m,0,sizeof m);
    m.cmd = 1; strncpy(m.name, name, sizeof m.name - 1); strncpy(m.value, val, sizeof m.value - 1);
    struct iovec iov = { &m, sizeof m };
    struct msghdr h; memset(&h,0,sizeof h);
    h.msg_iov = &iov; h.msg_iovlen = 1;
    if(sendmsg(fd, &h, 0) < 0) perror("[proptest] sendmsg");
    fprintf(stderr, "[proptest] 已发出 %s=%s\n", name, val);
    return 0;
}
