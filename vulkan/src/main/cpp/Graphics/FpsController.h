#ifndef HAISHINKIT_KT_FPSCONTROLLER_H
#define HAISHINKIT_KT_FPSCONTROLLER_H

#include "Kernel.h"
#include <android/choreographer.h>
#include <android/looper.h>

namespace Graphics {
    class FpsController {
    public:
        ~FpsController();

        void SetFrameRate(int frameRate);

        bool Advanced(long frameTime);

        void Clear();

    private:
        int frameRate = 60;
        long timestamp = 0;
        long elapsed = 1000000000 / 60;
    };
}

#endif //HAISHINKIT_KT_FPSCONTROLLER_H
