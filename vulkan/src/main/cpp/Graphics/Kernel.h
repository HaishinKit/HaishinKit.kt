#ifndef HAISHINKIT_KT_KERNEL_H
#define HAISHINKIT_KT_KERNEL_H

#define VK_USE_PLATFORM_ANDROID_KHR 1

#include "../haishinkit.hpp"
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_core.h>
#include <vulkan/vulkan_android.h>
#include <vulkan/vulkan.hpp>
#include <android/asset_manager.h>
#include "CommandBuffer.h"
#include "SwapChain.h"
#include "Pipeline.h"
#include "Texture.h"
#include "Queue.h"
#include "Feature/FeatureManager.h"
#include "SurfaceRotation.hpp"

namespace Graphics {
    class Kernel {
    public:
        mutable vk::DispatchLoaderDynamic dispatcher;
        vk::UniqueInstance instance;
        vk::UniqueDevice device;
        vk::UniqueSurfaceKHR surface;
        SwapChain swapChain;
        Pipeline pipeline;
        Queue queue;
        CommandBuffer commandBuffer;

        Kernel();

        ~Kernel();

        void SetDeviceOrientation(SurfaceRotation surfaceRotation);

        void SetAssetManager(AAssetManager *newAssetManager);

        bool IsRotatesWithContent() const;

        void SetRotatesWithContent(bool rotatesWithContent);

        void SetImageExtent(int32_t width, int32_t height);

        void SetNativeWindow(ANativeWindow *window);

        vk::Result DrawFrame(const std::function<void(uint32_t)> &lambda);

        void Submit(const std::function<void(vk::CommandBuffer)> &transaction);

        bool IsAvailable() const;

        vk::ShaderModule LoadShader(const std::string &fileName);

        vk::ShaderModule LoadShaderStrict(const std::string &fileName);

        void SelectPhysicalDevice();

        vk::UniqueImageView CreateImageView(vk::Image image, vk::Format);

        uint32_t FindMemoryType(uint32_t typeFilter, vk::MemoryPropertyFlags properties) const;

        void OnOrientationChange();

        bool HasFeatures();

        vk::SurfaceCapabilitiesKHR GetSurfaceCapabilities();

        vk::SurfaceFormatKHR GetSurfaceFormat();

        void ReadPixels(void *buffer);

    private:
        const std::string applicationName = "HaishinKit";
        const std::string engineName = "Vulkan::Kernel";
        const std::vector<const char *> validationLayers = {
                "VK_LAYER_KHRONOS_validation"
        };
        bool validationLayersEnabled;
        bool available = false;
        bool rotatesWithContent = true;
        AAssetManager *assetManager = nullptr;
        FeatureManager *featureManager = nullptr;
        ANativeWindow *nativeWindow = nullptr;
        int32_t selectedPhysicalDevice = -1;
        vk::PhysicalDevice physicalDevice;

        std::vector<char> ReadFile(const std::string &fileName);

        bool IsValidationLayersSupported();
        bool Supports(vk::PhysicalDevice candidate);
    };
}

#endif //HAISHINKIT_KT_KERNEL_H
