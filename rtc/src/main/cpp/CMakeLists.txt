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
    URL https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.8/mbedtls-3.6.8.tar.bz2
    URL_HASH SHA256=bc54b818e87f49c0b3281dd85d24adaede0e63b4e822e3fbd49c005a38b9595b)
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
set(NO_WEBSOCKET OFF CACHE BOOL "" FORCE)
set(NO_MEDIA OFF CACHE BOOL "" FORCE)
set(NO_TESTS ON CACHE BOOL "" FORCE)
set(NO_EXAMPLES ON CACHE BOOL "" FORCE)
set(TEST_APPS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(datachannel
    GIT_REPOSITORY https://github.com/paullouisageneau/libdatachannel.git
    GIT_TAG 800e849a1ede5667caaf8f6caa1eb21e4906dd87
    GIT_SUBMODULES deps/json deps/libsrtp deps/plog deps/usrsctp
    GIT_SUBMODULES_RECURSE TRUE)
FetchContent_GetProperties(datachannel)
if(NOT datachannel_POPULATED)
    FetchContent_Populate(datachannel)
    if(NOT FETCHCONTENT_SOURCE_DIR_DATACHANNEL)
        # Use the exact libdatachannel gitlink revision without relying on
        # the runner's Git mirror to serve that submodule commit.
        FetchContent_Declare(rtc_libjuice
            URL https://codeload.github.com/paullouisageneau/libjuice/tar.gz/b89c792e3612faf2f12cf35bcc56857313a06be3
            URL_HASH SHA256=713b22174e8cb2e06538fa9bac2650bda21b83c86f5128813ab4dcf8f5bea9ed
            SOURCE_DIR "${datachannel_SOURCE_DIR}/deps/libjuice")
        FetchContent_Populate(rtc_libjuice)
    endif()
    # v0.24.7's escaped brackets are rejected by libc++'s POSIX extended
    # regex parser. Keep IPv6 URL support using portable bracket expressions.
    find_package(Git REQUIRED)
    set(rtc_websocket_patch "${CMAKE_CURRENT_SOURCE_DIR}/../assets/libdatachannel/websocket-url-regex.patch")
    execute_process(COMMAND "${GIT_EXECUTABLE}" apply --reverse --check "${rtc_websocket_patch}"
        WORKING_DIRECTORY "${datachannel_SOURCE_DIR}"
        RESULT_VARIABLE rtc_websocket_patch_applied OUTPUT_QUIET ERROR_QUIET)
    if(NOT rtc_websocket_patch_applied EQUAL 0)
        execute_process(COMMAND "${GIT_EXECUTABLE}" apply "${rtc_websocket_patch}"
            WORKING_DIRECTORY "${datachannel_SOURCE_DIR}"
            RESULT_VARIABLE rtc_websocket_patch_result ERROR_VARIABLE rtc_websocket_patch_error)
        if(NOT rtc_websocket_patch_result EQUAL 0)
            message(FATAL_ERROR "Unable to apply libdatachannel WebSocket fix: ${rtc_websocket_patch_error}")
        endif()
    endif()
    add_subdirectory("${datachannel_SOURCE_DIR}" "${datachannel_BINARY_DIR}")
endif()
add_library(haishinkit_rtc SHARED rtc-jni.cpp websocket-jni.cpp)
set_target_properties(haishinkit_rtc PROPERTIES OUTPUT_NAME hkrtc)
target_link_libraries(haishinkit_rtc PRIVATE datachannel-static)
target_compile_features(haishinkit_rtc PRIVATE cxx_std_17)
if(ANDROID)
    target_link_options(haishinkit_rtc PRIVATE "-Wl,-z,max-page-size=16384")
else()
    enable_testing()
    add_executable(rtc_unicode_test "${CMAKE_CURRENT_SOURCE_DIR}/../../socketTest/cpp/unicode-test.cpp")
    target_include_directories(rtc_unicode_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_compile_features(rtc_unicode_test PRIVATE cxx_std_17)
    add_test(NAME rtc_unicode_test COMMAND rtc_unicode_test)
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
