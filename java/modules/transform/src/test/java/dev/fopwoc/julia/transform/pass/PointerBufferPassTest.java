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
    void plainMultiDrawGetsItsOwnHelperBesideTheBaseVertexOne() throws Exception {
        var node = new ClassNode();
        node.name = "example/Clipmap";
        var method = new MethodNode();
        node.methods.add(method);
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL14",
                "glMultiDrawElements",
                "(ILjava/nio/IntBuffer;ILorg/lwjgl/PointerBuffer;)V",
                false));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL32", "glMultiDrawElementsBaseVertex", DRAW));
        PointerBufferPass.adapt(node);
        assertEquals(3, node.methods.size());
        var bridge = Class.forName(
                "com.gtnewhorizons.angelica.sdlgpu.bridge.DrawRangeBridge",
                false,
                getClass().getClassLoader());
        for (var helper : node.methods.subList(1, 3)) {
            var target = (MethodInsnNode) helper.instructions.getLast().getPrevious();
            // The bridge must declare exactly the descriptor the helper calls.
            var arguments = Type.getArgumentTypes(target.desc);
            var classes = new Class<?>[arguments.length];
            for (int i = 0; i < arguments.length; i++)
                classes[i] = switch (arguments[i].getSort()) {
                    case Type.INT -> int.class;
                    case Type.LONG -> long.class;
                    default -> Class.forName(arguments[i].getClassName());
                };
            assertNotNull(bridge.getMethod(target.name, classes));
        }
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
