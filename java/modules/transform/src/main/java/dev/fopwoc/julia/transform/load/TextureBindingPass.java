package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Select the operation target before GLSM consults its single-binding cache; queries use logical state. */
public final class TextureBindingPass implements UpstreamPatch {
    private static final Set<String> OPERATIONS = Set.of(
            "glTexParameteri",
            "glTexParameterf",
            "glTexParameter",
            "glGetTexParameteri",
            "glGetTexParameterf",
            "glGetTexParameter",
            "glTexImage1D",
            "glTexImage2D",
            "glTexImage3D",
            "glTexSubImage1D",
            "glTexSubImage2D",
            "glTexSubImage3D",
            "glTexStorage2D",
            "glTexStorage3D",
            "glGenerateMipmap",
            "glGetTexImage",
            "glGetTexLevelParameteri",
            "glGetTexLevelParameterf",
            "glGetTexLevelParameter",
            "glCopyTexImage1D",
            "glCopyTexImage2D",
            "glCopyTexSubImage1D",
            "glCopyTexSubImage2D",
            "glCopyTexSubImage3D",
            "glCompressedTexImage1D",
            "glCompressedTexImage2D",
            "glCompressedTexImage3D",
            "glCompressedTexSubImage1D",
            "glCompressedTexSubImage2D",
            "glCompressedTexSubImage3D");

    @Override
    public boolean targets(String name) {
        return name.equals("com.gtnewhorizons.angelica.glsm.GLStateManager");
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        int selected = 0, queries = 0, deletes = 0;
        for (var method : node.methods) {
            if (OPERATIONS.contains(method.name)
                    && method.desc.startsWith("(I")
                    && (method.access & Opcodes.ACC_STATIC) != 0) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ILOAD, 0));
                head.add(call("select", "(I)V"));
                method.instructions.insert(head);
                selected++;
            }
            if (method.name.equals("glGetInteger")
                    && method.desc.equals("(Lcom/gtnewhorizons/angelica/glsm/GLContextState;I)I")) {
                var head = new InsnList();
                var next = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ILOAD, 1));
                head.add(call("bindingQuery", "(I)Z"));
                head.add(new JumpInsnNode(Opcodes.IFEQ, next));
                head.add(new VarInsnNode(Opcodes.ILOAD, 1));
                head.add(call("query", "(I)I"));
                head.add(new InsnNode(Opcodes.IRETURN));
                head.add(next);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                queries++;
            }
            if (method.name.equals("onDeleteTexture") && method.desc.equals("(I)V")) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ILOAD, 0));
                head.add(call("deleted", "(I)V"));
                method.instructions.insert(head);
                deletes++;
            }
        }
        Anchors.require("texture-target selection", selected, 30);
        Anchors.require("texture binding query", queries, 1);
        Anchors.require("texture deletion", deletes, 1);
        return true;
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC, Endpoint.TEXTURE_BINDING_HOOKS.owner(), name, descriptor, false);
    }
}
