#ifndef HAISHINKIT_KT_COMMAND_BUFFER_H
#define HAISHINKIT_KT_COMMAND_BUFFER_H

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <vulkan/vulkan.hpp>
#include "Vertex.hpp"

namespace Graphics {
    class Kernel;

    struct Texture;

    struct CommandBuffer {
        std::vector<vk::UniqueCommandBuffer> commandBuffers;
        std::vector<vk::Buffer> buffers;
        std::vector<vk::DeviceMemory> memories;
        std::vector<vk::DeviceSize> offsets;
        std::vector<vk::Framebuffer> framebuffers;
        vk::UniqueCommandPool commandPool;

        CommandBuffer();

        ~CommandBuffer();

        void SetUp(Kernel &kernel);

        void Reset(Kernel &kernel);

        void TearDown(Kernel &kernel);

        vk::CommandBuffer Allocate(Kernel &kernel);

    private:
        vk::Buffer CreateBuffer(Kernel &kernel, void *data, vk::DeviceSize size);
    };
}

#endif //HAISHINKIT_KT_COMMAND_BUFFER_H
