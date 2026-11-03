#include "FpsController.h"

using namespace Graphics;

FpsController::~FpsController() = default;

void FpsController::SetFrameRate(int newFrameRate) {
    Clear();
    frameRate = std::min(60, std::max(1, newFrameRate));
    elapsed = 1000000000 / (long) frameRate;
}

bool FpsController::Advanced(long frameTime) {
    if (timestamp == 0) {
        timestamp = frameTime;
        return true;
    }
    if (elapsed <= (frameTime - timestamp)) {
        timestamp = frameTime;
        return true;
    }
    return false;
}

void FpsController::Clear() {
    timestamp = 0;
}
