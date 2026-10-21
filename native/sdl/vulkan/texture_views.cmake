# A view owns its VkImageViews and pins the physical parent's image and allocator region.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace([=[struct VulkanTexture
{]=] [=[struct VulkanTexture
{
    VulkanRenderer *juliaRenderer;
    VulkanTexture *juliaViewOwner;
    Uint32 juliaBaseLevel;
    Uint32 juliaBaseLayer;]=] 1)
julia_replace([=[    texture->swizzle = SwizzleForSDLFormat(createinfo->format);]=] [=[    texture->juliaRenderer = renderer;
    texture->swizzle = SwizzleForSDLFormat(createinfo->format);]=] 1)
julia_replace([=[    if (texture->image) {
        renderer->vkDestroyImage(]=] [=[    if (texture->image && !texture->juliaViewOwner) {
        renderer->vkDestroyImage(]=] 1)
julia_replace([=[    SDL_free(texture);
}

static void VULKAN_INTERNAL_DestroyBuffer]=] [=[    if (texture->juliaViewOwner) {
        (void)SDL_AtomicDecRef(&texture->juliaViewOwner->usedRegion->allocation->referenceCount);
        (void)SDL_AtomicDecRef(&texture->juliaViewOwner->referenceCount);
    }
    SDL_free(texture);
}

static void VULKAN_INTERNAL_DestroyBuffer]=] 1)
julia_replace([=[        0,
        texture->levelCount,
        0,
        texture->layerCount,]=] [=[        texture->juliaBaseLevel,
        texture->levelCount,
        texture->juliaBaseLayer,
        texture->layerCount,]=] 1)
foreach(field layer mip_level)
    if(field STREQUAL "layer")
        set(vkfield baseArrayLayer)
        set(subfield layer)
    else()
        set(vkfield mipLevel)
        set(subfield level)
    endif()
    foreach(direction source destination)
        julia_replace("imageCopy.imageSubresource.${vkfield} = ${direction}->${field};"
            "imageCopy.imageSubresource.${vkfield} = vulkanTextureSubresource->${subfield};" 1)
    endforeach()
    julia_replace("imageCopy.srcSubresource.${vkfield} = source->${field};"
        "imageCopy.srcSubresource.${vkfield} = srcSubresource->${subfield};" 1)
    julia_replace("imageCopy.dstSubresource.${vkfield} = destination->${field};"
        "imageCopy.dstSubresource.${vkfield} = dstSubresource->${subfield};" 1)
endforeach()
julia_replace("blit.srcSubresource.baseArrayLayer = layer;" "blit.srcSubresource.baseArrayLayer = layer + container->activeTexture->juliaBaseLayer;" 1)
julia_replace("blit.dstSubresource.baseArrayLayer = layer;" "blit.dstSubresource.baseArrayLayer = layer + container->activeTexture->juliaBaseLayer;" 1)
julia_replace("blit.srcSubresource.mipLevel = level - 1;" "blit.srcSubresource.mipLevel = level - 1 + container->activeTexture->juliaBaseLevel;" 1)
julia_replace("blit.dstSubresource.mipLevel = level;" "blit.dstSubresource.mipLevel = level + container->activeTexture->juliaBaseLevel;" 1)
file(WRITE "${backend}" "${source}\n#include \"gpu_texture_views.c\"\n")
