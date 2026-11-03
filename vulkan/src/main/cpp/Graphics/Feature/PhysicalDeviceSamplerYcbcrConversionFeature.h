#ifndef HAISHINKIT_KT_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURE_H
#define HAISHINKIT_KT_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURE_H

#include "../../haishinkit.hpp"
#include "Feature.h"
#include <vulkan/vulkan.hpp>

namespace Graphics {
    class PhysicalDeviceSamplerYcbcrConversionFeature : public Feature {
    public:
        PhysicalDeviceSamplerYcbcrConversionFeature();

        void Create(void **next) override;

    private:
        vk::PhysicalDeviceSamplerYcbcrConversionFeatures features;
    };
}

#endif //HAISHINKIT_KT_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURE_H
