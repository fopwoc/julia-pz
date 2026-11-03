package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class FramebufferSizePassTest {
    private static final String GLFW = "org/lwjgl/glfw/GLFW";
    private static final String ARRAYS = "(J[I[I)V";
    private static final String BUFFERS = "(JLjava/nio/IntBuffer;Ljava/nio/IntBuffer;)V";

    @Test
    void directCallsAndMethodReferencesAgreeWithTheLogicalRenderTarget() throws Exception {
        var types = fixture();
        var consumer = types[0];
        var glfw = types[1];
        var width = new int[1];
        var height = new int[1];
        consumer.getMethod("arrays", long.class, int[].class, int[].class).invoke(null, 41L, width, height);
        assertArrayEquals(new int[] {1800}, width);
        assertArrayEquals(new int[] {1102}, height);
        assertEquals(41L, glfw.getField("window").getLong(null));
        var w = IntBuffer.allocate(3).position(1);
        var h = IntBuffer.allocate(3).position(2);
        consumer.getMethod("buffers", long.class, IntBuffer.class, IntBuffer.class)
                .invoke(null, 42L, w, h);
        assertEquals(1800, w.get(1));
        assertEquals(1102, h.get(2));
        assertEquals(1, w.position());
        assertEquals(2, h.position());
        consumer.getMethod("handle", long.class, int[].class, int[].class).invoke(null, 43L, width, height);
        assertArrayEquals(new int[] {1800}, width);
        var query = (Runnable) consumer.getMethod("lambda", long.class, int[].class, int[].class)
                .invoke(null, 44L, width, height);
        width[0] = 0;
        query.run();
        assertEquals(1800, width[0]);
        assertEquals(44L, glfw.getField("window").getLong(null));
        // Null optional outputs and unrelated native pixel queries retain their original API behavior.
        consumer.getMethod("arrays", long.class, int[].class, int[].class).invoke(null, 45L, null, height);
        glfw.getMethod("glfwGetFramebufferSize", long.class, int[].class, int[].class)
                .invoke(null, 46L, width, height);
        assertEquals(3600, width[0]);
        assertEquals(2204, height[0]);
    }

    @Test
    void nativeAddressOverloadPreservesTheWindowAndOutputPointers() throws Exception {
        var types = fixture();
        types[0].getMethod("nativeQuery", long.class, long.class, long.class).invoke(null, 51L, 0x100000001L, 0L);
        assertEquals(51L, types[1].getField("window").getLong(null));
        assertEquals(0x100000001L, types[1].getField("widthAddress").getLong(null));
        assertEquals(0L, types[1].getField("heightAddress").getLong(null));
    }

    @Test
    void refusesUnrecognizedFramebufferQuerySignatures() {
        var node = new ClassNode();
        var method = new MethodNode();
        node.methods.add(method);
        method.instructions.add(new MethodInsnNode(INVOKESTATIC, GLFW, "glfwGetFramebufferSize", "(J)J", false));
        assertThrows(IllegalArgumentException.class, () -> FramebufferSizePass.adapt(node));
    }

    private static Class<?>[] fixture() throws Exception {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V25, ACC_PUBLIC, "example/WindowConsumer", null, "java/lang/Object", null);
        for (String name : new String[] {"arrays", "buffers", "handle", "lambda", "nativeQuery"}) {
            String descriptor = name.equals("buffers") ? BUFFERS : name.equals("nativeQuery") ? "(JJJ)V" : ARRAYS;
            String queryName = name.equals("nativeQuery") ? "nglfwGetFramebufferSize" : "glfwGetFramebufferSize";
            var target = new Handle(H_INVOKESTATIC, GLFW, queryName, descriptor, false);
            String result = name.equals("lambda") ? "(J[I[I)Ljava/lang/Runnable;" : descriptor;
            var method = writer.visitMethod(
                    ACC_PUBLIC | ACC_STATIC, name, result, null, new String[] {"java/lang/Throwable"});
            method.visitCode();
            if (name.equals("handle")) method.visitLdcInsn(target);
            method.visitVarInsn(LLOAD, 0);
            method.visitVarInsn(name.equals("nativeQuery") ? LLOAD : ALOAD, 2);
            method.visitVarInsn(name.equals("nativeQuery") ? LLOAD : ALOAD, name.equals("nativeQuery") ? 4 : 3);
            if (name.equals("handle"))
                method.visitMethodInsn(
                        INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", descriptor, false);
            else if (name.equals("lambda"))
                method.visitInvokeDynamicInsn(
                        "run",
                        result,
                        new Handle(
                                H_INVOKESTATIC,
                                "java/lang/invoke/LambdaMetafactory",
                                "metafactory",
                                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                                false),
                        Type.getMethodType("()V"),
                        target,
                        Type.getMethodType("()V"));
            else method.visitMethodInsn(INVOKESTATIC, GLFW, queryName, descriptor, false);
            method.visitInsn(name.equals("lambda") ? ARETURN : RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        var node = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(node, 0);
        BridgePipeline.apply(node, reason -> fail(reason));
        FramebufferSizePass.adapt(node);
        var transformed = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(transformed);
        var stub = new ClassWriter(0);
        new ClassReader(Type.getInternalName(HighDpiGlfw.class))
                .accept(
                        new ClassRemapper(
                                stub, new SimpleRemapper(ASM9, Type.getInternalName(HighDpiGlfw.class), GLFW)),
                        0);
        var loader = new ClassLoader(null) {
            Class<?> define(byte[] bytes) {
                return defineClass(null, bytes, 0, bytes.length);
            }
        };
        var glfw = loader.define(stub.toByteArray());
        var consumer = loader.define(transformed.toByteArray());
        return new Class<?>[] {consumer, glfw};
    }

    public static final class HighDpiGlfw {
        public static long window, widthAddress, heightAddress;

        public static void glfwGetWindowSize(long handle, int[] width, int[] height) {
            window = handle;
            if (width != null) width[0] = 1800;
            if (height != null) height[0] = 1102;
        }

        public static void glfwGetWindowSize(long handle, IntBuffer width, IntBuffer height) {
            window = handle;
            if (width != null) width.put(width.position(), 1800);
            if (height != null) height.put(height.position(), 1102);
        }

        public static void glfwGetFramebufferSize(long handle, int[] width, int[] height) {
            window = handle;
            if (width != null) width[0] = 3600;
            if (height != null) height[0] = 2204;
        }

        public static void nglfwGetWindowSize(long handle, long width, long height) {
            window = handle;
            widthAddress = width;
            heightAddress = height;
        }
    }
}
