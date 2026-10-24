package dev.fopwoc.julia.transform.load;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * Every upstream rewrite in application order. A targeted class is parsed once, edited by each matching patch, and
 * written once; untargeted or unchanged classes keep their original bytes.
 */
public final class UpstreamPatches {
    static final List<UpstreamPatch> ORDER = List.of(
            new WindowQueryPass(),
            new StrictQueryPass(),
            new FramebufferPass(),
            new ColorMaskPass(),
            new DrawTracePass(),
            new TextureBindingPass(),
            new TextureUploadOrderPass(),
            new TextureFormatPass(),
            new TextureViewPass(),
            new TextureStoragePass(),
            new BufferTexturePass(),
            new SamplerBindingPass(),
            new SamplerResourcePass(),
            new VertexInputPass(),
            new ShaderSourcePass(),
            new CompatShaderPass(),
            new FrameContinuityPass(),
            new QuadIndexUploadPass(),
            new StencilReferencePass());

    private UpstreamPatches() {}

    /** Namespace remapping first, so every patch matches LWJGL3ify's {@code org.lwjglx} owners. */
    public static byte[] apply(String className, byte[] bytes) {
        return apply(ORDER, className, LegacyNamespacePass.adapt(className, bytes));
    }

    static byte[] apply(List<UpstreamPatch> patches, String className, byte[] bytes) {
        ClassNode node = null;
        boolean changed = false;
        for (var patch : patches) {
            if (!patch.targets(className)) continue;
            if (node == null) {
                node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
            }
            changed |= patch.apply(className, node);
        }
        if (!changed) return bytes;
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
