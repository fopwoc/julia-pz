# Volumes shrink in all three dimensions; array textures retain their layer count.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace([=[                texture->subresources[subresourceIndex].renderTargetViews = SDL_malloc(
                    depth * sizeof(VkImageView));

                if (depth > 1) {
                    for (Uint32 k = 0; k < depth; k += 1) {]=] [=[                Uint32 mipDepth = SDL_max(1u, depth >> j);
                texture->subresources[subresourceIndex].renderTargetViews = SDL_calloc(
                    mipDepth, sizeof(VkImageView));

                if (mipDepth > 1) {
                    for (Uint32 k = 0; k < mipDepth; k += 1) {]=] 1)
julia_replace("depthIndex < texture->depth;"
    "depthIndex < SDL_max(1u, texture->depth >> texture->subresources[subresourceIndex].level);" 2)
julia_replace("for (Uint32 layerOrDepthIndex = 0; layerOrDepthIndex < container->header.info.layer_count_or_depth; layerOrDepthIndex += 1) {"
    "for (Uint32 layerOrDepthIndex = 0; layerOrDepthIndex < (container->header.info.type == SDL_GPU_TEXTURETYPE_3D ? 1u : container->header.info.layer_count_or_depth); layerOrDepthIndex += 1) {" 1)
foreach(axis x y)
    if(axis STREQUAL "x")
        set(dimension width)
    else()
        set(dimension height)
    endif()
    julia_replace("blit.srcOffsets[1].${axis} = container->header.info.${dimension} >> (level - 1);"
        "blit.srcOffsets[1].${axis} = SDL_max(1u, container->header.info.${dimension} >> (level - 1));" 1)
    julia_replace("blit.dstOffsets[1].${axis} = container->header.info.${dimension} >> level;"
        "blit.dstOffsets[1].${axis} = SDL_max(1u, container->header.info.${dimension} >> level);" 1)
endforeach()
julia_replace("blit.srcOffsets[1].z = depth + 1;"
    "blit.srcOffsets[1].z = container->header.info.type == SDL_GPU_TEXTURETYPE_3D ? SDL_max(1u, container->header.info.layer_count_or_depth >> (level - 1)) : depth + 1;" 1)
julia_replace("blit.dstOffsets[1].z = depth + 1;"
    "blit.dstOffsets[1].z = container->header.info.type == SDL_GPU_TEXTURETYPE_3D ? SDL_max(1u, container->header.info.layer_count_or_depth >> level) : depth + 1;" 1)
julia_replace("imageCopy.extent.depth = info.type == SDL_GPU_TEXTURETYPE_3D ? info.layer_count_or_depth : 1;"
    "imageCopy.extent.depth = info.type == SDL_GPU_TEXTURETYPE_3D ? SDL_max(1u, info.layer_count_or_depth >> srcSubresource->level) : 1;" 1)
# Failed view creation destroys already allocated mip views, so record each level before allocating them.
julia_replace("            texture->subresources[subresourceIndex].level = j;" "" 1)
julia_replace("            if (createinfo->usage & SDL_GPU_TEXTUREUSAGE_COLOR_TARGET) {"
    "            texture->subresources[subresourceIndex].level = j;\n            if (createinfo->usage & SDL_GPU_TEXTUREUSAGE_COLOR_TARGET) {" 1)
file(WRITE "${backend}" "${source}")
