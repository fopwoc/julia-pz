package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.runtime.RenderSession;
import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.TestFrames;
import dev.fopwoc.julia.transform.prepare.RuntimePreparation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GLDebugMessageCallbackI;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class DebugCallbackPassTest {
    private static final String DESCRIPTOR = "(Lorg/lwjgl/opengl/GLDebugMessageCallbackI;J)V";

    @Test
    void executesPreparedRegistrationWithHostInterfaceAndFullWidthUserPointer() throws Throwable {
        var prepared = new RuntimePreparation().prepare(fixture(Endpoint.DEBUG_SHIM.owner()));
        assertTrue(prepared.ready(), prepared.blockers().toString());
        assertEquals(1, prepared.calls());
        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(byte[] bytes) {
                return defineClass(null, bytes, 0, bytes.length);
            }
        };
        var register = loader.define(prepared.requireBytecode())
                .getMethod("register", GLDebugMessageCallbackI.class, long.class);
        var captured = new AtomicReference<MethodHandle>();
        long[] parameter = new long[1];
        var target = MethodHandles.lookup()
                .findStatic(
                        DebugCallbackPassTest.class,
                        "capture",
                        MethodType.methodType(
                                void.class, AtomicReference.class, long[].class, MethodHandle.class, long.class));
        try (var _ = RenderSession.open(
                (owner, name, type) -> {
                    assertEquals(Endpoint.DEBUG_CALLBACK_BRIDGE.owner(), owner);
                    return MethodHandles.insertArguments(target, 0, captured, parameter)
                            .asType(type);
                },
                TestFrames.idle())) {
            int[] calls = new int[1];
            GLDebugMessageCallbackI callback = (s, t, i, v, l, m, p) -> calls[0]++;
            register.invoke(null, callback, 0x123456789L);
            assertEquals(0x123456789L, parameter[0]);
            captured.get().invokeExact(1, 2, 3, 4, 5, 6L, parameter[0]);
            assertEquals(1, calls[0]);
            register.invoke(null, null, 0L);
            assertNull(captured.get());
        }
    }

    @Test
    void rewritesOriginalGl43RegistrationToo() throws Exception {
        var prepared = new RuntimePreparation().prepare(fixture("org/lwjgl/opengl/GL43"));
        assertTrue(prepared.ready(), prepared.blockers().toString());
    }

    public static void capture(
            AtomicReference<MethodHandle> callback, long[] parameter, MethodHandle handle, long pointer) {
        callback.set(handle);
        parameter[0] = pointer;
    }

    private static byte[] fixture(String owner) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "zombie/DebugFixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", DESCRIPTOR, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.LLOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "glDebugMessageCallback", DESCRIPTOR, false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
