/* Julia extension: indirect sampled resources, compiled inside the pinned SDL Metal backend. */
#ifdef SDL_GPU_METAL
__attribute__((visibility("default"))) int SDLCALL JULIA_SDL_SamplerArgumentsABI(void) { return 2; }

static void JULIA_MetalBindSamplerArguments(MetalCommandBuffer *commandBuffer, bool fragment)
{
    MetalGraphicsPipeline *pipeline = commandBuffer->graphics_pipeline;
    id<MTLArgumentEncoder> encoder = fragment ? pipeline->juliaFragmentArguments : pipeline->juliaVertexArguments;
    Uint32 samplerMask = fragment ? pipeline->juliaFragmentSamplerMask : pipeline->juliaVertexSamplerMask;
    Uint32 count = fragment ? pipeline->header.num_fragment_samplers : pipeline->header.num_vertex_samplers;
    id<MTLTexture> __strong *textures = fragment ? commandBuffer->fragmentTextures : commandBuffer->vertexTextures;
    id<MTLSamplerState> __strong *samplers = fragment ? commandBuffer->fragmentSamplers : commandBuffer->vertexSamplers;
    id<MTLBuffer> arguments = [commandBuffer->renderer->device newBufferWithLength:encoder.encodedLength options:MTLResourceStorageModeShared];
    if (arguments == nil) {
        SDL_LogCritical(SDL_LOG_CATEGORY_GPU, "Julia cannot allocate Metal sampler arguments");
        abort();
    }
    [encoder setArgumentBuffer:arguments offset:0];
    NSMutableArray *retained = [NSMutableArray arrayWithObject:arguments];
    MTLRenderStages stage = fragment ? MTLRenderStageFragment : MTLRenderStageVertex;
    for (Uint32 i = 0; i < count; i++) {
        [encoder setTexture:textures[i] atIndex:i];
        if (samplerMask & ((Uint32)1 << i)) [encoder setSamplerState:samplers[i] atIndex:32 + i];
        [commandBuffer->renderEncoder useResource:textures[i] usage:MTLResourceUsageRead stages:stage];
        if (textures[i] != nil) [retained addObject:textures[i]];
        if ((samplerMask & ((Uint32)1 << i)) && samplers[i] != nil) [retained addObject:samplers[i]];
    }
    if (fragment) [commandBuffer->renderEncoder setFragmentBuffer:arguments offset:0 atIndex:13];
    else [commandBuffer->renderEncoder setVertexBuffer:arguments offset:0 atIndex:13];
    /* Each draw keeps an immutable snapshot. SDL owns tracked textures; indirect samplers and
       argument storage must additionally survive deletion or rebinding until GPU completion. */
    [commandBuffer->handle addCompletedHandler:^(id<MTLCommandBuffer> completed) { (void)retained.count; }];
}
#endif
