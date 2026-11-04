#ifndef HAISHINKIT_KT_VERTEX_HPP
#define HAISHINKIT_KT_VERTEX_HPP

#include <vulkan/vulkan.hpp>
#include <glm/glm.hpp>

namespace Graphics {
    struct Vertex {
        glm::vec2 pos;
        glm::vec2 coord;

        static const vk::VertexInputBindingDescription CreateBindingDescription() {
            return vk::VertexInputBindingDescription()
                    .setBinding(0)
                    .setStride(sizeof(Vertex))
                    .setInputRate(vk::VertexInputRate::eVertex);
        }

        static const std::vector<vk::VertexInputAttributeDescription>
        CreateAttributeDescriptions() {
            return {
                    vk::VertexInputAttributeDescription()
                            .setLocation(0)
                            .setBinding(0)
                            .setFormat(vk::Format::eR32G32Sfloat)
                            .setOffset(0),
                    vk::VertexInputAttributeDescription()
                            .setLocation(1)
                            .setBinding(0)
                            .setFormat(vk::Format::eR32G32Sfloat)
                            .setOffset(offsetof(Vertex, coord))
            };
        }
    };
}

#endif //HAISHINKIT_KT_VERTEX_HPP
