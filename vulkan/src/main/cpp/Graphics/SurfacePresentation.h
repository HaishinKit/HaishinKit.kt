#ifndef HAISHINKIT_SURFACE_PRESENTATION_H
#define HAISHINKIT_SURFACE_PRESENTATION_H
#include <vulkan/vulkan.hpp>
#include <glm/ext/matrix_transform.hpp>
#include <algorithm>
#include <stdexcept>

namespace Graphics {
    struct SurfacePresentation {
        vk::SurfaceTransformFlagBitsKHR transform;
        vk::Extent2D extent;
        glm::mat4 matrix{1.0f};
        bool swapsAxes = false;
    };

    inline SurfacePresentation SelectSurfacePresentation(vk::Extent2D requested,
                                                         const vk::SurfaceCapabilitiesKHR &capabilities) {
        const auto transform = capabilities.supportedTransforms & vk::SurfaceTransformFlagBitsKHR::eIdentity
                ? vk::SurfaceTransformFlagBitsKHR::eIdentity : capabilities.currentTransform;
        SurfacePresentation result{transform, requested};
        float degrees = 0;
        bool mirrored = false;
        switch (transform) {
            case vk::SurfaceTransformFlagBitsKHR::eIdentity:
            case vk::SurfaceTransformFlagBitsKHR::eInherit: break;
            case vk::SurfaceTransformFlagBitsKHR::eRotate90: degrees = 90; break;
            case vk::SurfaceTransformFlagBitsKHR::eRotate180: degrees = 180; break;
            case vk::SurfaceTransformFlagBitsKHR::eRotate270: degrees = 270; break;
            case vk::SurfaceTransformFlagBitsKHR::eHorizontalMirror: mirrored = true; break;
            case vk::SurfaceTransformFlagBitsKHR::eHorizontalMirrorRotate90: mirrored = true; degrees = 90; break;
            case vk::SurfaceTransformFlagBitsKHR::eHorizontalMirrorRotate180: mirrored = true; degrees = 180; break;
            case vk::SurfaceTransformFlagBitsKHR::eHorizontalMirrorRotate270: mirrored = true; degrees = 270; break;
            default: throw std::runtime_error("Unsupported surface transform");
        }
        result.swapsAxes = degrees == 90 || degrees == 270;
        if (result.swapsAxes) std::swap(result.extent.width, result.extent.height);
        result.extent.width = std::max(capabilities.minImageExtent.width, std::min(result.extent.width, capabilities.maxImageExtent.width));
        result.extent.height = std::max(capabilities.minImageExtent.height, std::min(result.extent.height, capabilities.maxImageExtent.height));
        result.matrix = glm::rotate(glm::mat4(1.0f), glm::radians(degrees), glm::vec3(0, 0, 1));
        if (mirrored) result.matrix = glm::scale(result.matrix, glm::vec3(-1, 1, 1));
        return result;
    }

    inline vk::SubpassDependency AcquireColorAttachmentDependency() {
        return vk::SubpassDependency()
                .setSrcSubpass(VK_SUBPASS_EXTERNAL)
                .setDstSubpass(0)
                .setSrcStageMask(vk::PipelineStageFlagBits::eColorAttachmentOutput)
                .setDstStageMask(vk::PipelineStageFlagBits::eColorAttachmentOutput)
                .setDstAccessMask(vk::AccessFlagBits::eColorAttachmentWrite);
    }
}
#endif
