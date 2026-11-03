#include <jni.h>
#include <srt.h>

extern "C" {
JNIEXPORT void JNICALL Java_com_haishinkit_srt_SrtLogger_nativeSetLevel(JNIEnv*, jobject, jint level) {
    srt_setloglevel(level);
}

JNIEXPORT void JNICALL Java_com_haishinkit_srt_SrtLogger_nativeAddFunctionalArea(JNIEnv*, jobject, jint area) {
    srt_addlogfa(area);
}

JNIEXPORT void JNICALL Java_com_haishinkit_srt_SrtLogger_nativeRemoveFunctionalArea(JNIEnv*, jobject, jint area) {
    srt_dellogfa(area);
}
}
