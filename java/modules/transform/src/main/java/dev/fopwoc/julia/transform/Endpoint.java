package dev.fopwoc.julia.transform;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every static endpoint a transformed consumer may call across the graphics boundary.
 *
 * <p>Owners are internal names, never class literals: the transform layer must not load backend classes.
 */
public enum Endpoint {
    UNSUPPORTED_GRAPHICS("dev/fopwoc/julia/runtime/UnsupportedGraphics", false),
    MOD_APPROVAL_HOOKS("dev/fopwoc/julia/runtime/ModApprovalHooks", false),
    LUA_STATUS("dev/fopwoc/julia/runtime/JuliaStatus", false),
    GLSM_ATTRIB_STACK("com/gtnewhorizons/angelica/glsm/GLSMAttribStack", true),
    GLSM_BLEND("com/gtnewhorizons/angelica/glsm/GLSMBlend", true),
    GLSM_BUFFERS("com/gtnewhorizons/angelica/glsm/GLSMBuffers", true),
    GLSM_CURRENT_ATTRIBS("com/gtnewhorizons/angelica/glsm/GLSMCurrentAttribs", true),
    GLSM_DEPTH_STENCIL_ALPHA("com/gtnewhorizons/angelica/glsm/GLSMDepthStencilAlpha", true),
    GLSM_DRAWS("com/gtnewhorizons/angelica/glsm/GLSMDraws", true),
    GLSM_ENABLE("com/gtnewhorizons/angelica/glsm/GLSMEnable", true),
    GLSM_FOG("com/gtnewhorizons/angelica/glsm/GLSMFog", true),
    GLSM_FRAMEBUFFER("com/gtnewhorizons/angelica/glsm/GLSMFramebuffer", true),
    GLSM_LIGHTING("com/gtnewhorizons/angelica/glsm/GLSMLighting", true),
    GLSM_MATRICES("com/gtnewhorizons/angelica/glsm/GLSMMatrices", true),
    GLSM_PROGRAMS("com/gtnewhorizons/angelica/glsm/GLSMPrograms", true),
    GLSM_QUERIES("com/gtnewhorizons/angelica/glsm/GLSMQueries", true),
    GLSM_RASTERIZER("com/gtnewhorizons/angelica/glsm/GLSMRasterizer", true),
    GLSM_TEX_ENV_GEN("com/gtnewhorizons/angelica/glsm/GLSMTexEnvGen", true),
    GLSM_TEXTURE_LIFETIME("com/gtnewhorizons/angelica/glsm/GLSMTextureLifetime", true),
    GLSM_TEXTURES("com/gtnewhorizons/angelica/glsm/GLSMTextures", true),
    GLSM_VERTEX_INPUT("com/gtnewhorizons/angelica/glsm/GLSMVertexInput", true),
    STATE_MANAGER("com/gtnewhorizons/angelica/glsm/GLStateManager", true),
    DEBUG_SHIM("com/gtnewhorizons/angelica/sdlgpu/compat/AwareGLDebugShim", true),
    DEPTH_RANGE_BRIDGE("dev/fopwoc/julia/backend/bridge/DepthRangeBridge", false),
    VIEWPORT_BRIDGE("dev/fopwoc/julia/backend/bridge/ViewportBridge", false),
    DRAW_RANGE_BRIDGE("dev/fopwoc/julia/backend/bridge/DrawRangeBridge", false),
    DEBUG_CALLBACK_BRIDGE("dev/fopwoc/julia/backend/bridge/DebugCallbackBridge", false),
    PROGRAM_RESOURCE_BRIDGE("dev/fopwoc/julia/backend/bridge/ProgramResourceBridge", false),
    UNIFORM_BRIDGE("dev/fopwoc/julia/backend/bridge/UniformBridge", false),
    RESOURCE_ARRAY_BRIDGE("dev/fopwoc/julia/backend/bridge/ResourceArrayBridge", false),
    SAMPLER_RESOURCES("com/gtnewhorizons/angelica/sdlgpu/shader/SamplerResources", false),
    SAMPLER_SHADER_BRIDGE("dev/fopwoc/julia/backend/bridge/SamplerShaderBridge", false),
    SYNC_BRIDGE("dev/fopwoc/julia/backend/bridge/SyncBridge", false),
    QUERY_BRIDGE("dev/fopwoc/julia/backend/bridge/QueryBridge", false),
    UNIFORM_QUERY_BRIDGE("dev/fopwoc/julia/backend/bridge/UniformQueryBridge", false),
    UNIFORM_UPLOAD_BRIDGE("dev/fopwoc/julia/backend/bridge/UniformUploadBridge", false),
    CAPABILITY_BRIDGE("dev/fopwoc/julia/runtime/CapabilitySnapshot", false),
    COLOR_MASK_BRIDGE("dev/fopwoc/julia/backend/bridge/ColorMaskBridge", false),
    COLOR_MASK_HOOKS("dev/fopwoc/julia/backend/framebuffer/ColorMaskHooks", false),
    BLEND_EQUATION_HOOKS("dev/fopwoc/julia/backend/framebuffer/BlendEquationHooks", false),
    BLEND_BRIDGE("com/gtnewhorizons/angelica/sdlgpu/bridge/BlendBridge", false),
    FRAMEBUFFER_CLEAR_BRIDGE("com/gtnewhorizons/angelica/sdlgpu/bridge/FramebufferClearBridge", false),
    FRAMEBUFFER_BRIDGE("dev/fopwoc/julia/backend/bridge/FramebufferBridge", false),
    FRAMEBUFFER_HOOKS("dev/fopwoc/julia/backend/framebuffer/FramebufferHooks", false),
    BOUND_TEXTURE_BRIDGE("dev/fopwoc/julia/backend/bridge/BoundTextureBridge", false),
    NAMED_TEXTURE_BRIDGE("dev/fopwoc/julia/backend/bridge/NamedTextureBridge", false),
    TEXTURE_ALLOCATION_BRIDGE("dev/fopwoc/julia/backend/bridge/TextureAllocationBridge", false),
    BUFFER_TRANSFER_BRIDGE("com/gtnewhorizons/angelica/sdlgpu/bridge/BufferTransferBridge", false),
    BUFFER_STORAGE_BRIDGE("dev/fopwoc/julia/backend/bridge/BufferStorageBridge", false),
    BUFFER_BINDING_BRIDGE("dev/fopwoc/julia/backend/bridge/BufferBindingBridge", false),
    TEXTURE_CLEAR_BRIDGE("dev/fopwoc/julia/backend/bridge/TextureClearBridge", false),
    TEXTURE_VIEW_BRIDGE("dev/fopwoc/julia/backend/bridge/TextureViewBridge", false),
    VERTEX_BINDING_BRIDGE("dev/fopwoc/julia/backend/bridge/VertexBindingBridge", false),
    INDIRECT_DRAWS("dev/fopwoc/julia/backend/vertex/IndirectDraws", false),
    INSTANCE_INDEX_SPIRV("dev/fopwoc/julia/backend/shader/InstanceIndexSpirv", false),
    BASE_INSTANCE_DRAWS("com/gtnewhorizons/angelica/sdlgpu/vertex/BaseInstanceDraws", false),
    VERTEX_INPUTS("com/gtnewhorizons/angelica/sdlgpu/vertex/VertexInputs", false),
    BUFFER_TEXTURE_BRIDGE("dev/fopwoc/julia/backend/bridge/BufferTextureBridge", false),
    BUFFER_TEXTURE_HOOKS("dev/fopwoc/julia/backend/texture/buffer/BufferTextureHooks", false),
    TEXTURE_BINDING_HOOKS("dev/fopwoc/julia/backend/texture/TextureBindingHooks", false),
    TEXTURE_VIEW_HOOKS("dev/fopwoc/julia/backend/texture/TextureViewHooks", false),
    TEXTURE_READBACK_BRIDGE("com/gtnewhorizons/angelica/sdlgpu/bridge/TextureReadbackBridge", false),
    TEXTURE_COPY_BRIDGE("dev/fopwoc/julia/backend/bridge/TextureCopyBridge", false);

    private static final Map<String, Endpoint> BY_OWNER =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Endpoint::owner, Function.identity()));

    private final String owner;
    private final boolean upstream;

    Endpoint(String owner, boolean upstream) {
        this.owner = owner;
        this.upstream = upstream;
    }

    /** Internal class name, e.g. {@code com/gtnewhorizons/angelica/glsm/GLStateManager}. */
    public String owner() {
        return owner;
    }

    /** Published Angelica API, as opposed to one of Julia's compatibility bridges. */
    public boolean upstream() {
        return upstream;
    }

    public boolean is(String internalName) {
        return owner.equals(internalName);
    }

    public static boolean isGlsm(String internalName) {
        var endpoint = BY_OWNER.get(internalName);
        return endpoint != null && endpoint.upstream && internalName.startsWith("com/gtnewhorizons/angelica/glsm/");
    }

    public static boolean contains(String internalName) {
        return BY_OWNER.containsKey(internalName);
    }

    /** Shared JDK-only endpoints are callable without entering the graphics thread or isolated loader. */
    public static boolean shared(String owner) {
        return contains(owner) && owner.startsWith("dev/fopwoc/julia/runtime/");
    }

    public static Set<String> owners() {
        return BY_OWNER.keySet();
    }
}
