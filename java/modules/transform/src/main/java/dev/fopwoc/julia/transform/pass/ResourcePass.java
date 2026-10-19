package dev.fopwoc.julia.transform.pass;

import dev.fopwoc.julia.transform.Endpoint;
import java.util.Map;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Only the exact observed resource overloads enter the bounded bridge implementations. */
public final class ResourcePass {
    private static final Map<String, Endpoint> ROUTES = Map.ofEntries(
            Map.entry(Endpoint.STATE_MANAGER.owner() + ".glBindTexture(II)V", Endpoint.BUFFER_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL31.glTexBuffer(III)V", Endpoint.BUFFER_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL43.glTexBufferRange(IIIJJ)V", Endpoint.BUFFER_TEXTURE_BRIDGE),
            Map.entry(Endpoint.STATE_MANAGER.owner() + ".glGenTextures([I)V", Endpoint.RESOURCE_ARRAY_BRIDGE),
            Map.entry(Endpoint.STATE_MANAGER.owner() + ".glDeleteTextures([I)V", Endpoint.RESOURCE_ARRAY_BRIDGE),
            Map.entry(Endpoint.STATE_MANAGER.owner() + ".glDeleteFramebuffers([I)V", Endpoint.RESOURCE_ARRAY_BRIDGE),
            Map.entry(Endpoint.STATE_MANAGER.owner() + ".glDrawBuffers([I)V", Endpoint.RESOURCE_ARRAY_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL20.glUniform1iv(I[I)V", Endpoint.UNIFORM_UPLOAD_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL30.glUniform1iv(I[I)V", Endpoint.UNIFORM_UPLOAD_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL43.glUniform1iv(I[I)V", Endpoint.UNIFORM_UPLOAD_BRIDGE),
            Map.entry(Endpoint.STATE_MANAGER.owner() + ".glGenQueries([I)V", Endpoint.QUERY_BRIDGE),
            Map.entry(Endpoint.STATE_MANAGER.owner() + ".glDeleteQueries([I)V", Endpoint.QUERY_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL33.glGetQueryObjecti64(II)J", Endpoint.QUERY_BRIDGE),
            Map.entry(Endpoint.STATE_MANAGER.owner() + ".glDrawRangeElements(IIIIIJ)V", Endpoint.DRAW_RANGE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL43.glGetProgramResourceIndex(IILjava/lang/CharSequence;)I",
                    Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL43.glGetProgramResourceName(IIII)Ljava/lang/String;",
                    Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL43.glGetProgramResourceiv(III[I[I[I)V", Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL44.glTextureView(IIIIIIII)V", Endpoint.TEXTURE_VIEW_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL45.glNamedBufferStorage(ILjava/nio/ByteBuffer;I)V",
                    Endpoint.BUFFER_STORAGE_BRIDGE),
            Map.entry(
                    Endpoint.STATE_MANAGER.owner() + ".glCopyImageSubData(IIIIIIIIIIIIIII)V",
                    Endpoint.TEXTURE_COPY_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL44.glClearTexSubImage(IIIIIIIIII[F)V", Endpoint.TEXTURE_CLEAR_BRIDGE));

    private ResourcePass() {}

    public static void adapt(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
                    var route = ROUTES.get(call.owner + "." + call.name + call.desc);
                    if (route != null) call.owner = route.owner();
                } else if (instruction instanceof LdcInsnNode constant) {
                    constant.cst = constant(constant.cst);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    dynamic.bsm = (Handle) constant(dynamic.bsm);
                    for (int i = 0; i < dynamic.bsmArgs.length; i++) dynamic.bsmArgs[i] = constant(dynamic.bsmArgs[i]);
                }
            }
        }
    }

    private static Object constant(Object value) {
        if (value instanceof Handle handle && handle.getTag() == Opcodes.H_INVOKESTATIC) {
            var route = ROUTES.get(handle.getOwner() + "." + handle.getName() + handle.getDesc());
            if (route != null)
                return new Handle(handle.getTag(), route.owner(), handle.getName(), handle.getDesc(), false);
        }
        if (value instanceof ConstantDynamic dynamic) {
            Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
            for (int i = 0; i < arguments.length; i++) arguments[i] = constant(dynamic.getBootstrapMethodArgument(i));
            return new ConstantDynamic(
                    dynamic.getName(),
                    dynamic.getDescriptor(),
                    (Handle) constant(dynamic.getBootstrapMethod()),
                    arguments);
        }
        return value;
    }
}
