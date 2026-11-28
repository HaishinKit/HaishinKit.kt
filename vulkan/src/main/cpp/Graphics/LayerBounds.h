#pragma once

#include <vulkan/vulkan.hpp>
#include <algorithm>
#include <array>
#include <cstdint>
#include <stdexcept>

namespace Graphics {
inline vk::Viewport LayerViewport(const std::array<int, 8> &bounds,
                                  const vk::PhysicalDeviceLimits &limits) {
    const vk::Viewport viewport(float(bounds[0]), float(bounds[1]), float(bounds[2]),
                                float(bounds[3]), 0.f, 1.f);
    const double x = viewport.x, y = viewport.y;
    const double width = viewport.width, height = viewport.height;
    if (width <= 0 || height <= 0 || width > limits.maxViewportDimensions[0] ||
        height > limits.maxViewportDimensions[1] || x < limits.viewportBoundsRange[0] ||
        y < limits.viewportBoundsRange[0] || x + width > limits.viewportBoundsRange[1] ||
        y + height > limits.viewportBoundsRange[1])
        throw std::runtime_error("Layer viewport exceeds device limits");
    return viewport;
}

inline vk::Rect2D LayerScissor(const std::array<int, 8> &bounds,
                               const std::array<int, 4> &clip, vk::Extent2D extent) {
    auto x = [&](int value) { return std::clamp<int64_t>(int64_t(value) + bounds[0], 0, extent.width); };
    auto y = [&](int value) { return std::clamp<int64_t>(int64_t(value) + bounds[1], 0, extent.height); };
    const auto left = x(clip[0]), top = y(clip[1]);
    const auto right = x(clip[2]), bottom = y(clip[3]);
    if (left >= right || top >= bottom) return {};
    return vk::Rect2D(vk::Offset2D(int32_t(left), int32_t(top)),
                      vk::Extent2D(uint32_t(right - left), uint32_t(bottom - top)));
}
} // namespace Graphics
