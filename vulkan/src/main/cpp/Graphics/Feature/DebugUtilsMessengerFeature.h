#ifndef HAISHINKIT_KT_DEBUG_UTILS_MESSENGER_FEATURE_H
#define HAISHINKIT_KT_DEBUG_UTILS_MESSENGER_FEATURE_H

#include "Feature.h"
#include <vulkan/vulkan.hpp>

namespace Graphics {
    class DebugUtilsMessengerFeature : public Feature {
    public:
        DebugUtilsMessengerFeature();

        void Create(void **next) override;

        static VKAPI_ATTR VkBool32 VKAPI_CALL
        callback(VkDebugUtilsMessageSeverityFlagBitsEXT messageSeverity,
                 VkDebugUtilsMessageTypeFlagsEXT messageType,
                 const VkDebugUtilsMessengerCallbackDataEXT *pCallbackData, void *pUserData);

    private:
        vk::DebugUtilsMessengerCreateInfoEXT info;
    };
}

#endif //HAISHINKIT_KT_DEBUG_UTILS_MESSENGER_FEATURE_H
