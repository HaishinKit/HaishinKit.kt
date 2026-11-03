cmake_minimum_required(VERSION 3.22.1)
project(srt_android LANGUAGES C CXX)

include(FetchContent)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(mbedtls
    URL https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.5/mbedtls-3.6.5.tar.bz2
    URL_HASH SHA256=4a11f1777bb95bf4ad96721cac945a26e04bf19f57d905f241fe77ebeddf46d8)
FetchContent_MakeAvailable(mbedtls)

set(ENABLE_APPS OFF CACHE BOOL "" FORCE)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(ENABLE_ENCRYPTION ON CACHE BOOL "" FORCE)
set(USE_ENCLIB mbedtls CACHE STRING "" FORCE)
# Use the Android targets above rather than discovering host crypto libraries.
set(SSL_LIBRARY_DIRS "${mbedtls_BINARY_DIR}/library")
set(SSL_INCLUDE_DIRS "${mbedtls_SOURCE_DIR}/include")
set(SSL_LIBRARIES mbedcrypto)
FetchContent_Declare(srt
    URL https://codeload.github.com/Haivision/srt/tar.gz/refs/tags/v1.5.7
    URL_HASH SHA256=017cd1e437ef2073a4dd10ddf7b55e86bc3d6ebac0393d13bd22f6a57055d32b)
FetchContent_MakeAvailable(srt)

add_library(srt SHARED srt-runtime.cpp srt-logger.cpp)
target_include_directories(srt PRIVATE "${srt_SOURCE_DIR}/srtcore" "${srt_BINARY_DIR}")
target_link_libraries(srt PRIVATE srt_static)
if(ANDROID)
    target_link_options(srt PRIVATE "-Wl,-z,max-page-size=16384")
else()
    # Host JNI build for native tests without an Android device.
    find_path(SRT_JNI_INCLUDE_DIR jni.h REQUIRED)
    target_include_directories(srt PRIVATE "${SRT_JNI_INCLUDE_DIR}")
    enable_testing()
    add_executable(srt_logger_test "${CMAKE_CURRENT_SOURCE_DIR}/../../socketTest/cpp/srt-logger-test.cpp")
    target_include_directories(srt_logger_test PRIVATE
        "${SRT_JNI_INCLUDE_DIR}" "${srt_SOURCE_DIR}/srtcore" "${srt_BINARY_DIR}")
    target_link_libraries(srt_logger_test PRIVATE srt)
    add_test(NAME srt_logger_test COMMAND srt_logger_test)
endif()
