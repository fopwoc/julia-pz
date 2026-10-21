# VK_ATTACHMENT_UNUSED preserves fragment output locations without allocating dummy attachments.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace([=[        VulkanTextureContainer *container = (VulkanTextureContainer *)colorTargetInfos[i].texture;
        attachmentDescriptions]=] [=[        VulkanTextureContainer *container = (VulkanTextureContainer *)colorTargetInfos[i].texture;
        if (!container) {
            colorAttachmentReferences[colorAttachmentReferenceCount].attachment = VK_ATTACHMENT_UNUSED;
            colorAttachmentReferences[colorAttachmentReferenceCount].layout = VK_IMAGE_LAYOUT_UNDEFINED;
            resolveReferences[colorAttachmentReferenceCount].attachment = VK_ATTACHMENT_UNUSED;
            colorAttachmentReferenceCount++;
            continue;
        }
        attachmentDescriptions]=] 1)
julia_replace([=[        attachmentDescription = targetInfo.color_target_descriptions[i];]=] [=[        attachmentDescription = targetInfo.color_target_descriptions[i];
        if (attachmentDescription.format == SDL_GPU_TEXTUREFORMAT_INVALID) {
            colorAttachmentReferences[colorAttachmentReferenceCount].attachment = VK_ATTACHMENT_UNUSED;
            colorAttachmentReferences[colorAttachmentReferenceCount].layout = VK_IMAGE_LAYOUT_UNDEFINED;
            colorAttachmentReferenceCount++;
            continue;
        }]=] 1)
julia_replace([=[        key.colorTargetDescriptions[i].format = SDLToVK_TextureFormat]=] [=[        if (!colorTargetInfos[i].texture) continue;
        key.colorTargetDescriptions[i].format = SDLToVK_TextureFormat]=] 1)
julia_replace([=[    if (numColorTargets > 0) {
        key.sampleCount = SDLToVK_SampleCount[((VulkanTextureContainer *)colorTargetInfos[0].texture)->header.info.sample_count];
    } else if (numColorTargets == 0 && depthStencilTargetInfo != NULL) {
        key.sampleCount = SDLToVK_SampleCount[((VulkanTextureContainer *)depthStencilTargetInfo->texture)->header.info.sample_count];
    }]=] [=[    if (depthStencilTargetInfo != NULL)
        key.sampleCount = SDLToVK_SampleCount[((VulkanTextureContainer *)depthStencilTargetInfo->texture)->header.info.sample_count];
    for (i = 0; i < numColorTargets; i++) if (colorTargetInfos[i].texture) {
        key.sampleCount = SDLToVK_SampleCount[((VulkanTextureContainer *)colorTargetInfos[i].texture)->header.info.sample_count];
        break;
    }]=] 1)
julia_replace([=[        VulkanTextureContainer *container = (VulkanTextureContainer *)colorTargetInfos[i].texture;
        VulkanTextureSubresource *subresource]=] [=[        if (!colorTargetInfos[i].texture) continue;
        VulkanTextureContainer *container = (VulkanTextureContainer *)colorTargetInfos[i].texture;
        VulkanTextureSubresource *subresource]=] 2)
julia_replace([=[        VulkanTextureContainer *textureContainer = (VulkanTextureContainer *)colorTargetInfos[i].texture;]=] [=[        if (!colorTargetInfos[i].texture) continue;
        VulkanTextureContainer *textureContainer = (VulkanTextureContainer *)colorTargetInfos[i].texture;]=] 2)
julia_replace([=[        clearValues[clearIndex].color.float32[0] = colorTargetInfos[i].clear_color.r;]=] [=[        if (!colorTargetInfos[i].texture) continue;
        clearValues[clearIndex].color.float32[0] = colorTargetInfos[i].clear_color.r;]=] 1)
julia_replace([=[        SDL_GPUColorTargetBlendState blendState = createinfo->target_info.color_target_descriptions[i].blend_state;]=] [=[        if (createinfo->target_info.color_target_descriptions[i].format == SDL_GPU_TEXTUREFORMAT_INVALID) {
            SDL_zero(colorBlendAttachmentStates[i]);
            continue;
        }
        SDL_GPUColorTargetBlendState blendState = createinfo->target_info.color_target_descriptions[i].blend_state;]=] 1)
file(WRITE "${backend}" "${source}")
