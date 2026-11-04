#include "DynamicLoader.h"
#include "VulkanLock.h"
#include "Kernel.h"

using namespace Graphics;

bool DynamicLoader::Load() {
    std::lock_guard<std::recursive_mutex> guard(VulkanLock());
    try {
        if (loaded) {
            return true;
        }
        static vk::DynamicLoader dl;
        const auto vkGetInstanceProcAddr = dl.getProcAddress<PFN_vkGetInstanceProcAddr>(
                "vkGetInstanceProcAddr");
        if (!vkGetInstanceProcAddr) return false;
        procAddress = vkGetInstanceProcAddr;
        VULKAN_HPP_DEFAULT_DISPATCHER.init(vkGetInstanceProcAddr);
        loaded = true;
        return vkGetInstanceProcAddr != nullptr;
    } catch (...) {
        return false;
    }
}
