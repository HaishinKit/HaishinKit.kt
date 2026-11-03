#ifndef HAISHINKIT_KT_IMAGESTORAGE_H
#define HAISHINKIT_KT_IMAGESTORAGE_H

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <vulkan/vulkan.hpp>

namespace Graphics {
    class Kernel;

    struct ImageStorage {
        vk::Format format;
        vk::ImageLayout layout = vk::ImageLayout::ePreinitialized;
        vk::Extent2D extent = vk::Extent2D(0, 0);

        vk::DescriptorImageInfo GetDescriptorImageInfo();

        void SetExternalFormat(uint64_t newExternalFormat);

        void SetUp(Kernel &kernel, vk::UniqueSamplerYcbcrConversion &conversion);

        void Update(Kernel &kernel, AHardwareBuffer *buffer);

        void TearDown(Kernel &kernel);
        void Release(Kernel &kernel, vk::CommandBuffer commandBuffer);

        void SetLayout(
                vk::CommandBuffer &commandBuffer,
                uint32_t queueFamily,
                vk::ImageLayout newLayout,
                vk::PipelineStageFlagBits srcStageMask,
                vk::PipelineStageFlagBits dstStageMask);

    private:
        vk::UniqueDeviceMemory memory;
        vk::UniqueImage image;
        vk::UniqueImageView imageView;

        vk::ExternalFormatANDROID externalFormat;
        vk::DescriptorImageInfo descriptorImageInfo;
        vk::ImageCreateInfo imageCreateInfo;
        vk::ImageViewCreateInfo imageViewCreateInfo;
        vk::MemoryAllocateInfo memoryAllocateInfo;
        vk::SamplerYcbcrConversionInfo samplerYcbcrConversionInfo;
    };
}

#endif //HAISHINKIT_KT_IMAGESTORAGE_H
