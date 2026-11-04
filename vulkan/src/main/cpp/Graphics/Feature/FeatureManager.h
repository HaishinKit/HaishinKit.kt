#ifndef HAISHINKIT_KT_FEATURE_MANAGER_H
#define HAISHINKIT_KT_FEATURE_MANAGER_H

#include <jni.h>
#include <vector>
#include "Feature.h"
#include "DebugUtilsMessengerFeature.h"

namespace Graphics {
    class FeatureManager {
    public:
        std::vector<Feature *> features;

        std::vector<const char *> GetExtensions(FeatureType type);

        void *GetNext(FeatureType type);

        FeatureManager();

        ~FeatureManager();
    };
}

#endif //HAISHINKIT_KT_FEATURE_MANAGER_H
