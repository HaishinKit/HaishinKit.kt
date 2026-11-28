cmake_minimum_required(VERSION 3.22.1)
project(haishinkit_rnnoise LANGUAGES C CXX)
include(FetchContent)
# RNNoise v0.2, including its matching official model. No runtime downloads.
FetchContent_Declare(rnnoise_source
 URL https://github.com/xiph/rnnoise/archive/c9137adac37fe21ede831f8a0aa31c17560c01e7.tar.gz
 URL_HASH SHA256=d7ba71dece88b415e4d3ba717cc4d2df6dfe839548cd4536d7e1229e78226c2e)
FetchContent_Declare(rnnoise_model
 URL https://media.xiph.org/rnnoise/models/rnnoise_data-0b50c45.tar.gz
 URL_HASH SHA256=4ac81c5c0884ec4bd5907026aaae16209b7b76cd9d7f71af582094a2f98f4b43)
FetchContent_MakeAvailable(rnnoise_source rnnoise_model)
set(RNN ${rnnoise_source_SOURCE_DIR}/src)
add_library(haishinkit_rnnoise SHARED rnnoise_jni.cpp
 ${RNN}/denoise.c ${RNN}/rnn.c ${RNN}/pitch.c ${RNN}/kiss_fft.c
 ${RNN}/celt_lpc.c ${RNN}/nnet.c ${RNN}/nnet_default.c
 ${RNN}/parse_lpcnet_weights.c ${RNN}/rnnoise_tables.c
 ${rnnoise_model_SOURCE_DIR}/src/rnnoise_data.c)
target_include_directories(haishinkit_rnnoise PRIVATE compat ${RNN} ${rnnoise_source_SOURCE_DIR}/include ${rnnoise_model_SOURCE_DIR}/src)
target_compile_features(haishinkit_rnnoise PRIVATE cxx_std_17)
target_compile_options(haishinkit_rnnoise PRIVATE -O3)
if(ANDROID_ABI STREQUAL "arm64-v8a")
 target_compile_definitions(haishinkit_rnnoise PRIVATE OPUS_ARM_PRESUME_AARCH64_NEON_INTR)
endif()
target_link_libraries(haishinkit_rnnoise m)
target_link_options(haishinkit_rnnoise PRIVATE "-Wl,-z,max-page-size=16384")
