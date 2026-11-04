#ifndef HAISHINKIT_VULKAN_LOCK_H
#define HAISHINKIT_VULKAN_LOCK_H
#include <mutex>

// Vulkan-Hpp's default dispatcher is shared. Do not reinitialize it while another
// transform is issuing Vulkan commands through it.
inline std::recursive_mutex &VulkanLock() {
    static std::recursive_mutex mutex;
    return mutex;
}
#endif
