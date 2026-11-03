#include <jni.h>
#include <srt.h>
#include <atomic>
#include <cstdio>

extern "C" {
void Java_com_haishinkit_srt_SrtLogger_nativeSetLevel(JNIEnv*, jobject, jint);
void Java_com_haishinkit_srt_SrtLogger_nativeAddFunctionalArea(JNIEnv*, jobject, jint);
void Java_com_haishinkit_srt_SrtLogger_nativeRemoveFunctionalArea(JNIEnv*, jobject, jint);
}

namespace {
std::atomic<int> messages{0};

void capture(void*, int level, const char*, int, const char*, const char*) {
    if (level == LOG_ERR) ++messages;
}

bool emitsError() {
    messages = 0;
    // Invalid accept produces a synchronous CONNECTION-area error in libsrt.
    srt_accept(SRT_INVALID_SOCK, nullptr, nullptr);
    return messages.load() > 0;
}
}

int main() {
    if (srt_startup() != 0) return 1;
    srt_setloghandler(nullptr, capture);
    srt_resetlogfa(nullptr, 0);
    Java_com_haishinkit_srt_SrtLogger_nativeAddFunctionalArea(nullptr, nullptr, SRT_LOGFA_CONN);
    Java_com_haishinkit_srt_SrtLogger_nativeSetLevel(nullptr, nullptr, LOG_ERR);
    const bool enabled = emitsError();
    Java_com_haishinkit_srt_SrtLogger_nativeSetLevel(nullptr, nullptr, LOG_CRIT);
    const bool filteredByLevel = !emitsError();
    Java_com_haishinkit_srt_SrtLogger_nativeSetLevel(nullptr, nullptr, LOG_ERR);
    Java_com_haishinkit_srt_SrtLogger_nativeRemoveFunctionalArea(nullptr, nullptr, SRT_LOGFA_CONN);
    const bool filteredByArea = !emitsError();
    Java_com_haishinkit_srt_SrtLogger_nativeAddFunctionalArea(nullptr, nullptr, SRT_LOGFA_CONN);
    const bool reenabled = emitsError();
    srt_setloghandler(nullptr, nullptr);
    srt_cleanup();
    if (!(enabled && filteredByLevel && filteredByArea && reenabled)) {
        std::fprintf(stderr, "SRT native logger filtering failed: %d %d %d %d\n",
            enabled, filteredByLevel, filteredByArea, reenabled);
        return 1;
    }
    std::puts("SRT native logger level and functional-area filtering passed");
    return 0;
}
