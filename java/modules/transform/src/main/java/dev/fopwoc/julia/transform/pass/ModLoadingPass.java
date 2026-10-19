package dev.fopwoc.julia.transform.pass;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Gates the approved load operation before classpath publication or patch discovery. */
public final class ModLoadingPass {
    public static final String LOADER = "me/zed_0xff/zombie_buddy/Loader";
    private static final String DESCRIPTOR =
            "(Ljava/nio/file/Path;Ljava/lang/String;Ljava/lang/String;L" + LOADER + "$Phase;)Z";

    private ModLoadingPass() {}

    public static void adapt(ClassNode node) {
        if (!node.name.equals(LOADER)) return;
        var candidates = node.methods.stream()
                .filter(m ->
                        m.name.equals("loadJar") && m.desc.equals(DESCRIPTOR) && (m.access & Opcodes.ACC_STATIC) != 0)
                .toList();
        if (candidates.size() != 1)
            throw new IllegalArgumentException("Expected one approved ZombieBuddy loadJar boundary");
        var method = candidates.getFirst();
        int publications = 0;
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) {
                if (Endpoint.MOD_APPROVAL_HOOKS.is(call.owner))
                    throw new IllegalArgumentException("Mod loading already transformed");
                if (call.owner.equals(LOADER)
                        && call.name.equals("addJarToClasspath")
                        && call.desc.equals("(Ljava/nio/file/Path;Ljava/lang/String;Ljava/lang/String;)Z"))
                    publications++;
            }
        if (publications != 1) throw new IllegalArgumentException("Expected one mod classpath publication");
        var accepted = new LabelNode();
        var guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 2));
        guard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Endpoint.MOD_APPROVAL_HOOKS.owner(),
                "prepare",
                "(Ljava/nio/file/Path;Ljava/lang/String;Ljava/lang/String;)Z",
                false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, accepted));
        guard.add(new InsnNode(Opcodes.ICONST_0));
        guard.add(new InsnNode(Opcodes.IRETURN));
        guard.add(accepted);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(guard);
        method.maxStack = Math.max(3, method.maxStack);
    }
}
