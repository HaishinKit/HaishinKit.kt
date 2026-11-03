#ifndef HAISHINKIT_KT_PHYSICAL_DEVICE_FEATURE_H
#define HAISHINKIT_KT_PHYSICAL_DEVICE_FEATURE_H

#include "../../haishinkit.hpp"
#include "Feature.h"
#include <vulkan/vulkan.hpp>

namespace Graphics {
    class PhysicalDeviceFeature : public Feature {
    public:
        PhysicalDeviceFeature();

        void Create(void **next) override;

    private:
        vk::PhysicalDeviceFeatures2 features;
    };
}

#endif //HAISHINKIT_KT_PHYSICAL_DEVICE_FEATURE_H
