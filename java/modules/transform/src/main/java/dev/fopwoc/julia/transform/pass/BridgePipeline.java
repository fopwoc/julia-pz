package dev.fopwoc.julia.transform.pass;

import com.gtnewhorizons.angelica.glsm.redirect.lwjgl3.Lwjgl3Redirects;
import java.util.function.Consumer;
import org.objectweb.asm.tree.ClassNode;

/**
 * Renderer GL routing followed by Julia's host and isolation adaptations, before typed dispatch.
 *
 * <p>Renderer aliases and overloads are resolved before loader-specific adaptations.
 */
public final class BridgePipeline {
    private BridgePipeline() {}

    /**
     * Adapts a redirected class. A capability object that escapes local use cannot be rewritten; it is reported to
     * {@code blockers} and the remaining passes still run so every other issue surfaces too.
     */
    public static ClassNode apply(ClassNode node, Consumer<String> blockers) {
        try {
            node = CapabilityPass.adapt(node);
        } catch (IllegalArgumentException failure) {
            blockers.accept("CAPABILITY_BOUNDARY " + failure.getMessage());
        }
        Lwjgl3Redirects.apply(node);
        PointerBufferPass.adapt(node);
        DebugCallbackPass.adapt(node);
        ImGuiPass.adapt(node);
        ImGuiScalePass.adapt(node);
        try {
            ApprovalPass.adapt(node);
        } catch (IllegalArgumentException failure) {
            blockers.accept("APPROVAL_BOUNDARY " + failure.getMessage());
        }
        FramebufferSizePass.adapt(node);
        try {
            ModLoadingPass.adapt(node);
            LuaStatusPass.adapt(node);
        } catch (IllegalArgumentException failure) {
            blockers.accept("HOST_INTEGRATION " + failure.getMessage());
        }
        ShutdownPass.adapt(node);
        return node;
    }
}
