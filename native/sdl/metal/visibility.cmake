# Version-pinned SDL extension: render passes carry a visibility buffer and count samples for active GL queries.

file(READ "${backend}" source)
string(FIND "${source}" "gpu_visibility.m" visibility_patched)
if(visibility_patched EQUAL -1)
    julia_replace([=[static void METAL_BeginRenderPass(]=] [=[/* Julia extension: GL occlusion queries. */
static void JULIA_MetalVisibilityAttach(MetalRenderer *renderer, MTLRenderPassDescriptor *descriptor);
static void JULIA_MetalVisibilityBegin(MetalCommandBuffer *command);

static void METAL_BeginRenderPass(]=] 1)
    julia_replace([=[        metalCommandBuffer->renderEncoder = [metalCommandBuffer->handle renderCommandEncoderWithDescriptor:passDescriptor];]=] [=[        JULIA_MetalVisibilityAttach(renderer, passDescriptor);
        metalCommandBuffer->renderEncoder = [metalCommandBuffer->handle renderCommandEncoderWithDescriptor:passDescriptor];
        JULIA_MetalVisibilityBegin(metalCommandBuffer);]=] 1)
    string(APPEND source "\n" [=[#include "gpu_visibility.m"]=] "\n")
    file(WRITE "${backend}" "${source}")
endif()
