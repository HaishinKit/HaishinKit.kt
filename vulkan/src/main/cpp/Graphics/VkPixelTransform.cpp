#include "DynamicLoader.h"
#include "Kernel.h"
#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_haishinkit_vulkan_VkPixelTransform_00024Companion_nativeIsDeviceSupported(JNIEnv *, jobject) {
    if (!Graphics::DynamicLoader::GetInstance().Load())
        return JNI_FALSE;
    try {
        Graphics::Kernel kernel;
        return kernel.HasFeatures();
    } catch (...) {
        return JNI_FALSE;
    }
}
