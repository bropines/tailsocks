#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include "main.h"

extern int server_fd;
static int g_proxy_running = 0;

JNIEXPORT jint JNICALL
Java_io_github_bropines_tailscaled_core_ByeDpiProxy_jniStartProxy(JNIEnv *env, jobject thiz, jobjectArray args) {
    if (g_proxy_running) return -1;
    int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc(argc, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        argv[i] = strdup(arg_str);
        (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        (*env)->DeleteLocalRef(env, arg);
    }
    g_proxy_running = 1;
    optind = 1;
    int result = main(argc, argv);
    g_proxy_running = 0;
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_bropines_tailscaled_core_ByeDpiProxy_jniStopProxy(JNIEnv *env, jobject thiz) {
    if (!g_proxy_running) return -1;
    shutdown(server_fd, SHUT_RDWR);
    g_proxy_running = 0;
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_bropines_tailscaled_core_ByeDpiProxy_jniForceClose(JNIEnv *env, jobject thiz) {
    close(server_fd);
    g_proxy_running = 0;
    return 0;
}
