/*
 * proot-getcwd-shim: LD_PRELOAD override for getcwd() on Android proot.
 *
 * Android's seccomp filter blocks the ARM64 getcwd syscall (#17) for app
 * processes. Glibc's getcwd() calls this syscall directly and gets ENOSYS.
 * proot intercepts readlinkat (used by readlink) correctly, so we use
 * readlink("/proc/self/cwd") to implement getcwd without the blocked syscall.
 *
 * Compile:
 *   aarch64-linux-gnu-gcc -shared -fPIC -nostartfiles -O2 \
 *     -o proot-getcwd.so getcwd_shim.c
 *
 * Usage (set in proot launch env):
 *   LD_PRELOAD=/usr/local/lib/proot-getcwd.so
 */

#define _GNU_SOURCE
#include <unistd.h>
#include <errno.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>

/* Override getcwd() to use readlink("/proc/self/cwd") instead of the
 * getcwd syscall, which is blocked by Android's seccomp in proot. */
char *getcwd(char *buf, size_t size) {
    char tmp[PATH_MAX + 1];
    ssize_t len = readlink("/proc/self/cwd", tmp, sizeof(tmp) - 1);

    if (len < 0) {
        /* readlink failed — last resort: use $PWD from environment */
        const char *pwd = getenv("PWD");
        if (pwd) {
            size_t plen = strlen(pwd);
            if (buf == NULL) {
                char *nb = (char *)malloc(plen + 1);
                if (!nb) { errno = ENOMEM; return NULL; }
                memcpy(nb, pwd, plen + 1);
                return nb;
            }
            if (size == 0 || size <= plen) { errno = ERANGE; return NULL; }
            memcpy(buf, pwd, plen + 1);
            return buf;
        }
        errno = ENOENT;
        return NULL;
    }

    tmp[len] = '\0';

    if (buf == NULL) {
        /* POSIX extension: allocate buffer if buf is NULL */
        char *nb = (char *)malloc((size_t)len + 1);
        if (!nb) { errno = ENOMEM; return NULL; }
        memcpy(nb, tmp, (size_t)len + 1);
        return nb;
    }

    if (size == 0 || size <= (size_t)len) { errno = ERANGE; return NULL; }
    memcpy(buf, tmp, (size_t)len + 1);
    return buf;
}

/* Also override get_current_dir_name() which glibc may call instead */
char *get_current_dir_name(void) {
    return getcwd(NULL, 0);
}
