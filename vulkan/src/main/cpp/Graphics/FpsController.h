#ifndef HAISHINKIT_KT_FPSCONTROLLER_H
#define HAISHINKIT_KT_FPSCONTROLLER_H

#include <cstdint>

namespace Graphics {
    class FpsController {
    public:
        ~FpsController();

        void SetFrameRate(int frameRate);

        bool Advanced(int64_t frameTime);

        void Clear();

    private:
        int frameRate = 60;
        int64_t timestamp = 0;
        int64_t elapsed = 1000000000 / 60;
    };
}

#endif //HAISHINKIT_KT_FPSCONTROLLER_H
