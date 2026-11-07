package dev.fopwoc.julia.profiling.agent;

import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;

/** Gives the automation a tick on the game's logic thread, the one that owns Lua and the world. */
public final class LogicInstrumentation {
    public static final String TARGET = "zombie/GameWindow";
    private static final ClassDesc HOOKS = ClassDesc.of("dev.fopwoc.julia.profiling.agent.ProfilingHooks");

    private LogicInstrumentation() {}

    public static byte[] instrument(byte[] bytes, ClassLoader loader) {
        var api = ClassFile.of(
                ClassFile.ClassHierarchyResolverOption.of(ClassHierarchyResolver.ofResourceParsing(loader)));
        var model = api.parse(bytes);
        if (!model.thisClass().asInternalName().equals(TARGET))
            throw new IllegalArgumentException("Unexpected logic owner");
        var methods = model.methods().stream()
                .filter(method -> method.methodName().equalsString("frameStep")
                        && method.methodType().equalsString("()V")
                        && (method.flags().flagsMask() & ClassFile.ACC_STATIC) != 0)
                .toList();
        if (methods.size() != 1 || methods.getFirst().code().isEmpty())
            throw new IllegalArgumentException("Expected one static GameWindow.frameStep()V body");
        return api.transformClass(
                model, ClassTransform.transformingMethodBodies(methods::contains, new CodeTransform() {
                    @Override
                    public void atStart(CodeBuilder builder) {
                        builder.invokestatic(HOOKS, "tick", MethodTypeDesc.of(CD_void));
                    }

                    @Override
                    public void accept(CodeBuilder builder, CodeElement element) {
                        builder.with(element);
                    }
                }));
    }
}
