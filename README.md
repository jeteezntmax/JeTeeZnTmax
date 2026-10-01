# JeTeeZnTmax

KernelSU 工具箱模块 —— **一个把系统底层信息、机型伪装、温控、应用/进程管理
和「受保护的执行」全塞进一个玻璃拟态 WebUI 的东西。**

作者：**[@JeTeeZnTmax](https://github.com/jeteezntmax)**
UI 借鉴：**@月虹yh**

---

## 功能

### 🛡 受保护的执行（本模块最特别的一块）

把一个**来路不明的脚本或程序**放在 `ptrace` 底下跑，在危险 syscall
**执行之前**把它拦下来。

不是沙箱、不是虚拟机 —— 是同权限下的 syscall 级拦截。

**为什么需要它：**

现在流行的「格机」程序基本都是这样写的：

```c
#define my_openat(dirfd, path, flags, mode) syscall(__NR_openat, dirfd, path, flags, mode)
```

**全部走 raw syscall** —— `LD_PRELOAD` / libc hook 完全失效；
而且它用 `openat(目录fd, "sda1")` 这种方式打开块设备，
连完整路径都不给，基于路径匹配的 LSM / seccomp 也拦不住。

**ptrace 在内核停点上看得到，它绕不过。**

**拦什么：**

| 类别 | 内容 |
| --- | --- |
| 块设备 | `/dev/block` 下的一切（读、写、列目录、stat、readlink），`/proc/partitions` |
| 建节点 | `mknod` / `mknodat` 带 `S_IFBLK` |
| 内核开关 | 写 `/proc/sysrq-trigger`、`/proc/sys/kernel/panic`、`/sys/fs/selinux` |
| 危险删除 | `/vendor` `/system` `/persist` `/metadata` `/dev/input` `/data/data` 等，以及名字含 `touch` `firmware` `modem` `abl` `xbl` `vbmeta` 的 |
| 绕道路径 | `io_uring_setup/enter/register`、`splice`、`sendfile`、`copy_file_range` |
| 逃逸监控 | `clone` / `clone3` 带 `CLONE_UNTRACED` |
| 重启 | `reboot(2)` syscall、属性 `sys.powerctl` |

**两种模式：**

- **先观察** —— 只记录不拦。**先用它跑一遍看清行为，再决定要不要动刀**
- **拦截并杀死** —— 命中立刻 SIGKILL 整个进程组

**两种范围：**

- **读写全拦**（默认）—— 碰 `/dev/block` 就杀
- **只拦写** —— 放行读。**自己备份 boot 镜像的时候用这个**，
  否则 `dd if=/dev/block/by-name/boot of=/sdcard/boot.img` 会被当成攻击一起杀掉

### 📦 应用管理

全部应用列表（中文应用名）、搜索、点开看包名 / 版本 / 安装路径 / SDK /
UID / 大小 / 安装时间。支持**冻结**、**卸载**、**备份 APK**。

### ⚙️ 进程管理

PID / PPID / 用户 / **实时 CPU%（两次采样差值，不是生命周期平均值）** /
RSS / 状态 / cpuset / cgroup / wchan，可按 CPU / 内存 / PID / UID 排序，
底栏搜索。可以 **kill -9**。

### 🌡 温度 / 电源 / 性能

- 环形仪表盘、各核心频率、真实 CPU 占用趋势
- 实时功率、电池健康 / 循环次数、充电信息、剩余时间
- 温度分类总览、thermal zone 状态、降温限频、充电电流限制
- **Extreme GT 去温控**集成（见下方"依赖"）

### 📱 机型伪装

Device Faker 的配置前端（TOML 编辑、模板管理、备份）。

### 🌐 浏览器访问

设置页开启后，在浏览器输入 `127.0.0.1:8765` 就能用同一个界面。
（只读快照，不需要 token）

### 📱 独立桌面 App

`app/` 是真源码，`release/` 有编好的 APK。

它自带 `ksu` 桥，所以**打开就是完整功能，不是只读预览**。
自带应用图标（走 PackageManager，比 shell 快得多）。

---

## 安装

### 要求

- **KernelSU**（或任何提供 `su` 的 root 方案）
- Android 8+ / arm64
- 建议先完整备份

### 步骤

1. 下载 Release 里的 `ksu-toolbox-update.zip`
2. KernelSU 管理器 → 模块 → 从本地安装
3. 重启
4. 管理器里点模块的「打开」，或装桌面 App

---

## ⚠️ 依赖（**必须自己装，本仓库不含**）

### Device Faker —— 机型伪装需要

- 地址：https://github.com/Seyud/device_faker
- 协议：**GPL-3.0**

> 因为它是 GPL-3.0，本人**无权对它附加「禁止修改」这类限制**，
> 所以它的任何文件都**没有**放进本仓库。
> 想用机型伪装功能，请自行去上游下载安装。

### Extreme GT —— 去温控需要

- 原作者：**嘟嘟ski & AB**
- 版本：vAB-1.3.0（二改板：无损去温控）

本仓库内有三个**基于它修改**的文件（`eg.sh` / `eg-setup.sh` /
`sys_thermal_control_config_default.xml`），它们**不属于本人原创，
不适用本仓库协议**，详见 CREDITS.md。

---

## 目录结构

```
.
├── webroot/index.html      全内联 WebUI（HTML/CSS/JS，约 200KB）
├── bin/
│   ├── ksu_guard           ptrace 拦截器（aarch64 静态 ELF）
│   ├── collect.sh          HTTP 快照采集
│   └── webui-server.sh     本地服务
├── app/                    独立桌面 App 源码
│   ├── java/.../MainActivity.java
│   ├── build.sh            不用 Gradle，直接调 SDK 工具链
│   ├── make_icon.py        纯 Python 生成图标（不依赖 PIL）
│   └── guard/              ksu_guard 的 C 源码 + 测试小程序
├── customize.sh / service.sh / module.prop
└── release/                编好的 APK 和模块 zip
```

---

## 自己编译

### WebUI

`webroot/index.html` 是全内联的，改完直接刷。

### ksu_guard（ptrace 拦截器）

需要 aarch64 交叉工具链 + musl 静态库：

```sh
# 工具链
apt install gcc-aarch64-linux-gnu binutils-aarch64-linux-gnu
# musl（要自己编一份 aarch64 静态版，装到 /opt/musl）
sh app/guard/build-guard.sh
```

### 桌面 App

**不需要 Android Studio**，只要 JDK + SDK 的 build-tools：

```sh
sh app/build.sh
```

它干的事：`aapt2 compile` → `aapt2 link` → `javac` → `d8` →
塞 `classes.dex` → `zipalign` → `apksigner`。

---

## 协议

**自定义许可，不是开源协议。** 简单说：

- ✅ 免费用、原样分享、读源码学习
- ❌ **禁止二改后发布**、**禁止倒卖**、禁止去除署名、禁止商业用途

> 自己改了自己用没问题，改完再发出去不行。

**Extreme GT 那三个文件不适用本协议**，它们归原作者。
完整条款见 [LICENSE](LICENSE)。

---

## 免责声明

本模块涉及系统底层修改（内核接口、分区读写拦截、Zygisk 注入）。

**使用风险自负。** 因使用本模块导致的设备损坏、数据丢失、无法开机、
保修失效、账号被封等后果，作者不承担责任。

**请务必先备份，并先在备用设备上验证。**

---

## 致谢

- **@月虹yh** —— UI 设计借鉴
- **Seyud** —— Device Faker
- **嘟嘟ski & AB** —— Extreme GT
- **tiann** —— KernelSU

详见 [CREDITS.md](CREDITS.md)。
