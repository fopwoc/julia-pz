package dev.fopwoc.julia.profiling.agent;

import static java.lang.constant.ConstantDescs.CD_boolean;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.FieldInstruction;

/** Scenario-only continuation: preserves the loading state's asset, streaming and error checks. */
public final class LoadingInstrumentation {
    public static final String TARGET = "zombie/gameStates/GameLoadingState";

    private LoadingInstrumentation() {}

    public static byte[] instrument(byte[] bytes, ClassLoader loader) {
        var api = ClassFile.of(
                ClassFile.ClassHierarchyResolverOption.of(ClassHierarchyResolver.ofResourceParsing(loader)));
        var model = api.parse(bytes);
        if (!model.thisClass().asInternalName().equals(TARGET))
            throw new IllegalArgumentException("Unexpected loading state");
        var fields = model.fields().stream()
                .filter(field -> field.fieldName().equalsString("forceDone")
                        && field.fieldType().equalsString("Z")
                        && (field.flags().flagsMask() & ClassFile.ACC_STATIC) == 0)
                .toList();
        var methods = model.methods().stream()
                .filter(method -> method.methodName().equalsString("update")
                        && method.methodType().equalsString("()Lzombie/gameStates/GameStateMachine$StateAction;")
                        && (method.flags().flagsMask() & ClassFile.ACC_STATIC) == 0)
                .toList();
        if (fields.size() != 1
                || methods.size() != 1
                || methods.getFirst().code().isEmpty())
            throw new IllegalArgumentException("Loading-state continuation contract changed");
        boolean readsFlag = methods.getFirst()
                .code()
                .orElseThrow()
                .elementStream()
                .anyMatch(element -> element instanceof FieldInstruction field
                        && field.opcode() == java.lang.classfile.Opcode.GETFIELD
                        && field.owner().asInternalName().equals(TARGET)
                        && field.name().equalsString("forceDone"));
        if (!readsFlag) throw new IllegalArgumentException("Loading state no longer checks continuation flag");
        return api.transformClass(
                model, ClassTransform.transformingMethodBodies(methods::contains, new CodeTransform() {
                    @Override
                    public void accept(CodeBuilder builder, CodeElement element) {
                        builder.with(element);
                    }

                    @Override
                    public void atStart(CodeBuilder builder) {
                        // The original update still returns early while assets load or any loading error is active.
                        builder.aload(0).iconst_1().putfield(model.thisClass().asSymbol(), "forceDone", CD_boolean);
                    }
                }));
    }
}
