#include "../Unmanaged.hpp"
#include "PixelTransform.h"
#include "VulkanLock.h"
#include "vulkan/vulkan_android.h"
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include <media/NdkImageReader.h>
#include "../haishinkit.hpp"
#include "DynamicLoader.h"
#include <dlfcn.h>
#include <time.h>

using namespace Graphics;

void *PixelTransform::OnRunning(void *data) {
    reinterpret_cast<PixelTransform *>(data)->OnRunning();
    return (void *) nullptr;
}

void PixelTransform::OnFrameLegacy(long, void *data) {
    // API 28's legacy callback truncates timestamps to 32 bits on 32-bit ABIs.
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    OnFrame64(static_cast<int64_t>(now.tv_sec) * 1000000000LL + now.tv_nsec, data);
}

void PixelTransform::OnFrame64(int64_t frameTimeNanos, void *data) {

    auto self = reinterpret_cast<PixelTransform *>(data);
    try { self->OnFrame(frameTimeNanos); }
    catch (const std::exception &error) { self->running = false; LOGE("Vulkan frame failed; renderer stopped: %s", error.what()); }
}

PixelTransform::PixelTransform() :
        kernel(new Kernel()),
        textures(std::vector<Texture *>(0)),
        imageReader(new ImageReader()),
        fpsController(new FpsController()),
        videoEffect(new VideoEffect()) {
}

PixelTransform::~PixelTransform() {
    DispatcherScope dispatchScope(kernel->dispatcher);
    StopRunning();
    std::lock_guard<std::recursive_mutex> guard(VulkanLock());
    auto idleResult = WaitIdleForDisposal(kernel->device.get(), kernel->dispatcher);
    if (idleResult != VK_SUCCESS) LOGW("Vulkan disposal wait returned %d", idleResult);
    for (auto texture : textures) delete texture;
    delete videoEffect;
    delete fpsController;
    delete imageReader;
    delete kernel;
}

void PixelTransform::SetFrameRate(int frameRate) {
    std::lock_guard<std::mutex> lock(mutex);
    fpsController->SetFrameRate(frameRate);
}

void PixelTransform::SetImageExtent(int32_t width, int32_t height) {
    std::lock_guard<std::mutex> lock(mutex);
    kernel->SetImageExtent(width, height);
}

ANativeWindow *PixelTransform::GetInputSurface() {
    return imageReader->GetWindow();
}

void PixelTransform::SetVideoGravity(VideoGravity newVideoGravity) {
    std::lock_guard<std::mutex> lock(mutex);
    videoGravity = newVideoGravity;
    for (auto &texture: textures) {
        texture->SetVideoGravity(newVideoGravity);
    }
}

void PixelTransform::SetImageOrientation(ImageOrientation newImageOrientation) {
    std::lock_guard<std::mutex> lock(mutex);
    imageOrientation = newImageOrientation;
    for (auto &texture: textures) {
        texture->SetImageOrientation(newImageOrientation);
    }
}

void PixelTransform::SetResampleFilter(ResampleFilter newResampleFilter) {
    std::lock_guard<std::mutex> lock(mutex);
    resampleFilter = newResampleFilter;
    for (auto &texture: textures) {
        texture->SetResampleFilter(newResampleFilter);
    }
}

void PixelTransform::SetImageReader(int32_t width, int32_t height, int32_t format) {
    DispatcherScope dispatchScope(kernel->dispatcher);
    inputWidth = width;
    inputHeight = height;
    inputFormat = format;
    ResetTextures();
    imageReader->SetUp(width, height, format);
    if (kernel->surface) {
        StartRunning();
    }
}

void PixelTransform::ResetTextures() {
    for (auto texture : textures) delete texture;
    textures.clear();
    auto texture = new Texture(vk::Extent2D(inputWidth, inputHeight), inputFormat);
    texture->SetVideoGravity(videoGravity);
    texture->SetResampleFilter(resampleFilter);
    texture->SetVideoEffect(videoEffect);
    texture->SetImageOrientation(imageOrientation);
    textures.push_back(texture);
}

void PixelTransform::RecoverSwapChain() {
    kernel->device->waitIdle();
    ResetTextures();
    kernel->commandBuffer.TearDown(*kernel);
    kernel->pipeline.TearDown(*kernel);
    kernel->swapChain.TearDown(*kernel);
    kernel->queue.TearDown(*kernel);
    kernel->queue.SetUp(*kernel, kernel->queue.queueFamilyIndex);
    kernel->swapChain.SetUp(*kernel, false);
    kernel->queue.SetImagesCount(*kernel, kernel->swapChain.GetImagesCount());
}

void PixelTransform::SetDeviceOrientation(SurfaceRotation surfaceRotation) {
    std::lock_guard<std::mutex> lock(mutex);
    kernel->SetDeviceOrientation(surfaceRotation);
}

void PixelTransform::SetAssetManager(AAssetManager *assetManager) {
    std::lock_guard<std::mutex> lock(mutex);
    kernel->SetAssetManager(assetManager);
}

void PixelTransform::SetNativeWindow(ANativeWindow *nativeWindow) {
    {
        StopRunning();
    }
    kernel->SetNativeWindow(nativeWindow);
    if (nativeWindow != nullptr && !textures.empty()) {
        StartRunning();
    }
}

std::string PixelTransform::InspectDevices() {
    return kernel->InspectDevices();
}

bool PixelTransform::HasFeatures() {
    return kernel->HasFeatures();
}

void PixelTransform::SetRotatesWithContent(bool expectedOrientationSynchronize) {
    std::lock_guard<std::mutex> lock(mutex);
    kernel->SetRotatesWithContent(expectedOrientationSynchronize);
}

void PixelTransform::StartRunning() {
    if (running) {
        return;
    }
    running = true;
    fpsController->Clear();
    auto result = pthread_create(&pthread, nullptr, &OnRunning, this);
    joinable = result == 0;
    if (result != 0) {
        running = false;
        LOGE("failed to create pthread error no: %d", result);
    }
}

void PixelTransform::StopRunning() {
    if (!joinable) return;
    running = false;
    pthread_join(pthread, nullptr);
    joinable = false;
}

void PixelTransform::PostFrameCallback() {
    using Post64 = void (*)(AChoreographer *, AChoreographer_frameCallback64, void *);
    static const auto post64 = reinterpret_cast<Post64>(dlsym(RTLD_DEFAULT, "AChoreographer_postFrameCallback64"));
    if (post64) post64(choreographer, OnFrame64, this);
    else AChoreographer_postFrameCallback(choreographer, OnFrameLegacy, this);
}

void PixelTransform::OnRunning() {
    looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    ALooper_acquire(looper);
    choreographer = AChoreographer_getInstance();
    if (choreographer == nullptr) {
        LOGI("failed get an AChoreographer instance.");
        return;
    }
    PostFrameCallback();
    while (running) ALooper_pollOnce(16, nullptr, nullptr, nullptr);
    ALooper_release(looper);
    looper = nullptr;
    choreographer = nullptr;
}

void PixelTransform::OnFrame(int64_t frameTimeNanos) {
    if (!running) return;
    DispatcherScope dispatchScope(kernel->dispatcher);
    std::lock_guard<std::recursive_mutex> guard(VulkanLock());
    std::lock_guard<std::mutex> lock(mutex);
    if (choreographer && running) {
        PostFrameCallback();
    }
    if (fpsController->Advanced(frameTimeNanos)) {
        if (kernel->device) kernel->device->waitIdle();
        AHardwareBuffer *buffer = imageReader->GetLatestBuffer();
        if (!kernel->IsAvailable() || buffer == nullptr) {

            return;
        }
        kernel->queue.presentationTime = imageReader->timestamp;
        const auto &texture = textures[0];
        texture->SetUp(*kernel, buffer);
        auto result = kernel->DrawFrame([=](uint32_t index) {
            texture->UpdateAt(*kernel, index, buffer);
            texture->LayoutAt(*kernel, index);
        });
        if (result == vk::Result::eErrorOutOfDateKHR || result == vk::Result::eSuboptimalKHR) {
            RecoverSwapChain();
        }
    }

}

void PixelTransform::SetVideoEffect(VideoEffect *newVideoEffect) {
    DispatcherScope dispatchScope(kernel->dispatcher);
    std::lock_guard<std::mutex> lock(mutex);
    if (kernel->device) kernel->device->waitIdle();
    auto oldVideoEffect = videoEffect;
    videoEffect = newVideoEffect;
    // TODO
    if (kernel->device) {
        videoEffect->SetUp(*kernel);
    }
    for (auto &texture: textures) {
        texture->SetVideoEffect(newVideoEffect);
    }
    delete oldVideoEffect;
}

void PixelTransform::ReadPixels(void *data) {
    kernel->ReadPixels(data);
}

extern "C"
{
JNIEXPORT jboolean JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_00024Companion_nativeIsDeviceSupported(JNIEnv *env,
                                                                       jobject thiz) {
    if (!Graphics::DynamicLoader::GetInstance().Load()) return false;
    try {
        Kernel kernel;
        return kernel.HasFeatures();
    } catch (...) { return false; }
}

JNIEXPORT void JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeSetVideoGravity(JNIEnv *env, jobject thiz,
                                                                  jint value) {
    Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe([=](PixelTransform *self) {
        self->SetVideoGravity(static_cast<VideoGravity>(value));
        return nullptr;
    });
}

JNIEXPORT void JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeSetSurface(JNIEnv *env, jobject thiz,
                                                             jobject surface) {
    ANativeWindow *window = nullptr;
    if (surface != nullptr) {
        window = ANativeWindow_fromSurface(env, surface);
    }
    Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe([=](PixelTransform *self) {
        self->SetNativeWindow(window);
        return nullptr;
    });
}

JNIEXPORT void JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeSetImageOrientation(JNIEnv *env, jobject thiz,
                                                                      jint value) {
    Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe([=](PixelTransform *self) {
        self->SetImageOrientation(static_cast<ImageOrientation>(value));
        return nullptr;
    });
}

JNIEXPORT jobject JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeCreateInputSurface(JNIEnv *env, jobject thiz,
                                                                     jint width,
                                                                     jint height, jint format) {
    return static_cast<jobject>(Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe(
            [=](PixelTransform *self) {
                self->SetImageReader(width, height, format);
                return ANativeWindow_toSurface(env, self->GetInputSurface());
            }));
}

JNIEXPORT void JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeSetDeviceOrientation(JNIEnv *env, jobject thiz,
                                                                       jint value) {
    Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe([=](PixelTransform *self) {
        self->SetDeviceOrientation(static_cast<SurfaceRotation>(value));
        return nullptr;
    });
}

JNIEXPORT void JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeSetResampleFilter(JNIEnv *env, jobject thiz,
                                                                    jint value) {
    Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe([=](PixelTransform *self) {
        self->SetResampleFilter(static_cast<ResampleFilter>(value));
        return nullptr;
    });
}

JNIEXPORT void JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeSetAssetManager(JNIEnv *env, jobject thiz,
                                                                  jobject asset_manager) {
    AAssetManager *manager = AAssetManager_fromJava(env, asset_manager);
    Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe([=](PixelTransform *self) {
        self->SetAssetManager(manager);
        return nullptr;
    });
}

JNIEXPORT jstring JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_inspectDevices(JNIEnv *env, jobject thiz) {
    return static_cast<jstring>(Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe(
            [=](PixelTransform *self) {
                std::string string = self->InspectDevices();
                return env->NewStringUTF(string.c_str());
            }));
}

JNIEXPORT jboolean JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeIsSupported(JNIEnv *env, jobject thiz) {
    if (!DynamicLoader::GetInstance().Load()) {
        return false;
    }
    try { return Unmanaged<PixelTransform>::FromOpaque(env, thiz)->TakeRetainedValue()->HasFeatures(); }
    catch (...) { return false; }
}

JNIEXPORT void JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeDispose(JNIEnv *env, jobject thiz) {
    Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Release();
}

JNIEXPORT void JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeSetImageExtent(JNIEnv *env, jobject thiz,
                                                                 jint width,
                                                                 jint height) {
    Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe([=](PixelTransform *self) {
        self->SetImageExtent(width, height);
        return nullptr;
    });
}

JNIEXPORT void JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeSetRotatesWithContent(JNIEnv *env,
                                                                        jobject thiz,
                                                                        jboolean expectedOrientationSynchronize) {
    Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe([=](PixelTransform *self) {
        self->SetRotatesWithContent(expectedOrientationSynchronize);
        return nullptr;
    });
}

JNIEXPORT void JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeSetFrameRate(JNIEnv *env, jobject thiz,
                                                               jint frameRate) {
    Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe([=](PixelTransform *self) {
        self->SetFrameRate(frameRate);
        return nullptr;
    });
}

JNIEXPORT void JNICALL
Java_com_haishinkit_vulkan_NativePixelTransform_nativeReadPixels(JNIEnv *env, jobject thiz,
                                                             jobject byteBuffer) {
    Unmanaged<PixelTransform>::FromOpaque(env, thiz)->Safe([=](PixelTransform *self) {
        void *buffer = env->GetDirectBufferAddress(byteBuffer);
        self->ReadPixels(buffer);
        return nullptr;
    });
}
}
