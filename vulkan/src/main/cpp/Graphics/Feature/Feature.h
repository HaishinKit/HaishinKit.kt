#ifndef HAISHINKIT_KT_FEATURE_H
#define HAISHINKIT_KT_FEATURE_H

#include "../../haishinkit.hpp"

namespace Graphics {
    enum FeatureType {
        INSTANCE = 0,
        DEVICE = 1,
        FEATURE = 2,
    };

    class Feature {
    public:
        const FeatureType type;
        const char *name;

        Feature(FeatureType type, const char *name);

        virtual ~Feature() = default;

        virtual void Create(void **next);
    };
}

#endif //HAISHINKIT_KT_FEATURE_H
