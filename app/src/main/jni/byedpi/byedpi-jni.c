#include <jni.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>

#include "params.h"
#include "proxy.h"
#include "error.h"
#include "main.h"

/*
 * One proxy runs at a time: the core keeps its settings and its state in
 * globals. A run is everything between entering jniStartProxy and leaving it,
 * and the caller names it with an id that only grows, so a stop always means
 * one run and the runs before it, wherever that run is at the moment.
 *
 * Every descriptor of a run is closed by the thread that runs it and by nobody
 * else. This file used to close the listening socket from the stopping thread
 * as well; the number could then be handed to another part of the process
 * before the loop closed it a second time, and fdsan aborts on that.
 *
 * A stop only shuts the listening socket down. accept() fails from then on,
 * which is what makes the loop leave, and it stays that way, so the loop
 * cannot miss it. The stop goes through g_wake_fd, a duplicate of the socket
 * that is published and withdrawn under the lock and closed after the loop has
 * returned: the number a stop acts on is ours for as long as a stop can see it.
 */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static bool g_running;       /* a run is between start and return */
static jlong g_run_id;       /* which one */
static jlong g_stopped_id;   /* every run up to this id was asked to stop */
static int g_wake_fd = -1;   /* the listening socket of the loop, -1 without one */

char *g_log_file_path = NULL;

JNIEXPORT void JNICALL
Java_io_github_bropines_tailscaled_core_ByeDpiProxy_jniSetLogPath(JNIEnv *env, jclass clazz, jstring path) {
    pthread_mutex_lock(&g_lock);
    /* The loop reads the path for every line it logs: never under a run. */
    if (!g_running) {
        if (g_log_file_path) {
            free(g_log_file_path);
            g_log_file_path = NULL;
        }
        if (path) {
            const char *path_str = (*env)->GetStringUTFChars(env, path, 0);
            g_log_file_path = strdup(path_str);
            (*env)->ReleaseStringUTFChars(env, path, path_str);

            /* Start the file anew for the run that follows. */
            FILE *f = fopen(g_log_file_path, "w");
            if (f) fclose(f);
        }
    }
    pthread_mutex_unlock(&g_lock);
}

void android_log_to_file(int level, const char *fmt, ...) {
    if (!g_log_file_path) return;
    FILE *f = fopen(g_log_file_path, "a");
    if (!f) return;

    va_list args;
    va_start(args, fmt);
    vfprintf(f, fmt, args);
    va_end(args);
    fclose(f);
}

/*
 * What the core's main() does, taken apart so that the listening socket passes
 * through our hands on its way into the loop. run() is left out with it: its
 * SIGINT/SIGTERM handlers shut down whatever number the last run listened on,
 * and they were installed for the whole app process.
 */
static int run_proxy(int argc, char **argv, jlong id) {
    optind = 1;
    int status = parse_args(argc, argv);
    if (status) {
        return status - 1;
    }
    INIT_ADDR_STR(params.laddr);
    LOG(LOG_S, "listen address: %s:%d\n", ADDR_STR, ntohs(params.laddr.in.sin_port));

    if (init() < 0) {
        return -1;
    }
    /* The core sends without MSG_NOSIGNAL. */
    signal(SIGPIPE, SIG_IGN);

    int fd = listen_socket(&params.laddr);
    if (fd < 0) {
        return -1;
    }
    int wake = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (wake < 0) {
        uniperror("dup");
        close(fd);
        return -1;
    }
    pthread_mutex_lock(&g_lock);
    bool stopped = id <= g_stopped_id;
    if (!stopped) {
        g_wake_fd = wake;
    }
    pthread_mutex_unlock(&g_lock);

    int result = 0;
    if (stopped) {
        /* Asked to stop before there was anything to stop. */
        close(fd);
    } else {
        /* The loop owns fd from here and closes it on every way out. */
        result = start_event_loop(fd);

        pthread_mutex_lock(&g_lock);
        g_wake_fd = -1;
        pthread_mutex_unlock(&g_lock);

        if (result >= 0) {
            dump_all_cache();
        }
    }
    close(wake);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_bropines_tailscaled_core_ByeDpiProxy_jniStartProxy(JNIEnv *env, jclass clazz, jobjectArray args, jlong id) {
    pthread_mutex_lock(&g_lock);
    bool busy = g_running;
    bool stopped = id <= g_stopped_id;
    if (!busy && !stopped) {
        g_running = true;
        g_run_id = id;
    }
    pthread_mutex_unlock(&g_lock);
    if (busy) return -1;
    if (stopped) return 0;

    int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc(argc, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        argv[i] = strdup(arg_str);
        (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        (*env)->DeleteLocalRef(env, arg);
    }
    int result = run_proxy(argc, argv, id);
    clear_params(0, 0);
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);

    pthread_mutex_lock(&g_lock);
    g_running = false;
    pthread_mutex_unlock(&g_lock);
    return result;
}

/*
 * Asks the run with this id, and any before it, to stop; safe from any thread
 * and at any time. Returns 0 when a run was told, -1 when none was there: the
 * request is kept then, and the run leaves as soon as it gets here.
 */
JNIEXPORT jint JNICALL
Java_io_github_bropines_tailscaled_core_ByeDpiProxy_jniStopProxy(JNIEnv *env, jclass clazz, jlong id) {
    int result = -1;
    pthread_mutex_lock(&g_lock);
    if (id > g_stopped_id) {
        g_stopped_id = id;
    }
    if (g_running && g_run_id <= g_stopped_id) {
        if (g_wake_fd >= 0) {
            shutdown(g_wake_fd, SHUT_RDWR);
        }
        result = 0;
    }
    pthread_mutex_unlock(&g_lock);
    return result;
}
