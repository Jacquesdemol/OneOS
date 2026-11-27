/*
 * oneos_storaged: guest-namespace storage size calculator and cache/data cleaner.
 *
 * Host installd resolves /data_mirror/data_{ce,de}/null/0/<pkg> on One UI's own
 * dm-58 userdata rather than the guest's pivoted /data/user/0, and One UI 9.5
 * AIDL signatures for getAppSize/getUserSize/clearAppData also differ from
 * One UI 8's installd. Guest system_server (uid 1000) cannot traverse or clear
 * mode-0700 per-app directories directly, so it talks to this helper over
 * /data/system/oneos_storage.sock inside the guest's mount namespace.
 *
 * Authentication: SO_PEERCRED requires cred.uid == 1000 (AID_SYSTEM) and
 * /proc/<cred.pid>/root matching our own root (same mount namespace).
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/xattr.h>
#include <unistd.h>

#define SOCK_PATH "/data/system/oneos_storage.sock"
#define AID_SYSTEM 1000
#define MAX_LINE 16384
#define MAX_DEPTH 64

typedef struct {
    uint64_t bytes;
    uint64_t count;
} size_acc_t;

typedef struct {
    dev_t dev;
    ino_t ino;
    uint8_t used;
} ino_slot_t;

typedef struct {
    ino_slot_t *slots;
    size_t cap;
    size_t count;
} ino_set_t;

static struct stat g_my_root;

static int ino_set_init(ino_set_t *s, size_t cap) {
    s->cap = cap;
    s->count = 0;
    s->slots = calloc(cap, sizeof(ino_slot_t));
    return s->slots ? 0 : -1;
}

static void ino_set_free(ino_set_t *s) {
    free(s->slots);
    s->slots = NULL;
    s->cap = 0;
    s->count = 0;
}

/* Returns 1 if newly inserted (should count), 0 if already seen. */
static int ino_set_add(ino_set_t *s, dev_t dev, ino_t ino) {
    if (!s->slots) return 1;
    if ((s->count + 1) * 4 >= s->cap * 3) {
        size_t ncap = s->cap * 2;
        ino_slot_t *ns = calloc(ncap, sizeof(ino_slot_t));
        if (!ns) return 1;
        for (size_t i = 0; i < s->cap; i++) {
            if (!s->slots[i].used) continue;
            uint64_t h = ((uint64_t)s->slots[i].dev * 11400714819323198485ULL) ^
                         ((uint64_t)s->slots[i].ino * 14029467366897019727ULL);
            size_t idx = (size_t)(h & (ncap - 1));
            while (ns[idx].used) idx = (idx + 1) & (ncap - 1);
            ns[idx] = s->slots[i];
        }
        free(s->slots);
        s->slots = ns;
        s->cap = ncap;
    }
    uint64_t h = ((uint64_t)dev * 11400714819323198485ULL) ^
                 ((uint64_t)ino * 14029467366897019727ULL);
    size_t idx = (size_t)(h & (s->cap - 1));
    while (s->slots[idx].used) {
        if (s->slots[idx].dev == dev && s->slots[idx].ino == ino) return 0;
        idx = (idx + 1) & (s->cap - 1);
    }
    s->slots[idx].dev = dev;
    s->slots[idx].ino = ino;
    s->slots[idx].used = 1;
    s->count++;
    return 1;
}

static uint64_t stat_bytes(const struct stat *st) {
    if (st->st_blocks > 0) return (uint64_t)st->st_blocks * 512ULL;
    return st->st_size > 0 ? (uint64_t)st->st_size : 0ULL;
}

static int valid_user(const char *s, int *out) {
    if (!s || !*s) return 0;
    for (const char *p = s; *p; p++) {
        if (!isdigit((unsigned char)*p)) return 0;
    }
    long v = strtol(s, NULL, 10);
    if (v < 0 || v > 100000) return 0;
    *out = (int)v;
    return 1;
}

static int valid_pkg(const char *s) {
    if (!s || !*s) return 0;
    if (strcmp(s, ".") == 0 || strcmp(s, "..") == 0) return 0;
    size_t n = 0;
    for (const char *p = s; *p; p++, n++) {
        unsigned char c = (unsigned char)*p;
        if (!(isalnum(c) || c == '.' || c == '_' || c == '-')) return 0;
    }
    return n > 0 && n <= 255;
}

static int has_dotdot_segment(const char *p) {
    const char *cur = p;
    while (*cur) {
        while (*cur == '/') cur++;
        if (!*cur) break;
        const char *end = strchr(cur, '/');
        size_t len = end ? (size_t)(end - cur) : strlen(cur);
        if (len == 2 && cur[0] == '.' && cur[1] == '.') return 1;
        if (!end) break;
        cur = end + 1;
    }
    return 0;
}

static int valid_code_path(const char *p) {
    if (!p || p[0] != '/' || has_dotdot_segment(p)) return 0;
    static const char *const kPrefixes[] = {
        "/data/app/", "/system/", "/product/", "/system_ext/", "/vendor/", "/apex/", "/mnt/expand/",
    };
    for (size_t i = 0; i < sizeof(kPrefixes) / sizeof(kPrefixes[0]); i++) {
        if (strncmp(p, kPrefixes[i], strlen(kPrefixes[i])) == 0) return 1;
    }
    return 0;
}

static void walk_dir_fd(int dirfd, size_acc_t *acc, ino_set_t *seen, int depth) {
    if (dirfd < 0 || depth > MAX_DEPTH) {
        if (dirfd >= 0) close(dirfd);
        return;
    }
    DIR *d = fdopendir(dirfd);
    if (!d) {
        close(dirfd);
        return;
    }
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
        struct stat st;
        if (fstatat(dirfd, de->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            acc->bytes += stat_bytes(&st);
            int sub = openat(dirfd, de->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            if (sub >= 0) walk_dir_fd(sub, acc, seen, depth + 1);
        } else {
            if (st.st_nlink > 1 && !ino_set_add(seen, st.st_dev, st.st_ino)) continue;
            acc->bytes += stat_bytes(&st);
            acc->count++;
        }
    }
    closedir(d);
}

static void walk_path(const char *path, size_acc_t *acc, ino_set_t *seen) {
    struct stat st;
    if (lstat(path, &st) != 0) return;
    if (S_ISDIR(st.st_mode)) {
        acc->bytes += stat_bytes(&st);
        int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (fd >= 0) walk_dir_fd(fd, acc, seen, 0);
    } else {
        if (st.st_nlink <= 1 || ino_set_add(seen, st.st_dev, st.st_ino)) {
            acc->bytes += stat_bytes(&st);
            acc->count++;
        }
    }
}

/* Walks /data/user/<u|de>/<pkg> or /data/media/<u>/Android/data/<pkg>,
 * splitting top-level cache dirs into cache_acc and the rest into data_acc. */
static void walk_pkg_data_dir(const char *pkg_dir, size_acc_t *data_acc,
                              size_acc_t *cache_acc, ino_set_t *seen,
                              int split_code_cache) {
    struct stat root_st;
    if (lstat(pkg_dir, &root_st) != 0 || !S_ISDIR(root_st.st_mode)) return;
    int fd = open(pkg_dir, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return;
    DIR *d = fdopendir(fd);
    if (!d) {
        close(fd);
        return;
    }
    data_acc->bytes += stat_bytes(&root_st);
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
        struct stat st;
        if (fstatat(fd, de->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0) continue;
        int is_cache = (strcmp(de->d_name, "cache") == 0) ||
                       (split_code_cache && strcmp(de->d_name, "code_cache") == 0);
        size_acc_t *dst = is_cache ? cache_acc : data_acc;
        if (S_ISDIR(st.st_mode)) {
            dst->bytes += stat_bytes(&st);
            int sub = openat(fd, de->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            if (sub >= 0) walk_dir_fd(sub, dst, seen, 0);
        } else {
            if (st.st_nlink > 1 && !ino_set_add(seen, st.st_dev, st.st_ino)) continue;
            dst->bytes += stat_bytes(&st);
            dst->count++;
        }
    }
    closedir(d);
}

/* Recursively unlinks everything inside dirfd.
 * If keep_lib_symlink is non-zero, preserves a top-level "lib" symlink. */
static int clear_dir_contents(int dirfd, int depth, int keep_lib_symlink) {
    if (dirfd < 0 || depth > MAX_DEPTH) {
        if (dirfd >= 0) close(dirfd);
        return 0;
    }
    DIR *d = fdopendir(dirfd);
    if (!d) {
        close(dirfd);
        return -1;
    }
    int rc = 0;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
        struct stat st;
        if (fstatat(dirfd, de->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0) continue;
        if (keep_lib_symlink && depth == 0 && S_ISLNK(st.st_mode) &&
            strcmp(de->d_name, "lib") == 0) {
            continue;
        }
        if (S_ISDIR(st.st_mode)) {
            int sub = openat(dirfd, de->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            if (sub >= 0) {
                if (clear_dir_contents(sub, depth + 1, 0) != 0) rc = -1;
            }
            if (unlinkat(dirfd, de->d_name, AT_REMOVEDIR) != 0 && errno != ENOENT) rc = -1;
        } else {
            if (unlinkat(dirfd, de->d_name, 0) != 0 && errno != ENOENT) rc = -1;
        }
    }
    closedir(d);
    return rc;
}

static void clear_dir_by_path(const char *path, int remove_self, int keep_lib_symlink) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return;
    clear_dir_contents(fd, 0, keep_lib_symlink);
    if (remove_self) rmdir(path);
}

static void handle_app_size(int client, char *rest) {
    char *u_str = strsep(&rest, "\t");
    char *pkgs_str = strsep(&rest, "\t");
    char *codes_str = strsep(&rest, "\t");
    int user_id = 0;
    if (!valid_user(u_str, &user_id)) {
        dprintf(client, "ERR bad user\n");
        return;
    }

    ino_set_t seen;
    ino_set_init(&seen, 1024);

    size_acc_t code = {0}, data = {0}, cache = {0};
    size_acc_t ext_code = {0}, ext_data = {0}, ext_cache = {0};

    if (codes_str && *codes_str) {
        char *cur = codes_str;
        while (cur && *cur) {
            char *cp = strsep(&cur, "|");
            if (*cp && valid_code_path(cp)) walk_path(cp, &code, &seen);
        }
    }

    if (pkgs_str && *pkgs_str) {
        char *cur = pkgs_str;
        while (cur && *cur) {
            char *pkg = strsep(&cur, ",");
            if (!*pkg || !valid_pkg(pkg)) continue;

            char path[PATH_MAX];
            snprintf(path, sizeof(path), "/data/user/%d/%s", user_id, pkg);
            walk_pkg_data_dir(path, &data, &cache, &seen, 1);

            snprintf(path, sizeof(path), "/data/user_de/%d/%s", user_id, pkg);
            walk_pkg_data_dir(path, &data, &cache, &seen, 1);

            snprintf(path, sizeof(path), "/data/misc/profiles/cur/%d/%s", user_id, pkg);
            walk_path(path, &data, &seen);

            snprintf(path, sizeof(path), "/data/misc/profiles/ref/%s", pkg);
            walk_path(path, &code, &seen);

            snprintf(path, sizeof(path), "/data/media/%d/Android/data/%s", user_id, pkg);
            walk_pkg_data_dir(path, &ext_data, &ext_cache, &seen, 0);

            snprintf(path, sizeof(path), "/data/media/%d/Android/media/%s", user_id, pkg);
            walk_path(path, &ext_data, &seen);

            snprintf(path, sizeof(path), "/data/media/%d/Android/obb/%s", user_id, pkg);
            walk_path(path, &ext_code, &seen);
        }
    }

    ino_set_free(&seen);
    dprintf(client,
            "OK %llu %llu %llu %llu %llu %llu %llu %llu %llu %llu %llu %llu\n",
            (unsigned long long)code.bytes,
            (unsigned long long)data.bytes,
            (unsigned long long)cache.bytes,
            (unsigned long long)ext_code.bytes,
            (unsigned long long)ext_data.bytes,
            (unsigned long long)ext_cache.bytes,
            (unsigned long long)code.count,
            (unsigned long long)data.count,
            (unsigned long long)cache.count,
            (unsigned long long)ext_code.count,
            (unsigned long long)ext_data.count,
            (unsigned long long)ext_cache.count);
}

static void sum_pkg_root(const char *base, size_acc_t *data_acc,
                         size_acc_t *cache_acc, ino_set_t *seen,
                         int split_code_cache) {
    DIR *d = opendir(base);
    if (!d) return;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
        if (!valid_pkg(de->d_name)) continue;
        char path[PATH_MAX];
        snprintf(path, sizeof(path), "%s/%s", base, de->d_name);
        walk_pkg_data_dir(path, data_acc, cache_acc, seen, split_code_cache);
    }
    closedir(d);
}

static void handle_user_size(int client, char *rest) {
    char *u_str = strsep(&rest, "\t");
    int user_id = 0;
    if (!valid_user(u_str, &user_id)) {
        dprintf(client, "ERR bad user\n");
        return;
    }

    ino_set_t seen;
    ino_set_init(&seen, 4096);

    size_acc_t code = {0}, data = {0}, cache = {0};
    size_acc_t ext_code = {0}, ext_data = {0}, ext_cache = {0};
    char path[PATH_MAX];

    snprintf(path, sizeof(path), "/data/app");
    walk_path(path, &code, &seen);

    snprintf(path, sizeof(path), "/data/user/%d", user_id);
    sum_pkg_root(path, &data, &cache, &seen, 1);

    snprintf(path, sizeof(path), "/data/user_de/%d", user_id);
    sum_pkg_root(path, &data, &cache, &seen, 1);

    snprintf(path, sizeof(path), "/data/media/%d/Android/data", user_id);
    sum_pkg_root(path, &ext_data, &ext_cache, &seen, 0);

    snprintf(path, sizeof(path), "/data/media/%d/Android/media", user_id);
    walk_path(path, &ext_data, &seen);

    snprintf(path, sizeof(path), "/data/media/%d/Android/obb", user_id);
    walk_path(path, &ext_code, &seen);

    ino_set_free(&seen);
    dprintf(client, "OK %llu %llu %llu %llu %llu %llu\n",
            (unsigned long long)code.bytes,
            (unsigned long long)data.bytes,
            (unsigned long long)cache.bytes,
            (unsigned long long)ext_code.bytes,
            (unsigned long long)ext_data.bytes,
            (unsigned long long)ext_cache.bytes);
}

static int has_ext_in(const char *name, const char *const *exts) {
    const char *dot = strrchr(name, '.');
    if (!dot || !dot[1]) return 0;
    for (int i = 0; exts[i]; i++) {
        if (strcasecmp(dot + 1, exts[i]) == 0) return 1;
    }
    return 0;
}

typedef struct {
    uint64_t total, audio, video, image, app, obb;
} ext_stats_t;

static void walk_ext_media_fd(int dirfd, ext_stats_t *es, ino_set_t *seen,
                              int in_app_dir, int in_obb_dir, int depth) {
    static const char *const kAudio[] = {"mp3", "m4a", "aac", "flac", "ogg", "wav", "opus", "amr", "mid", NULL};
    static const char *const kVideo[] = {"mp4", "mkv", "webm", "mov", "avi", "3gp", "ts", "m4v", NULL};
    static const char *const kImage[] = {"jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "dng", "bmp", "raw", NULL};
    static const char *const kApp[]   = {"apk", "apks", "xapk", NULL};
    if (dirfd < 0 || depth > MAX_DEPTH) {
        if (dirfd >= 0) close(dirfd);
        return;
    }
    DIR *d = fdopendir(dirfd);
    if (!d) {
        close(dirfd);
        return;
    }
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
        struct stat st;
        if (fstatat(dirfd, de->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0) continue;
        uint64_t b = stat_bytes(&st);
        if (S_ISDIR(st.st_mode)) {
            es->total += b;
            int child_app = in_app_dir;
            int child_obb = in_obb_dir;
            if (depth == 0 && strcmp(de->d_name, "Android") == 0) {
                /* Wait for depth == 1 to classify data / obb / media */
            } else if (depth == 1 && (strcmp(de->d_name, "data") == 0 || strcmp(de->d_name, "media") == 0)) {
                child_app = 1;
            } else if (depth == 1 && strcmp(de->d_name, "obb") == 0) {
                child_obb = 1;
            }
            if (child_app) es->app += b;
            if (child_obb) es->obb += b;
            int sub = openat(dirfd, de->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            if (sub >= 0) walk_ext_media_fd(sub, es, seen, child_app, child_obb, depth + 1);
        } else {
            if (st.st_nlink > 1 && !ino_set_add(seen, st.st_dev, st.st_ino)) continue;
            es->total += b;
            if (in_obb_dir || has_ext_in(de->d_name, (const char *const[]){"obb", NULL})) {
                es->obb += b;
            } else if (in_app_dir || has_ext_in(de->d_name, kApp)) {
                es->app += b;
            } else if (has_ext_in(de->d_name, kAudio)) {
                es->audio += b;
            } else if (has_ext_in(de->d_name, kVideo)) {
                es->video += b;
            } else if (has_ext_in(de->d_name, kImage)) {
                es->image += b;
            }
        }
    }
    closedir(d);
}

static void handle_ext_size(int client, char *rest) {
    char *u_str = strsep(&rest, "\t");
    int user_id = 0;
    if (!valid_user(u_str, &user_id)) {
        dprintf(client, "ERR bad user\n");
        return;
    }
    ext_stats_t es = {0};
    ino_set_t seen;
    ino_set_init(&seen, 2048);
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "/data/media/%d", user_id);
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd >= 0) walk_ext_media_fd(fd, &es, &seen, 0, 0, 0);
    ino_set_free(&seen);
    dprintf(client, "OK %llu %llu %llu %llu %llu %llu\n",
            (unsigned long long)es.total,
            (unsigned long long)es.audio,
            (unsigned long long)es.video,
            (unsigned long long)es.image,
            (unsigned long long)es.app,
            (unsigned long long)es.obb);
}

static void handle_clear(int client, char *rest, int cache_only) {
    char *u_str = strsep(&rest, "\t");
    char *pkg = strsep(&rest, "\t");
    int user_id = 0;
    if (!valid_user(u_str, &user_id) || !valid_pkg(pkg)) {
        dprintf(client, "ERR bad args\n");
        return;
    }
    char path[PATH_MAX];
    if (cache_only) {
        static const char *const kInternalRoots[] = {"/data/user", "/data/user_de"};
        static const char *const kCacheDirs[] = {"cache", "code_cache"};
        for (size_t i = 0; i < 2; i++) {
            for (size_t j = 0; j < 2; j++) {
                snprintf(path, sizeof(path), "%s/%d/%s/%s", kInternalRoots[i], user_id, pkg, kCacheDirs[j]);
                clear_dir_by_path(path, 0, 0);
            }
        }
        snprintf(path, sizeof(path), "/data/media/%d/Android/data/%s/cache", user_id, pkg);
        clear_dir_by_path(path, 0, 0);
    } else {
        snprintf(path, sizeof(path), "/data/user/%d/%s", user_id, pkg);
        clear_dir_by_path(path, 0, 1);

        snprintf(path, sizeof(path), "/data/user_de/%d/%s", user_id, pkg);
        clear_dir_by_path(path, 0, 1);

        snprintf(path, sizeof(path), "/data/media/%d/Android/data/%s", user_id, pkg);
        clear_dir_by_path(path, 1, 0);

        snprintf(path, sizeof(path), "/data/misc/profiles/cur/%d/%s", user_id, pkg);
        clear_dir_by_path(path, 0, 0);
    }
    dprintf(client, "OK\n");
}

static int same_root(pid_t pid) {
    char p[64];
    struct stat st;
    snprintf(p, sizeof(p), "/proc/%d/root", (int)pid);
    if (stat(p, &st) != 0) return 0;
    return st.st_dev == g_my_root.st_dev && st.st_ino == g_my_root.st_ino;
}

static void handle_client(int client) {
    struct timeval tv = {.tv_sec = 10, .tv_usec = 0};
    setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    struct ucred cred;
    socklen_t clen = sizeof(cred);
    if (getsockopt(client, SOL_SOCKET, SO_PEERCRED, &cred, &clen) != 0 ||
        cred.uid != AID_SYSTEM || cred.pid <= 0 || !same_root(cred.pid)) {
        close(client);
        return;
    }

    char buf[MAX_LINE];
    size_t len = 0;
    while (len + 1 < sizeof(buf)) {
        ssize_t n = read(client, buf + len, sizeof(buf) - 1 - len);
        if (n <= 0) break;
        len += (size_t)n;
        buf[len] = '\0';
        char *nl = strchr(buf, '\n');
        if (nl) {
            *nl = '\0';
            if (nl > buf && nl[-1] == '\r') nl[-1] = '\0';
            break;
        }
    }
    if (len == 0) {
        close(client);
        return;
    }

    char *cur = buf;
    char *cmd = strsep(&cur, "\t");
    if (!cmd) {
        dprintf(client, "ERR empty\n");
    } else if (strcmp(cmd, "APP_SIZE") == 0) {
        handle_app_size(client, cur);
    } else if (strcmp(cmd, "USER_SIZE") == 0) {
        handle_user_size(client, cur);
    } else if (strcmp(cmd, "EXT_SIZE") == 0) {
        handle_ext_size(client, cur);
    } else if (strcmp(cmd, "CLEAR_CACHE") == 0) {
        handle_clear(client, cur, 1);
    } else if (strcmp(cmd, "CLEAR_DATA") == 0) {
        handle_clear(client, cur, 0);
    } else {
        dprintf(client, "ERR unknown\n");
    }
    close(client);
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--version") == 0) {
        puts("oneos_storaged 1");
        return 0;
    }
    signal(SIGPIPE, SIG_IGN);
    if (stat("/", &g_my_root) != 0) return 1;

    if (argc > 1 && strcmp(argv[1], "--daemon") == 0) {
        pid_t pid = fork();
        if (pid < 0) return 1;
        if (pid > 0) return 0;
        setsid();
        int dn = open("/dev/null", O_RDWR);
        if (dn >= 0) {
            dup2(dn, STDIN_FILENO);
            dup2(dn, STDOUT_FILENO);
            dup2(dn, STDERR_FILENO);
            if (dn > 2) close(dn);
        }
    }

    unlink(SOCK_PATH);
    int srv = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (srv < 0) return 1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", SOCK_PATH);

    mode_t old_umask = umask(0077);
    if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        umask(old_umask);
        close(srv);
        return 1;
    }
    umask(old_umask);
    chown(SOCK_PATH, AID_SYSTEM, AID_SYSTEM);
    chmod(SOCK_PATH, 0600);
    (void)setxattr(SOCK_PATH, "security.selinux", "u:object_r:system_data_file:s0",
                   sizeof("u:object_r:system_data_file:s0"), 0);

    if (listen(srv, 16) != 0) {
        close(srv);
        return 1;
    }

    for (;;) {
        int c = accept(srv, NULL, NULL);
        if (c < 0) {
            if (errno == EINTR) continue;
            break;
        }
        handle_client(c);
    }
    close(srv);
    return 0;
}
