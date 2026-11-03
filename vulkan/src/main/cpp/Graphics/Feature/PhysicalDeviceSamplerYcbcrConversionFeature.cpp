#include "PhysicalDeviceSamplerYcbcrConversionFeature.h"

using namespace Graphics;

PhysicalDeviceSamplerYcbcrConversionFeature::PhysicalDeviceSamplerYcbcrConversionFeature()
        : Feature(FEATURE, "PhysicalDeviceSamplerYcbcrConversionFeature") {
}

void PhysicalDeviceSamplerYcbcrConversionFeature::Create(void **next) {
    features = vk::PhysicalDeviceSamplerYcbcrConversionFeatures()
            .setPNext(*next)
            .setSamplerYcbcrConversion(true);
    *next = &features;
}
