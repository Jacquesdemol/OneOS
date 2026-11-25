/*
 * oneos_bpf_ringbuf: an empty BPF ring buffer pinned where a newer network module looks for one.
 *
 * One UI 9.5 on a One UI 8 phone (oneui8test11, S22 Ultra 6fcc905a): the guest keeps One UI's Android 16
 * tethering module. Its train 372038420 starts ConnectivityService's loopback event poller only on
 * Android 17, so under One UI 8 the boot-time BPF loader never pinned the poller's ring buffer, and in the
 * guest (Android 17) system_server aborted in LoopbackEventHandler::Start ("BpfRingbuf init failed: failed
 * to retrieve ringbuffer at /sys/fs/bpf/netd_shared/map_netd_loopback_access_ringbuf"). An empty ring
 * buffer there lets the poller start; no program writes into it, so it only ever sees nothing.
 *
 *   oneos_bpf_ringbuf create <pinned path> [bytes]   pins one (bytes: a power of two, default 65536);
 *                                                    exit 0 also when the path already exists (left alone)
 *   oneos_bpf_ringbuf remove <pinned path>           unlinks the pin (the caller removes only its own)
 *
 * The pin gets mode 0660, owner root:system and the SELinux label of its folder's other maps.
 *
 *   oneos_bpf_ringbuf alias <existing ring buffer pin> <pinned path>
 *                                                    pins a ring buffer One UI already has at the new path
 *                                                    (0.5.38: the Galaxy S22 Ultra Exynos 91c4fef5 refused
 *                                                    BPF_MAP_CREATE with EPERM; getting and pinning an
 *                                                    existing map needs no map creation). Only a ring buffer.
 *
 * oneui8test13, S22 on One UI 8 (tethering train 374205010; test11 3d35a984, e7d51e44): the guest's netd
 * (Android 17) asked libnetd_updatable for /sys/fs/bpf/netd_shared/prog_netd_sockops_prog, which One UI 8's
 * loader had not pinned on that boot (other boots of the same phone had it): netd aborted every 5 s,
 * system_server waited for it in NetworkManagementService.create and the watchdog killed it. A socket-ops
 * program that does nothing (returns 1, the kernel's default) pinned there lets netd attach it and go on.
 *
 *   oneos_bpf_ringbuf sockops-create <pinned path>   pins one; exit 0 also when the path already exists
 *   oneos_bpf_ringbuf sockops-remove <pinned path> [cgroup dir]
 *                                                    detaches it from the cgroup (default /sys/fs/cgroup)
 *                                                    only if the program attached there is this one, then
 *                                                    unlinks the pin
 *
 * 0.5.44, S22 on One UI 8 (0.5.43 494048ab): One UI 8's tethering module, under Android 17, opens
 * /sys/fs/bpf/netd_shared/map_netd_l4s_accecn_enabled_map (an array, 4-byte key, 1-byte value, 1 entry;
 * read on the A36's One UI 9) in BpfNetMaps.initBpfMaps, which One UI 8's loader never pinned:
 * NetworkStatsService died on "Cannot open l4s_accecn_ map" 10 s into the guest's boot. An empty one (all
 * zero: L4S off) pinned there for the session.
 *
 *   oneos_bpf_ringbuf array-create <pinned path> <key bytes> <value bytes> <entries>
 *                                                    pins an empty array map; exit 0 also when the path
 *                                                    already exists (left alone); removed with remove
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/bpf.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/xattr.h>
#include <unistd.h>
#include <dirent.h>

#ifndef BPF_MAP_TYPE_RINGBUF
#define BPF_MAP_TYPE_RINGBUF 27
#endif

static long sys_bpf(int cmd, union bpf_attr *attr) {
    return syscall(__NR_bpf, cmd, attr, sizeof(*attr));
}

/* Resolve SELinux context from parent bpffs dir first (e.g. /sys/fs/bpf/netd_shared), else sibling pins. */
static int resolve_pin_label(const char *path, int is_prog, char *label, size_t label_cap, ssize_t *label_len) {
    char dir[512], probe[1024];
    snprintf(dir, sizeof(dir), "%s", path);
    char *slash = strrchr(dir, '/');
    if (!slash) return -1;
    *slash = 0;

    *label_len = -1;
    ssize_t dn = getxattr(dir, "security.selinux", label, label_cap - 1);
    if (dn > 0) {
        label[dn] = '\0';
        *label_len = (ssize_t)strlen(label);
        if (*label_len > 0) return 0;
    }

    const char *want = is_prog ? "prog_" : "map_";
    size_t want_len = strlen(want);
    for (int pass = 0; pass < 2; pass++) {
        DIR *d = opendir(dir);
        if (!d) return -1;
        struct dirent *e;
        while ((e = readdir(d))) {
            if (e->d_name[0] == '.') continue;
            if (pass == 0 && strncmp(e->d_name, want, want_len) != 0) continue;
            snprintf(probe, sizeof(probe), "%s/%s", dir, e->d_name);
            if (strcmp(probe, path) == 0) continue;
            ssize_t n = getxattr(probe, "security.selinux", label, label_cap - 1);
            if (n > 0) {
                label[n] = '\0';
                *label_len = (ssize_t)strlen(label);
                if (*label_len > 0) {
                    closedir(d);
                    return 0;
                }
            }
        }
        closedir(d);
    }
    return -1;
}

static int open_fscreate(void) {
    int fd = open("/proc/thread-self/attr/fscreate", O_WRONLY | O_CLOEXEC);
    if (fd < 0) fd = open("/proc/self/attr/fscreate", O_WRONLY | O_CLOEXEC);
    return fd;
}

/*
 * Pin BPF fd at path with SELinux context applied at creation time via fscreate,
 * since Linux 5.10 bpffs rejects post-creation setxattr(..., "security.selinux", ...) with EOPNOTSUPP.
 * Ownership/mode match host bpfloader: root:net_bw_acct (0:1006) 0660 for maps, root:root (0:0) 0440 for progs.
 */
static int pin_bpf_object(const char *path, int fd, int is_prog, mode_t mode) {
    char label[256];
    ssize_t label_len = -1;
    uid_t uid = 0;
    gid_t gid = is_prog ? 0 : 1006;
    int have_label = (resolve_pin_label(path, is_prog, label, sizeof(label), &label_len) == 0);

    int fs_fd = -1;
    int fscreate_ok = 0;
    if (have_label) {
        fs_fd = open_fscreate();
        if (fs_fd >= 0) {
            size_t wlen = (size_t)label_len + 1; /* include trailing NUL for procfs fscreate */
            if (label_len > 0 && write(fs_fd, label, wlen) == (ssize_t)wlen) {
                fscreate_ok = 1;
            } else {
                fprintf(stderr, "fscreate write('%s') failed for %s: %s\n", label, path, strerror(errno));
            }
        } else {
            fprintf(stderr, "open fscreate failed for %s: %s\n", path, strerror(errno));
        }
    }

    union bpf_attr a;
    memset(&a, 0, sizeof(a));
    a.pathname = (uint64_t)(uintptr_t)path;
    a.bpf_fd = (uint32_t)fd;
    long pin_rc = sys_bpf(BPF_OBJ_PIN, &a);
    int pin_err = errno;

    if (fs_fd >= 0) {
        if (fscreate_ok) {
            ssize_t clr = write(fs_fd, "", 0);
            (void)clr;
        }
        close(fs_fd);
    }

    if (pin_rc != 0) {
        errno = pin_err;
        perror("BPF_OBJ_PIN");
        return -1;
    }

    if (chown(path, uid, gid) != 0) perror("chown");
    if (chmod(path, mode) != 0) perror("chmod");

    if (have_label) {
        char got[256];
        ssize_t gn = getxattr(path, "security.selinux", got, sizeof(got) - 1);
        int matches = 0;
        if (gn > 0) {
            got[gn] = '\0';
            matches = (strcmp(got, label) == 0);
        }
        if (!matches) {
            if (setxattr(path, "security.selinux", label, (size_t)label_len + 1, 0) != 0) {
                fprintf(stderr, "no SELinux label copied for %s (want=%s got=%s fscreate=%s setxattr=%s)\n",
                        path, label, gn > 0 ? got : "none", fscreate_ok ? "ok" : "err", strerror(errno));
            }
        }
    } else {
        fprintf(stderr, "no SELinux label copied for %s (no labeled dir/sibling found)\n", path);
    }
    return 0;
}

static int sockops_create(const char *path) {
    struct stat st;
    if (stat(path, &st) == 0) { printf("exists %s\n", path); return 0; }
    struct bpf_insn insns[2];
    memset(insns, 0, sizeof(insns));
    insns[0].code = BPF_ALU64 | BPF_MOV | BPF_K;   /* r0 = 1 */
    insns[0].dst_reg = BPF_REG_0;
    insns[0].imm = 1;
    insns[1].code = BPF_JMP | BPF_EXIT;            /* exit */
    static const char license[] = "Apache 2.0";
    union bpf_attr a;
    memset(&a, 0, sizeof(a));
    a.prog_type = BPF_PROG_TYPE_SOCK_OPS;
    a.insn_cnt = 2;
    a.insns = (uint64_t)(uintptr_t)insns;
    a.license = (uint64_t)(uintptr_t)license;
    a.expected_attach_type = BPF_CGROUP_SOCK_OPS;
    long fd = sys_bpf(BPF_PROG_LOAD, &a);
    if (fd < 0) { perror("BPF_PROG_LOAD"); return 1; }
    int rc = pin_bpf_object(path, (int)fd, 1, 0440);
    close((int)fd);
    if (rc != 0) return 1;
    printf("created %s (socket-ops program that returns 1)\n", path);
    return 0;
}

static int sockops_remove(const char *path, const char *cgroup) {
    union bpf_attr a;
    memset(&a, 0, sizeof(a));
    a.pathname = (uint64_t)(uintptr_t)path;
    long fd = sys_bpf(BPF_OBJ_GET, &a);
    if (fd < 0) {
        if (errno == ENOENT) { printf("no %s\n", path); return 0; }
        perror("BPF_OBJ_GET"); return 1;
    }
    struct bpf_prog_info info;
    memset(&info, 0, sizeof(info));
    memset(&a, 0, sizeof(a));
    a.info.bpf_fd = (uint32_t)fd;
    a.info.info_len = sizeof(info);
    a.info.info = (uint64_t)(uintptr_t)&info;
    if (sys_bpf(BPF_OBJ_GET_INFO_BY_FD, &a) != 0) { perror("BPF_OBJ_GET_INFO_BY_FD"); close((int)fd); return 1; }
    int cg = open(cgroup, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (cg >= 0) {
        uint32_t ids[64], cnt = 64;
        memset(&a, 0, sizeof(a));
        a.query.target_fd = (uint32_t)cg;
        a.query.attach_type = BPF_CGROUP_SOCK_OPS;
        a.query.prog_ids = (uint64_t)(uintptr_t)ids;
        a.query.prog_cnt = cnt;
        if (sys_bpf(BPF_PROG_QUERY, &a) == 0) {
            int ours = 0;
            for (uint32_t i = 0; i < a.query.prog_cnt && i < cnt; i++) if (ids[i] == info.id) ours = 1;
            if (ours) {
                memset(&a, 0, sizeof(a));
                a.target_fd = (uint32_t)cg;
                a.attach_bpf_fd = (uint32_t)fd;
                a.attach_type = BPF_CGROUP_SOCK_OPS;
                if (sys_bpf(BPF_PROG_DETACH, &a) == 0) printf("detached program %u from %s\n", info.id, cgroup);
                else perror("BPF_PROG_DETACH");
            } else {
                printf("program %u not attached to %s; nothing detached\n", info.id, cgroup);
            }
        } else {
            perror("BPF_PROG_QUERY");
        }
        close(cg);
    } else {
        perror(cgroup);
    }
    close((int)fd);
    if (unlink(path) != 0 && errno != ENOENT) { perror("unlink"); return 1; }
    printf("removed %s\n", path);
    return 0;
}

static int ringbuf_alias(const char *src, const char *path) {
    struct stat st;
    if (stat(path, &st) == 0) { printf("exists %s\n", path); return 0; }
    union bpf_attr a;
    memset(&a, 0, sizeof(a));
    a.pathname = (uint64_t)(uintptr_t)src;
    a.file_flags = BPF_F_RDONLY;
    long fd = sys_bpf(BPF_OBJ_GET, &a);
    if (fd < 0) {
        memset(&a, 0, sizeof(a));
        a.pathname = (uint64_t)(uintptr_t)src;
        fd = sys_bpf(BPF_OBJ_GET, &a);
    }
    if (fd < 0) { perror("BPF_OBJ_GET"); return 1; }
    struct bpf_map_info info;
    memset(&info, 0, sizeof(info));
    memset(&a, 0, sizeof(a));
    a.info.bpf_fd = (uint32_t)fd;
    a.info.info_len = sizeof(info);
    a.info.info = (uint64_t)(uintptr_t)&info;
    if (sys_bpf(BPF_OBJ_GET_INFO_BY_FD, &a) != 0) { perror("BPF_OBJ_GET_INFO_BY_FD"); close((int)fd); return 1; }
    if (info.type != BPF_MAP_TYPE_RINGBUF) { fprintf(stderr, "%s is not a ring buffer (type %u)\n", src, info.type); close((int)fd); return 1; }
    int rc = pin_bpf_object(path, (int)fd, 0, 0660);
    close((int)fd);
    if (rc != 0) return 1;
    printf("pinned %s at %s (ring buffer id %u, %u bytes)\n", src, path, info.id, info.max_entries);
    return 0;
}

static int array_create(const char *path, unsigned long key, unsigned long value, unsigned long entries) {
    if (key == 0 || value == 0 || entries == 0 || key > 512 || value > 65536 || entries > 65536) {
        fprintf(stderr, "bad array sizes\n");
        return 2;
    }
    struct stat st;
    if (stat(path, &st) == 0) { printf("exists %s\n", path); return 0; }
    union bpf_attr a;
    memset(&a, 0, sizeof(a));
    a.map_type = BPF_MAP_TYPE_ARRAY;
    a.key_size = (uint32_t)key;
    a.value_size = (uint32_t)value;
    a.max_entries = (uint32_t)entries;
    long fd = sys_bpf(BPF_MAP_CREATE, &a);
    if (fd < 0) { perror("BPF_MAP_CREATE"); return 1; }
    int rc = pin_bpf_object(path, (int)fd, 0, 0660);
    close((int)fd);
    if (rc != 0) return 1;
    printf("created %s (array, key %lu, value %lu, %lu entries)\n", path, key, value, entries);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 6 && strcmp(argv[1], "array-create") == 0)
        return array_create(argv[2], strtoul(argv[3], NULL, 0), strtoul(argv[4], NULL, 0), strtoul(argv[5], NULL, 0));
    if (argc >= 4 && strcmp(argv[1], "alias") == 0) return ringbuf_alias(argv[2], argv[3]);
    if (argc >= 3 && strcmp(argv[1], "sockops-create") == 0) return sockops_create(argv[2]);
    if (argc >= 3 && strcmp(argv[1], "sockops-remove") == 0)
        return sockops_remove(argv[2], argc > 3 ? argv[3] : "/sys/fs/cgroup");
    if (argc >= 3 && strcmp(argv[1], "remove") == 0) {
        if (unlink(argv[2]) != 0 && errno != ENOENT) { perror("unlink"); return 1; }
        printf("removed %s\n", argv[2]);
        return 0;
    }
    if (argc < 3 || strcmp(argv[1], "create") != 0) {
        fprintf(stderr, "usage: %s create <pinned path> [bytes] | alias <ring buffer pin> <pinned path> | remove <pinned path> | sockops-create <pinned path> | sockops-remove <pinned path> [cgroup] | array-create <pinned path> <key> <value> <entries>\n", argv[0]);
        return 2;
    }
    const char *path = argv[2];
    unsigned long bytes = argc > 3 ? strtoul(argv[3], NULL, 0) : 65536;
    if (bytes < 4096 || (bytes & (bytes - 1))) { fprintf(stderr, "bytes must be a power of two >= 4096\n"); return 2; }
    struct stat st;
    if (stat(path, &st) == 0) { printf("exists %s\n", path); return 0; }
    union bpf_attr a;
    memset(&a, 0, sizeof(a));
    a.map_type = BPF_MAP_TYPE_RINGBUF;
    a.max_entries = (uint32_t)bytes;
    long fd = sys_bpf(BPF_MAP_CREATE, &a);
    if (fd < 0) { perror("BPF_MAP_CREATE"); return 1; }
    int rc = pin_bpf_object(path, (int)fd, 0, 0660);
    close((int)fd);
    if (rc != 0) return 1;
    printf("created %s (%lu bytes)\n", path, bytes);
    return 0;
}
