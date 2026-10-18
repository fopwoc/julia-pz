package dev.julia.audit.scan;

import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.constant.ConstantDescs.*;
import static java.lang.constant.DirectMethodHandleDesc.Kind.STATIC;
import static org.junit.jupiter.api.Assertions.*;

import dev.julia.audit.model.GraphicsReference;
import dev.julia.audit.model.GraphicsSymbol;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class GraphicsClassScannerTest {
    private static final ClassDesc GL = ClassDesc.of("org.lwjgl.opengl.GL11");
    private final GraphicsClassScanner scanner = new GraphicsClassScanner();

    @Test
    void preservesOverloadDescriptorsAndCallerIdentity() {
        var references = scanner.scan(fixture(code -> code.iconst_0()
                .invokestatic(GL, "glDeleteTextures", MethodTypeDesc.of(CD_void, CD_int))
                .aconst_null()
                .invokestatic(GL, "glDeleteTextures", MethodTypeDesc.of(CD_void, ClassDesc.of("java.nio.IntBuffer")))
                .return_()));
        assertEquals(2, references.size());
        assertEquals("(I)V", references.get(0).target().descriptor());
        assertEquals("(Ljava/nio/IntBuffer;)V", references.get(1).target().descriptor());
        assertEquals("example/Renderer", references.getFirst().callerClass());
        assertEquals("render", references.getFirst().callerMethod());
        assertEquals("()V", references.getFirst().callerDescriptor());
    }

    @Test
    void includesCapabilityFieldsAndWindowCalls() {
        var references = scanner.scan(fixture(code -> code.getstatic(
                        ClassDesc.of("org.lwjgl.opengl.GL"), "exampleCapability", CD_boolean)
                .pop()
                .lconst_0()
                .invokestatic(
                        ClassDesc.of("org.lwjgl.glfw.GLFW"), "glfwSwapBuffers", MethodTypeDesc.of(CD_void, CD_long))
                .return_()));
        assertEquals(GraphicsSymbol.Kind.FIELD, references.getFirst().target().kind());
        assertEquals("glfwSwapBuffers", references.getLast().target().name());
    }

    @Test
    void includesLdcMethodHandles() {
        var handle = MethodHandleDesc.ofMethod(STATIC, GL, "glGetError", MethodTypeDesc.of(CD_int));
        var references = scanner.scan(fixture(code -> code.ldc(handle).pop().return_()));
        assertEquals(1, references.size());
        assertEquals("glGetError", references.getFirst().target().name());
        assertEquals(GraphicsReference.Kind.METHOD_HANDLE, references.getFirst().kind());
    }

    @Test
    void includesLambdaMethodReferences() {
        var metafactory = MethodHandleDesc.ofMethod(
                STATIC,
                ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
                "metafactory",
                MethodTypeDesc.of(
                        CD_CallSite,
                        CD_MethodHandles_Lookup,
                        CD_String,
                        CD_MethodType,
                        CD_MethodType,
                        CD_MethodHandle,
                        CD_MethodType));
        var signature = MethodTypeDesc.of(CD_int);
        var lambda = DynamicCallSiteDesc.of(
                metafactory,
                "getAsInt",
                MethodTypeDesc.of(ClassDesc.of("java.util.function.IntSupplier")),
                signature,
                MethodHandleDesc.ofMethod(STATIC, GL, "glGetError", signature),
                signature);
        var references =
                scanner.scan(fixture(code -> code.invokedynamic(lambda).pop().return_()));
        assertEquals(1, references.size());
        assertEquals("glGetError", references.getFirst().target().name());
        assertEquals(GraphicsReference.Kind.METHOD_HANDLE, references.getFirst().kind());
    }

    @Test
    void ignoresLookalikeStringsAndUnrelatedMethods() {
        var bytes = fixture(code -> code.ldc("org/lwjgl/opengl/GL11.glEnable(I)V")
                .pop()
                .invokestatic(ClassDesc.of("example.GL11"), "glEnable", MethodTypeDesc.of(CD_void))
                .return_());
        assertTrue(scanner.scan(bytes).isEmpty());
    }

    @Test
    void rejectsBrokenClassFiles() {
        assertThrows(IllegalArgumentException.class, () -> scanner.scan(new byte[] {1, 2, 3}));
    }

    private static byte[] fixture(Consumer<CodeBuilder> body) {
        return ClassFile.of()
                .build(
                        ClassDesc.of("example.Renderer"),
                        builder -> builder.withMethodBody(
                                "render", MethodTypeDesc.of(CD_void), ACC_PUBLIC | ACC_STATIC, body));
    }
}
