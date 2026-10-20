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
            Map.entry("org/lwjgl/opengl/GL43.glMultiDrawArraysIndirect(IJII)V", Endpoint.INDIRECT_DRAWS),
            Map.entry("org/lwjgl/opengl/GL43C.glMultiDrawArraysIndirect(IJII)V", Endpoint.INDIRECT_DRAWS),
            Map.entry("org/lwjgl/opengl/GL44.glMultiDrawArraysIndirect(IJII)V", Endpoint.INDIRECT_DRAWS),
            Map.entry("org/lwjgl/opengl/GL45.glMultiDrawArraysIndirect(IJII)V", Endpoint.INDIRECT_DRAWS),
            Map.entry(
                    "org/lwjgl/opengl/ARBMultiDrawIndirect.glMultiDrawArraysIndirect(IJII)V", Endpoint.INDIRECT_DRAWS),
            Map.entry(Endpoint.STATE_MANAGER.owner() + ".glMultiDrawArraysIndirect(IJII)V", Endpoint.INDIRECT_DRAWS),
            Map.entry("org/lwjgl/opengl/GL42.glDrawArraysInstancedBaseInstance(IIIII)V", Endpoint.BASE_INSTANCE_DRAWS),
            Map.entry("org/lwjgl/opengl/GL42C.glDrawArraysInstancedBaseInstance(IIIII)V", Endpoint.BASE_INSTANCE_DRAWS),
            Map.entry(
                    "org/lwjgl/opengl/ARBBaseInstance.glDrawArraysInstancedBaseInstance(IIIII)V",
                    Endpoint.BASE_INSTANCE_DRAWS),
            Map.entry(
                    Endpoint.STATE_MANAGER.owner() + ".glDrawArraysInstancedBaseInstance(IIIII)V",
                    Endpoint.BASE_INSTANCE_DRAWS),
            Map.entry("org/lwjgl/opengl/GL20.glUniform1iv(ILjava/nio/IntBuffer;)V", Endpoint.UNIFORM_UPLOAD_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL30.glUniform1iv(ILjava/nio/IntBuffer;)V", Endpoint.UNIFORM_UPLOAD_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL43.glUniform1iv(ILjava/nio/IntBuffer;)V", Endpoint.UNIFORM_UPLOAD_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL32.glFenceSync(II)J", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL32.glClientWaitSync(JIJ)I", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL32.glDeleteSync(J)V", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL32.glIsSync(J)Z", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL32C.glFenceSync(II)J", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL32C.glClientWaitSync(JIJ)I", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL32C.glDeleteSync(J)V", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL32C.glIsSync(J)Z", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/ARBSync.glFenceSync(II)J", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/ARBSync.glClientWaitSync(JIJ)I", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/ARBSync.glDeleteSync(J)V", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/ARBSync.glIsSync(J)Z", Endpoint.SYNC_BRIDGE),
            Map.entry("com/gtnewhorizons/angelica/glsm/GLStateManager.glFenceSync(II)J", Endpoint.SYNC_BRIDGE),
            Map.entry("com/gtnewhorizons/angelica/glsm/GLStateManager.glClientWaitSync(JIJ)I", Endpoint.SYNC_BRIDGE),
            Map.entry("com/gtnewhorizons/angelica/glsm/GLStateManager.glDeleteSync(J)V", Endpoint.SYNC_BRIDGE),
            Map.entry("com/gtnewhorizons/angelica/glsm/GLStateManager.glIsSync(J)Z", Endpoint.SYNC_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL42.glTexStorage3D(IIIIII)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL42C.glTexStorage3D(IIIIII)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/ARBTextureStorage.glTexStorage3D(IIIIII)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(Endpoint.STATE_MANAGER.owner() + ".glTexStorage3D(IIIIII)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL12.glTexSubImage3D(IIIIIIIIIILjava/nio/IntBuffer;)V",
                    Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL12C.glTexSubImage3D(IIIIIIIIIILjava/nio/IntBuffer;)V",
                    Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(
                    Endpoint.STATE_MANAGER.owner() + ".glTexSubImage3D(IIIIIIIIIILjava/nio/IntBuffer;)V",
                    Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL11.glTexSubImage2D(IIIIIIIILjava/nio/FloatBuffer;)V",
                    Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL11C.glTexSubImage2D(IIIIIIIILjava/nio/FloatBuffer;)V",
                    Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(
                    Endpoint.STATE_MANAGER.owner() + ".glTexSubImage2D(IIIIIIIILjava/nio/FloatBuffer;)V",
                    Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL11.glTexSubImage2D(IIIIIIIILjava/nio/ShortBuffer;)V",
                    Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(
                    Endpoint.STATE_MANAGER.owner() + ".glTexSubImage2D(IIIIIIIILjava/nio/ShortBuffer;)V",
                    Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL43.glGetProgramInterfacei(III)I", Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL43.glGetProgramInterfaceiv(III[I)V", Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL43.glGetProgramInterfaceiv(IIILjava/nio/IntBuffer;)V",
                    Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL43.glGetProgramResourceiv(IIILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;)V",
                    Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glCreateFramebuffers()I", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glCreateFramebuffers([I)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glCreateFramebuffers(Ljava/nio/IntBuffer;)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glNamedFramebufferTexture(IIII)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glNamedFramebufferTextureLayer(IIIII)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glNamedFramebufferDrawBuffer(II)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glNamedFramebufferDrawBuffers(I[I)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL45.glNamedFramebufferDrawBuffers(ILjava/nio/IntBuffer;)V",
                    Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glNamedFramebufferReadBuffer(II)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glCheckNamedFramebufferStatus(II)I", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL30.glFramebufferTextureLayer(IIIII)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(
                    Endpoint.STATE_MANAGER.owner() + ".glFramebufferTextureLayer(IIIII)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glCreateTextures(I)I", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glCreateTextures(ILjava/nio/IntBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glCreateTextures(I[I)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glTextureParameteri(III)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glTextureParameterf(IIF)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glTextureStorage2D(IIIII)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glTextureStorage3D(IIIIII)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL45.glTextureSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
                    Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL45.glTextureSubImage2D(IIIIIIIILjava/nio/IntBuffer;)V",
                    Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL45.glTextureSubImage2D(IIIIIIIILjava/nio/FloatBuffer;)V",
                    Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL45.glTextureSubImage2D(IIIIIIIILjava/nio/ShortBuffer;)V",
                    Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL45.glTextureSubImage3D(IIIIIIIIIILjava/nio/ByteBuffer;)V",
                    Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL45.glTextureSubImage3D(IIIIIIIIIILjava/nio/IntBuffer;)V",
                    Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL45.glTextureSubImage3D(IIIIIIIIIILjava/nio/FloatBuffer;)V",
                    Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL45.glTextureSubImage3D(IIIIIIIIIILjava/nio/ShortBuffer;)V",
                    Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL43.glVertexBindingDivisor(II)V", Endpoint.VERTEX_BINDING_BRIDGE),
            Map.entry(Endpoint.STATE_MANAGER.owner() + ".glVertexAttribDivisor(II)V", Endpoint.VERTEX_BINDING_BRIDGE),
            Map.entry(
                    Endpoint.STATE_MANAGER.owner() + ".glVertexAttribDivisorARB(II)V", Endpoint.VERTEX_BINDING_BRIDGE),
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
            Map.entry("org/lwjgl/opengl/GL43.glTextureView(IIIIIIII)V", Endpoint.TEXTURE_VIEW_BRIDGE),
            Map.entry("org/lwjgl/opengl/ARBTextureView.glTextureView(IIIIIIII)V", Endpoint.TEXTURE_VIEW_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/GL45.glNamedBufferStorage(ILjava/nio/ByteBuffer;I)V",
                    Endpoint.BUFFER_STORAGE_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL43.glCopyImageSubData(IIIIIIIIIIIIIII)V", Endpoint.TEXTURE_COPY_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL43C.glCopyImageSubData(IIIIIIIIIIIIIII)V", Endpoint.TEXTURE_COPY_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL44.glCopyImageSubData(IIIIIIIIIIIIIII)V", Endpoint.TEXTURE_COPY_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL44C.glCopyImageSubData(IIIIIIIIIIIIIII)V", Endpoint.TEXTURE_COPY_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45.glCopyImageSubData(IIIIIIIIIIIIIII)V", Endpoint.TEXTURE_COPY_BRIDGE),
            Map.entry("org/lwjgl/opengl/GL45C.glCopyImageSubData(IIIIIIIIIIIIIII)V", Endpoint.TEXTURE_COPY_BRIDGE),
            Map.entry(
                    "org/lwjgl/opengl/ARBCopyImage.glCopyImageSubData(IIIIIIIIIIIIIII)V", Endpoint.TEXTURE_COPY_BRIDGE),
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
