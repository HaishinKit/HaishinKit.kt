#include "Kernel.h"
#include "Queue.h"

using namespace Graphics;

int32_t Queue::GetCurrentFrame() {
    return currentFrame;
}

void Queue::SetImagesCount(Kernel &kernel, int32_t imagesCount) {
    currentFrame = 0;
    images.resize(imagesCount);
}

void Queue::SetUp(Kernel &kernel, uint32_t newQueueFamilyIndex) {
    queueFamilyIndex = newQueueFamilyIndex;
    queue = kernel.device->getQueue(newQueueFamilyIndex, 0);

    waitSemaphores.resize(DEFAULT_MAX_FRAMES);
    signalSemaphores.resize(DEFAULT_MAX_FRAMES);
    fences.resize(DEFAULT_MAX_FRAMES);
    for (auto i = 0; i < DEFAULT_MAX_FRAMES; ++i) {
        waitSemaphores[i] = kernel.device->createSemaphoreUnique({});
        signalSemaphores[i] = kernel.device->createSemaphoreUnique({});
        fences[i] = kernel.device->createFence(
                {vk::FenceCreateFlagBits::eSignaled});
    }
}

void Queue::TearDown(Kernel &kernel) {
    for (auto &fence: fences) {
        kernel.device->destroy(fence);
    }
    images.clear();
    fences.clear();
    waitSemaphores.clear();
    signalSemaphores.clear();
}

void Queue::Submit(Kernel &kernel, vk::CommandBuffer &commandBuffer) {
    auto fence = kernel.device->createFence({});
    queue.submit(vk::SubmitInfo().setCommandBuffers(commandBuffer), fence);
    kernel.device->waitForFences(fence, true, std::numeric_limits<uint64_t>::max());
    kernel.device->destroy(fence);
}

vk::Result
Queue::DrawFrame(Kernel &kernel, const std::function<void(uint32_t)> &lambda) {
    uint32_t nextIndex;
    vk::Result result = kernel.device->acquireNextImageKHR(
            kernel.swapChain.swapchain.get(),
            16000000,
            waitSemaphores[currentFrame].get(),
            nullptr,
            &nextIndex);

    if (result != vk::Result::eSuccess && result != vk::Result::eSuboptimalKHR) {
        return result;
    }

    kernel.device->waitForFences(fences[currentFrame], true,
                                 std::numeric_limits<uint64_t>::max());

    const auto waitStageMask =
            vk::PipelineStageFlags(vk::PipelineStageFlagBits::eColorAttachmentOutput);

    if (images[nextIndex]) {
        kernel.device->waitForFences(images[nextIndex], true,
                                     std::numeric_limits<uint64_t>::max());
    }
    images[nextIndex] = fences[currentFrame];


    vk::CommandBuffer commandBuffer = kernel.commandBuffer.commandBuffers[nextIndex].get();
    lambda(nextIndex);
    kernel.device->resetFences(fences[currentFrame]);

    queue.submit(
            submitInfo
                    .setWaitSemaphores(waitSemaphores[currentFrame].get())
                    .setWaitDstStageMask(waitStageMask)
                    .setCommandBuffers(commandBuffer)
                    .setSignalSemaphores(signalSemaphores[currentFrame].get()),
            fences[currentFrame]);

    vk::PresentTimeGOOGLE time(0, presentationTime);
    vk::PresentTimesInfoGOOGLE timing(1, &time);
    result = queue.presentKHR(
            presentInfo
                    .setPNext(&timing)
                    .setSwapchains(kernel.swapChain.swapchain.get())
                    .setImageIndices(nextIndex)
                    .setWaitSemaphores(signalSemaphores[currentFrame].get())
                    .setPResults(&result)
    );

    currentFrame = (currentFrame + 1) % DEFAULT_MAX_FRAMES;

    return result;
}
