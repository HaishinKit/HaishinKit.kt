#include "../haishinkit.hpp"
#include "Kernel.h"
#include "Util.h"
#include "CommandBuffer.h"

using namespace Graphics;

CommandBuffer::CommandBuffer() = default;

CommandBuffer::~CommandBuffer() = default;

void CommandBuffer::SetUp(Kernel &kernel) {
    commandPool = kernel.device->createCommandPoolUnique(
            vk::CommandPoolCreateInfo()
                    .setPNext(nullptr)
                    .setFlags(
                            vk::CommandPoolCreateFlags(
                                    vk::CommandPoolCreateFlagBits::eResetCommandBuffer))
                    .setQueueFamilyIndex(kernel.queue.queueFamilyIndex)
    );

    buffers.resize(4);
    const Vertex rotation_0[] = {
            {{-1.f, -1.f}, {0.f, 0.f}},
            {{-1.f, 1.f},  {0.f, 1.f}},
            {{1.f,  -1.f}, {1.f, 0.f}},
            {{1.f,  1.f},  {1.f, 1.f}},
    };
    buffers[0] = CreateBuffer(kernel, (void *) rotation_0,
                              sizeof(rotation_0));

    const Vertex rotation_90[] = {
            {{-1.f, -1.f}, {1.f, 0.f}},
            {{-1.f, 1.f},  {0.f, 0.f}},
            {{1.f,  -1.f}, {1.f, 1.f}},
            {{1.f,  1.f},  {0.f, 1.f}},
    };
    buffers[1] = CreateBuffer(kernel, (void *) rotation_90,
                              sizeof(rotation_90));

    const Vertex rotation_180[] = {
            {{-1.f, -1.f}, {1.f, 1.f}},
            {{-1.f, 1.f},  {1.f, 0.f}},
            {{1.f,  -1.f}, {0.f, 1.f}},
            {{1.f,  1.f},  {0.f, 0.f}},
    };
    buffers[2] = CreateBuffer(kernel, (void *) rotation_180,
                              sizeof(rotation_180));

    const Vertex rotation_270[] = {
            {{-1.f, -1.f}, {0.f, 1.f}},
            {{-1.f, 1.f},  {1.f, 1.f}},
            {{1.f,  -1.f}, {0.f, 0.f}},
            {{1.f,  1.f},  {1.f, 0.f}},
    };
    buffers[3] = CreateBuffer(kernel, (void *) rotation_270,
                              sizeof(rotation_270));

    offsets.resize(1);
    offsets[0] = {0};

    Reset(kernel);
}

void CommandBuffer::Reset(Kernel &kernel) {
    auto imagesCount = kernel.swapChain.GetImagesCount();
    commandBuffers = kernel.device->allocateCommandBuffersUnique(
            vk::CommandBufferAllocateInfo()
                    .setPNext(nullptr)
                    .setCommandPool(commandPool.get())
                    .setLevel(vk::CommandBufferLevel::ePrimary)
                    .setCommandBufferCount(imagesCount)
    );

    framebuffers.resize(imagesCount);
    for (auto i = 0; i < imagesCount; ++i) {
        framebuffers[i] = kernel.swapChain.CreateFramebuffer(kernel, i);
    }
}

void CommandBuffer::TearDown(Kernel &kernel) {
    commandBuffers.clear();
    commandBuffers.shrink_to_fit();
    for (auto &buffer: buffers) {
        kernel.device->destroy(buffer);
    }
    for (auto memory : memories) kernel.device->free(memory);
    memories.clear();
    buffers.clear();
    for (auto &framebuffer: framebuffers) {
        kernel.device->destroy(framebuffer);
    }
}

vk::CommandBuffer CommandBuffer::Allocate(Kernel &kernel) {
    const auto commandBuffers = kernel.device->allocateCommandBuffers(
            vk::CommandBufferAllocateInfo()
                    .setCommandBufferCount(1)
                    .setCommandPool(commandPool.get())
                    .setLevel(vk::CommandBufferLevel::ePrimary)
    );
    return commandBuffers[0];
}

vk::Buffer CommandBuffer::CreateBuffer(Kernel &kernel, void *data, vk::DeviceSize size) {
    const auto result = kernel.device->createBuffer(
            vk::BufferCreateInfo()
                    .setSize(size)
                    .setUsage(vk::BufferUsageFlagBits::eVertexBuffer)
                    .setQueueFamilyIndexCount(1)
                    .setSharingMode(vk::SharingMode::eExclusive)
                    .setQueueFamilyIndices(kernel.queue.queueFamilyIndex)
    );
    const auto memoryRequirements = kernel.device->getBufferMemoryRequirements(
            result);
    const auto memory = kernel.device->allocateMemory(
            vk::MemoryAllocateInfo()
                    .setAllocationSize(
                            memoryRequirements.size)
                    .setMemoryTypeIndex(
                            kernel.FindMemoryType(memoryRequirements.memoryTypeBits,
                                                  vk::MemoryPropertyFlagBits::eHostVisible |
                                                  vk::MemoryPropertyFlagBits::eHostCoherent))
    );
    void *map = kernel.device->mapMemory(memory, 0, memoryRequirements.size);
    memcpy(map, data, size);
    kernel.device->unmapMemory(memory);
    kernel.device->bindBufferMemory(result, memory, 0);
    memories.push_back(memory);
    return result;
}
