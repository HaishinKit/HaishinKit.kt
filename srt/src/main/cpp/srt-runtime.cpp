#include <jni.h>
#include <srt.h>

extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return srt_startup() == 0 ? JNI_VERSION_1_6 : JNI_ERR;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    srt_cleanup();
}
}
