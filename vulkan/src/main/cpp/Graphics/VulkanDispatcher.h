#ifndef HAISHINKIT_VULKAN_DISPATCHER_H
#define HAISHINKIT_VULKAN_DISPATCHER_H
#if defined(__ANDROID__) && !defined(VK_USE_PLATFORM_ANDROID_KHR)
#define VK_USE_PLATFORM_ANDROID_KHR 1
#endif
namespace vk { class DispatchLoaderDynamic; }
namespace Graphics { vk::DispatchLoaderDynamic &CurrentDispatcher(); }
#define VULKAN_HPP_DEFAULT_DISPATCHER ::Graphics::CurrentDispatcher()
#include <vulkan/vulkan.hpp>

namespace Graphics {
    inline thread_local vk::DispatchLoaderDynamic *activeDispatcher = nullptr;
    inline vk::DispatchLoaderDynamic &CurrentDispatcher() {
        static thread_local vk::DispatchLoaderDynamic bootstrap;
        return activeDispatcher ? *activeDispatcher : bootstrap;
    }
    // UniqueHandle deleters retain this dispatcher's address, not the thread-local
    // selector. Each Kernel's dispatcher outlives all of that Kernel's handles.
    class DispatcherScope {
        vk::DispatchLoaderDynamic *previous;
    public:
        explicit DispatcherScope(vk::DispatchLoaderDynamic &dispatcher)
            : previous(activeDispatcher) { activeDispatcher = &dispatcher; }
        ~DispatcherScope() { activeDispatcher = previous; }
    };
}
#endif
