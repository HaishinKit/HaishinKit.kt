#ifndef HAISHINKIT_VULKAN_DISPATCHER_H
#define HAISHINKIT_VULKAN_DISPATCHER_H
#if defined(__ANDROID__) && !defined(VK_USE_PLATFORM_ANDROID_KHR)
#define VK_USE_PLATFORM_ANDROID_KHR 1
#endif
namespace vk { namespace detail { class DispatchLoaderDynamic; } }
namespace Graphics { vk::detail::DispatchLoaderDynamic &CurrentDispatcher(); }
#define VULKAN_HPP_DEFAULT_DISPATCHER ::Graphics::CurrentDispatcher()
#include <vulkan/vulkan.hpp>

namespace Graphics {
    inline thread_local vk::detail::DispatchLoaderDynamic *activeDispatcher = nullptr;
    inline vk::detail::DispatchLoaderDynamic &CurrentDispatcher() {
        static thread_local vk::detail::DispatchLoaderDynamic bootstrap;
        return activeDispatcher ? *activeDispatcher : bootstrap;
    }
    inline VkResult WaitIdleForDisposal(vk::Device device, const vk::detail::DispatchLoaderDynamic &dispatcher) noexcept {
        return device ? dispatcher.vkDeviceWaitIdle(static_cast<VkDevice>(device)) : VK_SUCCESS;
    }
    // UniqueHandle deleters retain this dispatcher's address, not the thread-local
    // selector. Each Kernel's dispatcher outlives all of that Kernel's handles.
    class DispatcherScope {
        vk::detail::DispatchLoaderDynamic *previous;
    public:
        explicit DispatcherScope(vk::detail::DispatchLoaderDynamic &dispatcher)
            : previous(activeDispatcher) { activeDispatcher = &dispatcher; }
        ~DispatcherScope() { activeDispatcher = previous; }
    };
}
#endif
