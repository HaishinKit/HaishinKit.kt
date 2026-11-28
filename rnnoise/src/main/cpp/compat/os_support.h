#ifndef HAISHINKIT_RNNOISE_OS_SUPPORT_H
#define HAISHINKIT_RNNOISE_OS_SUPPORT_H
// RNNoise v0.2's NEON header refers to an Opus header omitted from its source archive.
// Its only dependency is OPUS_CLEAR; use RNNoise's equivalent typed clear macro.
#include "common.h"
#define OPUS_CLEAR(dst, count) RNN_CLEAR(dst, count)
#endif
