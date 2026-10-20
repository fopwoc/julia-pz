package dev.fopwoc.julia.transform.load;

import java.util.List;
import org.objectweb.asm.tree.ClassNode;

/** A version-pinned rewrite of upstream classes loaded into the isolated runtime. */
public interface UpstreamPatch {
    /** Whether this patch rewrites the class with this binary name. */
    boolean targets(String className);

    /** Edits {@code node} in place and reports whether it changed; a missing pinned anchor throws. */
    boolean apply(String className, ClassNode node);

    /** Runs this patch alone, for tests and audits. */
    default byte[] adapt(String className, byte[] bytes) {
        return UpstreamPatches.apply(List.of(this), className, bytes);
    }
}
