#ifndef HAISHINKIT_KT_IMAGEREADER_H
#define HAISHINKIT_KT_IMAGEREADER_H

#include "Kernel.h"
#include <media/NdkImageReader.h>

namespace Graphics {
    class ImageReader {
    public:
        AImageReader_ImageListener *listener = nullptr;
        int64_t timestamp = 0;

        ANativeWindow *GetWindow();

        AHardwareBuffer *GetLatestBuffer();

        ~ImageReader();

        void SetUp(int32_t width, int32_t height, int32_t format);

        void TearDown();

    private:
        ANativeWindow *window = nullptr;
        AImageReader *reader = nullptr;
        std::vector<AHardwareBuffer *> buffers;
        std::vector<AImage *> images;
        int32_t maxImages = 2;
        int32_t cursor = 0;
    };
}

#endif //HAISHINKIT_KT_IMAGEREADER_H
