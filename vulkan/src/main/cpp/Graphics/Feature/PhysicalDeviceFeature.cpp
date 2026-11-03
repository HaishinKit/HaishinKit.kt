#include "PhysicalDeviceFeature.h"

using namespace Graphics;

PhysicalDeviceFeature::PhysicalDeviceFeature() :
        Feature(FEATURE, "PhysicalDeviceFeatures2") {
}

void PhysicalDeviceFeature::Create(void **next) {
    features = vk::PhysicalDeviceFeatures2().setPNext(*next);
    *next = &features;
}
