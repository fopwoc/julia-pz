# Shader layouts include array widths in their cache identities and in each descriptor write.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace([=[typedef struct VulkanShader
{]=] [=[typedef struct VulkanShader
{
    Uint32 juliaSamplerWidths[MAX_TEXTURE_SAMPLERS_PER_STAGE];]=] 1)
julia_replace([=[typedef struct DescriptorSetLayoutHashTableKey
{]=] [=[typedef struct DescriptorSetLayoutHashTableKey
{
    Uint32 juliaSamplerWidths[MAX_TEXTURE_SAMPLERS_PER_STAGE];]=] 1)
julia_replace([=[typedef struct DescriptorSetLayout
{]=] [=[typedef struct DescriptorSetLayout
{
    Uint32 juliaSamplerWidths[MAX_TEXTURE_SAMPLERS_PER_STAGE];]=] 1)
julia_replace([=[typedef struct GraphicsPipelineResourceLayoutHashTableKey
{]=] [=[typedef struct GraphicsPipelineResourceLayoutHashTableKey
{
    Uint32 juliaVertexSamplerWidths[MAX_TEXTURE_SAMPLERS_PER_STAGE];
    Uint32 juliaFragmentSamplerWidths[MAX_TEXTURE_SAMPLERS_PER_STAGE];]=] 1)
# Forward declarations follow the completed descriptor-layout type.
julia_replace([=[} DescriptorSetLayout;]=] [=[} DescriptorSetLayout;
static bool JULIA_VulkanReadSamplerWidths(VulkanRenderer *, const SDL_GPUShaderCreateInfo *, Uint32 *);
static Uint32 JULIA_VulkanSamplerBinding(const DescriptorSetLayout *, Uint32, Uint32 *);]=] 1)
julia_replace([=[    // Category 1: read resources
    Uint32 samplerCount,
    Uint32 storageTextureCount,]=] [=[    // Category 1: read resources
    Uint32 samplerCount,
    const Uint32 *juliaSamplerWidths,
    Uint32 storageTextureCount,]=] 1)
julia_replace([=[    key.shaderStage = shaderStage;]=] [=[    key.shaderStage = shaderStage;
    for (Uint32 i = 0; i < samplerCount; i++) key.juliaSamplerWidths[i] = juliaSamplerWidths ? juliaSamplerWidths[i] : 1;]=] 1)
julia_replace([=[    key.vertexSamplerCount = vertexShader->numSamplers;]=] [=[    SDL_memcpy(key.juliaVertexSamplerWidths, vertexShader->juliaSamplerWidths, sizeof(key.juliaVertexSamplerWidths));
    SDL_memcpy(key.juliaFragmentSamplerWidths, fragmentShader->juliaSamplerWidths, sizeof(key.juliaFragmentSamplerWidths));
    key.vertexSamplerCount = vertexShader->numSamplers;]=] 1)
julia_replace([=[        descriptorSetLayoutBindings[i].descriptorCount = 1;
        descriptorSetLayoutBindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;]=] [=[        descriptorSetLayoutBindings[i].descriptorCount = key.juliaSamplerWidths[i];
        descriptorSetLayoutBindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;]=] 1)
julia_replace([=[    VkResult vulkanResult = renderer->vkCreateDescriptorSetLayout(]=] [=[    Uint32 juliaBindingCount = 0;
    for (Uint32 i = 0; i < descriptorSetLayoutCreateInfo.bindingCount; i++) {
        if (i < samplerCount && key.juliaSamplerWidths[i] == 0) continue;
        descriptorSetLayoutBindings[juliaBindingCount++] = descriptorSetLayoutBindings[i];
    }
    descriptorSetLayoutCreateInfo.bindingCount = juliaBindingCount;
    VkResult vulkanResult = renderer->vkCreateDescriptorSetLayout(]=] 1)
julia_replace([=[    layout->samplerCount = samplerCount;]=] [=[    SDL_memcpy(layout->juliaSamplerWidths, key.juliaSamplerWidths, sizeof(layout->juliaSamplerWidths));
    layout->samplerCount = samplerCount;]=] 1)
foreach(stage VERTEX FRAGMENT)
    string(TOLOWER "${stage}" lower)
    julia_replace("        VK_SHADER_STAGE_${stage}_BIT,\n        ${lower}Shader->numSamplers," "        VK_SHADER_STAGE_${stage}_BIT,\n        ${lower}Shader->numSamplers,\n        ${lower}Shader->juliaSamplerWidths," 1)
    julia_replace("        VK_SHADER_STAGE_${stage}_BIT,\n        0," "        VK_SHADER_STAGE_${stage}_BIT,\n        0,\n        NULL," 1)
    julia_replace("            currentWriteDescriptorSet->dstArrayElement = 0;\n            currentWriteDescriptorSet->dstBinding = i;\n            currentWriteDescriptorSet->dstSet = commandBuffer->${lower}ResourceDescriptorSet;"
        "            currentWriteDescriptorSet->dstBinding = JULIA_VulkanSamplerBinding(descriptorSetLayout, i, &currentWriteDescriptorSet->dstArrayElement);\n            currentWriteDescriptorSet->dstSet = commandBuffer->${lower}ResourceDescriptorSet;" 1)
endforeach()
julia_replace([=[        VK_SHADER_STAGE_COMPUTE_BIT,
        createinfo->num_samplers,]=] [=[        VK_SHADER_STAGE_COMPUTE_BIT,
        createinfo->num_samplers,
        NULL,]=] 1)
julia_replace([=[        VK_SHADER_STAGE_COMPUTE_BIT,
        0,]=] [=[        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        NULL,]=] 2)
julia_replace([=[    vulkanShader = SDL_malloc(sizeof(VulkanShader));]=] [=[    vulkanShader = SDL_malloc(sizeof(VulkanShader));
    if (!vulkanShader) return NULL;
    if (!JULIA_VulkanReadSamplerWidths(renderer, createinfo, vulkanShader->juliaSamplerWidths)) {
        SDL_free(vulkanShader);
        return NULL;
    }]=] 1)
julia_replace([=[    result = result * hashFactor + hashTableKey->vertexSamplerCount;]=] [=[    for (Uint32 i = 0; i < MAX_TEXTURE_SAMPLERS_PER_STAGE; i++) {
        result = result * hashFactor + hashTableKey->juliaVertexSamplerWidths[i];
        result = result * hashFactor + hashTableKey->juliaFragmentSamplerWidths[i];
    }
    result = result * hashFactor + hashTableKey->vertexSamplerCount;]=] 1)
julia_replace([=[    result = result * hashFactor + hashTableKey->shaderStage;]=] [=[    for (Uint32 i = 0; i < MAX_TEXTURE_SAMPLERS_PER_STAGE; i++) result = result * hashFactor + hashTableKey->juliaSamplerWidths[i];
    result = result * hashFactor + hashTableKey->shaderStage;]=] 1)
file(WRITE "${backend}" "${source}\n#include \"gpu_sampler_arrays.c\"\n#include \"gpu_extensions.c\"\n")
