# Pinned SDL Metal sampler integration; private layouts remain inside SDL's own translation unit.
set(sampler_header "${sdl_SOURCE_DIR}/src/gpu/SDL_sysgpu.h")
file(READ "${sampler_header}" sampler_definitions)
string(FIND "${sampler_definitions}" "#define MAX_TEXTURE_SAMPLERS_PER_STAGE 16" old_capacity)
if(NOT old_capacity EQUAL -1)
    string(REPLACE "#define MAX_TEXTURE_SAMPLERS_PER_STAGE 16" "#define MAX_TEXTURE_SAMPLERS_PER_STAGE 32" sampler_definitions "${sampler_definitions}")
    file(WRITE "${sampler_header}" "${sampler_definitions}")
else()
    string(FIND "${sampler_definitions}" "#define MAX_TEXTURE_SAMPLERS_PER_STAGE 32" new_capacity)
    if(new_capacity EQUAL -1)
        message(FATAL_ERROR "Pinned SDL sampler capacity changed")
    endif()
endif()

# Compute retains the direct Metal limit. Only tagged graphics shaders use indirect samplers.
set(gpu_api "${sdl_SOURCE_DIR}/src/gpu/SDL_gpu.c")
file(READ "${gpu_api}" gpu_api_source)
string(REPLACE "MAX_TEXTURE_SAMPLERS_PER_STAGE == 16" "MAX_TEXTURE_SAMPLERS_PER_STAGE == 32" gpu_api_source "${gpu_api_source}")
string(REPLACE "Shader sampler count cannot be higher than 16!" "Shader sampler count cannot be higher than 32!" gpu_api_source "${gpu_api_source}")
string(REPLACE "createinfo->num_samplers > MAX_TEXTURE_SAMPLERS_PER_STAGE) {\n            SDL_COMPILE_TIME_ASSERT(compute_texture_samplers" "createinfo->num_samplers > 16) {\n            SDL_COMPILE_TIME_ASSERT(compute_texture_samplers" gpu_api_source "${gpu_api_source}")
file(WRITE "${gpu_api}" "${gpu_api_source}")

function(julia_sampler_replace before after)
    string(FIND "${source}" "${before}" anchor)
    if(anchor EQUAL -1)
        message(FATAL_ERROR "Pinned SDL sampler anchor changed: ${before}")
    endif()
    string(REPLACE "${before}" "${after}" source "${source}")
    set(source "${source}" PARENT_SCOPE)
endfunction()

file(READ "${backend}" source)
string(FIND "${source}" "juliaVertexArguments" arguments_patched)
if(arguments_patched EQUAL -1)
    julia_sampler_replace([=[typedef struct MetalShader
{]=] [=[typedef struct MetalShader
{
    id<MTLArgumentEncoder> juliaArguments;]=])
    julia_sampler_replace([=[typedef struct MetalGraphicsPipeline
{]=] [=[typedef struct MetalGraphicsPipeline
{
    id<MTLArgumentEncoder> juliaVertexArguments;
    id<MTLArgumentEncoder> juliaFragmentArguments;]=])
    julia_sampler_replace([=[        metalShader->function = nil;]=] [=[        metalShader->juliaArguments = nil;
        metalShader->function = nil;]=])
    julia_sampler_replace([=[        metalGraphicsPipeline->handle = nil;]=] [=[        metalGraphicsPipeline->juliaVertexArguments = nil;
        metalGraphicsPipeline->juliaFragmentArguments = nil;
        metalGraphicsPipeline->handle = nil;]=])
    julia_sampler_replace([=[        result->header.num_vertex_samplers = vertexShader->numSamplers;]=] [=[        result->juliaVertexArguments = vertexShader->juliaArguments;
        result->juliaFragmentArguments = fragmentShader->juliaArguments;
        result->header.num_vertex_samplers = vertexShader->numSamplers;]=])
    julia_sampler_replace([=[        samplerDesc.sAddressMode =]=] [=[        samplerDesc.supportArgumentBuffers = YES;
        samplerDesc.sAddressMode =]=])
    julia_sampler_replace([=[        result->numUniformBuffers = createinfo->num_uniform_buffers;
        return (SDL_GPUShader *)result;]=] [=[        result->numUniformBuffers = createinfo->num_uniform_buffers;
        const char *marker = "// julia sampler arguments v1";
        if (createinfo->format == SDL_GPU_SHADERFORMAT_MSL && createinfo->code_size >= SDL_strlen(marker) &&
            SDL_strncmp((const char *)createinfo->code, marker, SDL_strlen(marker)) == 0) {
            if (((MetalRenderer *)driverData)->device.argumentBuffersSupport != MTLArgumentBuffersTier2) {
                METAL_ReleaseShader(driverData, (SDL_GPUShader *)result);
                SDL_SetError("Julia sampled resources require Metal argument-buffer tier 2");
                return NULL;
            }
            result->juliaArguments = [result->function newArgumentEncoderWithBufferIndex:13];
            if (result->juliaArguments == nil) {
                METAL_ReleaseShader(driverData, (SDL_GPUShader *)result);
                SDL_SetError("Julia sampled-resource argument encoder is unavailable");
                return NULL;
            }
        }
        return (SDL_GPUShader *)result;]=])
    julia_sampler_replace([=[// This function assumes that it's called from within an autorelease pool
static void METAL_INTERNAL_BindGraphicsResources(]=] [=[static void JULIA_MetalBindSamplerArguments(MetalCommandBuffer *commandBuffer, bool fragment);

// This function assumes that it's called from within an autorelease pool
static void METAL_INTERNAL_BindGraphicsResources(]=])
    julia_sampler_replace([=[        if (graphicsPipeline->header.num_vertex_samplers > 0) {
            [commandBuffer->renderEncoder setVertexSamplerStates:]=] [=[        if (graphicsPipeline->juliaVertexArguments != nil) {
            JULIA_MetalBindSamplerArguments(commandBuffer, false);
        } else if (graphicsPipeline->header.num_vertex_samplers > 0) {
            [commandBuffer->renderEncoder setVertexSamplerStates:]=])
    julia_sampler_replace([=[        if (graphicsPipeline->header.num_fragment_samplers > 0) {
            [commandBuffer->renderEncoder setFragmentSamplerStates:]=] [=[        if (graphicsPipeline->juliaFragmentArguments != nil) {
            JULIA_MetalBindSamplerArguments(commandBuffer, true);
        } else if (graphicsPipeline->header.num_fragment_samplers > 0) {
            [commandBuffer->renderEncoder setFragmentSamplerStates:]=])
    string(APPEND source "\n" [=[#include "gpu_sampler_arguments.m"]=] "\n")
    file(WRITE "${backend}" "${source}")
endif()
