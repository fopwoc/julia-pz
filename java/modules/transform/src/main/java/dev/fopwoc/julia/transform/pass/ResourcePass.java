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
    // Core functions keep their name in every later GL class (GL45 inherits GL32.glFenceSync), so one canonical
    // CORE key matches any GLnn or GLnnC owner; extension and GLSM owners stay exact.
    private static final String CORE = "GL.";
    private static final String LWJGL = "org/lwjgl/opengl/";
    private static final String GLSM = Endpoint.STATE_MANAGER.owner() + ".";
    private static final Map<String, Endpoint> ROUTES = Map.ofEntries(
            Map.entry(CORE + "glEnablei(II)V", Endpoint.BLEND_BRIDGE),
            Map.entry(CORE + "glDisablei(II)V", Endpoint.BLEND_BRIDGE),
            Map.entry(CORE + "glIsEnabledi(II)Z", Endpoint.BLEND_BRIDGE),
            Map.entry(CORE + "glBlendFuncSeparatei(IIIII)V", Endpoint.BLEND_BRIDGE),
            Map.entry(GLSM + "glGetBooleani_v(IILjava/nio/ByteBuffer;)V", Endpoint.COLOR_MASK_BRIDGE),
            Map.entry(CORE + "glColorMaski(IZZZZ)V", Endpoint.COLOR_MASK_BRIDGE),
            Map.entry(CORE + "glGetBooleani_v(IILjava/nio/ByteBuffer;)V", Endpoint.COLOR_MASK_BRIDGE),
            Map.entry(CORE + "glBlendFunci(III)V", Endpoint.BLEND_BRIDGE),
            Map.entry(CORE + "glClearBufferfv(II[F)V", Endpoint.FRAMEBUFFER_CLEAR_BRIDGE),
            Map.entry(CORE + "glClearBufferfv(IILjava/nio/FloatBuffer;)V", Endpoint.FRAMEBUFFER_CLEAR_BRIDGE),
            Map.entry(CORE + "glMultiDrawArraysIndirect(IJII)V", Endpoint.INDIRECT_DRAWS),
            Map.entry(LWJGL + "ARBMultiDrawIndirect.glMultiDrawArraysIndirect(IJII)V", Endpoint.INDIRECT_DRAWS),
            Map.entry(GLSM + "glMultiDrawArraysIndirect(IJII)V", Endpoint.INDIRECT_DRAWS),
            Map.entry(CORE + "glDrawArraysInstancedBaseInstance(IIIII)V", Endpoint.BASE_INSTANCE_DRAWS),
            Map.entry(
                    LWJGL + "ARBBaseInstance.glDrawArraysInstancedBaseInstance(IIIII)V", Endpoint.BASE_INSTANCE_DRAWS),
            Map.entry(GLSM + "glDrawArraysInstancedBaseInstance(IIIII)V", Endpoint.BASE_INSTANCE_DRAWS),
            Map.entry(CORE + "glUniform1iv(ILjava/nio/IntBuffer;)V", Endpoint.UNIFORM_UPLOAD_BRIDGE),
            Map.entry(CORE + "glFenceSync(II)J", Endpoint.SYNC_BRIDGE),
            Map.entry(CORE + "glClientWaitSync(JIJ)I", Endpoint.SYNC_BRIDGE),
            Map.entry(CORE + "glDeleteSync(J)V", Endpoint.SYNC_BRIDGE),
            Map.entry(CORE + "glIsSync(J)Z", Endpoint.SYNC_BRIDGE),
            Map.entry(LWJGL + "ARBSync.glFenceSync(II)J", Endpoint.SYNC_BRIDGE),
            Map.entry(LWJGL + "ARBSync.glClientWaitSync(JIJ)I", Endpoint.SYNC_BRIDGE),
            Map.entry(LWJGL + "ARBSync.glDeleteSync(J)V", Endpoint.SYNC_BRIDGE),
            Map.entry(LWJGL + "ARBSync.glIsSync(J)Z", Endpoint.SYNC_BRIDGE),
            Map.entry(GLSM + "glFenceSync(II)J", Endpoint.SYNC_BRIDGE),
            Map.entry(GLSM + "glClientWaitSync(JIJ)I", Endpoint.SYNC_BRIDGE),
            Map.entry(GLSM + "glDeleteSync(J)V", Endpoint.SYNC_BRIDGE),
            Map.entry(GLSM + "glIsSync(J)Z", Endpoint.SYNC_BRIDGE),
            Map.entry(CORE + "glTexStorage3D(IIIIII)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(LWJGL + "ARBTextureStorage.glTexStorage3D(IIIIII)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(GLSM + "glTexStorage3D(IIIIII)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTexSubImage3D(IIIIIIIIIILjava/nio/IntBuffer;)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(GLSM + "glTexSubImage3D(IIIIIIIIIILjava/nio/IntBuffer;)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTexSubImage2D(IIIIIIIILjava/nio/FloatBuffer;)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(GLSM + "glTexSubImage2D(IIIIIIIILjava/nio/FloatBuffer;)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTexSubImage2D(IIIIIIIILjava/nio/ShortBuffer;)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(GLSM + "glTexSubImage2D(IIIIIIIILjava/nio/ShortBuffer;)V", Endpoint.BOUND_TEXTURE_BRIDGE),
            Map.entry(CORE + "glGetProgramInterfacei(III)I", Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry(CORE + "glGetProgramInterfaceiv(III[I)V", Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry(CORE + "glGetProgramInterfaceiv(IIILjava/nio/IntBuffer;)V", Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry(
                    CORE + "glGetProgramResourceiv(IIILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;)V",
                    Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry(CORE + "glCreateFramebuffers()I", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(CORE + "glCreateFramebuffers([I)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(CORE + "glCreateFramebuffers(Ljava/nio/IntBuffer;)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(CORE + "glNamedFramebufferTexture(IIII)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(CORE + "glNamedFramebufferTextureLayer(IIIII)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(CORE + "glNamedFramebufferDrawBuffer(II)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(CORE + "glNamedFramebufferDrawBuffers(I[I)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(CORE + "glNamedFramebufferDrawBuffers(ILjava/nio/IntBuffer;)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(CORE + "glNamedFramebufferReadBuffer(II)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(CORE + "glCheckNamedFramebufferStatus(II)I", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(CORE + "glFramebufferTextureLayer(IIIII)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(GLSM + "glFramebufferTextureLayer(IIIII)V", Endpoint.FRAMEBUFFER_BRIDGE),
            Map.entry(CORE + "glGetTextureLevelParameteri(III)I", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glGetTextureLevelParameterf(III)F", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glGetTextureLevelParameteriv(IIILjava/nio/IntBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glGetTextureLevelParameteriv(III[I)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glGetTextureLevelParameterfv(IIILjava/nio/FloatBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glGetTextureLevelParameterfv(III[F)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(LWJGL + "ARBDirectStateAccess.glGetTextureLevelParameteri(III)I", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(LWJGL + "ARBDirectStateAccess.glGetTextureLevelParameterf(III)F", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    LWJGL + "ARBDirectStateAccess.glGetTextureLevelParameteriv(IIILjava/nio/IntBuffer;)V",
                    Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    LWJGL + "ARBDirectStateAccess.glGetTextureLevelParameteriv(III[I)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    LWJGL + "ARBDirectStateAccess.glGetTextureLevelParameterfv(IIILjava/nio/FloatBuffer;)V",
                    Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(
                    LWJGL + "ARBDirectStateAccess.glGetTextureLevelParameterfv(III[F)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glCreateTextures(I)I", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glCreateTextures(ILjava/nio/IntBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glCreateTextures(I[I)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureParameteri(III)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureParameterf(IIF)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureStorage2D(IIIII)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureStorage3D(IIIIII)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureSubImage2D(IIIIIIIILjava/nio/IntBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureSubImage2D(IIIIIIIILjava/nio/FloatBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureSubImage2D(IIIIIIIILjava/nio/ShortBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureSubImage3D(IIIIIIIIIILjava/nio/ByteBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureSubImage3D(IIIIIIIIIILjava/nio/IntBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureSubImage3D(IIIIIIIIIILjava/nio/FloatBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTextureSubImage3D(IIIIIIIIIILjava/nio/ShortBuffer;)V", Endpoint.NAMED_TEXTURE_BRIDGE),
            Map.entry(CORE + "glVertexBindingDivisor(II)V", Endpoint.VERTEX_BINDING_BRIDGE),
            Map.entry(GLSM + "glVertexAttribDivisor(II)V", Endpoint.VERTEX_BINDING_BRIDGE),
            Map.entry(GLSM + "glVertexAttribDivisorARB(II)V", Endpoint.VERTEX_BINDING_BRIDGE),
            Map.entry(GLSM + "glBindTexture(II)V", Endpoint.BUFFER_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTexBuffer(III)V", Endpoint.BUFFER_TEXTURE_BRIDGE),
            Map.entry(CORE + "glTexBufferRange(IIIJJ)V", Endpoint.BUFFER_TEXTURE_BRIDGE),
            Map.entry(GLSM + "glGenTextures([I)V", Endpoint.RESOURCE_ARRAY_BRIDGE),
            Map.entry(GLSM + "glDeleteTextures([I)V", Endpoint.RESOURCE_ARRAY_BRIDGE),
            Map.entry(GLSM + "glDeleteFramebuffers([I)V", Endpoint.RESOURCE_ARRAY_BRIDGE),
            Map.entry(GLSM + "glDrawBuffers([I)V", Endpoint.RESOURCE_ARRAY_BRIDGE),
            Map.entry(CORE + "glUniform1iv(I[I)V", Endpoint.UNIFORM_UPLOAD_BRIDGE),
            Map.entry(GLSM + "glGenQueries([I)V", Endpoint.QUERY_BRIDGE),
            Map.entry(GLSM + "glDeleteQueries([I)V", Endpoint.QUERY_BRIDGE),
            Map.entry(CORE + "glGetQueryObjecti64(II)J", Endpoint.QUERY_BRIDGE),
            Map.entry(GLSM + "glDrawRangeElements(IIIIIJ)V", Endpoint.DRAW_RANGE_BRIDGE),
            Map.entry(
                    CORE + "glGetProgramResourceIndex(IILjava/lang/CharSequence;)I", Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry(CORE + "glGetProgramResourceName(IIII)Ljava/lang/String;", Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry(CORE + "glGetProgramResourceiv(III[I[I[I)V", Endpoint.PROGRAM_RESOURCE_BRIDGE),
            Map.entry(CORE + "glTextureView(IIIIIIII)V", Endpoint.TEXTURE_VIEW_BRIDGE),
            Map.entry(LWJGL + "ARBTextureView.glTextureView(IIIIIIII)V", Endpoint.TEXTURE_VIEW_BRIDGE),
            Map.entry(CORE + "glNamedBufferStorage(ILjava/nio/ByteBuffer;I)V", Endpoint.BUFFER_STORAGE_BRIDGE),
            Map.entry(CORE + "glNamedBufferStorage(IJI)V", Endpoint.BUFFER_STORAGE_BRIDGE),
            Map.entry(LWJGL + "ARBDirectStateAccess.glNamedBufferStorage(IJI)V", Endpoint.BUFFER_STORAGE_BRIDGE),
            Map.entry(
                    LWJGL + "ARBDirectStateAccess.glNamedBufferStorage(ILjava/nio/ByteBuffer;I)V",
                    Endpoint.BUFFER_STORAGE_BRIDGE),
            Map.entry(CORE + "glCopyNamedBufferSubData(IIJJJ)V", Endpoint.BUFFER_TRANSFER_BRIDGE),
            Map.entry(CORE + "glGetNamedBufferSubData(IJLjava/nio/ByteBuffer;)V", Endpoint.BUFFER_TRANSFER_BRIDGE),
            Map.entry(LWJGL + "ARBDirectStateAccess.glCopyNamedBufferSubData(IIJJJ)V", Endpoint.BUFFER_TRANSFER_BRIDGE),
            Map.entry(
                    LWJGL + "ARBDirectStateAccess.glGetNamedBufferSubData(IJLjava/nio/ByteBuffer;)V",
                    Endpoint.BUFFER_TRANSFER_BRIDGE),
            Map.entry(CORE + "glGetTextureImage(IIIIIJ)V", Endpoint.TEXTURE_READBACK_BRIDGE),
            Map.entry(CORE + "glGetTextureImage(IIIILjava/nio/ByteBuffer;)V", Endpoint.TEXTURE_READBACK_BRIDGE),
            Map.entry(LWJGL + "ARBDirectStateAccess.glGetTextureImage(IIIIIJ)V", Endpoint.TEXTURE_READBACK_BRIDGE),
            Map.entry(
                    LWJGL + "ARBDirectStateAccess.glGetTextureImage(IIIILjava/nio/ByteBuffer;)V",
                    Endpoint.TEXTURE_READBACK_BRIDGE),
            Map.entry(CORE + "glCopyImageSubData(IIIIIIIIIIIIIII)V", Endpoint.TEXTURE_COPY_BRIDGE),
            Map.entry(LWJGL + "ARBCopyImage.glCopyImageSubData(IIIIIIIIIIIIIII)V", Endpoint.TEXTURE_COPY_BRIDGE),
            Map.entry(GLSM + "glCopyImageSubData(IIIIIIIIIIIIIII)V", Endpoint.TEXTURE_COPY_BRIDGE),
            Map.entry(CORE + "glClearTexSubImage(IIIIIIIIII[F)V", Endpoint.TEXTURE_CLEAR_BRIDGE));

    private ResourcePass() {}

    public static void adapt(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
                    var route = ROUTES.get(key(call.owner, call.name, call.desc));
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

    static String key(String owner, String name, String descriptor) {
        return (core(owner) ? CORE : owner + ".") + name + descriptor;
    }

    /** {@code org/lwjgl/opengl/GL} followed by a version number and an optional core-profile {@code C}. */
    private static boolean core(String owner) {
        int start = LWJGL.length() + 2;
        if (!owner.startsWith(LWJGL + "GL")) return false;
        int end = owner.endsWith("C") ? owner.length() - 1 : owner.length();
        if (end <= start) return false;
        for (int i = start; i < end; i++) if (!Character.isDigit(owner.charAt(i))) return false;
        return true;
    }

    private static Object constant(Object value) {
        if (value instanceof Handle handle && handle.getTag() == Opcodes.H_INVOKESTATIC) {
            var route = ROUTES.get(key(handle.getOwner(), handle.getName(), handle.getDesc()));
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
