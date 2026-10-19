package dev.fopwoc.julia.backend.texture;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import dev.fopwoc.julia.backend.BackendInternals;

/** Pinned GLSM integration keeps its operation cache aligned without changing any logical binding. */
public final class TextureBindingHooks {
    private TextureBindingHooks() {}

    public static boolean bindingQuery(int parameter) {
        return TextureBindings.queryTarget(parameter) != 0;
    }

    public static int query(int parameter) {
        var state = BackendInternals.context();
        return BackendInternals.textureBindings()
                .get(state, state.activeTextureUnit, TextureBindings.queryTarget(parameter));
    }

    public static void select(int target) {
        if (!TextureBindings.selectable(target)) return;
        var state = BackendInternals.context();
        int name = BackendInternals.textureBindings().get(state, state.activeTextureUnit, target);
        GLStateManager.glBindTexture(target, name);
    }

    public static void samplerOverride(long[] overrides) {
        BackendInternals.textureBindings().samplerOverride(BackendInternals.context(), overrides != null);
    }

    public static void deleted(int name) {
        BackendInternals.textureBindings().deleted(BackendInternals.context(), name);
    }
}
