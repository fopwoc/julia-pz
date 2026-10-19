package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.transform.prepare.RuntimePreparation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

class UnsupportedCallPassTest implements Opcodes {
    @Test
    void unusedUnsupportedBranchLoadsAndRunsWithoutNativeOpenGL() throws Exception {
        var writer = type("mod/Branches");
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "select", "(Z)I", null, null);
        method.visitCode();
        var safe = new Label();
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, safe);
        method.visitMethodInsn(INVOKESTATIC, "org/lwjgl/opengl/GL45", "glUnsupported", "()I", false);
        method.visitInsn(IRETURN);
        method.visitLabel(safe);
        method.visitIntInsn(BIPUSH, 42);
        method.visitInsn(IRETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        byte[] original = writer.toByteArray();
        assertFalse(
                new RuntimePreparation().prepare(original).ready(),
                "Strict startup audits must still flag unsupported GL");
        var type = prepare(original);
        assertEquals(42, type.getMethod("select", boolean.class).invoke(null, false));
        var failure = assertThrows(
                InvocationTargetException.class,
                () -> type.getMethod("select", boolean.class).invoke(null, true));
        assertFailure(failure.getCause(), "mod/Branches.select(Z)I", "glUnsupported()I");
    }

    @Test
    void unsupportedFieldReadsAndWritesFailAtAccess() throws Exception {
        var writer = type("mod/Fields");
        var read = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "()J", null, null);
        read.visitCode();
        read.visitFieldInsn(GETSTATIC, "org/lwjgl/opengl/GL45", "unsupportedField", "J");
        read.visitInsn(LRETURN);
        read.visitMaxs(2, 0);
        read.visitEnd();
        var write = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "write", "(J)V", null, null);
        write.visitCode();
        write.visitVarInsn(LLOAD, 0);
        write.visitFieldInsn(PUTSTATIC, "org/lwjgl/opengl/GL45", "unsupportedField", "J");
        write.visitInsn(RETURN);
        write.visitMaxs(2, 2);
        write.visitEnd();
        var type = prepare(writer.toByteArray());
        assertFailure(
                assertThrows(
                                InvocationTargetException.class,
                                () -> type.getMethod("read").invoke(null))
                        .getCause(),
                "mod/Fields.read()J",
                "unsupportedFieldJ");
        assertFailure(
                assertThrows(
                                InvocationTargetException.class,
                                () -> type.getMethod("write", long.class).invoke(null, 4L))
                        .getCause(),
                "mod/Fields.write(J)V",
                "unsupportedFieldJ");
    }

    @Test
    void unsupportedMethodHandleCanBeCreatedButFailsWhenInvoked() throws Throwable {
        var writer = type("mod/Handles");
        var method =
                writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "handle", "()Ljava/lang/invoke/MethodHandle;", null, null);
        method.visitCode();
        method.visitLdcInsn(new Handle(H_INVOKESTATIC, "org/lwjgl/opengl/GL45", "glUnsupported", "(JD)I", false));
        method.visitInsn(ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        var type = prepare(writer.toByteArray());
        var handle = (MethodHandle) type.getMethod("handle").invoke(null);
        var failure = assertThrows(UnsupportedOperationException.class, () -> {
            int ignored = (int) handle.invokeExact(1L, 2.0);
        });
        assertFailure(failure, "mod/Handles.handle", "glUnsupported(JD)I");
    }

    @Test
    void capabilityStorageDoesNotBlockLoadingAndNativeAccessFailsExplicitly() throws Exception {
        var writer = type("mod/Capabilities");
        String descriptor = "Lorg/lwjgl/opengl/GLCapabilities;";
        writer.visitField(ACC_PUBLIC, "capabilities", descriptor, null, null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(" + descriptor + ")Z", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, "org/lwjgl/opengl/GLCapabilities", "OpenGL46", "Z");
        method.visitInsn(IRETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        var type = prepare(writer.toByteArray());
        var read = type.getDeclaredMethods();
        var target = java.util.Arrays.stream(read)
                .filter(m -> m.getName().equals("read"))
                .findFirst()
                .orElseThrow();
        var failure = assertThrows(InvocationTargetException.class, () -> target.invoke(null, new Object[] {null}));
        assertFailure(failure.getCause(), "mod/Capabilities.read", "GLCapabilities.OpenGL46Z");
    }

    private static ClassWriter type(String name) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V25, ACC_PUBLIC, name, null, "java/lang/Object", null);
        return writer;
    }

    private Class<?> prepare(byte[] bytes) throws Exception {
        var prepared = new RuntimePreparation().prepareMod(bytes);
        assertTrue(prepared.ready(), prepared.blockers().toString());
        assertFalse(prepared.unsupportedCalls().isEmpty());
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                byte[] result = prepared.requireBytecode();
                return defineClass(null, result, 0, result.length);
            }
        }.define();
    }

    private static void assertFailure(Throwable failure, String source, String operation) {
        assertInstanceOf(UnsupportedOperationException.class, failure);
        assertTrue(failure.getMessage().contains(source), failure.getMessage());
        assertTrue(failure.getMessage().contains(operation), failure.getMessage());
    }
}
