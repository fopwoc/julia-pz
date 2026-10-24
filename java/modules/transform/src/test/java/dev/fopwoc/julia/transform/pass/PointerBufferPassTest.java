package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.transform.Endpoint;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class PointerBufferPassTest {
    private static final String DRAW = "(ILjava/nio/IntBuffer;ILorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;)V";

    @Test
    void routesPointerBufferMultiDrawsThroughOneJdkOnlyHelper() {
        var node = new ClassNode();
        node.name = "example/Shadows";
        var method = new MethodNode();
        node.methods.add(method);
        var first = new MethodInsnNode(
                Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL32", "glMultiDrawElementsBaseVertex", DRAW, false);
        var second = new MethodInsnNode(
                Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL45C", "glMultiDrawElementsBaseVertex", DRAW, false);
        method.instructions.add(first);
        method.instructions.add(second);
        PointerBufferPass.adapt(node);
        assertEquals(2, node.methods.size());
        var helper = node.methods.get(1);
        for (var call : new MethodInsnNode[] {first, second}) {
            assertEquals(node.name, call.owner);
            assertEquals(helper.name, call.name);
        }
        var bridge = helper.instructions.getLast().getPrevious();
        assertInstanceOf(MethodInsnNode.class, bridge);
        var target = (MethodInsnNode) bridge;
        assertEquals(Endpoint.DRAW_RANGE_BRIDGE.owner(), target.owner);
        assertDoesNotThrow(() -> DispatchPass.checkDescriptor(target.desc));
        assertEquals(Type.getArgumentTypes(DRAW).length + 1, Type.getArgumentTypes(target.desc).length);
    }

    @Test
    void leavesClassesWithoutPointerBufferCallsUntouched() {
        var node = new ClassNode();
        node.name = "example/Plain";
        node.methods.add(new MethodNode());
        PointerBufferPass.adapt(node);
        assertEquals(1, node.methods.size());
    }
}
