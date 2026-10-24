package dev.fopwoc.julia.backend.texture.buffer;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.texture.TextureViewHooks;
import org.lwjgl.util.spvc.Spvc;

/** Internal integration points for typed sampler binding, physical storage ownership, and native MSL reads. */
public final class BufferTextureHooks {
    private BufferTextureHooks() {}

    public static int boundBuffer() {
        return Subsystems.current().bufferTextures().boundBuffer();
    }

    public static int samplerBinding(ContextState state, int unit) {
        // Unread moved samplers use -1; they have no texture dependency.
        if (unit == -1) return 0;
        return Subsystems.current().bufferTextures().samplerBinding(state, unit);
    }

    public static int samplerBinding(ContextState state, int unit, int slot, boolean fragment) {
        return Subsystems.current().bufferTextures().samplerBinding(state, unit, slot, fragment);
    }

    public static int computeSamplerBinding(ContextState state, int unit, int slot, String[] names) {
        if (names == null || slot >= names.length || names[slot] == null) return samplerBinding(state, unit);
        return Subsystems.current().bufferTextures().computeSamplerBinding(state, unit, names[slot]);
    }

    public static long samplingHandle(ResourceManager resources, int name) {
        return TextureViewHooks.samplingHandle(resources, name);
    }

    public static boolean retire(ResourceManager resources, long handle) {
        if (!Subsystems.current().bufferTextures().retains(handle)) return false;
        resources.releaseBufferHandle(handle);
        return true;
    }

    public static int installMslOptions(long compiler, long options) {
        int result =
                Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
        if (result != Spvc.SPVC_SUCCESS) return result;
        return Spvc.spvc_compiler_install_compiler_options(compiler, options);
    }
}
