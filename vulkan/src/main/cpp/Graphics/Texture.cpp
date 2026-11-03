#include "Kernel.h"
#include "Texture.h"
#include "Util.h"
#include "ImageStorage.h"
#include <sys/socket.h>
#include <unistd.h>
#include <glm/ext/matrix_transform.hpp>

using namespace Graphics;

vk::ClearColorValue Texture::CLEAR_COLOR = vk::ClearColorValue().setFloat32({0.f, 0.f, 0.f, 1.f});

Texture::Texture(vk::Extent2D extent, int32_t format) : extent(extent), format(format) {
    colors.clear();
    colors.push_back(vk::ClearValue().setColor(CLEAR_COLOR));
    scissors.resize(1);
    viewports.resize(1);
}

Texture::~Texture() = default;

void Texture::SetImageOrientation(ImageOrientation newImageOrientation) {
    imageOrientation = newImageOrientation;
}

void Texture::SetUp(Kernel &kernel, AHardwareBuffer *buffer) {
    if (!sampler) {
        vk::Filter filter = vk::Filter::eNearest;
        if (resampleFilter != NEAREST) {
            LOGI("failed to set resampleFilter, use a nearest.");
        }

        vk::AndroidHardwareBufferFormatPropertiesANDROID format;
        vk::AndroidHardwareBufferPropertiesANDROID properties;
        properties.pNext = &format;
        kernel.device->getAndroidHardwareBufferPropertiesANDROID(buffer, &properties);
        auto externalFormat = vk::ExternalFormatANDROID().setExternalFormat(format.externalFormat);

        this->externalFormat = format.format == vk::Format::eUndefined ? format.externalFormat : 0;

        if (format.format == vk::Format::eUndefined) conversion = kernel.device->createSamplerYcbcrConversionUnique(
                vk::SamplerYcbcrConversionCreateInfo()
                        .setPNext(&externalFormat)
                        .setFormat(format.format)
                        .setYcbcrModel(format.suggestedYcbcrModel)
                        .setYcbcrRange(format.suggestedYcbcrRange)
                        .setComponents(format.samplerYcbcrConversionComponents)
                        .setXChromaOffset(format.suggestedXChromaOffset)
                        .setYChromaOffset(format.suggestedYChromaOffset)
                        .setChromaFilter(filter)
                        .setForceExplicitReconstruction(false));

        auto samplerCreate = vk::SamplerCreateInfo()
                .setMagFilter(filter)
                .setMinFilter(filter)
                .setAddressModeU(vk::SamplerAddressMode::eClampToEdge)
                .setAddressModeV(vk::SamplerAddressMode::eClampToEdge)
                .setAddressModeW(vk::SamplerAddressMode::eClampToEdge)
                .setMipLodBias(0.0f)
                .setMaxAnisotropy(1)
                .setCompareOp(vk::CompareOp::eNever)
                .setMinLod(0.0f)
                .setMaxLod(0.0f)
                .setBorderColor(vk::BorderColor::eFloatOpaqueWhite)
                .setUnnormalizedCoordinates(false);

        vk::SamplerYcbcrConversionInfo ycbcrConversionInfo;
        if (conversion) {
            ycbcrConversionInfo = vk::SamplerYcbcrConversionInfo()
                    .setConversion(
                            conversion.get());
            samplerCreate.setPNext(&ycbcrConversionInfo);
        }

        sampler = kernel.device->createSamplerUnique(samplerCreate);

        std::vector<vk::Sampler> samplers(1);
        samplers[0] = sampler.get();

        kernel.pipeline.SetUp(kernel, samplers, videoEffect);
        kernel.commandBuffer.SetUp(kernel);
        invalidateLayout = false;

        storages.resize(kernel.swapChain.GetImagesCount());
        for (auto &storage: storages) {
            storage.extent = extent;
            storage.format = format.format;
            storage.SetExternalFormat(this->externalFormat);
            storage.SetUp(kernel, conversion);
        }
    }
}

void Texture::TearDown(Kernel &kernel) {
}

void Texture::UpdateAt(Kernel &kernel, uint32_t currentFrame, AHardwareBuffer *buffer) {
    if (invalidateLayout) {
        std::vector<vk::Sampler> samplers(1);
        samplers[0] = sampler.get();
        kernel.pipeline.SetUp(kernel, samplers, videoEffect);
        invalidateLayout = false;
    }
    auto storage = &storages[currentFrame];
    storage->Update(kernel, buffer);
    storage->GetDescriptorImageInfo().setSampler(sampler.get());
    kernel.pipeline.UpdateDescriptorSets(kernel, *storage, videoEffect);
}

void Texture::LayoutAt(Kernel &kernel, uint32_t currentFrame) {
    auto degrees = 0.f;

    switch (imageOrientation) {
        case UP:
            degrees = 0.f;
            break;
        case DOWN:
            degrees = 180.f;
            break;
        case LEFT:
            degrees = 270.f;
            break;
        case RIGHT:
            degrees = 90.f;
            break;
        case UP_MIRRORED:
            degrees = 0.f;
            break;
        case DOWN_MIRRORED:
            degrees = 180.0;
            break;
        case LEFT_MIRRORED:
            degrees = 270.f;
            break;
        case RIGHT_MIRRORED:
            degrees = 90.f;
            break;
    }

    if (kernel.IsRotatesWithContent()) {
        switch (kernel.swapChain.GetSurfaceRotation()) {
            case ROTATION_0:
                degrees += 0;
                break;
            case ROTATION_90:
                degrees += 90;
                break;
            case ROTATION_180:
                degrees += 180;
                break;
            case ROTATION_270:
                degrees += 270;
                break;
        }
    }

    if (((int) degrees % 180) == 0 &&
        (imageOrientation == RIGHT || imageOrientation == RIGHT_MIRRORED)) {
        degrees += 180;
    }

    auto swapped = false;
    auto index = 0;
    switch ((int) degrees % 360) {
        case 0:
            index = 0;
            break;
        case 90:
            swapped = true;
            index = 1;
            break;
        case 180:
            index = 2;
            break;
        case 270:
            swapped = true;
            index = 3;
            break;
    }

    vk::Extent2D surface = kernel.swapChain.GetImageExtent();
    scissors[0].setExtent(surface);
    auto newImageExtent = swapped ? vk::Extent2D(extent.height, extent.width) : extent;

    viewports[0]
            .setX(0)
            .setY(0)
            .setWidth((float) surface.width)
            .setHeight((float) surface.height);

    switch (videoGravity) {
        case RESIZE: {
            pushConstantsBlock.mvpMatrix = glm::scale(glm::mat4(1.0F), glm::vec3(1.0F, 1.0f, 1.0F));
            break;
        }
        case RESIZE_ASPECT: {
            const float iRatio = (float) surface.width / (float) surface.height;
            const float fRatio = (float) newImageExtent.width / (float) newImageExtent.height;
            if (iRatio < fRatio) {
                pushConstantsBlock.mvpMatrix = glm::scale(
                        glm::mat4(1.0F),
                        glm::vec3(1.0F,
                                  (float) newImageExtent.height /
                                  (float) newImageExtent.width *
                                  iRatio, 1.0F
                        )
                );
            } else {
                pushConstantsBlock.mvpMatrix = glm::scale(
                        glm::mat4(1.0F),
                        glm::vec3((float) newImageExtent.width /
                                  (float) newImageExtent.height /
                                  iRatio,
                                  1.0f,
                                  1.0F
                        )
                );
            }
            break;
        }
        case RESIZE_ASPECT_FILL: {
            const float iRatio = (float) surface.width / (float) surface.height;
            const float fRatio = (float) newImageExtent.width / (float) newImageExtent.height;
            if (iRatio < fRatio) {
                pushConstantsBlock.mvpMatrix = glm::scale(
                        glm::mat4(1.0F),
                        glm::vec3(
                                (float) surface.height /
                                (float) surface.width *
                                fRatio, 1.0f, 1.0F
                        )
                );
            } else {
                pushConstantsBlock.mvpMatrix = glm::scale(
                        glm::mat4(1.0F),
                        glm::vec3(1.0f, (float) surface.width /
                                        (float) surface.height /
                                        fRatio,
                                  1.0F
                        )
                );
            }
            break;
        }
    }

    auto &commandBuffer = kernel.commandBuffer.commandBuffers[currentFrame].get();
    commandBuffer.begin(
            vk::CommandBufferBeginInfo()
                    .setFlags(vk::CommandBufferUsageFlagBits::eRenderPassContinue));

    commandBuffer.setViewport(0, viewports);
    commandBuffer.setScissor(0, scissors);

    commandBuffer.pushConstants(
            kernel.pipeline.pipelineLayout.get(),
            vk::ShaderStageFlagBits::eVertex,
            0,
            sizeof(PushConstants),
            &pushConstantsBlock);

    commandBuffer.beginRenderPass(
            vk::RenderPassBeginInfo()
                    .setRenderPass(kernel.swapChain.renderPass.get())
                    .setFramebuffer(kernel.commandBuffer.framebuffers[currentFrame])
                    .setRenderArea(
                            vk::Rect2D().setOffset({0, 0}).setExtent(surface))
                    .setClearValues(colors),
            vk::SubpassContents::eInline);

    commandBuffer.bindPipeline(vk::PipelineBindPoint::eGraphics,
                               kernel.pipeline.pipeline.get());

    commandBuffer.bindVertexBuffers(0, kernel.commandBuffer.buffers[index],
                                    kernel.commandBuffer.offsets);

    commandBuffer.bindDescriptorSets(
            vk::PipelineBindPoint::eGraphics,
            kernel.pipeline.pipelineLayout.get(),
            0,
            kernel.pipeline.descriptorSets.size(),
            kernel.pipeline.descriptorSets.data(),
            0,
            nullptr);

    commandBuffer.draw(4, 1, 0, 0);
    commandBuffer.endRenderPass();
    storages[currentFrame].Release(kernel, commandBuffer);
    commandBuffer.end();
}

void Texture::SetVideoEffect(VideoEffect *newVideoEffect) {
    videoEffect = newVideoEffect;
    invalidateLayout = true;
}

void Texture::SetVideoGravity(VideoGravity newVideoGravity) {
    videoGravity = newVideoGravity;
}

void Texture::SetResampleFilter(ResampleFilter newResampleFilter) {
    resampleFilter = newResampleFilter;
}
