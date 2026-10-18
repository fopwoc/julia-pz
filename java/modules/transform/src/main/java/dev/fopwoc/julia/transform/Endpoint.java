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
    STATE_MANAGER("com/gtnewhorizons/angelica/glsm/GLStateManager", true),
    DEBUG_SHIM("com/gtnewhorizons/angelica/sdlgpu/compat/AwareGLDebugShim", true),
    DRAW_RANGE_BRIDGE("dev/fopwoc/julia/backend/bridge/DrawRangeBridge", false),
    DEBUG_CALLBACK_BRIDGE("dev/fopwoc/julia/backend/bridge/DebugCallbackBridge", false),
    PROGRAM_RESOURCE_BRIDGE("dev/fopwoc/julia/backend/bridge/ProgramResourceBridge", false),
    UNIFORM_BRIDGE("dev/fopwoc/julia/backend/bridge/UniformBridge", false),
    UNIFORM_QUERY_BRIDGE("dev/fopwoc/julia/backend/bridge/UniformQueryBridge", false),
    UNIFORM_UPLOAD_BRIDGE("dev/fopwoc/julia/backend/bridge/UniformUploadBridge", false),
    CAPABILITY_BRIDGE("dev/fopwoc/julia/runtime/CapabilitySnapshot", false),
    TEXTURE_ALLOCATION_BRIDGE("dev/fopwoc/julia/backend/bridge/TextureAllocationBridge", false),
    BUFFER_STORAGE_BRIDGE("dev/fopwoc/julia/backend/bridge/BufferStorageBridge", false),
    TEXTURE_CLEAR_BRIDGE("dev/fopwoc/julia/backend/bridge/TextureClearBridge", false),
    TEXTURE_VIEW_BRIDGE("dev/fopwoc/julia/backend/bridge/TextureViewBridge", false),
    STENCIL_REFERENCE("dev/fopwoc/julia/backend/compat/StencilReference", false),
    SHADER_SOURCES("dev/fopwoc/julia/backend/compat/ShaderSources", false),
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

    public static Set<String> owners() {
        return BY_OWNER.keySet();
    }
}
