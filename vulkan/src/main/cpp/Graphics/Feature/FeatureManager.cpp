#include "Feature.h"
#include "FeatureManager.h"
#include "vulkan/vulkan.h"
#include "vulkan/vulkan.hpp"
#include "vulkan/vulkan_android.h"
#include "PhysicalDeviceFeature.h"
#include "PhysicalDeviceSamplerYcbcrConversionFeature.h"

using namespace Graphics;

FeatureManager::FeatureManager() {
    // INSTANCE
    features.emplace_back(new Feature(INSTANCE, VK_KHR_SURFACE_EXTENSION_NAME));
    features.emplace_back(new Feature(INSTANCE, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME));
    // DEVICE
    features.emplace_back(new Feature(DEVICE, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME));
    features.emplace_back(new Feature(DEVICE, VK_KHR_SWAPCHAIN_EXTENSION_NAME));
    features.emplace_back(
            new Feature(DEVICE, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME));
    features.emplace_back(new Feature(DEVICE, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME));
    // FEATURE
    features.emplace_back(new PhysicalDeviceFeature());
    features.emplace_back(new PhysicalDeviceSamplerYcbcrConversionFeature());
}

FeatureManager::~FeatureManager() {
    for (auto feature : features) delete feature;
}

std::vector<const char *> FeatureManager::GetExtensions(FeatureType type) {
    std::vector<const char *> extensions;
    for (auto &feature: features) {
        if (feature->type == type) {
            extensions.push_back(feature->name);
        }
    }
    return extensions;
}

void *FeatureManager::GetNext(FeatureType type) {
    void *next = nullptr;
    for (auto &feature: features) {
        if (feature->type == type) {
            feature->Create(&next);
        }
    }
    return next;
}
