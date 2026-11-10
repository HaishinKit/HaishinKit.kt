#include "Kernel.h"
#include "SpirvValidation.h"
#include "VulkanLock.h"
#include "SwapChain.h"
#include "Texture.h"
#include "stdexcept"
#include <jni.h>
#include "../haishinkit.hpp"
#include "vulkan/vulkan.h"
#include "vulkan/vulkan.hpp"
#include "vulkan/vulkan_android.h"
#include "DynamicLoader.h"
#include "Util.h"
#include <android/native_window.h>



using namespace Graphics;

Kernel::Kernel() : validationLayersEnabled(true), assetManager(nullptr),
                   featureManager(new FeatureManager()) {
    DispatcherScope dispatchScope(dispatcher);
    std::lock_guard<std::recursive_mutex> guard(VulkanLock());
    if (!DynamicLoader::GetInstance().Load()) {
        return;
    }

    dispatcher.init(DynamicLoader::GetInstance().procAddress);
    const auto useValidationLayers = validationLayersEnabled && IsValidationLayersSupported();
    if (useValidationLayers) {
        featureManager->features.emplace_back(new DebugUtilsMessengerFeature());
    }

    const auto appInfo = vk::ApplicationInfo()
            .setPNext(nullptr)
            .setPApplicationName(applicationName.c_str())
            .setApplicationVersion(VK_MAKE_VERSION(1, 0, 0))
            .setPEngineName(engineName.c_str())
            .setEngineVersion(VK_MAKE_VERSION(1, 0, 0))
            .setApiVersion(VK_API_VERSION_1_1);

    std::vector<const char *> extensions = featureManager->GetExtensions(INSTANCE);

    auto createInfo = vk::InstanceCreateInfo()
            .setFlags(vk::InstanceCreateFlags())
            .setPApplicationInfo(&appInfo)
            .setEnabledLayerCount(0)
            .setPEnabledLayerNames(nullptr)
            .setEnabledExtensionCount(extensions.size())
            .setPEnabledExtensionNames(extensions)
            .setPNext(featureManager->GetNext(INSTANCE));

    if (useValidationLayers) {
        createInfo
                .setEnabledLayerCount(validationLayers.size())
                .setPpEnabledLayerNames(validationLayers.data());
    }

    instance = vk::createInstanceUnique(createInfo);

    VULKAN_HPP_DEFAULT_DISPATCHER.init(instance.get());
    available = true;
}

Kernel::~Kernel() {
    DispatcherScope dispatchScope(dispatcher);
    std::lock_guard<std::recursive_mutex> guard(VulkanLock());
    auto idleResult = WaitIdleForDisposal(device.get(), dispatcher);
    if (idleResult != VK_SUCCESS) LOGW("Vulkan disposal wait returned %d", idleResult);
    commandBuffer.TearDown(*this);
    pipeline.TearDown(*this);
    swapChain.TearDown(*this);
    queue.TearDown(*this);
    if (nativeWindow) ANativeWindow_release(nativeWindow);
    delete featureManager;
    nativeWindow = nullptr;
}

void Kernel::SetImageExtent(int32_t width, int32_t height) {
    DispatcherScope dispatchScope(dispatcher);
    swapChain.SetImageExtent(width, height);
}

void Kernel::SetAssetManager(AAssetManager *newAssetManager) {
    DispatcherScope dispatchScope(dispatcher);
    assetManager = newAssetManager;
}

void Kernel::SetNativeWindow(ANativeWindow *newNativeWindow) {
    DispatcherScope dispatchScope(dispatcher);
    std::lock_guard<std::recursive_mutex> guard(VulkanLock());
    SelectPhysicalDevice();
    ANativeWindow *oldNativeWindow = nativeWindow;
    nativeWindow = nullptr;
    device->waitIdle();
    if (newNativeWindow) {
        nativeWindow = newNativeWindow;
        surface = instance->createAndroidSurfaceKHRUnique(
                vk::AndroidSurfaceCreateInfoKHR()
                        .setFlags(vk::AndroidSurfaceCreateFlagsKHR())
                        .setPNext(nullptr)
                        .setWindow(newNativeWindow));
        if (!physicalDevice.getSurfaceSupportKHR(queue.queueFamilyIndex, surface.get())) {
            throw std::runtime_error("The destination Surface does not support Vulkan presentation");
        }
        swapChain.SetUp(*this, false);
        queue.SetImagesCount(*this, swapChain.GetImagesCount());
    } else {
        swapChain.TearDown(*this);
        surface.reset();
    }
    if (oldNativeWindow) {
        ANativeWindow_release(oldNativeWindow);
    }
    nativeWindow = newNativeWindow;
}

vk::Result Kernel::DrawFrame(const std::function<void(uint32_t)> &lambda) {
    DispatcherScope dispatchScope(dispatcher);
    if (!available) {
        return vk::Result::eErrorInitializationFailed;
    }
    if (swapChain.IsInvalidate()) {
        this->OnOrientationChange();
    }
    try { return queue.DrawFrame(*this, lambda); }
    catch (const vk::OutOfDateKHRError &) { return vk::Result::eErrorOutOfDateKHR; }
}

bool Kernel::IsAvailable() const {
    DispatcherScope dispatchScope(dispatcher);
    return available && nativeWindow != nullptr;
}

void Kernel::SetDeviceOrientation(SurfaceRotation newSurfaceRotation) {
    DispatcherScope dispatchScope(dispatcher);
    swapChain.SetSurfaceRotation(newSurfaceRotation);
}

vk::ShaderModule Kernel::LoadShader(const std::string &fileName) {
    DispatcherScope dispatchScope(dispatcher);
    const auto code = ReadFile(fileName);
    return device->createShaderModule(
            vk::ShaderModuleCreateInfo()
                    .setCodeSize(code.size())
                    .setPCode(reinterpret_cast<const uint32_t *>(code.data()))
    );
}

bool Kernel::IsValidationLayersSupported() {
    DispatcherScope dispatchScope(dispatcher);
    const auto properties = vk::enumerateInstanceLayerProperties();
    for (auto layerName: validationLayers) {
        for (auto layerProperties: properties) {
            if (strcmp(layerName, layerProperties.layerName) == 0) {
                return true;
            }
        }
    }
    return false;
}

bool Kernel::IsRotatesWithContent() const {
    DispatcherScope dispatchScope(dispatcher);
    return rotatesWithContent;
}

void Kernel::SetRotatesWithContent(bool newRotatesWithContent) {
    DispatcherScope dispatchScope(dispatcher);
    rotatesWithContent = newRotatesWithContent;
}

void Kernel::SelectPhysicalDevice() {
    DispatcherScope dispatchScope(dispatcher);
    std::lock_guard<std::recursive_mutex> guard(VulkanLock());
    if (0 <= selectedPhysicalDevice) {
        return;
    }

    auto physicalDevices = instance->enumeratePhysicalDevices();
    for (auto i = 0; i < physicalDevices.size(); i++) {
        if (!Supports(physicalDevices[i])) continue;
        this->physicalDevice = physicalDevices[i];
        selectedPhysicalDevice = i;
        break;
    }
    if (!physicalDevice) throw std::runtime_error("No compatible Vulkan device");
    uint32_t propertyCount;
    physicalDevice.getQueueFamilyProperties(&propertyCount, nullptr);
    const auto queueFamilyProperties = physicalDevice.getQueueFamilyProperties();
    size_t graphicsQueueFamilyIndex = std::distance(
            queueFamilyProperties.begin(),
            std::find_if(
                    queueFamilyProperties.begin(), queueFamilyProperties.end(),
                    [](vk::QueueFamilyProperties const &qfp) {
                        return qfp.queueFlags & vk::QueueFlagBits::eGraphics;
                    }));
    assert(graphicsQueueFamilyIndex < queueFamilyProperties.size());

    std::vector<float> queuePriorities{1.0f};
    std::vector<const char *> extensions = featureManager->GetExtensions(DEVICE);

    device = physicalDevice.createDeviceUnique(
            vk::DeviceCreateInfo()
                    .setPNext(featureManager->GetNext(FEATURE))
                    .setEnabledExtensionCount(extensions.size())
                    .setPpEnabledExtensionNames(extensions.data())
                    .setQueueCreateInfoCount(1)
                    .setQueueCreateInfos(
                            vk::DeviceQueueCreateInfo()
                                    .setFlags(vk::DeviceQueueCreateFlags())
                                    .setQueueFamilyIndex(
                                            static_cast<uint32_t>( graphicsQueueFamilyIndex ))
                                    .setQueueCount(1)
                                    .setQueuePriorities(queuePriorities)
                    )
    );
    VULKAN_HPP_DEFAULT_DISPATCHER.init(device.get());
    queue.SetUp(*this, graphicsQueueFamilyIndex);
}

void Kernel::Submit(const std::function<void(vk::CommandBuffer)> &transaction) {
    DispatcherScope dispatchScope(dispatcher);
    auto commandBuffers = device->allocateCommandBuffers(
            vk::CommandBufferAllocateInfo()
                    .setCommandBufferCount(1)
                    .setCommandPool(commandBuffer.commandPool.get())
                    .setLevel(vk::CommandBufferLevel::ePrimary)
    );
    commandBuffers[0].begin(vk::CommandBufferBeginInfo());
    transaction(commandBuffers[0]);
    commandBuffers[0].end();
    queue.Submit(*this, commandBuffers[0]);
    device->freeCommandBuffers(commandBuffer.commandPool.get(), commandBuffers);
}

vk::UniqueImageView Kernel::CreateImageView(vk::Image image, vk::Format format) {
    DispatcherScope dispatchScope(dispatcher);
    return device->createImageViewUnique(
            vk::ImageViewCreateInfo()
                    .setImage(image)
                    .setViewType(vk::ImageViewType::e2D)
                    .setFormat(format)
                    .setComponents(
                            vk::ComponentMapping()
                                    .setR(vk::ComponentSwizzle::eR)
                                    .setG(vk::ComponentSwizzle::eG)
                                    .setB(vk::ComponentSwizzle::eB)
                                    .setA(vk::ComponentSwizzle::eA))
                    .setSubresourceRange(
                            vk::ImageSubresourceRange()
                                    .setAspectMask(vk::ImageAspectFlagBits::eColor)
                                    .setBaseMipLevel(0)
                                    .setLevelCount(1)
                                    .setBaseArrayLayer(0)
                                    .setLayerCount(1)));
}

uint32_t
Kernel::FindMemoryType(uint32_t typeFilter, vk::MemoryPropertyFlags properties) const {
    DispatcherScope dispatchScope(dispatcher);
    auto memoryProperties = physicalDevice.getMemoryProperties();
    for (uint32_t i = 0; i < VK_MAX_MEMORY_TYPES; ++i) {
        if ((typeFilter & (1 << i)) &&
            (memoryProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    throw std::runtime_error("failed to find suitable memory type!");
}

bool Kernel::Supports(vk::PhysicalDevice candidate) {
    DispatcherScope dispatchScope(dispatcher);
    if (candidate.getProperties().apiVersion < VK_API_VERSION_1_1) return false;
    const auto properties = candidate.enumerateDeviceExtensionProperties();
    for (const auto extension : featureManager->GetExtensions(DEVICE)) {
        bool found = false;
        for (const auto &property : properties) {
            if (strcmp(property.extensionName, extension) == 0) found = true;
        }
        if (!found) return false;
    }
    bool graphicsQueue = false;
    for (const auto &family : candidate.getQueueFamilyProperties()) {
        if (family.queueFlags & vk::QueueFlagBits::eGraphics) graphicsQueue = true;
    }
    if (!graphicsQueue) return false;
    vk::PhysicalDeviceSamplerYcbcrConversionFeatures conversion;
    vk::PhysicalDeviceFeatures2 features;
    features.setPNext(&conversion);
    candidate.getFeatures2(&features);
    return conversion.samplerYcbcrConversion;
}

bool Kernel::HasFeatures() {
    DispatcherScope dispatchScope(dispatcher);
    std::lock_guard<std::recursive_mutex> guard(VulkanLock());
    if (!instance) return false;
    for (const auto candidate : instance->enumeratePhysicalDevices()) {
        if (Supports(candidate)) return true;
    }
    return false;
}

std::vector<char> Kernel::ReadFile(const std::string &fileName) {
    DispatcherScope dispatchScope(dispatcher);
    if (assetManager == nullptr) {
        throw std::runtime_error("java.lang.IllegalStateException");
    }

    AAsset *file = AAssetManager_open(assetManager, fileName.c_str(), AASSET_MODE_BUFFER);
    if (file == nullptr) {
        if (fileName.find("vert") != std::string::npos) {
            file = AAssetManager_open(assetManager, "shaders/default.vert.spv", AASSET_MODE_BUFFER);
        } else if (fileName.find("frag") != std::string::npos) {
            file = AAssetManager_open(assetManager, "shaders/default.frag.spv", AASSET_MODE_BUFFER);
        } else {
            throw std::runtime_error("java.io.FileNotFoundException");
        }
    }

    const auto length = AAsset_getLength(file);
    std::vector<char> contents(length);
    AAsset_read(file, static_cast<void *>(contents.data()), length);
    AAsset_close(file);
    return contents;
}

void Kernel::OnOrientationChange() {
    DispatcherScope dispatchScope(dispatcher);
    device->waitIdle();
    if (swapChain.SetUp(*this, true)) {
        queue.SetImagesCount(*this, swapChain.GetImagesCount());
        commandBuffer.Reset(*this);
    }
}

vk::SurfaceCapabilitiesKHR Kernel::GetSurfaceCapabilities() {
    DispatcherScope dispatchScope(dispatcher);
    return physicalDevice.getSurfaceCapabilitiesKHR(surface.get());
}

vk::SurfaceFormatKHR Kernel::GetSurfaceFormat() {
    DispatcherScope dispatchScope(dispatcher);
    const auto formats = physicalDevice.getSurfaceFormatsKHR(surface.get());
    for (const auto &format: formats) {
        if (format.format == vk::Format::eR8G8B8A8Unorm) {
            return format;
        }
    }
    throw std::runtime_error("failed to find suitable surface format!");
}

void Kernel::ReadPixels(void *buffer) {
    DispatcherScope dispatchScope(dispatcher);
    if (nativeWindow == nullptr) {
        throw std::runtime_error("java.lang.IllegalStateException");
    }

    auto imageExtent = swapChain.GetImageExtent();
    auto srcImage = swapChain.GetImage(queue.GetCurrentFrame());
    auto dstImage = device->createImageUnique(
            vk::ImageCreateInfo()
                    .setExtent({imageExtent.width, imageExtent.height, 1})
                    .setImageType(vk::ImageType::e2D)
                    .setArrayLayers(1)
                    .setMipLevels(1)
                    .setFormat(vk::Format::eR8G8B8A8Unorm)
                    .setInitialLayout(vk::ImageLayout::eUndefined)
                    .setSamples(vk::SampleCountFlagBits::e1)
                    .setTiling(vk::ImageTiling::eLinear)
                    .setUsage(
                            vk::ImageUsageFlagBits::eTransferDst)
    );
    auto memoryRequirements = device->getImageMemoryRequirements(dstImage.get());
    auto dstImageMemory = device->allocateMemory(
            vk::MemoryAllocateInfo()
                    .setMemoryTypeIndex(FindMemoryType(memoryRequirements.memoryTypeBits,
                                                       vk::MemoryPropertyFlagBits::eHostVisible |
                                                       vk::MemoryPropertyFlagBits::eHostCoherent))
                    .setAllocationSize(memoryRequirements.size)
    );
    device->bindImageMemory(dstImage.get(), dstImageMemory, 0);

    Submit([&dstImage, &srcImage, &imageExtent](vk::CommandBuffer commandBuffer) {
        commandBuffer.pipelineBarrier(
                vk::PipelineStageFlagBits::eTransfer,
                vk::PipelineStageFlagBits::eTransfer,
                vk::DependencyFlags(),
                nullptr,
                nullptr,
                Util::CreateImageMemoryBarrier(
                        vk::ImageLayout::eUndefined,
                        vk::ImageLayout::eTransferDstOptimal)
                        .setImage(dstImage.get())
                        .setSubresourceRange({vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1})
        );

        commandBuffer.pipelineBarrier(
                vk::PipelineStageFlagBits::eTransfer,
                vk::PipelineStageFlagBits::eTransfer,
                vk::DependencyFlags(),
                nullptr,
                nullptr,
                Util::CreateImageMemoryBarrier(
                        vk::ImageLayout::ePresentSrcKHR,
                        vk::ImageLayout::eTransferSrcOptimal)
                        .setImage(srcImage)
                        .setSubresourceRange({vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1})
        );

        auto subresource = vk::ImageSubresourceLayers()
                .setAspectMask(vk::ImageAspectFlagBits::eColor)
                .setMipLevel(0)
                .setBaseArrayLayer(0)
                .setLayerCount(1);

        commandBuffer.copyImage(
                srcImage,
                vk::ImageLayout::eTransferSrcOptimal,
                dstImage.get(),
                vk::ImageLayout::eTransferDstOptimal,
                vk::ImageCopy()
                        .setExtent(
                                {imageExtent.width, imageExtent.height, 1})
                        .setDstSubresource(subresource)
                        .setDstOffset({0, 0, 0})
                        .setSrcSubresource(subresource)
                        .setSrcOffset({0, 0, 0})
        );

        commandBuffer.pipelineBarrier(
                vk::PipelineStageFlagBits::eTransfer,
                vk::PipelineStageFlagBits::eTransfer,
                vk::DependencyFlags(),
                nullptr,
                nullptr,
                Util::CreateImageMemoryBarrier(
                        vk::ImageLayout::eTransferDstOptimal,
                        vk::ImageLayout::eUndefined)
                        .setImage(dstImage.get())
                        .setSubresourceRange({vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1})
        );

        commandBuffer.pipelineBarrier(
                vk::PipelineStageFlagBits::eTransfer,
                vk::PipelineStageFlagBits::eTransfer,
                vk::DependencyFlags(),
                nullptr,
                nullptr,
                Util::CreateImageMemoryBarrier(
                        vk::ImageLayout::eTransferSrcOptimal,
                        vk::ImageLayout::ePresentSrcKHR)
                        .setImage(srcImage)
                        .setSubresourceRange({vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1})
        );
    });

    const char *data;
    device->mapMemory(dstImageMemory, 0, memoryRequirements.size, vk::MemoryMapFlagBits(),
                      (void **) &data);

    auto imageSubresource = vk::SubresourceLayout();
    auto imageSubresourceLayout = vk::ImageSubresource().setAspectMask(
            vk::ImageAspectFlagBits::eColor);
    device->getImageSubresourceLayout(dstImage.get(), &imageSubresourceLayout, &imageSubresource);
    data += imageSubresource.offset;

    for (uint32_t y = 0; y < imageExtent.height; ++y) {
        auto *dst = reinterpret_cast<unsigned char *> ((char *) buffer + imageExtent.width * 4 * y);
        auto *src = reinterpret_cast<unsigned char *> ((char *) data +
                                                       imageSubresource.rowPitch * y);
        memcpy(dst, src, 4 * imageExtent.width);
    }

    device->unmapMemory(dstImageMemory);
    device->free(dstImageMemory);
}

vk::ShaderModule Kernel::LoadShaderStrict(const std::string &fileName) {
    DispatcherScope scope(dispatcher);
    if (!assetManager) throw std::runtime_error("No shader asset manager");
    AAsset *asset = AAssetManager_open(assetManager, fileName.c_str(), AASSET_MODE_BUFFER);
    if (!asset) throw std::runtime_error("Missing Vulkan shader asset: " + fileName);
    const auto length = AAsset_getLength(asset);
    std::vector<uint32_t> code((length + 3) / 4);
    const auto read = AAsset_read(asset, code.data(), length);
    AAsset_close(asset);
    if (read != length || length < 20 || length % 4 != 0 || code[0] != 0x07230203)
        throw std::runtime_error("Invalid SPIR-V shader asset: " + fileName);
    std::string diagnostic;
    if (!ValidateSpirv(code, diagnostic))
        throw std::runtime_error("Invalid SPIR-V shader asset: " + fileName + " (" + diagnostic + ")");
    return device->createShaderModule(vk::ShaderModuleCreateInfo().setCode(code));
}
