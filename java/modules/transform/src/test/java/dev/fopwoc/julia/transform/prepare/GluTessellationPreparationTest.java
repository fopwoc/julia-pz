package dev.fopwoc.julia.transform.prepare;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class GluTessellationPreparationTest implements Opcodes {
    @Test
    void preservesCpuTessellationFactoryAndCallbackConstruction() throws Exception {
        var writer = fixture();
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "prepare", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(NEW, "org/lwjgl/util/glu/GLUtessellatorCallbackAdapter");
        method.visitInsn(DUP);
        method.visitMethodInsn(
                INVOKESPECIAL, "org/lwjgl/util/glu/GLUtessellatorCallbackAdapter", "<init>", "()V", false);
        method.visitInsn(POP);
        method.visitMethodInsn(
                INVOKESTATIC, "org/lwjgl/util/glu/GLU", "gluNewTess", "()Lorg/lwjgl/util/glu/GLUtessellator;", false);
        method.visitInsn(DUP);
        method.visitInsn(ACONST_NULL);
        method.visitMethodInsn(
                INVOKEINTERFACE,
                "org/lwjgl/util/glu/GLUtessellator",
                "gluTessBeginPolygon",
                "(Ljava/lang/Object;)V",
                true);
        method.visitMethodInsn(INVOKEINTERFACE, "org/lwjgl/util/glu/GLUtessellator", "gluTessEndPolygon", "()V", true);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        var preparation = new RuntimePreparation();
        for (var result : new PreparedClass[] {
            preparation.prepare(writer.toByteArray()), preparation.prepareMod(writer.toByteArray())
        }) {
            assertTrue(result.ready(), result.blockers().toString());
            assertTrue(result.unsupportedCalls().isEmpty());
            assertEquals(0, result.calls());
        }
    }

    @Test
    void doesNotExemptOtherGluConstructorsOrUnknownGluOperations() throws Exception {
        var writer = fixture();
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(NEW, "org/lwjgl/util/glu/Sphere");
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, "org/lwjgl/util/glu/Sphere", "<init>", "()V", false);
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        assertThrows(IllegalArgumentException.class, () -> new RuntimePreparation().prepareMod(writer.toByteArray()));
        var unknown = fixture();
        var call = unknown.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "()V", null, null);
        call.visitCode();
        call.visitMethodInsn(INVOKESTATIC, "org/lwjgl/util/glu/GLU", "gluUnknown", "()V", false);
        call.visitInsn(RETURN);
        call.visitMaxs(0, 0);
        call.visitEnd();
        unknown.visitEnd();
        var guarded = new RuntimePreparation().prepareMod(unknown.toByteArray());
        assertFalse(guarded.unsupportedCalls().isEmpty());
    }

    private static ClassWriter fixture() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V25, ACC_PUBLIC, "fixture/TessellationConsumer", null, "java/lang/Object", null);
        return writer;
    }
}
