#include "ImageReader.h"

using namespace Graphics;

ImageReader::~ImageReader() {
    TearDown();
    listener = nullptr;
}

void ImageReader::SetUp(int32_t width, int32_t height, int32_t format) {
    TearDown();

    cursor = 0;
    buffers.resize(maxImages);
    images.resize(maxImages);

    auto result = AImageReader_newWithUsage(
            width,
            height,
            format,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            maxImages + 2,
            &reader);

    if (result == AMEDIA_OK) {
        AImageReader_getWindow(reader, &window);
    } else {
        LOGE("Failed to AImageReader_newWithUsage error: %d", result);
    }
}

void ImageReader::TearDown() {
    for (auto image : images) { if (image) AImage_delete(image); }
    images.clear();
    buffers.clear();
    if (reader) {
        AImageReader_delete(reader);
        reader = nullptr;
    }
    window = nullptr;
}

ANativeWindow *ImageReader::GetWindow() {
    return window;
}

AHardwareBuffer *ImageReader::GetLatestBuffer() {
    if (reader == nullptr) {
        return nullptr;
    }
    if (cursor == buffers.size()) {
        cursor = 0;
    }
    AImage *image = nullptr;
    if (AImageReader_acquireLatestImage(reader, &image) != AMEDIA_OK) {
        return nullptr;
    }
    if (images[cursor]) {
        AImage_delete(images[cursor]);
    }
    images[cursor] = image;
    AImage_getTimestamp(image, &timestamp);
    AHardwareBuffer *buffer;
    if (AImage_getHardwareBuffer(image, &buffer) != AMEDIA_OK) {
        return nullptr;
    }
    buffers[cursor] = buffer;
    ++cursor;
    return buffer;
}