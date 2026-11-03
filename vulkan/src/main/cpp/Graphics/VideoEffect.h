#ifndef HAISHINKIT_KT_VIDEOEFFECT_H
#define HAISHINKIT_KT_VIDEOEFFECT_H

#include <string>
#include "Kernel.h"

namespace Graphics {
    // Effects are applied to the composition by the OpenGL bridge. Vulkan uses
    // an unmodified RGBA sample, so no Java references cross the render thread.
    class VideoEffect {
    public:
        void SetUp(Kernel &) {}
        std::string GetShaderPath(const std::string &type) {
            return "shaders/default." + type + ".spv";
        }
        std::vector<vk::DescriptorSetLayoutBinding> GetDescriptorSetLayoutBindings() { return {}; }
        std::vector<vk::DescriptorBufferInfo> GetDescriptorBufferInfo() { return {}; }
    };
}
#endif
