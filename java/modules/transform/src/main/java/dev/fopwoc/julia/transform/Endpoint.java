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
    STATE_MANAGER("com/gtnewhorizons/angelica/glsm/GLStateManager", true),
    DEBUG_SHIM("com/gtnewhorizons/angelica/sdlgpu/compat/AwareGLDebugShim", true),
    DEPTH_RANGE_BRIDGE("dev/fopwoc/julia/backend/bridge/DepthRangeBridge", false),
    DRAW_RANGE_BRIDGE("dev/fopwoc/julia/backend/bridge/DrawRangeBridge", false),
    DEBUG_CALLBACK_BRIDGE("dev/fopwoc/julia/backend/bridge/DebugCallbackBridge", false),
    PROGRAM_RESOURCE_BRIDGE("dev/fopwoc/julia/backend/bridge/ProgramResourceBridge", false),
    UNIFORM_BRIDGE("dev/fopwoc/julia/backend/bridge/UniformBridge", false),
    RESOURCE_ARRAY_BRIDGE("dev/fopwoc/julia/backend/bridge/ResourceArrayBridge", false),
    QUERY_BRIDGE("dev/fopwoc/julia/backend/bridge/QueryBridge", false),
    UNIFORM_QUERY_BRIDGE("dev/fopwoc/julia/backend/bridge/UniformQueryBridge", false),
    UNIFORM_UPLOAD_BRIDGE("dev/fopwoc/julia/backend/bridge/UniformUploadBridge", false),
    CAPABILITY_BRIDGE("dev/fopwoc/julia/runtime/CapabilitySnapshot", false),
    TEXTURE_ALLOCATION_BRIDGE("dev/fopwoc/julia/backend/bridge/TextureAllocationBridge", false),
    BUFFER_STORAGE_BRIDGE("dev/fopwoc/julia/backend/bridge/BufferStorageBridge", false),
    TEXTURE_CLEAR_BRIDGE("dev/fopwoc/julia/backend/bridge/TextureClearBridge", false),
    TEXTURE_VIEW_BRIDGE("dev/fopwoc/julia/backend/bridge/TextureViewBridge", false),
    STENCIL_REFERENCE("dev/fopwoc/julia/backend/compat/StencilReference", false),
    COMMAND_BUFFER_STATE("dev/fopwoc/julia/backend/compat/CommandBufferState", false),
    COMPAT_SHADER_INPUT("dev/fopwoc/julia/backend/compat/CompatShaderInput", false),
    SHADER_SOURCES("dev/fopwoc/julia/backend/compat/ShaderSources", false),
    VERTEX_BINDING_BRIDGE("dev/fopwoc/julia/backend/bridge/VertexBindingBridge", false),
    VERTEX_INPUTS("dev/fopwoc/julia/backend/vertex/VertexInputs", false),
    BUFFER_TEXTURE_BRIDGE("dev/fopwoc/julia/backend/bridge/BufferTextureBridge", false),
    BUFFER_TEXTURE_HOOKS("dev/fopwoc/julia/backend/texture/buffer/BufferTextureHooks", false),
    TEXTURE_VIEW_HOOKS("dev/fopwoc/julia/backend/texture/TextureViewHooks", false),
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
