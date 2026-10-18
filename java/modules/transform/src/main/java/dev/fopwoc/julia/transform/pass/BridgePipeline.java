package dev.fopwoc.julia.transform.pass;

import java.util.function.Consumer;
import org.objectweb.asm.tree.ClassNode;

/**
 * Julia's compatibility passes, applied after upstream GLSM redirection and before typed dispatch.
 *
 * <p>The order matters: aliases may target overloads that later passes route to bridges.
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
        VanillaAliasPass.adapt(node);
        UniformOverloadPass.adapt(node);
        UniformAccessPass.adapt(node);
        ResourcePass.adapt(node);
        DebugCallbackPass.adapt(node);
        TextureAllocationPass.adapt(node);
        ImGuiPass.adapt(node);
        ShutdownPass.adapt(node);
        return node;
    }
}
