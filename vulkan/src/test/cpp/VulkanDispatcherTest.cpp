#include "../../main/cpp/Graphics/VulkanDispatcher.h"
#include <cassert>
#include <cstdint>

static int firstDestroyed = 0, secondDestroyed = 0;
static VKAPI_ATTR void VKAPI_CALL destroyFirst(VkDevice, VkBuffer, const VkAllocationCallbacks *) { ++firstDestroyed; }
static VKAPI_ATTR void VKAPI_CALL destroySecond(VkDevice, VkBuffer, const VkAllocationCallbacks *) { ++secondDestroyed; }

static VKAPI_ATTR VkResult VKAPI_CALL deviceLost(VkDevice) { return VK_ERROR_DEVICE_LOST; }

int main() {
    vk::detail::DispatchLoaderDynamic first, second;
    first.vkDestroyBuffer = destroyFirst;
    second.vkDestroyBuffer = destroySecond;
    vk::Device device(reinterpret_cast<VkDevice>(uintptr_t(1)));
    first.vkDeviceWaitIdle = deviceLost;
    static_assert(noexcept(Graphics::WaitIdleForDisposal(device, first)));
    assert(Graphics::WaitIdleForDisposal(device, first) == VK_ERROR_DEVICE_LOST);
    assert(Graphics::WaitIdleForDisposal(vk::Device{}, first) == VK_SUCCESS);
    vk::UniqueBuffer a, b;
    {
        Graphics::DispatcherScope scope(first);
        a = vk::UniqueBuffer(vk::Buffer((VkBuffer)1), vk::detail::ObjectDestroy<vk::Device, vk::detail::DispatchLoaderDynamic>(device));
    }
    {
        Graphics::DispatcherScope scope(second);
        b = vk::UniqueBuffer(vk::Buffer((VkBuffer)2), vk::detail::ObjectDestroy<vk::Device, vk::detail::DispatchLoaderDynamic>(device));
        a.reset();
        assert(firstDestroyed == 1 && secondDestroyed == 0);
    }
    b.reset();
    assert(firstDestroyed == 1 && secondDestroyed == 1);
}
