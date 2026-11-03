
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.10.2)

message(STATUS "PROJECT_SOURCE_DIR = ${PROJECT_SOURCE_DIR}")
message(STATUS "PROJECT_BINARY_DIR = ${PROJECT_BINARY_DIR}")

# Declares and names the project.

project("hkvulkan")

add_definitions(-DVULKAN_HPP_DISPATCH_LOADER_DYNAMIC)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.

add_library(
        # Sets the name of the library.
        hkvulkan
        # Sets the library as a shared library.
        SHARED
        # Provides a relative path to your source file(s).
        src/main/cpp/haishinkit.hpp
        src/main/cpp/Graphics/Kernel.cpp
        src/main/cpp/Graphics/ImageStorage.cpp
        src/main/cpp/Graphics/DynamicLoader.cpp
        src/main/cpp/Graphics/CommandBuffer.cpp
        src/main/cpp/Graphics/Queue.cpp
        src/main/cpp/Graphics/FpsController.cpp
        src/main/cpp/Graphics/PixelTransform.cpp
        src/main/cpp/Graphics/SwapChain.cpp
        src/main/cpp/Graphics/Pipeline.cpp
        src/main/cpp/Graphics/Texture.cpp
        src/main/cpp/Graphics/ImageReader.cpp
        src/main/cpp/Graphics/VideoEffect.cpp
        src/main/cpp/Graphics/Util.cpp
        src/main/cpp/Graphics/Vertex.hpp
        src/main/cpp/Graphics/Feature/Feature.cpp
        src/main/cpp/Graphics/Feature/FeatureManager.cpp
        src/main/cpp/Graphics/Feature/PhysicalDeviceFeature.cpp
        src/main/cpp/Graphics/Feature/PhysicalDeviceSamplerYcbcrConversionFeature.cpp
        src/main/cpp/Graphics/Feature/DebugUtilsMessengerFeature.cpp
)

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library(
        # Sets the name of the path variable.
        log-lib
        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log
)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries(
        # Specifies the target library.
        hkvulkan
        # Links the target library to the log library
        # included in the NDK.
        -landroid
        ${log-lib}
        mediandk
)

include_directories(
        vendor
        vendor/Vulkan-Headers/include
        vendor/glm
)
