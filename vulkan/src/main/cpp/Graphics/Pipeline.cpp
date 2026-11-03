#include "Kernel.h"
#include "Pipeline.h"
#include "SwapChain.h"
#include "Texture.h"
#include "Vertex.hpp"
#include "PushConstants.hpp"

using namespace Graphics;

void Pipeline::SetUp(Kernel &kernel, std::vector<vk::Sampler> &samplers, VideoEffect *videoEffect) {
    descriptorSetLayouts.clear();
    descriptorSetLayouts.shrink_to_fit();

    descriptorSetLayouts.push_back(kernel.device->createDescriptorSetLayout(
            vk::DescriptorSetLayoutCreateInfo()
                    .setBindingCount(1)
                    .setBindings(
                            vk::DescriptorSetLayoutBinding()
                                    .setBinding(0)
                                    .setDescriptorType(
                                            vk::DescriptorType::eCombinedImageSampler)
                                    .setDescriptorCount(1)
                                    .setImmutableSamplers(samplers)
                                    .setStageFlags(vk::ShaderStageFlagBits::eFragment)
                    )
    ));

    std::vector<vk::DescriptorSetLayoutBinding> descriptorSetLayoutBinding = videoEffect->GetDescriptorSetLayoutBindings();
    if (!descriptorSetLayoutBinding.empty()) {
        descriptorSetLayouts.push_back(kernel.device->createDescriptorSetLayout(
                vk::DescriptorSetLayoutCreateInfo()
                        .setBindingCount(descriptorSetLayoutBinding.size())
                        .setBindings(descriptorSetLayoutBinding)
        ));
    }

    std::vector<vk::DescriptorPoolSize> descriptorPoolSizes;
    descriptorPoolSizes.push_back(
            vk::DescriptorPoolSize()
                    .setType(vk::DescriptorType::eCombinedImageSampler)
                    .setDescriptorCount(1)
    );

    if (!descriptorSetLayoutBinding.empty()) {
        descriptorPoolSizes.push_back(
                vk::DescriptorPoolSize()
                        .setType(vk::DescriptorType::eUniformBuffer)
                        .setDescriptorCount(descriptorSetLayoutBinding.size())
        );
    }

    descriptorPool = kernel.device->createDescriptorPoolUnique(
            vk::DescriptorPoolCreateInfo()
                    .setMaxSets(descriptorSetLayouts.size())
                    .setPoolSizes(descriptorPoolSizes)
    );

    descriptorSets = kernel.device->allocateDescriptorSets(
            vk::DescriptorSetAllocateInfo()
                    .setDescriptorSetCount(descriptorSetLayouts.size())
                    .setDescriptorPool(descriptorPool.get())
                    .setSetLayouts(descriptorSetLayouts)
    );

    pipelineLayout = kernel.device->createPipelineLayoutUnique(
            vk::PipelineLayoutCreateInfo()
                    .setSetLayoutCount(descriptorSetLayouts.size())
                    .setSetLayouts(descriptorSetLayouts)
                    .setPushConstantRangeCount(1)
                    .setPPushConstantRanges(&vk::PushConstantRange()
                            .setOffset(0)
                            .setStageFlags(vk::ShaderStageFlagBits::eVertex)
                            .setSize(sizeof(Graphics::PushConstants)))
    );

    std::vector<vk::DescriptorBufferInfo> bufferInfo = videoEffect->GetDescriptorBufferInfo();
    if (!bufferInfo.empty()) {
        kernel.device->updateDescriptorSets(
                vk::WriteDescriptorSet()
                        .setDstSet(descriptorSets[1])
                        .setDescriptorCount(1)
                        .setDescriptorType(vk::DescriptorType::eUniformBuffer)
                        .setBufferInfo(bufferInfo),
                nullptr
        );
    }

    pipelineCache = kernel.device->createPipelineCacheUnique(vk::PipelineCacheCreateInfo());

    const auto vert = kernel.LoadShader(videoEffect->GetShaderPath("vert"));
    const auto frag = kernel.LoadShader(videoEffect->GetShaderPath("frag"));

    // pipeline
    std::vector<vk::PipelineShaderStageCreateInfo> shaderStages = {
            vk::PipelineShaderStageCreateInfo()
                    .setStage(vk::ShaderStageFlagBits::eVertex)
                    .setModule(vert)
                    .setPName("main"),
            vk::PipelineShaderStageCreateInfo()
                    .setStage(vk::ShaderStageFlagBits::eFragment)
                    .setModule(frag)
                    .setPName("main")
    };

    std::vector<vk::VertexInputAttributeDescription> vertexInputAttributeDescriptions = Vertex::CreateAttributeDescriptions();

    std::vector<vk::PipelineColorBlendAttachmentState> colorBlendAttachmentStates = {
            vk::PipelineColorBlendAttachmentState()
                    .setBlendEnable(false)
                    .setColorWriteMask(
                            vk::ColorComponentFlags(
                                    vk::ColorComponentFlagBits::eR |
                                    vk::ColorComponentFlagBits::eG |
                                    vk::ColorComponentFlagBits::eB |
                                    vk::ColorComponentFlagBits::eA
                            )
                    )
    };

    std::vector<vk::DynamicState> dynamicStates = {
            vk::DynamicState::eViewport,
            vk::DynamicState::eScissor,
    };

    const auto bindingDescription = Vertex::CreateBindingDescription();
    vk::Extent2D imageExtent = kernel.swapChain.GetImageExtent();

    pipeline = kernel.device->createGraphicsPipelineUnique(
            pipelineCache.get(),
            vk::GraphicsPipelineCreateInfo()
                    .setStageCount(shaderStages.size())
                    .setStages(shaderStages)
                    .setPVertexInputState(&vk::PipelineVertexInputStateCreateInfo()
                            .setVertexBindingDescriptionCount(1)
                            .setPVertexBindingDescriptions(&bindingDescription)
                            .setVertexAttributeDescriptions(vertexInputAttributeDescriptions)
                    )
                    .setPInputAssemblyState(&vk::PipelineInputAssemblyStateCreateInfo()
                            .setTopology(vk::PrimitiveTopology::eTriangleStrip)
                            .setPrimitiveRestartEnable(false)
                    )
                    .setPViewportState(&vk::PipelineViewportStateCreateInfo()
                            .setViewportCount(1)
                            .setViewports(vk::Viewport()
                                                  .setX(0)
                                                  .setY(0)
                                                  .setWidth(imageExtent.width)
                                                  .setHeight(imageExtent.height)
                                                  .setMinDepth(0.0f)
                                                  .setMaxDepth(1.0f)
                            )
                            .setScissorCount(1)
                            .setScissors(
                                    vk::Rect2D()
                                            .setExtent(imageExtent)
                                            .setOffset(vk::Offset2D(0, 0))
                            )
                    )
                    .setPRasterizationState(&vk::PipelineRasterizationStateCreateInfo()
                            .setDepthClampEnable(false)
                            .setRasterizerDiscardEnable(false)
                            .setPolygonMode(vk::PolygonMode::eFill)
                            .setCullMode(vk::CullModeFlagBits::eNone)
                            .setFrontFace(vk::FrontFace::eClockwise)
                            .setDepthBiasEnable(false)
                            .setLineWidth(1)
                    )
                    .setPMultisampleState(&vk::PipelineMultisampleStateCreateInfo()
                            .setRasterizationSamples(vk::SampleCountFlagBits::e1)
                            .setSampleShadingEnable(false)
                            .setAlphaToCoverageEnable(false)
                            .setAlphaToCoverageEnable(false)
                    )
                    .setPColorBlendState(&vk::PipelineColorBlendStateCreateInfo()
                            .setLogicOp(vk::LogicOp::eCopy)
                            .setAttachmentCount(1)
                            .setAttachments(colorBlendAttachmentStates)
                    )
                    .setPDynamicState(&vk::PipelineDynamicStateCreateInfo()
                            .setDynamicStates(dynamicStates)
                    )
                    .setLayout(pipelineLayout.get())
                    .setRenderPass(kernel.swapChain.renderPass.get())
    ).value;

    kernel.device->destroy(vert);
    kernel.device->destroy(frag);
}

void Pipeline::TearDown(Kernel &kernel) {
    pipeline.reset();
    pipelineLayout.reset();
    descriptorPool.reset();
    for (auto layout : descriptorSetLayouts) kernel.device->destroy(layout);
    descriptorSetLayouts.clear();
}

void
Pipeline::UpdateDescriptorSets(Kernel &kernel, ImageStorage &storage, VideoEffect *videoEffect) {
    std::vector<vk::DescriptorImageInfo> images(1);
    for (auto i = 0; i < images.size(); ++i) {
        images[i] = storage.GetDescriptorImageInfo();
    }

    kernel.device->updateDescriptorSets(
            vk::WriteDescriptorSet()
                    .setDstSet(descriptorSets[0])
                    .setDescriptorCount(1)
                    .setDescriptorType(vk::DescriptorType::eCombinedImageSampler)
                    .setImageInfo(images),
            nullptr
    );
}