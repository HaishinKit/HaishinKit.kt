#ifndef HAISHINKIT_KT_TEXTURE_H
#define HAISHINKIT_KT_TEXTURE_H

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <vulkan/vulkan.hpp>
#include <android/native_window.h>
#include "ImageStorage.h"
#include "VideoGravity.h"
#include "ResampleFilter.h"
#include "ImageOrientation.h"
#include "VideoEffect.h"
#include "PushConstants.hpp"

namespace Graphics {
    class Kernel;

    struct ImageStorage;

    struct Texture {

        Texture(vk::Extent2D extent, int32_t format);

        ~Texture();

        void SetVideoEffect(VideoEffect *videoEffect);

        void SetVideoGravity(VideoGravity newVideoGravity);

        void SetResampleFilter(ResampleFilter newResampleFilter);

        void SetUp(Kernel &kernel, AHardwareBuffer *buffer);

        void TearDown(Kernel &kernel);

        void SetImageOrientation(ImageOrientation newImageOrientation);

        void UpdateAt(Kernel &kernel, uint32_t currentFrame, AHardwareBuffer *buffer);

        void LayoutAt(Kernel &kernel, uint32_t currentFrame);

    private:
        static vk::ClearColorValue CLEAR_COLOR;

        int32_t format;
        vk::Extent2D extent;
        vk::UniqueSamplerYcbcrConversion conversion;
        vk::UniqueSampler sampler;
        std::vector<ImageStorage> storages;
        ImageOrientation imageOrientation = UP;
        uint64_t externalFormat = 0;
        VideoEffect *videoEffect = nullptr;
        bool invalidateLayout = true;
        VideoGravity videoGravity = RESIZE_ASPECT;
        ResampleFilter resampleFilter = NEAREST;
        PushConstants pushConstantsBlock;

        std::vector<vk::Rect2D> scissors;
        std::vector<vk::ClearValue> colors;
        std::vector<vk::Viewport> viewports;
    };
}

#endif //HAISHINKIT_KT_TEXTURE_H
