# Resource writes and texture layers

Julia routes the observed named buffer storage and constant texture-clear calls into the isolated Angelica backend. Image copies also pass through a Julia bridge that distinguishes array layers from volume slices. Published Angelica 2.2.34 remains unchanged.

## Supported paths

`BufferStorageBridge` allocates immutable storage from a direct, nonempty ByteBuffer through the upstream storage implementation. It temporarily binds GL_COPY_WRITE_BUFFER and restores that binding even on failure. Caller buffer position and limit remain unchanged. It validates storage flags and rejects repeated immutable allocation. Objects must already be bound or allocated in upstream state; merely generated, unbound buffer identities are not supported. Mapping behavior for every accepted flag combination is not verified.

`TextureClearBridge` accepts the observed RGBA/FLOAT input for normalized RGBA8/BGRA8 storage on 2D, 2D-array, and 3D targets. It validates mip bounds and preserves array-layer counts across mip levels. Constant-color uploads use bounded tiles, preserve surrounding texels and pending framebuffer clears, and distinguish SDL array layers from volume coordinates. A null color clears to zero. Unsupported formats, including sRGB, integer and depth storage, are rejected. Cubemaps and other input layouts remain unsupported.

`TextureCopyBridge` fixes the upstream alias path that used volume coordinates for array layers. It supports the same three targets with identical SDL storage formats, validates both regions, preserves pending clears, and rejects overlapping regions. Format reinterpretation and texture views remain unsupported. A texture view must share storage and lifetime with its parent; copying pixels would not implement that contract.

These bridges use pinned private backend fields to reach existing resource, frame and clear-tracking state. Their entry points require the owned hosted context and an active frame for texture work. Invalid operations raise Java exceptions rather than implementing the complete OpenGL error-state model.

## Verification

73 JVM tests pass. The windowless Metal suite verifies 36,352 pixels: 2,048 raw baseline pixels, 1,024 shader-rendered adapter pixels, and 33,280 resource pixels. Resource checks cover full and partial tiled clears, null clears, array-layer isolation, array-to-2D and 2D-to-array copies, and unchanged neighboring texels. Buffer checks verify uploaded bytes, immutable flags, binding restoration, caller buffer bounds, and repeated-allocation rejection.

Resource readback is diagnostic code inside the controlled probe. It drains a command buffer and invalidates the backend binding mirror before continuing. It opens no window. `resource-checks.txt` records stage success; the separate final `result.txt` records completion of the adapter suite. The latest artifact has not been tested inside PZ.

Whole-archive preparation now reports 177/182 graphics consumers structurally ready, 1,829 routed call sites, one frame anchor set, and six unique blockers:

- Three program-resource introspection entry points: index, name and properties.
- Texture views with shared storage.
- The native debug-callback boundary.
- Host ImGui rendering.

This is structural coverage, not a compatibility percentage. Semantic gaps outside these six symbols remain, including previously documented uniform-array limits. The startup agent remains audit-only; persistent live Metal frame ownership and automatic activation are not installed. No game launch or live-mod installation was performed for this milestone.

API semantics were checked against the published LWJGL bindings, pinned Angelica source, and native readback: [GL44C](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL44C.html), [direct state access](https://javadoc.lwjgl.org/org/lwjgl/opengl/ARBDirectStateAccess.html), and [shared texture storage](https://wikis.khronos.org/opengl/Texture_Storage).
