package dev.julia.transform.load;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

class LegacyNamespacePassTest {
    private static final String LEGACY = "com.gtnewhorizons.angelica.glsm.Fixture";

    @Test
    void remapsInheritanceDescriptorsClassLiteralsAndMethodHandles() {
        var node = read(LegacyNamespacePass.adapt(LEGACY, fixture(null)));
        assertEquals(LegacyNamespacePass.TESSELLATOR_STATE, node.superName);
        assertEquals("Lorg/lwjglx/opengl/Drawable;", node.fields.getFirst().desc);
        var instructions = node.methods.getFirst().instructions;
        var type = (Type) ((LdcInsnNode) instructions.getFirst()).cst;
        assertEquals("org/lwjglx/opengl/GLContext", type.getInternalName());
        var handle = (Handle) ((LdcInsnNode) instructions.get(2)).cst;
        assertEquals("org/lwjglx/opengl/GLContext", handle.getOwner());
        assertEquals("()Lorg/lwjglx/opengl/ContextCapabilities;", handle.getDesc());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesLwjgl3AwareClassesRegardlessOfAnnotationRetention(boolean visible) {
        byte[] original = fixture(visible);
        assertSame(original, LegacyNamespacePass.adapt(LEGACY, original));
    }

    @Test
    void neverRemapsGameBridgeOrProbeClasses() {
        byte[] original = fixture(null);
        for (String name : new String[] {
            "zombie.Fixture",
            "org.lwjgl.opengl.GL11",
            "org.lwjglx.opengl.Display",
            "dev.julia.probe.Fixture",
            "me.eigenraven.lwjgl3ify.Fixture"
        }) {
            assertSame(original, LegacyNamespacePass.adapt(name, original), name);
        }
    }

    private static byte[] fixture(Boolean visible) {
        var writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V25,
                Opcodes.ACC_PUBLIC,
                LEGACY.replace('.', '/'),
                null,
                "net/minecraft/client/renderer/Tessellator",
                null);
        if (visible != null)
            writer.visitAnnotation("Lme/eigenraven/lwjgl3ify/api/Lwjgl3Aware;", visible)
                    .visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "drawable", "Lorg/lwjgl/opengl/Drawable;", null, null)
                .visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "example", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn(Type.getObjectType("org/lwjgl/opengl/GLContext"));
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn(new Handle(
                Opcodes.H_INVOKESTATIC,
                "org/lwjgl/opengl/GLContext",
                "getCapabilities",
                "()Lorg/lwjgl/opengl/ContextCapabilities;",
                false));
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
