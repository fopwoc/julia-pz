package dev.fopwoc.julia.profiling.agent;

import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Map;

/** The hooks only a scene run needs: a tick on the logic thread, the status line, and shutting out player input. */
public final class AutomationInstrumentation {
    private static final ClassDesc HOOKS = ClassDesc.of("dev.fopwoc.julia.profiling.agent.ProfilingHooks");
    private static final MethodTypeDesc NOTHING = MethodTypeDesc.of(CD_void);

    private enum Change {
        CALL_TICK_FIRST,
        CALL_OVERLAY_FIRST,
        SKIP_BODY
    }

    private static final Map<String, Target> TARGETS = Map.of(
            "zombie/GameWindow", new Target("frameStep", Change.CALL_TICK_FIRST, true),
            // Every state ends its UI frame here, the menu and loading screens included.
            "zombie/core/Core", new Target("EndFrameUI", Change.CALL_OVERLAY_FIRST, false),
            // A stray key or click, pause above all, would change what's being measured.
            "zombie/input/GameKeyboard", new Target("update", Change.SKIP_BODY, true),
            "zombie/input/Mouse", new Target("update", Change.SKIP_BODY, true));

    private record Target(String method, Change change, boolean isStatic) {}

    private AutomationInstrumentation() {}

    public static Iterable<String> targets() {
        return TARGETS.keySet();
    }

    public static boolean targets(String name) {
        return TARGETS.containsKey(name);
    }

    public static byte[] instrument(byte[] bytes, ClassLoader loader) {
        var api = ClassFile.of(
                ClassFile.ClassHierarchyResolverOption.of(ClassHierarchyResolver.ofResourceParsing(loader)));
        var model = api.parse(bytes);
        var target = TARGETS.get(model.thisClass().asInternalName());
        if (target == null)
            throw new IllegalArgumentException(
                    "Unexpected class " + model.thisClass().asInternalName());
        var methods = model.methods().stream()
                .filter(method -> method.methodName().equalsString(target.method())
                        && method.methodType().equalsString("()V")
                        && ((method.flags().flagsMask() & ClassFile.ACC_STATIC) != 0) == target.isStatic())
                .toList();
        if (methods.size() != 1 || methods.getFirst().code().isEmpty())
            throw new IllegalArgumentException("Expected one " + target.method() + "()V in "
                    + model.thisClass().asInternalName());
        return api.transformClass(
                model, ClassTransform.transformingMethodBodies(methods::contains, new CodeTransform() {
                    @Override
                    public void atStart(CodeBuilder builder) {
                        switch (target.change()) {
                            case CALL_TICK_FIRST -> builder.invokestatic(HOOKS, "tick", NOTHING);
                            // Not a bare return: the class verifier rejects debug tables pointing into code left
                            // unreachable.
                            case SKIP_BODY ->
                                builder.invokestatic(HOOKS, "inputBlocked", MethodTypeDesc.of(CD_boolean))
                                        .ifThen(block -> block.return_());
                            case CALL_OVERLAY_FIRST -> builder.invokestatic(HOOKS, "overlay", NOTHING);
                        }
                    }

                    @Override
                    public void accept(CodeBuilder builder, CodeElement element) {
                        builder.with(element);
                    }
                }));
    }
}
