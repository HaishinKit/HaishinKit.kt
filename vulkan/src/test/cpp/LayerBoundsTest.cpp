#include "../../main/cpp/Graphics/LayerBounds.h"
#include <cassert>
#include <limits>

int main() {
    vk::PhysicalDeviceLimits limits;
    limits.maxViewportDimensions[0] = limits.maxViewportDimensions[1] = 4096;
    limits.viewportBoundsRange[0] = -8192;
    limits.viewportBoundsRange[1] = 8191;
    const auto valid = Graphics::LayerViewport({-64, -32, 4096, 4096, 0, 0, 0, 0}, limits);
    assert(valid.x == -64 && valid.width == 4096);
    auto reject = [&](std::array<int, 8> bounds) {
        bool rejected = false;
        try { Graphics::LayerViewport(bounds, limits); }
        catch (const std::runtime_error &) { rejected = true; }
        assert(rejected);
    };
    reject({0, 0, 4097, 32});
    reject({0, 0, 32, 4097});
    reject({-8193, 0, 32, 32});
    reject({0, -8193, 32, 32});
    reject({8190, 0, 32, 32});
    reject({0, 8190, 32, 32});
    reject({0, 0, 0, 32});
    reject({0, 0, 32, -1});
    reject({0, 0, std::numeric_limits<int>::max(), 32});

    auto clip = Graphics::LayerScissor({-16, -8, 64, 64}, {0, 0, 64, 64}, {64, 64});
    assert(clip.offset == vk::Offset2D(0, 0) && clip.extent == vk::Extent2D(48, 56));
    clip = Graphics::LayerScissor({32, 16, 64, 64}, {0, 0, 64, 64}, {64, 64});
    assert(clip.offset == vk::Offset2D(32, 16) && clip.extent == vk::Extent2D(32, 48));
    clip = Graphics::LayerScissor({32, 16, 64, 64}, {0, 0, std::numeric_limits<int>::max(), std::numeric_limits<int>::max()}, {64, 64});
    assert(clip.offset == vk::Offset2D(32, 16) && clip.extent == vk::Extent2D(32, 48));
    clip = Graphics::LayerScissor({-32, -16, 64, 64}, {std::numeric_limits<int>::min(), std::numeric_limits<int>::min(), 64, 64}, {64, 64});
    assert(clip.offset == vk::Offset2D(0, 0) && clip.extent == vk::Extent2D(32, 48));
    clip = Graphics::LayerScissor({128, 128, 64, 64}, {0, 0, 64, 64}, {64, 64});
    assert(clip.extent == vk::Extent2D());
}
