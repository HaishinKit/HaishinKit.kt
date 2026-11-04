#include "../../main/cpp/Graphics/FpsController.h"
#include <cassert>
#include <cmath>
#include <initializer_list>

int main() {
    for (auto displayRate : {60.01, 90.0, 120.0}) {
        for (auto requested : {24, 30, 60}) {
            Graphics::FpsController controller;
            controller.SetFrameRate(requested);
            int count = 0;
            const int callbacks = static_cast<int>(displayRate * 100);
            for (int i = 0; i < callbacks; ++i) {
                if (controller.Advanced(1000000000LL + static_cast<int64_t>(i * 1000000000.0 / displayRate))) ++count;
            }
            assert(std::abs(count - requested * 100) <= 2);
        }
    }
}
