#include "Kernel.h"
#include "Util.h"
#include "ImageStorage.h"

using namespace Graphics;

vk::DescriptorImageInfo ImageStorage::GetDescriptorImageInfo() {
    return descriptorImageInfo
            .setImageLayout(layout)
            .setImageView(imageView.get());
}

void ImageStorage::SetExternalFormat(uint64_t newExternalFormat) {
    externalFormat.setExternalFormat(newExternalFormat);
}

void ImageStorage::SetUp(Kernel &kernel, vk::UniqueSamplerYcbcrConversion &conversion) {
    // vk::ImageCreateInfo
    imageCreateInfo
            .setImageType(vk::ImageType::e2D)
            .setExtent(vk::Extent3D(extent.width, extent.height, 1))
            .setMipLevels(1)
            .setArrayLayers(1)
            .setFormat(format)
            .setInitialLayout(layout)
            .setSharingMode(vk::SharingMode::eExclusive)
            .setSamples(vk::SampleCountFlagBits::e1)
            .setInitialLayout(layout)
            .setUsage(vk::ImageUsageFlagBits::eSampled)
            .setTiling(vk::ImageTiling::eOptimal);

    // vk::ImageViewCreateInfo
    imageViewCreateInfo
            .setViewType(vk::ImageViewType::e2D)
            .setFormat(format)
            .setComponents(
                    vk::ComponentMapping()
                            .setR(vk::ComponentSwizzle::eR)
                            .setG(vk::ComponentSwizzle::eG)
                            .setB(vk::ComponentSwizzle::eB)
                            .setA(vk::ComponentSwizzle::eA))
            .setSubresourceRange(
                    vk::ImageSubresourceRange()
                            .setAspectMask(vk::ImageAspectFlagBits::eColor)
                            .setBaseMipLevel(0)
                            .setLevelCount(1)
                            .setBaseArrayLayer(0)
                            .setLayerCount(1));

    if (conversion) {
        samplerYcbcrConversionInfo.setConversion(conversion.get());
        imageViewCreateInfo.setPNext(&samplerYcbcrConversionInfo);
    }
}

void ImageStorage::Update(Kernel &kernel, AHardwareBuffer *buffer) {
    layout = vk::ImageLayout::eUndefined;

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);

    if (extent.width != desc.width || extent.height != desc.height) {
        LOGI("expected size is (%d,%d) but actual size is (%d,%d)", extent.width, extent.height,
             desc.width, desc.height);
        extent.width = desc.width;
        extent.height = desc.height;
        imageCreateInfo.setExtent(vk::Extent3D(extent.width, extent.height, 1));
    }

    imageView.reset();
    image.reset();
    memory.reset();
    image = kernel.device->createImageUnique(
            imageCreateInfo
                    .setInitialLayout(layout)
                    .setArrayLayers(desc.layers)
                    .setPNext(&vk::ExternalMemoryImageCreateInfo()
                            .setHandleTypes(
                                    vk::ExternalMemoryHandleTypeFlagBits::eAndroidHardwareBufferANDROID)
                            .setPNext(
                                    &externalFormat
                            )));

    const auto hardwareBufferProperties = kernel.device->getAndroidHardwareBufferPropertiesANDROID(
            *buffer);

    memory = kernel.device->allocateMemoryUnique(
            memoryAllocateInfo
                    .setAllocationSize(hardwareBufferProperties.allocationSize)
                    .setMemoryTypeIndex(
                            kernel.FindMemoryType(
                                    hardwareBufferProperties.memoryTypeBits,
                                    vk::MemoryPropertyFlagBits::eHostVisible
                            ))
                    .setPNext(&vk::MemoryDedicatedAllocateInfo()
                            .setImage(image.get())
                            .setPNext(&vk::ImportAndroidHardwareBufferInfoANDROID()
                                    .setBuffer(buffer)))
    );

    kernel.device->bindImageMemory(image.get(), memory.get(), 0);
    // Foreign producers preserve the imported contents in GENERAL layout.
    layout = vk::ImageLayout::eGeneral;
    kernel.Submit([this, &kernel](vk::CommandBuffer commandBuffer) {
        SetLayout(
                commandBuffer,
                kernel.queue.queueFamilyIndex,
                vk::ImageLayout::eShaderReadOnlyOptimal,
                vk::PipelineStageFlagBits::eTopOfPipe,
                vk::PipelineStageFlagBits::eFragmentShader
        );
    });

    imageViewCreateInfo.setImage(image.get());
    imageView = kernel.device->createImageViewUnique(imageViewCreateInfo);
}

void ImageStorage::TearDown(Kernel &kernel) {
}

void ImageStorage::SetLayout(vk::CommandBuffer &commandBuffer,
                             uint32_t queueFamily,
                             vk::ImageLayout newImageLayout,
                             vk::PipelineStageFlagBits srcStageMask,
                             vk::PipelineStageFlagBits dstStageMask) {

    const auto barrier = Util::CreateImageMemoryBarrier(layout, newImageLayout)
            .setSrcQueueFamilyIndex(VK_QUEUE_FAMILY_FOREIGN_EXT)
            .setDstQueueFamilyIndex(queueFamily)
            .setImage(image.get())
            .setSubresourceRange({vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1});

    commandBuffer.pipelineBarrier(
            srcStageMask,
            dstStageMask,
            vk::DependencyFlags(),
            nullptr,
            nullptr,
            barrier
    );

    layout = newImageLayout;
}

void ImageStorage::Release(Kernel &kernel, vk::CommandBuffer commandBuffer) {
    commandBuffer.pipelineBarrier(
        vk::PipelineStageFlagBits::eFragmentShader,
        vk::PipelineStageFlagBits::eBottomOfPipe,
        {}, nullptr, nullptr,
        vk::ImageMemoryBarrier()
            .setSrcAccessMask(vk::AccessFlagBits::eShaderRead)
            .setOldLayout(vk::ImageLayout::eShaderReadOnlyOptimal)
            .setNewLayout(vk::ImageLayout::eGeneral)
            .setSrcQueueFamilyIndex(kernel.queue.queueFamilyIndex)
            .setDstQueueFamilyIndex(VK_QUEUE_FAMILY_FOREIGN_EXT)
            .setImage(image.get())
            .setSubresourceRange({vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1}));
}
