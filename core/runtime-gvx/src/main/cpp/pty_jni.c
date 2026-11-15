#define _XOPEN_SOURCE 600
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

static void throw_io_exception(JNIEnv *env, const char *prefix, int err_num) {
    jclass ex_cls = (*env)->FindClass(env, "java/io/IOException");
    if (ex_cls == NULL) {
        return;
    }
    char msg[256];
    snprintf(msg, sizeof(msg), "%s: %s (errno=%d)", prefix, strerror(err_num), err_num);
    (*env)->ThrowNew(env, ex_cls, msg);
}

static uint16_t clamp_u16(jint val) {
    if (val < 0) return 0;
    if (val > 65535) return 65535;
    return (uint16_t)val;
}

JNIEXPORT jobject JNICALL
Java_dev_gfire_termvr_core_runtime_NativePtyBridge_nativeOpenPty(
    JNIEnv *env,
    jobject thiz,
    jint rows,
    jint cols,
    jint xpixel,
    jint ypixel
) {
    (void)thiz;
    int master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master_fd < 0) {
        throw_io_exception(env, "posix_openpt failed", errno);
        return NULL;
    }

    if (grantpt(master_fd) != 0) {
        int saved = errno;
        close(master_fd);
        throw_io_exception(env, "grantpt failed", saved);
        return NULL;
    }

    if (unlockpt(master_fd) != 0) {
        int saved = errno;
        close(master_fd);
        throw_io_exception(env, "unlockpt failed", saved);
        return NULL;
    }

    char slave_name[PATH_MAX];
    if (ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
        int saved = errno;
        close(master_fd);
        throw_io_exception(env, "ptsname_r failed", saved);
        return NULL;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = clamp_u16(rows);
    ws.ws_col = clamp_u16(cols);
    ws.ws_xpixel = clamp_u16(xpixel);
    ws.ws_ypixel = clamp_u16(ypixel);
    if (ioctl(master_fd, TIOCSWINSZ, &ws) != 0) {
        int saved = errno;
        close(master_fd);
        throw_io_exception(env, "ioctl(TIOCSWINSZ) failed", saved);
        return NULL;
    }

    jclass pair_cls = (*env)->FindClass(env, "dev/gfire/termvr/core/runtime/NativePtyOpenResult");
    if (pair_cls == NULL) {
        close(master_fd);
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, pair_cls, "<init>", "(ILjava/lang/String;)V");
    if (ctor == NULL) {
        close(master_fd);
        return NULL;
    }
    jstring j_slave = (*env)->NewStringUTF(env, slave_name);
    if (j_slave == NULL) {
        close(master_fd);
        return NULL;
    }
    return (*env)->NewObject(env, pair_cls, ctor, (jint)master_fd, j_slave);
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_runtime_NativePtyBridge_nativeResizePty(
    JNIEnv *env,
    jobject thiz,
    jint master_fd,
    jint rows,
    jint cols,
    jint xpixel,
    jint ypixel,
    jlong child_pid
) {
    (void)thiz;
    if (master_fd < 0) {
        throw_io_exception(env, "invalid master_fd", EBADF);
        return;
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = clamp_u16(rows);
    ws.ws_col = clamp_u16(cols);
    ws.ws_xpixel = clamp_u16(xpixel);
    ws.ws_ypixel = clamp_u16(ypixel);
    if (ioctl(master_fd, TIOCSWINSZ, &ws) != 0) {
        throw_io_exception(env, "ioctl(TIOCSWINSZ) failed", errno);
        return;
    }
    pid_t pgrp = tcgetpgrp(master_fd);
    if (pgrp > 0) {
        (void)kill(-pgrp, SIGWINCH);
    } else if (child_pid > 0) {
        (void)kill((pid_t)child_pid, SIGWINCH);
    }
}

JNIEXPORT jint JNICALL
Java_dev_gfire_termvr_core_runtime_NativePtyBridge_nativeReadFd(
    JNIEnv *env,
    jobject thiz,
    jint fd,
    jbyteArray buf,
    jint offset,
    jint length
) {
    (void)thiz;
    if (fd < 0) {
        throw_io_exception(env, "invalid fd", EBADF);
        return -1;
    }
    if (buf == NULL || offset < 0 || length < 0) {
        throw_io_exception(env, "invalid buffer arguments", EINVAL);
        return -1;
    }
    jsize cap = (*env)->GetArrayLength(env, buf);
    if (offset > cap || length > cap - offset) {
        throw_io_exception(env, "buffer bounds exceeded", EINVAL);
        return -1;
    }
    if (length == 0) {
        return 0;
    }

    jbyte stack_buf[4096];
    jbyte *tmp = (length <= (jint)sizeof(stack_buf)) ? stack_buf : (jbyte *)malloc((size_t)length);
    if (tmp == NULL) {
        throw_io_exception(env, "malloc failed", ENOMEM);
        return -1;
    }

    ssize_t n;
    do {
        n = read(fd, tmp, (size_t)length);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        int saved = errno;
        if (tmp != stack_buf) free(tmp);
        if (saved == EIO) {
            return -1;
        }
        throw_io_exception(env, "pty read failed", saved);
        return -1;
    }

    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buf, offset, (jsize)n, tmp);
    }
    if (tmp != stack_buf) free(tmp);
    return (n == 0) ? -1 : (jint)n;
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_runtime_NativePtyBridge_nativeWriteFd(
    JNIEnv *env,
    jobject thiz,
    jint fd,
    jbyteArray buf,
    jint offset,
    jint length
) {
    (void)thiz;
    if (fd < 0) {
        throw_io_exception(env, "invalid fd", EBADF);
        return;
    }
    if (buf == NULL || offset < 0 || length < 0) {
        throw_io_exception(env, "invalid buffer arguments", EINVAL);
        return;
    }
    jsize cap = (*env)->GetArrayLength(env, buf);
    if (offset > cap || length > cap - offset) {
        throw_io_exception(env, "buffer bounds exceeded", EINVAL);
        return;
    }
    if (length == 0) {
        return;
    }

    jbyte stack_buf[4096];
    jbyte *tmp = (length <= (jint)sizeof(stack_buf)) ? stack_buf : (jbyte *)malloc((size_t)length);
    if (tmp == NULL) {
        throw_io_exception(env, "malloc failed", ENOMEM);
        return;
    }
    (*env)->GetByteArrayRegion(env, buf, offset, length, tmp);

    jint written = 0;
    while (written < length) {
        ssize_t n = write(fd, tmp + written, (size_t)(length - written));
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            int saved = errno;
            if (tmp != stack_buf) free(tmp);
            throw_io_exception(env, "pty write failed", saved);
            return;
        }
        written += (jint)n;
    }
    if (tmp != stack_buf) free(tmp);
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_runtime_NativePtyBridge_nativeCloseFd(
    JNIEnv *env,
    jobject thiz,
    jint fd
) {
    (void)env;
    (void)thiz;
    if (fd >= 0) {
        close(fd);
    }
}
