package dev.julia.transform.load;

import static org.junit.jupiter.api.Assertions.*;

import dev.julia.audit.scan.GraphicsClassScanner;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;

class SceneTransformTest {
    @Test
    void routesRealLwjglCallsToGlsm() {
        byte[] transformed = SceneTransform.adapt(scene(true, false));
        var references = new GraphicsClassScanner(symbol -> true).scan(transformed);
        var node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        assertTrue(node.methods.stream()
                .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                .filter(InvokeDynamicInsnNode.class::isInstance)
                .map(InvokeDynamicInsnNode.class::cast)
                .anyMatch(call -> call.bsm.getOwner().equals("dev/julia/runtime/RenderDispatch")
                        && call.name.equals("glClear")
                        && Arrays.asList(call.bsmArgs).contains("com/gtnewhorizons/angelica/glsm/GLStateManager")));
        assertFalse(references.stream()
                .anyMatch(reference -> reference.target().owner().startsWith("org/lwjgl/opengl/")));
    }

    @Test
    void rejectsPartialRedirectionBeforeAnyNativeCallCanExecute() {
        var error = assertThrows(IllegalStateException.class, () -> SceneTransform.adapt(scene(true, true)));
        assertTrue(error.getMessage().startsWith("Unredirected scene calls:"));
    }

    @Test
    void rejectsScenesWithoutRedirection() {
        assertThrows(IllegalStateException.class, () -> SceneTransform.adapt(scene(false, false)));
    }

    private static byte[] scene(boolean clear, boolean unknown) {
        var writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V25, Opcodes.ACC_PUBLIC, "dev/julia/probe/adapter/Fixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "draw", "()V", null, null);
        method.visitCode();
        if (clear) {
            method.visitLdcInsn(0x4000);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glClear", "(I)V", false);
        }
        if (unknown)
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glUnknownJuliaTest", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
