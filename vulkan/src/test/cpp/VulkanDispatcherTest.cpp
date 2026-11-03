#include "../../main/cpp/Graphics/VulkanDispatcher.h"
#include <cassert>
#include <cstdint>

static int firstDestroyed = 0, secondDestroyed = 0;
static VKAPI_ATTR void VKAPI_CALL destroyFirst(VkDevice, VkBuffer, const VkAllocationCallbacks *) { ++firstDestroyed; }
static VKAPI_ATTR void VKAPI_CALL destroySecond(VkDevice, VkBuffer, const VkAllocationCallbacks *) { ++secondDestroyed; }

int main() {
    vk::DispatchLoaderDynamic first, second;
    first.vkDestroyBuffer = destroyFirst;
    second.vkDestroyBuffer = destroySecond;
    vk::Device device(reinterpret_cast<VkDevice>(uintptr_t(1)));
    vk::UniqueBuffer a, b;
    {
        Graphics::DispatcherScope scope(first);
        a = vk::UniqueBuffer(vk::Buffer((VkBuffer)1), vk::ObjectDestroy<vk::Device, vk::DispatchLoaderDynamic>(device));
    }
    {
        Graphics::DispatcherScope scope(second);
        b = vk::UniqueBuffer(vk::Buffer((VkBuffer)2), vk::ObjectDestroy<vk::Device, vk::DispatchLoaderDynamic>(device));
        a.reset();
        assert(firstDestroyed == 1 && secondDestroyed == 0);
    }
    b.reset();
    assert(firstDestroyed == 1 && secondDestroyed == 1);
}
