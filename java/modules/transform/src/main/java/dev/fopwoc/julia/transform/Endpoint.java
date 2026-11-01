package dev.fopwoc.julia.transform;

import com.gtnewhorizons.angelica.glsm.redirect.lwjgl3.RendererEndpoint;
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
    UNSUPPORTED_GRAPHICS("dev/fopwoc/julia/runtime/UnsupportedGraphics"),
    MOD_APPROVAL_HOOKS("dev/fopwoc/julia/runtime/ModApprovalHooks"),
    LUA_STATUS("dev/fopwoc/julia/runtime/JuliaStatus"),
    GLSM_ATTRIB_STACK(RendererEndpoint.GLSM_ATTRIB_STACK.owner()),
    GLSM_BLEND(RendererEndpoint.GLSM_BLEND.owner()),
    GLSM_BUFFERS(RendererEndpoint.GLSM_BUFFERS.owner()),
    GLSM_CURRENT_ATTRIBS(RendererEndpoint.GLSM_CURRENT_ATTRIBS.owner()),
    GLSM_DEPTH_STENCIL_ALPHA(RendererEndpoint.GLSM_DEPTH_STENCIL_ALPHA.owner()),
    GLSM_DRAWS(RendererEndpoint.GLSM_DRAWS.owner()),
    GLSM_ENABLE(RendererEndpoint.GLSM_ENABLE.owner()),
    GLSM_FOG(RendererEndpoint.GLSM_FOG.owner()),
    GLSM_FRAMEBUFFER(RendererEndpoint.GLSM_FRAMEBUFFER.owner()),
    GLSM_LIGHTING(RendererEndpoint.GLSM_LIGHTING.owner()),
    GLSM_MATRICES(RendererEndpoint.GLSM_MATRICES.owner()),
    GLSM_PROGRAMS(RendererEndpoint.GLSM_PROGRAMS.owner()),
    GLSM_QUERIES(RendererEndpoint.GLSM_QUERIES.owner()),
    GLSM_RASTERIZER(RendererEndpoint.GLSM_RASTERIZER.owner()),
    GLSM_TEX_ENV_GEN(RendererEndpoint.GLSM_TEX_ENV_GEN.owner()),
    GLSM_TEXTURE_LIFETIME(RendererEndpoint.GLSM_TEXTURE_LIFETIME.owner()),
    GLSM_TEXTURES(RendererEndpoint.GLSM_TEXTURES.owner()),
    GLSM_VERTEX_INPUT(RendererEndpoint.GLSM_VERTEX_INPUT.owner()),
    STATE_MANAGER(RendererEndpoint.STATE_MANAGER.owner()),
    DEBUG_SHIM(RendererEndpoint.DEBUG_SHIM.owner()),
    DEPTH_RANGE_BRIDGE(RendererEndpoint.DEPTH_RANGE_BRIDGE.owner()),
    VIEWPORT_BRIDGE(RendererEndpoint.VIEWPORT_BRIDGE.owner()),
    DRAW_RANGE_BRIDGE(RendererEndpoint.DRAW_RANGE_BRIDGE.owner()),
    DEBUG_CALLBACK_BRIDGE(RendererEndpoint.DEBUG_CALLBACK_BRIDGE.owner()),
    PROGRAM_RESOURCE_BRIDGE(RendererEndpoint.PROGRAM_RESOURCE_BRIDGE.owner()),
    UNIFORM_BRIDGE(RendererEndpoint.UNIFORM_BRIDGE.owner()),
    RESOURCE_ARRAY_BRIDGE(RendererEndpoint.RESOURCE_ARRAY_BRIDGE.owner()),
    SYNC_BRIDGE(RendererEndpoint.SYNC_BRIDGE.owner()),
    QUERY_BRIDGE(RendererEndpoint.QUERY_BRIDGE.owner()),
    UNIFORM_QUERY_BRIDGE(RendererEndpoint.UNIFORM_QUERY_BRIDGE.owner()),
    UNIFORM_UPLOAD_BRIDGE(RendererEndpoint.UNIFORM_UPLOAD_BRIDGE.owner()),
    CAPABILITY_BRIDGE("dev/fopwoc/julia/runtime/CapabilitySnapshot"),
    COLOR_MASK_BRIDGE(RendererEndpoint.COLOR_MASK_BRIDGE.owner()),
    BLEND_BRIDGE(RendererEndpoint.BLEND_BRIDGE.owner()),
    FRAMEBUFFER_CLEAR_BRIDGE(RendererEndpoint.FRAMEBUFFER_CLEAR_BRIDGE.owner()),
    FRAMEBUFFER_BRIDGE(RendererEndpoint.FRAMEBUFFER_BRIDGE.owner()),
    BOUND_TEXTURE_BRIDGE(RendererEndpoint.BOUND_TEXTURE_BRIDGE.owner()),
    NAMED_TEXTURE_BRIDGE(RendererEndpoint.NAMED_TEXTURE_BRIDGE.owner()),
    TEXTURE_ALLOCATION_BRIDGE(RendererEndpoint.TEXTURE_ALLOCATION_BRIDGE.owner()),
    BUFFER_TRANSFER_BRIDGE(RendererEndpoint.BUFFER_TRANSFER_BRIDGE.owner()),
    BUFFER_STORAGE_BRIDGE(RendererEndpoint.BUFFER_STORAGE_BRIDGE.owner()),
    BUFFER_BINDING_BRIDGE(RendererEndpoint.BUFFER_BINDING_BRIDGE.owner()),
    TEXTURE_CLEAR_BRIDGE(RendererEndpoint.TEXTURE_CLEAR_BRIDGE.owner()),
    TEXTURE_VIEW_BRIDGE(RendererEndpoint.TEXTURE_VIEW_BRIDGE.owner()),
    VERTEX_BINDING_BRIDGE(RendererEndpoint.VERTEX_BINDING_BRIDGE.owner()),
    INDIRECT_DRAWS(RendererEndpoint.INDIRECT_DRAWS.owner()),
    BASE_INSTANCE_DRAWS(RendererEndpoint.BASE_INSTANCE_DRAWS.owner()),
    BUFFER_TEXTURE_BRIDGE(RendererEndpoint.BUFFER_TEXTURE_BRIDGE.owner()),
    TEXTURE_READBACK_BRIDGE(RendererEndpoint.TEXTURE_READBACK_BRIDGE.owner()),
    TEXTURE_COPY_BRIDGE(RendererEndpoint.TEXTURE_COPY_BRIDGE.owner());

    private static final Map<String, Endpoint> BY_OWNER =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Endpoint::owner, Function.identity()));

    private final String owner;

    Endpoint(String owner) {
        this.owner = owner;
    }

    /** Internal class name, e.g. {@code com/gtnewhorizons/angelica/glsm/GLStateManager}. */
    public String owner() {
        return owner;
    }

    public boolean is(String internalName) {
        return owner.equals(internalName);
    }

    public static boolean isGlsm(String internalName) {
        var endpoint = BY_OWNER.get(internalName);
        return endpoint != null && internalName.startsWith("com/gtnewhorizons/angelica/glsm/");
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
