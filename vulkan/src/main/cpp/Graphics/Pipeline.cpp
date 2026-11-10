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

    vk::PushConstantRange pushConstantRange;
    pushConstantRange.setOffset(0)
        .setStageFlags(vk::ShaderStageFlagBits::eVertex)
        .setSize(sizeof(Graphics::PushConstants));
    pipelineLayout =
        kernel.device->createPipelineLayoutUnique(vk::PipelineLayoutCreateInfo()
                                                      .setSetLayoutCount(descriptorSetLayouts.size())
                                                      .setSetLayouts(descriptorSetLayouts)
                                                      .setPushConstantRangeCount(1)
                                                      .setPPushConstantRanges(&pushConstantRange));

    std::vector<vk::DescriptorBufferInfo> bufferInfo = videoEffect->GetDescriptorBufferInfo();
    if (!bufferInfo.empty()) {
        kernel.device->updateDescriptorSets(vk::WriteDescriptorSet()
                                                .setDstSet(descriptorSets[1])
                                                .setDescriptorCount(1)
                                                .setDescriptorType(vk::DescriptorType::eUniformBuffer)
                                                .setBufferInfo(bufferInfo),
                                            nullptr);
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

    vk::PipelineVertexInputStateCreateInfo pipelineVertexInputStateCreateInfo;
    pipelineVertexInputStateCreateInfo.setVertexBindingDescriptionCount(1)
        .setPVertexBindingDescriptions(&bindingDescription)
        .setVertexAttributeDescriptions(vertexInputAttributeDescriptions);
    vk::PipelineInputAssemblyStateCreateInfo pipelineInputAssemblyStateCreateInfo;
    pipelineInputAssemblyStateCreateInfo.setTopology(vk::PrimitiveTopology::eTriangleStrip)
        .setPrimitiveRestartEnable(false);
    const auto viewport = vk::Viewport(0, 0, imageExtent.width, imageExtent.height, 0.0f, 1.0f);
    const auto scissor = vk::Rect2D(vk::Offset2D(0, 0), imageExtent);
    vk::PipelineViewportStateCreateInfo pipelineViewportStateCreateInfo;
    pipelineViewportStateCreateInfo.setViewportCount(1).setViewports(viewport).setScissorCount(1).setScissors(
        scissor);
    vk::PipelineRasterizationStateCreateInfo pipelineRasterizationStateCreateInfo;
    pipelineRasterizationStateCreateInfo.setDepthClampEnable(false)
        .setRasterizerDiscardEnable(false)
        .setPolygonMode(vk::PolygonMode::eFill)
        .setCullMode(vk::CullModeFlagBits::eNone)
        .setFrontFace(vk::FrontFace::eClockwise)
        .setDepthBiasEnable(false)
        .setLineWidth(1);
    vk::PipelineMultisampleStateCreateInfo pipelineMultisampleStateCreateInfo;
    pipelineMultisampleStateCreateInfo.setRasterizationSamples(vk::SampleCountFlagBits::e1)
        .setSampleShadingEnable(false)
        .setAlphaToCoverageEnable(false)
        .setAlphaToCoverageEnable(false);
    vk::PipelineColorBlendStateCreateInfo pipelineColorBlendStateCreateInfo;
    pipelineColorBlendStateCreateInfo.setLogicOp(vk::LogicOp::eCopy)
        .setAttachmentCount(1)
        .setAttachments(colorBlendAttachmentStates);
    vk::PipelineDynamicStateCreateInfo pipelineDynamicStateCreateInfo;
    pipelineDynamicStateCreateInfo.setDynamicStates(dynamicStates);
    pipeline = kernel.device
                   ->createGraphicsPipelineUnique(
                       pipelineCache.get(), vk::GraphicsPipelineCreateInfo()
                                                .setStageCount(shaderStages.size())
                                                .setStages(shaderStages)
                                                .setPVertexInputState(&pipelineVertexInputStateCreateInfo)
                                                .setPInputAssemblyState(&pipelineInputAssemblyStateCreateInfo)
                                                .setPViewportState(&pipelineViewportStateCreateInfo)
                                                .setPRasterizationState(&pipelineRasterizationStateCreateInfo)
                                                .setPMultisampleState(&pipelineMultisampleStateCreateInfo)
                                                .setPColorBlendState(&pipelineColorBlendStateCreateInfo)
                                                .setPDynamicState(&pipelineDynamicStateCreateInfo)
                                                .setLayout(pipelineLayout.get())
                                                .setRenderPass(kernel.swapChain.renderPass.get()))
                   .value;

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