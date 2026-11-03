#include "../../haishinkit.hpp"
#include <vulkan/vulkan.hpp>
#include "Feature.h"
#include "DebugUtilsMessengerFeature.h"

using namespace Graphics;

VkBool32
DebugUtilsMessengerFeature::callback(VkDebugUtilsMessageSeverityFlagBitsEXT messageSeverity,
                                     VkDebugUtilsMessageTypeFlagsEXT messageType,
                                     const VkDebugUtilsMessengerCallbackDataEXT *pCallbackData,
                                     void *pUserData) {
    switch (messageSeverity) {
        case VkDebugUtilsMessageSeverityFlagBitsEXT::VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT:
            LOGI("%s", pCallbackData->pMessage);
            break;
        case VkDebugUtilsMessageSeverityFlagBitsEXT::VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT:
            LOGE("%s", pCallbackData->pMessage);
            break;
        default:
            LOGI("%s", pCallbackData->pMessage);
            break;
    }
    return false;
}

DebugUtilsMessengerFeature::DebugUtilsMessengerFeature() : Feature(INSTANCE,
                                                                   VK_EXT_DEBUG_UTILS_EXTENSION_NAME) {
}

void DebugUtilsMessengerFeature::Create(void **next) {
    info = vk::DebugUtilsMessengerCreateInfoEXT()
            .setPNext(*next)
            .setFlags(vk::DebugUtilsMessengerCreateFlagsEXT())
            .setMessageSeverity(
                    vk::DebugUtilsMessageSeverityFlagBitsEXT::eVerbose |
                    vk::DebugUtilsMessageSeverityFlagBitsEXT::eWarning |
                    vk::DebugUtilsMessageSeverityFlagBitsEXT::eInfo |
                    vk::DebugUtilsMessageSeverityFlagBitsEXT::eError
            )
            .setMessageType(
                    vk::DebugUtilsMessageTypeFlagBitsEXT::eGeneral |
                    vk::DebugUtilsMessageTypeFlagBitsEXT::eValidation |
                    vk::DebugUtilsMessageTypeFlagBitsEXT::ePerformance
            )
            .setPfnUserCallback(callback)
            .setPUserData(nullptr);

    *next = &info;
}
