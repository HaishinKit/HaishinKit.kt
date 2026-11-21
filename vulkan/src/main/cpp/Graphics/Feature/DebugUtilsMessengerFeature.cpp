#include "../../haishinkit.hpp"
#include <vulkan/vulkan.hpp>
#include "Feature.h"
#include "DebugUtilsMessengerFeature.h"

using namespace Graphics;

VKAPI_ATTR vk::Bool32 VKAPI_CALL
DebugUtilsMessengerFeature::callback(vk::DebugUtilsMessageSeverityFlagBitsEXT messageSeverity,
                                     vk::DebugUtilsMessageTypeFlagsEXT messageType,
                                     const vk::DebugUtilsMessengerCallbackDataEXT *pCallbackData,
                                     void *pUserData) {
    switch (messageSeverity) {
        case vk::DebugUtilsMessageSeverityFlagBitsEXT::eInfo:
            LOGI("%s", pCallbackData->pMessage);
            break;
        case vk::DebugUtilsMessageSeverityFlagBitsEXT::eWarning:
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
