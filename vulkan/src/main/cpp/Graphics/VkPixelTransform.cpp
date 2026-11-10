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

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_haishinkit_vulkan_VkPixelTransform_00024Companion_nativeInspectDevices(JNIEnv *env, jobject) {
    try {
        std::string json = "{\"devices\":[]}";
        if (Graphics::DynamicLoader::GetInstance().Load()) {
            Graphics::Kernel kernel;
            json = kernel.InspectDevices();
        }
        // Transfer standard UTF-8 bytes; NewStringUTF expects JNI's modified UTF-8 instead.
        auto bytes = env->NewByteArray(static_cast<jsize>(json.size()));
        if (bytes)
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(json.size()),
                                    reinterpret_cast<const jbyte *>(json.data()));
        return bytes;
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
    } catch (...) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Vulkan device inspection failed");
    }
    return nullptr;
}
