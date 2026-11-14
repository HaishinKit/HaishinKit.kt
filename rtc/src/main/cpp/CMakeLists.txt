cmake_minimum_required(VERSION 3.22.1)
project(haishinkit_rtc LANGUAGES C CXX)
include(FetchContent)
set(CMAKE_SKIP_INSTALL_RULES ON)
add_compile_definitions(MBEDTLS_USER_CONFIG_FILE="${CMAKE_CURRENT_SOURCE_DIR}/mbedtls-user-config.h")
list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(mbedtls
    URL https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.5/mbedtls-3.6.5.tar.bz2
    URL_HASH SHA256=4a11f1777bb95bf4ad96721cac945a26e04bf19f57d905f241fe77ebeddf46d8)
# The release archive includes generated sources. Its optional configuration
# warning script cannot run with older host interpreters such as JitPack's.
function(add_rtc_mbedtls)
    set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
    set(GEN_FILES OFF)
    set(CMAKE_DISABLE_FIND_PACKAGE_Python3 TRUE)
    FetchContent_MakeAvailable(mbedtls)
endfunction()
add_rtc_mbedtls()
FetchContent_GetProperties(mbedtls)
add_library(MbedTLS::MbedTLS ALIAS mbedtls)
# libdatachannel and libsrtp must use the Android-built TLS targets.
set(MBEDTLS_INCLUDE_DIRS "${mbedtls_SOURCE_DIR}/include")
set(MBEDTLS_LIBRARIES MbedTLS::mbedtls MbedTLS::mbedx509 MbedTLS::mbedcrypto)
set(USE_MBEDTLS ON CACHE BOOL "" FORCE)
set(NO_WEBSOCKET ON CACHE BOOL "" FORCE)
set(NO_MEDIA OFF CACHE BOOL "" FORCE)
set(NO_TESTS ON CACHE BOOL "" FORCE)
set(NO_EXAMPLES ON CACHE BOOL "" FORCE)
set(TEST_APPS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(datachannel
    GIT_REPOSITORY https://github.com/paullouisageneau/libdatachannel.git
    GIT_TAG c6696d157b5612df2a741d9a03b192b47ab6cefb
    GIT_SUBMODULES deps/json deps/libsrtp deps/plog deps/usrsctp
    GIT_SUBMODULES_RECURSE TRUE)
FetchContent_GetProperties(datachannel)
if(NOT datachannel_POPULATED)
    FetchContent_Populate(datachannel)
    if(NOT FETCHCONTENT_SOURCE_DIR_DATACHANNEL)
        # Use the exact libdatachannel gitlink revision without relying on
        # the runner's Git mirror to serve that submodule commit.
        FetchContent_Declare(rtc_libjuice
            URL https://codeload.github.com/paullouisageneau/libjuice/tar.gz/22145ec3473c73d811417ac9a6aee0de530aaa3f
            URL_HASH SHA256=4748aa200992301bea0cea1329de6dfd4ec7ec5885f2aceb0163618993fc7ff7
            SOURCE_DIR "${datachannel_SOURCE_DIR}/deps/libjuice")
        FetchContent_Populate(rtc_libjuice)
    endif()
    add_subdirectory("${datachannel_SOURCE_DIR}" "${datachannel_BINARY_DIR}")
endif()
add_library(haishinkit_rtc SHARED rtc-jni.cpp)
target_link_libraries(haishinkit_rtc PRIVATE datachannel-static)
target_compile_features(haishinkit_rtc PRIVATE cxx_std_17)
if(ANDROID)
    target_link_options(haishinkit_rtc PRIVATE "-Wl,-z,max-page-size=16384")
else()
    enable_testing()
    add_executable(rtc_media_test "${CMAKE_CURRENT_SOURCE_DIR}/../../socketTest/cpp/rtc-media-test.cpp")
    target_include_directories(rtc_media_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(rtc_media_test PRIVATE datachannel-static)
    target_compile_features(rtc_media_test PRIVATE cxx_std_17)
    add_test(NAME rtc_media_test COMMAND rtc_media_test)
    find_path(RTC_JNI_INCLUDE_DIR jni.h HINTS "$ENV{JAVA_HOME}/include" REQUIRED)
    target_include_directories(haishinkit_rtc PRIVATE "${RTC_JNI_INCLUDE_DIR}")
    find_path(RTC_JNI_PLATFORM_INCLUDE_DIR jni_md.h
        HINTS "${RTC_JNI_INCLUDE_DIR}" PATH_SUFFIXES linux darwin win32)
    if(RTC_JNI_PLATFORM_INCLUDE_DIR)
        target_include_directories(haishinkit_rtc PRIVATE "${RTC_JNI_PLATFORM_INCLUDE_DIR}")
    endif()
    if(RTC_NDK_JNI)
        target_compile_definitions(haishinkit_rtc PRIVATE HAISHINKIT_NDK_JNI)
    endif()
endif()
