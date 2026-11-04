#include "../../main/cpp/Graphics/SurfacePresentation.h"
#include <cassert>
#include <cmath>

static void expectPoint(const glm::mat4 &matrix, float x, float y) {
    const auto point = matrix * glm::vec4(-1, -1, 0, 1);
    assert(std::abs(point.x - x) < 0.0001f);
    assert(std::abs(point.y - y) < 0.0001f);
}

int main() {
    using Transform = vk::SurfaceTransformFlagBitsKHR;
    vk::SurfaceCapabilitiesKHR capabilities;
    capabilities.minImageExtent = vk::Extent2D(1, 1);
    capabilities.maxImageExtent = vk::Extent2D(4096, 4096);
    capabilities.currentTransform = Transform::eRotate90;
    capabilities.supportedTransforms = Transform::eIdentity | Transform::eRotate90;
    const auto identity = Graphics::SelectSurfacePresentation({1280, 720}, capabilities);
    assert(identity.transform == Transform::eIdentity);
    assert(identity.extent == vk::Extent2D(1280, 720));
    expectPoint(identity.matrix, -1, -1);

    for (auto transform : {Transform::eRotate90, Transform::eRotate180, Transform::eRotate270}) {
        capabilities.currentTransform = transform;
        capabilities.supportedTransforms = transform;
        const auto presentation = Graphics::SelectSurfacePresentation({1280, 720}, capabilities);
        assert(presentation.transform == transform);
        if (transform == Transform::eRotate180) {
            assert(presentation.extent == vk::Extent2D(1280, 720));
            expectPoint(presentation.matrix, 1, 1);
        } else {
            assert(presentation.extent == vk::Extent2D(720, 1280));
            expectPoint(presentation.matrix, transform == Transform::eRotate90 ? 1 : -1,
                        transform == Transform::eRotate90 ? -1 : 1);
        }
    }
    capabilities.currentTransform = Transform::eHorizontalMirror;
    capabilities.supportedTransforms = Transform::eHorizontalMirror;
    expectPoint(Graphics::SelectSurfacePresentation({1280, 720}, capabilities).matrix, 1, -1);

    const auto dependency = Graphics::AcquireColorAttachmentDependency();
    assert(dependency.srcSubpass == VK_SUBPASS_EXTERNAL && dependency.dstSubpass == 0);
    assert(dependency.srcStageMask == vk::PipelineStageFlagBits::eColorAttachmentOutput);
    assert(dependency.dstStageMask == vk::PipelineStageFlagBits::eColorAttachmentOutput);
    assert(dependency.dstAccessMask == vk::AccessFlagBits::eColorAttachmentWrite);
}
