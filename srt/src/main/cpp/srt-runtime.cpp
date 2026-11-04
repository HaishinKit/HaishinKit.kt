#include <jni.h>
#include <srt.h>
#ifdef __ANDROID__
#include <android/log.h>

namespace {
void logToAndroid(void*, int level, const char*, int, const char*, const char* message) {
    const int priority = level <= 2 ? ANDROID_LOG_FATAL :
        level == 3 ? ANDROID_LOG_ERROR :
        level == 4 ? ANDROID_LOG_WARN :
        level <= 6 ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_write(priority, "libsrt", message);
}
}
#endif
#ifdef HAISHINKIT_SRT_EPOLL
#include "SRT/SrtEventLoop.h"
#endif

extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
#ifdef __ANDROID__
    srt_setloghandler(nullptr, logToAndroid);
#endif
    return srt_startup() == 0 ? JNI_VERSION_1_6 : JNI_ERR;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
#ifdef HAISHINKIT_SRT_EPOLL
    haishinkit::SrtEventLoop::Shutdown();
#endif
    srt_cleanup();
}
}
