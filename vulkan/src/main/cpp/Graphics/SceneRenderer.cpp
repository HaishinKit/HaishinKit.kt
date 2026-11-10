#include "ImageStorage.h"
#include "Kernel.h"
#include "VulkanLock.h"
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window_jni.h>
#include <array>
#include <glm/gtc/type_ptr.hpp>
#include <media/NdkImageReader.h>
#include <memory>

namespace {
struct Constants {
    glm::mat4 matrix{1.f};
    glm::vec4 textureRect{0.f, 0.f, 1.f, 1.f};
    std::array<float, 12> parameters{};
};
static_assert(sizeof(Constants) == 128);

// Resource members are ordered so image views, images and memory are destroyed in that order.
struct Target {
    vk::UniqueDeviceMemory memory;
    vk::UniqueImage image;
    vk::UniqueImageView view;
    vk::UniqueRenderPass pass;
    vk::UniqueFramebuffer framebuffer;
    vk::Extent2D extent;
};

struct Program {
    vk::UniqueDescriptorSetLayout setLayout;
    vk::UniqueDescriptorPool pool;
    vk::DescriptorSet descriptor;
    vk::UniquePipelineLayout layout;
    vk::UniquePipeline pipeline;
    std::string vertex, fragment;

    void create(Graphics::Kernel &k, vk::RenderPass pass, vk::Sampler sampler, const std::string &vert,
                const std::string &frag, bool blend) {
        vertex = vert;
        fragment = frag;
        auto binding = vk::DescriptorSetLayoutBinding(0, vk::DescriptorType::eCombinedImageSampler, 1,
                                                      vk::ShaderStageFlagBits::eFragment, &sampler);
        setLayout = k.device->createDescriptorSetLayoutUnique(
            vk::DescriptorSetLayoutCreateInfo().setBindings(binding));
        // External YCbCr conversions can consume multiple descriptors per combined sampler.
        auto poolSize = vk::DescriptorPoolSize(vk::DescriptorType::eCombinedImageSampler, 32);
        pool = k.device->createDescriptorPoolUnique(
            vk::DescriptorPoolCreateInfo().setMaxSets(1).setPoolSizes(poolSize));
        descriptor = k.device->allocateDescriptorSets(
            vk::DescriptorSetAllocateInfo().setDescriptorPool(*pool).setSetLayouts(*setLayout))[0];
        auto stages = vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment;
        auto range = vk::PushConstantRange(stages, 0, sizeof(Constants));
        layout = k.device->createPipelineLayoutUnique(
            vk::PipelineLayoutCreateInfo().setSetLayouts(*setLayout).setPushConstantRanges(range));
        vk::UniqueShaderModule v(k.LoadShaderStrict(vert), {*k.device});
        vk::UniqueShaderModule f(k.LoadShaderStrict(frag), {*k.device});
        std::array<vk::PipelineShaderStageCreateInfo, 2> shaders = {
            vk::PipelineShaderStageCreateInfo({}, vk::ShaderStageFlagBits::eVertex, *v, "main"),
            vk::PipelineShaderStageCreateInfo({}, vk::ShaderStageFlagBits::eFragment, *f, "main")};
        vk::PipelineVertexInputStateCreateInfo input;
        auto assembly =
            vk::PipelineInputAssemblyStateCreateInfo().setTopology(vk::PrimitiveTopology::eTriangleStrip);
        auto viewport = vk::PipelineViewportStateCreateInfo().setViewportCount(1).setScissorCount(1);
        auto raster = vk::PipelineRasterizationStateCreateInfo()
                          .setPolygonMode(vk::PolygonMode::eFill)
                          .setCullMode(vk::CullModeFlagBits::eNone)
                          .setLineWidth(1.f);
        auto samples =
            vk::PipelineMultisampleStateCreateInfo().setRasterizationSamples(vk::SampleCountFlagBits::e1);
        auto attachment =
            vk::PipelineColorBlendAttachmentState()
                .setBlendEnable(blend)
                .setSrcColorBlendFactor(vk::BlendFactor::eOne)
                .setDstColorBlendFactor(vk::BlendFactor::eOneMinusSrcAlpha)
                .setColorBlendOp(vk::BlendOp::eAdd)
                .setSrcAlphaBlendFactor(vk::BlendFactor::eOne)
                .setDstAlphaBlendFactor(vk::BlendFactor::eOneMinusSrcAlpha)
                .setAlphaBlendOp(vk::BlendOp::eAdd)
                .setColorWriteMask(vk::ColorComponentFlagBits::eR | vk::ColorComponentFlagBits::eG |
                                   vk::ColorComponentFlagBits::eB | vk::ColorComponentFlagBits::eA);
        auto color = vk::PipelineColorBlendStateCreateInfo().setAttachments(attachment);
        std::array<vk::DynamicState, 2> states{vk::DynamicState::eViewport, vk::DynamicState::eScissor};
        auto dynamic = vk::PipelineDynamicStateCreateInfo().setDynamicStates(states);
        pipeline = k.device
                       ->createGraphicsPipelineUnique({}, vk::GraphicsPipelineCreateInfo()
                                                              .setStages(shaders)
                                                              .setPVertexInputState(&input)
                                                              .setPInputAssemblyState(&assembly)
                                                              .setPViewportState(&viewport)
                                                              .setPRasterizationState(&raster)
                                                              .setPMultisampleState(&samples)
                                                              .setPColorBlendState(&color)
                                                              .setPDynamicState(&dynamic)
                                                              .setLayout(*layout)
                                                              .setRenderPass(pass))
                       .value;
    }
    void bind(Graphics::Kernel &k, vk::CommandBuffer command, vk::DescriptorImageInfo image,
              const Constants &constants) {
        k.device->updateDescriptorSets(vk::WriteDescriptorSet()
                                           .setDstSet(descriptor)
                                           .setDstBinding(0)
                                           .setDescriptorType(vk::DescriptorType::eCombinedImageSampler)
                                           .setImageInfo(image),
                                       nullptr);
        command.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline);
        command.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout, 0, descriptor, nullptr);
        command.pushConstants(*layout, vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment,
                              0, sizeof(Constants), &constants);
        command.draw(4, 1, 0, 0);
    }
};

struct Layer {
    AHardwareBuffer *bitmapBuffer = nullptr;
    int generation = 0;
    vk::Format format = vk::Format::eUndefined;
    uint64_t externalFormat = 0;
    vk::UniqueSamplerYcbcrConversion conversion;
    vk::UniqueSampler sampler;
    std::unique_ptr<Graphics::ImageStorage> storage;
    std::unique_ptr<Program> program;
    Constants constants;
    vk::Viewport viewport;
    vk::Rect2D scissor;
    bool active = false;
    ~Layer() {
        if (bitmapBuffer)
            AHardwareBuffer_release(bitmapBuffer);
    }
};

vk::ClearValue clearColor(jint argb) {
    const float alpha = ((static_cast<uint32_t>(argb) >> 24) & 255) / 255.f;
    return vk::ClearValue(vk::ClearColorValue(std::array<float, 4>{((argb >> 16) & 255) / 255.f * alpha,
                                                                   ((argb >> 8) & 255) / 255.f * alpha,
                                                                   (argb & 255) / 255.f * alpha, alpha}));
}

class SceneRenderer {
  public:
    Graphics::Kernel kernel;
    std::unique_ptr<Target> target;
    vk::UniqueSampler linear;
    std::vector<std::unique_ptr<Layer>> layers;
    std::unique_ptr<Program> output;
    bool recreate = false;
    size_t cursor = 0;
    jint background = 0;

    ~SceneRenderer() {
        Graphics::DispatcherScope scope(kernel.dispatcher);
        std::lock_guard<std::recursive_mutex> lock(VulkanLock());
        if (kernel.device)
            Graphics::WaitIdleForDisposal(*kernel.device, kernel.dispatcher);
        output.reset();
        layers.clear();
        target.reset();
        linear.reset();
    }
    void initialize(AAssetManager *assets) {
        kernel.SetAssetManager(assets);
        kernel.SelectPhysicalDevice();
        kernel.commandBuffer.commandPool = kernel.device->createCommandPoolUnique(
            vk::CommandPoolCreateInfo()
                .setFlags(vk::CommandPoolCreateFlagBits::eResetCommandBuffer)
                .setQueueFamilyIndex(kernel.queue.queueFamilyIndex));
        linear = kernel.device->createSamplerUnique(vk::SamplerCreateInfo()
                                                        .setMagFilter(vk::Filter::eLinear)
                                                        .setMinFilter(vk::Filter::eLinear)
                                                        .setAddressModeU(vk::SamplerAddressMode::eClampToEdge)
                                                        .setAddressModeV(vk::SamplerAddressMode::eClampToEdge)
                                                        .setAddressModeW(vk::SamplerAddressMode::eClampToEdge)
                                                        .setMaxLod(0.f));
    }
    void surface(ANativeWindow *window, int width, int height) {
        kernel.device->waitIdle();
        output.reset();
        kernel.commandBuffer.commandBuffers.clear();
        for (auto f : kernel.commandBuffer.framebuffers)
            kernel.device->destroy(f);
        kernel.commandBuffer.framebuffers.clear();
        kernel.SetImageExtent(width, height);
        kernel.SetNativeWindow(window);
        if (window)
            kernel.commandBuffer.Reset(kernel);
    }
    void begin(int width, int height, jint color) {
        if (width <= 0 || height <= 0)
            throw std::runtime_error("Invalid Vulkan composition extent");
        kernel.device->waitIdle();
        background = color;
        cursor = 0;
        for (auto &l : layers)
            l->active = false;
        if (target && target->extent == vk::Extent2D(width, height))
            return;
        layers.clear();
        output.reset();
        target.reset();
        auto nextTarget = std::make_unique<Target>();
        auto &t = *nextTarget;
        t.extent = vk::Extent2D(static_cast<uint32_t>(width), static_cast<uint32_t>(height));
        t.image = kernel.device->createImageUnique(vk::ImageCreateInfo()
                                                       .setImageType(vk::ImageType::e2D)
                                                       .setFormat(vk::Format::eR8G8B8A8Unorm)
                                                       .setExtent({t.extent.width, t.extent.height, 1})
                                                       .setMipLevels(1)
                                                       .setArrayLayers(1)
                                                       .setSamples(vk::SampleCountFlagBits::e1)
                                                       .setTiling(vk::ImageTiling::eOptimal)
                                                       .setUsage(vk::ImageUsageFlagBits::eColorAttachment |
                                                                 vk::ImageUsageFlagBits::eSampled |
                                                                 vk::ImageUsageFlagBits::eTransferSrc));
        auto requirements = kernel.device->getImageMemoryRequirements(*t.image);
        t.memory = kernel.device->allocateMemoryUnique(vk::MemoryAllocateInfo(
            requirements.size,
            kernel.FindMemoryType(requirements.memoryTypeBits, vk::MemoryPropertyFlagBits::eDeviceLocal)));
        kernel.device->bindImageMemory(*t.image, *t.memory, 0);
        t.view = kernel.CreateImageView(*t.image, vk::Format::eR8G8B8A8Unorm);
        auto attachment = vk::AttachmentDescription()
                              .setFormat(vk::Format::eR8G8B8A8Unorm)
                              .setSamples(vk::SampleCountFlagBits::e1)
                              .setLoadOp(vk::AttachmentLoadOp::eClear)
                              .setStoreOp(vk::AttachmentStoreOp::eStore)
                              .setInitialLayout(vk::ImageLayout::eUndefined)
                              .setFinalLayout(vk::ImageLayout::eShaderReadOnlyOptimal);
        auto reference = vk::AttachmentReference(0, vk::ImageLayout::eColorAttachmentOptimal);
        auto subpass = vk::SubpassDescription()
                           .setPipelineBindPoint(vk::PipelineBindPoint::eGraphics)
                           .setColorAttachments(reference);
        std::array<vk::SubpassDependency, 2> dependencies{
            vk::SubpassDependency(VK_SUBPASS_EXTERNAL, 0, vk::PipelineStageFlagBits::eFragmentShader,
                                  vk::PipelineStageFlagBits::eColorAttachmentOutput,
                                  vk::AccessFlagBits::eShaderRead, vk::AccessFlagBits::eColorAttachmentWrite),
            vk::SubpassDependency(0, VK_SUBPASS_EXTERNAL, vk::PipelineStageFlagBits::eColorAttachmentOutput,
                                  vk::PipelineStageFlagBits::eFragmentShader,
                                  vk::AccessFlagBits::eColorAttachmentWrite,
                                  vk::AccessFlagBits::eShaderRead)};
        t.pass = kernel.device->createRenderPassUnique(vk::RenderPassCreateInfo()
                                                           .setAttachments(attachment)
                                                           .setSubpasses(subpass)
                                                           .setDependencies(dependencies));
        t.framebuffer = kernel.device->createFramebufferUnique(vk::FramebufferCreateInfo()
                                                                   .setRenderPass(*t.pass)
                                                                   .setAttachments(*t.view)
                                                                   .setWidth(t.extent.width)
                                                                   .setHeight(t.extent.height)
                                                                   .setLayers(1));
        target = std::move(nextTarget);
    }
    void add(JNIEnv *env, jobject bitmap, AHardwareBuffer *buffer, const std::array<int, 8> &bounds,
             const Constants &constants, const std::string &vertex, const std::string &fragment) {
        if (!target)
            throw std::runtime_error("begin must precede layer");
        if (cursor == layers.size())
            layers.push_back(std::make_unique<Layer>());
        auto &l = *layers[cursor++];
        if (bitmap) {
            auto cls = env->GetObjectClass(bitmap);
            int generation = env->CallIntMethod(bitmap, env->GetMethodID(cls, "getGenerationId", "()I"));
            env->DeleteLocalRef(cls);
            if (!l.bitmapBuffer || generation != l.generation) {
                AndroidBitmapInfo info{};
                if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
                    info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
                    throw std::runtime_error("Vulkan requires a software ARGB_8888 bitmap");
                AHardwareBuffer_Desc desc{};
                desc.width = info.width;
                desc.height = info.height;
                desc.layers = 1;
                desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
                desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
                AHardwareBuffer *next = nullptr;
                if (AHardwareBuffer_allocate(&desc, &next) != 0)
                    throw std::runtime_error("Bitmap hardware buffer allocation failed");
                void *pixels = nullptr, *mapped = nullptr;
                if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
                    AHardwareBuffer_release(next);
                    throw std::runtime_error("Bitmap lock failed");
                }
                int result =
                    AHardwareBuffer_lock(next, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &mapped);
                if (result == 0) {
                    AHardwareBuffer_describe(next, &desc);
                    for (uint32_t y = 0; y < info.height; ++y)
                        memcpy(static_cast<char *>(mapped) + y * desc.stride * 4,
                               static_cast<char *>(pixels) + y * info.stride, info.width * 4);
                    AHardwareBuffer_unlock(next, nullptr);
                }
                AndroidBitmap_unlockPixels(env, bitmap);
                if (result != 0) {
                    AHardwareBuffer_release(next);
                    throw std::runtime_error("Hardware buffer lock failed");
                }
                if (l.bitmapBuffer)
                    AHardwareBuffer_release(l.bitmapBuffer);
                l.bitmapBuffer = next;
                l.generation = generation;
            }
            buffer = l.bitmapBuffer;
        }
        if (!buffer)
            return;
        vk::AndroidHardwareBufferFormatPropertiesANDROID format;
        vk::AndroidHardwareBufferPropertiesANDROID properties;
        properties.pNext = &format;
        if (kernel.device->getAndroidHardwareBufferPropertiesANDROID(buffer, &properties) !=
            vk::Result::eSuccess)
            throw std::runtime_error("Unable to query input hardware buffer");
        // Video may have a multiplanar VkFormat or an opaque format. Use its external
        // conversion in either case so PRIVATE YUV never reaches an ordinary RGB sampler.
        const auto imageFormat = bitmap ? format.format : vk::Format::eUndefined;
        uint64_t external = bitmap ? 0 : format.externalFormat;

        if (!l.sampler || l.format != imageFormat || l.externalFormat != external) {
            l.program.reset();
            // Image views must die before their immutable sampler conversion.
            l.storage = std::make_unique<Graphics::ImageStorage>();
            l.sampler.reset();
            l.conversion.reset();
            l.format = imageFormat;
            l.externalFormat = external;
            auto filter = vk::Filter::eLinear;
            if (external && (!(format.formatFeatures &
                               vk::FormatFeatureFlagBits::eSampledImageYcbcrConversionLinearFilter) ||
                             !(format.formatFeatures & vk::FormatFeatureFlagBits::eSampledImageFilterLinear)))

                filter = vk::Filter::eNearest;
            vk::ExternalFormatANDROID externalInfo(external);
            if (external)
                l.conversion = kernel.device->createSamplerYcbcrConversionUnique(
                    vk::SamplerYcbcrConversionCreateInfo()
                        .setPNext(&externalInfo)
                        .setFormat(imageFormat)
                        .setYcbcrModel(format.suggestedYcbcrModel)
                        .setYcbcrRange(format.suggestedYcbcrRange)
                        .setComponents(format.samplerYcbcrConversionComponents)
                        .setXChromaOffset(format.suggestedXChromaOffset)
                        .setYChromaOffset(format.suggestedYChromaOffset)
                        .setChromaFilter(filter));
            vk::SamplerYcbcrConversionInfo conversionInfo(l.conversion.get());
            l.sampler =
                kernel.device->createSamplerUnique(vk::SamplerCreateInfo()
                                                       .setPNext(external ? &conversionInfo : nullptr)
                                                       .setMagFilter(filter)
                                                       .setMinFilter(filter)
                                                       .setAddressModeU(vk::SamplerAddressMode::eClampToEdge)
                                                       .setAddressModeV(vk::SamplerAddressMode::eClampToEdge)
                                                       .setAddressModeW(vk::SamplerAddressMode::eClampToEdge)
                                                       .setMaxLod(0.f));
            AHardwareBuffer_Desc desc{};
            AHardwareBuffer_describe(buffer, &desc);
            l.storage->extent = vk::Extent2D(desc.width, desc.height);
            l.storage->format = imageFormat;
            l.storage->SetExternalFormat(external);
            l.storage->SetUp(kernel, l.conversion);
        }
        l.storage->Update(kernel, buffer);
        l.active = true;
        if (!l.program || l.program->vertex != vertex || l.program->fragment != fragment) {
            auto nextProgram = std::make_unique<Program>();
            nextProgram->create(kernel, *target->pass, *l.sampler, vertex, fragment, true);
            l.program = std::move(nextProgram);
        }
        l.constants = constants;
        if (bounds[6] > bounds[4] && bounds[7] > bounds[5]) {
            AHardwareBuffer_Desc desc{};
            AHardwareBuffer_describe(buffer, &desc);
            l.constants.textureRect =
                glm::vec4(bounds[4] / float(desc.width), bounds[5] / float(desc.height),
                          bounds[6] / float(desc.width), bounds[7] / float(desc.height));
        }

        l.viewport = vk::Viewport(static_cast<float>(bounds[0]), static_cast<float>(bounds[1]),
                                  static_cast<float>(bounds[2]), static_cast<float>(bounds[3]), 0.f, 1.f);
        int left = std::max(bounds[0], 0), top = std::max(bounds[1], 0);
        int right = std::min(bounds[0] + bounds[2], static_cast<int>(target->extent.width));
        int bottom = std::min(bounds[1] + bounds[3], static_cast<int>(target->extent.height));
        l.scissor = vk::Rect2D({left, top}, {static_cast<uint32_t>(std::max(0, right - left)),
                                             static_cast<uint32_t>(std::max(0, bottom - top))});
        l.active = true;
    }
    void abort() {
        bool acquired = false;
        for (auto &l : layers)
            acquired |= l->active;
        if (!acquired)
            return;
        kernel.Submit([&](vk::CommandBuffer cmd) {
            for (auto &l : layers)
                if (l->active)
                    l->storage->Release(kernel, cmd);
        });
        for (auto &l : layers)
            l->active = false;
    }
    void end(int64_t timestamp, Constants constants, const std::string &vertex, const std::string &fragment,
             jint color) {
        auto &t = *target;
        kernel.Submit([&](vk::CommandBuffer cmd) {
            auto clear = clearColor(background);
            cmd.beginRenderPass(vk::RenderPassBeginInfo()
                                    .setRenderPass(*t.pass)
                                    .setFramebuffer(*t.framebuffer)
                                    .setRenderArea({{0, 0}, t.extent})
                                    .setClearValues(clear),
                                vk::SubpassContents::eInline);
            for (auto &l : layers)
                if (l->active && l->scissor.extent.width && l->scissor.extent.height) {
                    cmd.setViewport(0, l->viewport);
                    cmd.setScissor(0, l->scissor);
                    l->program->bind(kernel, cmd, l->storage->GetDescriptorImageInfo(), l->constants);
                }
            cmd.endRenderPass();
            for (auto &l : layers)
                if (l->active)
                    l->storage->Release(kernel, cmd);
        });
        for (auto &l : layers)
            l->active = false;
        layers.resize(cursor);
        if (!kernel.surface)
            return;
        if (recreate || kernel.swapChain.IsInvalidate()) {
            recreate = false;
            kernel.device->waitIdle();
            output.reset();
            kernel.commandBuffer.commandBuffers.clear();
            for (auto f : kernel.commandBuffer.framebuffers)
                kernel.device->destroy(f);
            kernel.commandBuffer.framebuffers.clear();
            kernel.swapChain.SetUp(kernel, true);
            kernel.queue.SetImagesCount(kernel, kernel.swapChain.GetImagesCount());
            kernel.commandBuffer.Reset(kernel);
        }
        if (!output || output->vertex != vertex || output->fragment != fragment) {
            auto nextProgram = std::make_unique<Program>();
            nextProgram->create(kernel, *kernel.swapChain.renderPass, *linear, vertex, fragment, false);
            output = std::move(nextProgram);
        }
        constants.matrix = kernel.swapChain.GetPreTransformMatrix() * constants.matrix;
        kernel.queue.presentationTime = timestamp;
        auto result = kernel.DrawFrame([&](uint32_t index) {
            auto cmd = kernel.commandBuffer.commandBuffers[index].get();
            cmd.reset();
            cmd.begin(vk::CommandBufferBeginInfo());
            auto extent = kernel.swapChain.GetImageExtent();
            auto clear = clearColor(color);
            cmd.beginRenderPass(vk::RenderPassBeginInfo()
                                    .setRenderPass(*kernel.swapChain.renderPass)
                                    .setFramebuffer(kernel.commandBuffer.framebuffers[index])
                                    .setRenderArea({{0, 0}, extent})
                                    .setClearValues(clear),
                                vk::SubpassContents::eInline);
            cmd.setViewport(0, vk::Viewport(0, 0, extent.width, extent.height, 0, 1));
            cmd.setScissor(0, vk::Rect2D({0, 0}, extent));
            output->bind(kernel, cmd,
                         vk::DescriptorImageInfo(*linear, *t.view, vk::ImageLayout::eShaderReadOnlyOptimal),
                         constants);
            cmd.endRenderPass();
            cmd.end();
        });
        kernel.device->waitIdle();
        if (result == vk::Result::eErrorOutOfDateKHR || result == vk::Result::eSuboptimalKHR) {
            // Retry with a rebuilt swapchain on the next frame.
            recreate = true;
        } else if (result != vk::Result::eSuccess && result != vk::Result::eTimeout &&
                   result != vk::Result::eNotReady) {
            throw std::runtime_error("Vulkan presentation failed");
        }
    }
    void read(JNIEnv *env, jobject bitmap) {
        auto &t = *target;
        vk::DeviceSize bytes = vk::DeviceSize(t.extent.width) * t.extent.height * 4;
        vk::UniqueDeviceMemory memory;
        auto buffer = kernel.device->createBufferUnique(
            vk::BufferCreateInfo().setSize(bytes).setUsage(vk::BufferUsageFlagBits::eTransferDst));
        auto requirements = kernel.device->getBufferMemoryRequirements(*buffer);
        memory = kernel.device->allocateMemoryUnique(vk::MemoryAllocateInfo(
            requirements.size, kernel.FindMemoryType(requirements.memoryTypeBits,
                                                     vk::MemoryPropertyFlagBits::eHostVisible |
                                                         vk::MemoryPropertyFlagBits::eHostCoherent)));
        kernel.device->bindBufferMemory(*buffer, *memory, 0);
        kernel.Submit([&](vk::CommandBuffer cmd) {
            auto barrier = vk::ImageMemoryBarrier()
                               .setImage(*t.image)
                               .setOldLayout(vk::ImageLayout::eShaderReadOnlyOptimal)
                               .setNewLayout(vk::ImageLayout::eTransferSrcOptimal)
                               .setSrcAccessMask(vk::AccessFlagBits::eShaderRead |
                                                 vk::AccessFlagBits::eColorAttachmentWrite)
                               .setDstAccessMask(vk::AccessFlagBits::eTransferRead)
                               .setSrcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                               .setDstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                               .setSubresourceRange({vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1});
            cmd.pipelineBarrier(vk::PipelineStageFlagBits::eFragmentShader |
                                    vk::PipelineStageFlagBits::eColorAttachmentOutput,
                                vk::PipelineStageFlagBits::eTransfer, {}, nullptr, nullptr, barrier);
            cmd.copyImageToBuffer(*t.image, vk::ImageLayout::eTransferSrcOptimal, *buffer,
                                  vk::BufferImageCopy()
                                      .setImageSubresource({vk::ImageAspectFlagBits::eColor, 0, 0, 1})
                                      .setImageExtent({t.extent.width, t.extent.height, 1}));
            auto hostBarrier = vk::BufferMemoryBarrier()
                                   .setBuffer(*buffer)
                                   .setOffset(0)
                                   .setSize(VK_WHOLE_SIZE)
                                   .setSrcAccessMask(vk::AccessFlagBits::eTransferWrite)
                                   .setDstAccessMask(vk::AccessFlagBits::eHostRead)
                                   .setSrcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                                   .setDstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            cmd.pipelineBarrier(vk::PipelineStageFlagBits::eTransfer, vk::PipelineStageFlagBits::eHost, {},
                                nullptr, hostBarrier, nullptr);
            barrier.setOldLayout(vk::ImageLayout::eTransferSrcOptimal)
                .setNewLayout(vk::ImageLayout::eShaderReadOnlyOptimal)
                .setSrcAccessMask(vk::AccessFlagBits::eTransferRead)
                .setDstAccessMask(vk::AccessFlagBits::eShaderRead);
            cmd.pipelineBarrier(vk::PipelineStageFlagBits::eTransfer,
                                vk::PipelineStageFlagBits::eFragmentShader, {}, nullptr, nullptr, barrier);
        });
        AndroidBitmapInfo info{};
        AndroidBitmap_getInfo(env, bitmap, &info);
        if (info.width != t.extent.width || info.height != t.extent.height ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
            throw std::runtime_error("Invalid readPixels bitmap");
        void *pixels = nullptr;
        auto mapped = static_cast<char *>(kernel.device->mapMemory(*memory, 0, bytes));
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            kernel.device->unmapMemory(*memory);
            throw std::runtime_error("Bitmap lock failed");
        }
        for (uint32_t y = 0; y < info.height; ++y)
            memcpy(static_cast<char *>(pixels) + y * info.stride, mapped + y * info.width * 4,
                   info.width * 4);
        kernel.device->unmapMemory(*memory);
        AndroidBitmap_unlockPixels(env, bitmap);
        // Vulkan memory must outlive the buffer bound to it.
        buffer.reset();
    }
};

jfieldID field(JNIEnv *env, jobject self) {
    auto cls = env->GetObjectClass(self);
    auto id = env->GetFieldID(cls, "memory", "J");
    env->DeleteLocalRef(cls);
    return id;
}
SceneRenderer &scene(JNIEnv *env, jobject self) {
    auto pointer = env->GetLongField(self, field(env, self));
    if (!pointer)
        throw std::runtime_error("Vulkan scene is closed");
    return *reinterpret_cast<SceneRenderer *>(pointer);
}
std::string string(JNIEnv *env, jobjectArray shaders, int index) {
    auto value = static_cast<jstring>(env->GetObjectArrayElement(shaders, index));
    auto chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    env->DeleteLocalRef(value);
    return result;
}
Constants constants(JNIEnv *env, jfloatArray matrix, jfloatArray params) {
    if (env->GetArrayLength(matrix) != 16 || env->GetArrayLength(params) > 12)
        throw std::runtime_error("Invalid Vulkan push constants");
    Constants result;
    env->GetFloatArrayRegion(matrix, 0, 16, glm::value_ptr(result.matrix));
    env->GetFloatArrayRegion(params, 0, env->GetArrayLength(params), result.parameters.data());
    return result;
}
template <typename F> void invoke(JNIEnv *env, jobject self, F action) {
    try {
        auto &s = scene(env, self);
        Graphics::DispatcherScope scope(s.kernel.dispatcher);
        std::lock_guard<std::recursive_mutex> lock(VulkanLock());
        try {
            action(s);
        } catch (...) {
            try {
                s.abort();
            } catch (...) {
            }
            throw;
        }

    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
    }
}
} // namespace

extern "C" {
JNIEXPORT void JNICALL Java_com_haishinkit_vulkan_NativeScene_nativeInitialize(JNIEnv *env, jobject self,
                                                                               jobject assets) {
    try {
        auto renderer = std::make_unique<SceneRenderer>();
        Graphics::DispatcherScope scope(renderer->kernel.dispatcher);
        std::lock_guard<std::recursive_mutex> lock(VulkanLock());
        renderer->initialize(AAssetManager_fromJava(env, assets));
        env->SetLongField(self, field(env, self), reinterpret_cast<jlong>(renderer.release()));
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
    }
}
JNIEXPORT void JNICALL Java_com_haishinkit_vulkan_NativeScene_setSurface(JNIEnv *env, jobject self,
                                                                         jobject surface, jint width,
                                                                         jint height) {
    invoke(env, self, [&](SceneRenderer &s) {
        s.surface(surface ? ANativeWindow_fromSurface(env, surface) : nullptr, width, height);
    });
}
JNIEXPORT void JNICALL Java_com_haishinkit_vulkan_NativeScene_begin(JNIEnv *env, jobject self, jint width,
                                                                    jint height, jint background) {
    invoke(env, self, [&](SceneRenderer &s) { s.begin(width, height, background); });
}
JNIEXPORT void JNICALL Java_com_haishinkit_vulkan_NativeScene_layer(JNIEnv *env, jobject self, jobject bitmap,
                                                                    jobject hardware, jintArray bounds,
                                                                    jfloatArray matrix, jobjectArray shaders,
                                                                    jfloatArray params) {
    invoke(env, self, [&](SceneRenderer &s) {
        std::array<int, 8> rect{};
        if (env->GetArrayLength(bounds) != 8)
            throw std::runtime_error("Invalid layer bounds");
        env->GetIntArrayRegion(bounds, 0, 8, rect.data());
        s.add(env, bitmap, hardware ? AHardwareBuffer_fromHardwareBuffer(env, hardware) : nullptr, rect,
              constants(env, matrix, params), string(env, shaders, 0), string(env, shaders, 1));
    });
}
JNIEXPORT void JNICALL Java_com_haishinkit_vulkan_NativeScene_end(JNIEnv *env, jobject self, jlong time,
                                                                  jfloatArray matrix, jobjectArray shaders,
                                                                  jfloatArray params, jint background) {
    invoke(env, self, [&](SceneRenderer &s) {
        s.end(time, constants(env, matrix, params), string(env, shaders, 0), string(env, shaders, 1),
              background);
    });
}
JNIEXPORT void JNICALL Java_com_haishinkit_vulkan_NativeScene_abort(JNIEnv *env, jobject self) {
    invoke(env, self, [&](SceneRenderer &s) { s.abort(); });
}
JNIEXPORT void JNICALL Java_com_haishinkit_vulkan_NativeScene_readPixels(JNIEnv *env, jobject self,
                                                                         jobject bitmap) {
    invoke(env, self, [&](SceneRenderer &s) { s.read(env, bitmap); });
}
JNIEXPORT void JNICALL Java_com_haishinkit_vulkan_NativeScene_nativeDispose(JNIEnv *env, jobject self) {
    auto pointer = env->GetLongField(self, field(env, self));
    env->SetLongField(self, field(env, self), 0);
    delete reinterpret_cast<SceneRenderer *>(pointer);
}
}

namespace {
struct InputReader {
    AImageReader *reader = nullptr;
    AImage *image = nullptr;
    ~InputReader() {
        if (image)
            AImage_delete(image);
        if (reader)
            AImageReader_delete(reader);
    }
};
} // namespace
extern "C" {
JNIEXPORT jobject JNICALL Java_com_haishinkit_vulkan_NativeInput_nativeInitialize(JNIEnv *env, jobject self,
                                                                                  jint width, jint height) {
    auto input = std::make_unique<InputReader>();
    if (AImageReader_newWithUsage(width, height, AIMAGE_FORMAT_PRIVATE,
                                  AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 4, &input->reader) != AMEDIA_OK) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "Failed to create Vulkan input reader");
        return nullptr;
    }
    ANativeWindow *window = nullptr;
    AImageReader_getWindow(input->reader, &window);
    auto surface = ANativeWindow_toSurface(env, window);
    if (surface)
        env->SetLongField(self, field(env, self), reinterpret_cast<jlong>(input.release()));
    return surface;
}
JNIEXPORT jobject JNICALL Java_com_haishinkit_vulkan_NativeInput_acquire(JNIEnv *env, jobject self) {
    auto input = reinterpret_cast<InputReader *>(env->GetLongField(self, field(env, self)));
    if (!input)
        return nullptr;
    AImage *next = nullptr;
    auto status = AImageReader_acquireLatestImage(input->reader, &next);
    if (status == AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE)
        return nullptr;
    if (status != AMEDIA_OK) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "Failed to acquire Vulkan input image");
        return nullptr;
    }
    AHardwareBuffer *buffer = nullptr;
    if (AImage_getHardwareBuffer(next, &buffer) != AMEDIA_OK) {
        AImage_delete(next);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "Failed to acquire input hardware buffer");
        return nullptr;
    }
    if (input->image)
        AImage_delete(input->image);
    input->image = next;
    return AHardwareBuffer_toHardwareBuffer(env, buffer);
}
JNIEXPORT jintArray JNICALL Java_com_haishinkit_vulkan_NativeInput_crop(JNIEnv *env, jobject self) {
    auto input = reinterpret_cast<InputReader *>(env->GetLongField(self, field(env, self)));
    AImageCropRect crop{};
    if (input && input->image)
        AImage_getCropRect(input->image, &crop);
    std::array<jint, 4> values{crop.left, crop.top, crop.right, crop.bottom};
    auto result = env->NewIntArray(4);
    env->SetIntArrayRegion(result, 0, 4, values.data());
    return result;
}
JNIEXPORT void JNICALL Java_com_haishinkit_vulkan_NativeInput_nativeDispose(JNIEnv *env, jobject self) {
    auto input = reinterpret_cast<InputReader *>(env->GetLongField(self, field(env, self)));
    env->SetLongField(self, field(env, self), 0);
    delete input;
}
}
