# Indirect Metal argument buffers for shaders whose sampled images exceed SDL's per-stage table.
file(READ "${backend}" source)
string(FIND "${source}" "juliaVertexArguments" arguments_patched)
if(arguments_patched EQUAL -1)
    julia_replace([=[typedef struct MetalShader
{]=] [=[typedef struct MetalShader
{
    id<MTLArgumentEncoder> juliaArguments;]=])
    julia_replace([=[typedef struct MetalGraphicsPipeline
{]=] [=[typedef struct MetalGraphicsPipeline
{
    id<MTLArgumentEncoder> juliaVertexArguments;
    id<MTLArgumentEncoder> juliaFragmentArguments;]=])
    julia_replace([=[        metalShader->function = nil;]=] [=[        metalShader->juliaArguments = nil;
        metalShader->function = nil;]=])
    julia_replace([=[        metalGraphicsPipeline->handle = nil;]=] [=[        metalGraphicsPipeline->juliaVertexArguments = nil;
        metalGraphicsPipeline->juliaFragmentArguments = nil;
        metalGraphicsPipeline->handle = nil;]=])
    julia_replace([=[        result->header.num_vertex_samplers = vertexShader->numSamplers;]=] [=[        result->juliaVertexArguments = vertexShader->juliaArguments;
        result->juliaFragmentArguments = fragmentShader->juliaArguments;
        result->header.num_vertex_samplers = vertexShader->numSamplers;]=])
    julia_replace([=[        samplerDesc.sAddressMode =]=] [=[        samplerDesc.supportArgumentBuffers = YES;
        samplerDesc.sAddressMode =]=])
    julia_replace([=[        result->numUniformBuffers = createinfo->num_uniform_buffers;
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
    julia_replace([=[// This function assumes that it's called from within an autorelease pool
static void METAL_INTERNAL_BindGraphicsResources(]=] [=[static void JULIA_MetalBindSamplerArguments(MetalCommandBuffer *commandBuffer, bool fragment);

// This function assumes that it's called from within an autorelease pool
static void METAL_INTERNAL_BindGraphicsResources(]=])
    julia_replace([=[        if (graphicsPipeline->header.num_vertex_samplers > 0) {
            [commandBuffer->renderEncoder setVertexSamplerStates:]=] [=[        if (graphicsPipeline->juliaVertexArguments != nil) {
            JULIA_MetalBindSamplerArguments(commandBuffer, false);
        } else if (graphicsPipeline->header.num_vertex_samplers > 0) {
            [commandBuffer->renderEncoder setVertexSamplerStates:]=])
    julia_replace([=[        if (graphicsPipeline->header.num_fragment_samplers > 0) {
            [commandBuffer->renderEncoder setFragmentSamplerStates:]=] [=[        if (graphicsPipeline->juliaFragmentArguments != nil) {
            JULIA_MetalBindSamplerArguments(commandBuffer, true);
        } else if (graphicsPipeline->header.num_fragment_samplers > 0) {
            [commandBuffer->renderEncoder setFragmentSamplerStates:]=])
    string(APPEND source "\n" [=[#include "gpu_sampler_arguments.m"]=] "\n")
    file(WRITE "${backend}" "${source}")
endif()

# Samplerless sampled images have no sampler member in MSL. Writing absent IDs corrupts the argument buffer.
file(READ "${backend}" source)
string(FIND "${source}" "juliaSamplerMask" sampler_layout_patched)
if(sampler_layout_patched EQUAL -1)
    julia_replace([=[    id<MTLArgumentEncoder> juliaArguments;]=] [=[    id<MTLArgumentEncoder> juliaArguments;
    Uint32 juliaSamplerMask;]=])
    julia_replace([=[    id<MTLArgumentEncoder> juliaFragmentArguments;]=] [=[    id<MTLArgumentEncoder> juliaFragmentArguments;
    Uint32 juliaVertexSamplerMask;
    Uint32 juliaFragmentSamplerMask;]=])
    julia_replace([=[        result->juliaFragmentArguments = fragmentShader->juliaArguments;]=] [=[        result->juliaFragmentArguments = fragmentShader->juliaArguments;
        result->juliaVertexSamplerMask = vertexShader->juliaSamplerMask;
        result->juliaFragmentSamplerMask = fragmentShader->juliaSamplerMask;]=])
    julia_replace([=[        const char *marker = "// julia sampler arguments v1";]=] [=[        result->juliaSamplerMask = 0;
        const char *marker = "// julia sampler arguments v1";]=])
    julia_replace([=[            result->juliaArguments = [result->function newArgumentEncoderWithBufferIndex:13];]=] [=[            MTLArgument *reflection = nil;
            result->juliaArguments = [result->function newArgumentEncoderWithBufferIndex:13 reflection:&reflection];
            if (reflection == nil || reflection.bufferStructType == nil) {
                METAL_ReleaseShader(driverData, (SDL_GPUShader *)result);
                SDL_SetError("Julia sampled-resource argument reflection is unavailable");
                return NULL;
            }
            for (MTLStructMember *member in reflection.bufferStructType.members) {
                NSUInteger length = 1;
                NSUInteger stride = 1;
                if (member.dataType == MTLDataTypeArray && member.arrayType.elementType == MTLDataTypeSampler) {
                    length = member.arrayType.arrayLength;
                    stride = member.arrayType.argumentIndexStride;
                } else if (member.dataType != MTLDataTypeSampler) {
                    continue;
                }
                for (NSUInteger i = 0; i < length; i++) {
                    NSUInteger index = member.argumentIndex + i * stride;
                    if (index < 32 || index >= 64) {
                        METAL_ReleaseShader(driverData, (SDL_GPUShader *)result);
                        SDL_SetError("Julia sampled-resource sampler index is outside its table");
                        return NULL;
                    }
                    result->juliaSamplerMask |= (Uint32)1 << (index - 32);
                }
            }]=])
    file(WRITE "${backend}" "${source}")
endif()
