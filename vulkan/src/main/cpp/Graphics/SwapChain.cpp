#include "Kernel.h"
#include "SwapChain.h"

using namespace Graphics;

vk::Image SwapChain::GetImage(int index) {
    return images[index];
}

void SwapChain::SetImageExtent(int32_t width, int height) {
    if (info.imageExtent.width == width && info.imageExtent.height == height) {
        return;
    }
    imageExtent.setWidth(width).setHeight(height);
    isInvalidate = true;
}

SurfaceRotation SwapChain::GetSurfaceRotation() const {
    return surfaceRotation;
}

void SwapChain::SetSurfaceRotation(SurfaceRotation newSurfaceRotation) {
    if (surfaceRotation == newSurfaceRotation) {
        return;
    }
    surfaceRotation = newSurfaceRotation;
    isInvalidate = true;
}

vk::Extent2D SwapChain::GetImageExtent() const {
    return info.imageExtent;
}

bool SwapChain::IsInvalidate() const {
    return isInvalidate;
}

bool SwapChain::SetUp(Kernel &kernel, bool requestRecreate) {
    if (isCreated && !requestRecreate) {
        return false;
    }

    const auto capabilities = kernel.GetSurfaceCapabilities();
    const auto format = kernel.GetSurfaceFormat();

    imageExtent.width = std::max(capabilities.minImageExtent.width, std::min(imageExtent.width, capabilities.maxImageExtent.width));
    imageExtent.height = std::max(capabilities.minImageExtent.height, std::min(imageExtent.height, capabilities.maxImageExtent.height));

    if (swapchain) {
        info.setOldSwapchain(swapchain.get());
    } else {
        info.setOldSwapchain(nullptr);
    }

    swapchain = kernel.device->createSwapchainKHRUnique(
            info
                    .setSurface(kernel.surface.get())
                    .setMinImageCount(capabilities.minImageCount)
                    .setImageFormat(format.format)
                    .setImageExtent(imageExtent)
                    .setImageColorSpace(format.colorSpace)
                    .setImageArrayLayers(1)
                    .setImageUsage(vk::ImageUsageFlagBits::eColorAttachment)
                    .setImageSharingMode(vk::SharingMode::eExclusive)
                    .setQueueFamilyIndexCount(1)
                    .setQueueFamilyIndices(kernel.queue.queueFamilyIndex)
                    .setPreTransform(capabilities.currentTransform)
                    .setPresentMode(vk::PresentModeKHR::eFifo)
                    .setClipped(true)
                    .setCompositeAlpha(static_cast<vk::CompositeAlphaFlagBitsKHR>(static_cast<uint32_t>(capabilities.supportedCompositeAlpha) & -static_cast<uint32_t>(capabilities.supportedCompositeAlpha))));

    images = kernel.device->getSwapchainImagesKHR(swapchain.get());

    const auto imagesCount = images.size();
    imageViews.resize(imagesCount);
    for (uint32_t i = 0; i < imagesCount; ++i) {
        imageViews[i] = kernel.CreateImageView(images[i], info.imageFormat);
    }

    const auto attachmentDescription = vk::AttachmentDescription()
            .setFormat(info.imageFormat)
            .setSamples(vk::SampleCountFlagBits::e1)
            .setLoadOp(vk::AttachmentLoadOp::eClear)
            .setStoreOp(vk::AttachmentStoreOp::eStore)
            .setStencilLoadOp(vk::AttachmentLoadOp::eDontCare)
            .setStencilStoreOp(vk::AttachmentStoreOp::eDontCare)
            .setInitialLayout(vk::ImageLayout::eUndefined)
            .setFinalLayout(vk::ImageLayout::ePresentSrcKHR);

    const auto colorAttachment = vk::AttachmentReference()
            .setAttachment(0)
            .setLayout(vk::ImageLayout::eColorAttachmentOptimal);

    const auto subpassDescription = vk::SubpassDescription()
            .setFlags(vk::SubpassDescriptionFlags())
            .setPipelineBindPoint(vk::PipelineBindPoint::eGraphics)
            .setInputAttachmentCount(0)
            .setPInputAttachments(nullptr)
            .setColorAttachmentCount(1)
            .setPColorAttachments(&colorAttachment)
            .setPreserveAttachmentCount(0)
            .setPPreserveAttachments(nullptr);

    renderPass = kernel.device->createRenderPassUnique(
            vk::RenderPassCreateInfo()
                    .setAttachmentCount(1)
                    .setAttachments(attachmentDescription)
                    .setSubpassCount(1)
                    .setPSubpasses(&subpassDescription)
                    .setDependencyCount(0)
                    .setDependencies(nullptr)
    );

    isCreated = true;
    isInvalidate = false;

    return true;
}

void SwapChain::TearDown(Kernel &kernel) {
    imageViews.clear();
    renderPass.reset();
    swapchain.reset();
    isCreated = false;
    isInvalidate = true;
}

int32_t SwapChain::GetImagesCount() {
    return images.size();
}

vk::Framebuffer SwapChain::CreateFramebuffer(Kernel &kernel, int32_t index) {
    vk::ImageView attachments[] = {
            imageViews[index].get()
    };
    return kernel.device->createFramebuffer(
            vk::FramebufferCreateInfo()
                    .setRenderPass(renderPass.get())
                    .setAttachmentCount(1)
                    .setPAttachments(attachments)
                    .setWidth(info.imageExtent.width)
                    .setHeight(info.imageExtent.height)
                    .setLayers(1)
    );
}