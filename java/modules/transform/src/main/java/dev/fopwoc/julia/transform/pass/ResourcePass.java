package dev.fopwoc.julia.transform.pass;

import dev.fopwoc.julia.transform.Endpoint;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Only the exact observed resource overloads enter the bounded bridge implementations. */
public final class ResourcePass {
    private static final Map<String, Endpoint> ROUTES = Map.of(
            Endpoint.STATE_MANAGER.owner() + ".glDrawRangeElements(IIIIIJ)V",
            Endpoint.DRAW_RANGE_BRIDGE,
            "org/lwjgl/opengl/GL43.glGetProgramResourceIndex(IILjava/lang/CharSequence;)I",
            Endpoint.PROGRAM_RESOURCE_BRIDGE,
            "org/lwjgl/opengl/GL43.glGetProgramResourceName(IIII)Ljava/lang/String;",
            Endpoint.PROGRAM_RESOURCE_BRIDGE,
            "org/lwjgl/opengl/GL43.glGetProgramResourceiv(III[I[I[I)V",
            Endpoint.PROGRAM_RESOURCE_BRIDGE,
            "org/lwjgl/opengl/GL44.glTextureView(IIIIIIII)V",
            Endpoint.TEXTURE_VIEW_BRIDGE,
            "org/lwjgl/opengl/GL45.glNamedBufferStorage(ILjava/nio/ByteBuffer;I)V",
            Endpoint.BUFFER_STORAGE_BRIDGE,
            Endpoint.STATE_MANAGER.owner() + ".glCopyImageSubData(IIIIIIIIIIIIIII)V",
            Endpoint.TEXTURE_COPY_BRIDGE,
            "org/lwjgl/opengl/GL44.glClearTexSubImage(IIIIIIIIII[F)V",
            Endpoint.TEXTURE_CLEAR_BRIDGE);

    private ResourcePass() {}

    public static void adapt(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
                    var route = ROUTES.get(call.owner + "." + call.name + call.desc);
                    if (route != null) call.owner = route.owner();
                }
            }
        }
    }
}
