package dev.fopwoc.julia.host;

import dev.fopwoc.julia.audit.scan.GraphicsArchiveScanner;
import dev.fopwoc.julia.audit.scan.GraphicsClassScanner;
import dev.fopwoc.julia.runtime.StartupHooks;
import dev.fopwoc.julia.transform.prepare.ArchivePlan;
import dev.fopwoc.julia.transform.prepare.RuntimePreparation;
import dev.fopwoc.julia.utils.io.Sha256;
import java.lang.instrument.ClassFileTransformer;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;

/** Publishes only preflighted, byte-identical consumers. Transformer exceptions alone would silently retain native GL. */
public final class PreparedTransformer implements ClassFileTransformer {
    private static final System.Logger LOG = System.getLogger(PreparedTransformer.class.getName());
    private volatile Map<String, ArchivePlan.Entry> entries;
    private final GraphicsClassScanner graphics = new GraphicsClassScanner(RuntimePreparation::isGraphicsConsumer);
    private final ClassLoader consumerLoader;
    private final CodeSource runtimeSource =
            StartupHooks.class.getProtectionDomain().getCodeSource();

    public PreparedTransformer(ArchivePlan plan, ClassLoader consumerLoader) {
        this.consumerLoader = consumerLoader;
        publish(plan);
    }

    public void publish(ArchivePlan plan) {
        plan.requireBytecode();
        var next = new HashMap<String, ArchivePlan.Entry>();
        for (var entry : plan.entries()) next.put(entry.prepared().name(), entry);
        entries = Map.copyOf(next);
    }

    @Override
    public byte[] transform(
            ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
        if (name == null || loader != consumerLoader || GraphicsArchiveScanner.isBindingClass(name)) return null;
        if (name.startsWith("dev/fopwoc/julia/runtime/")
                && domain != null
                && runtimeSource != null
                && runtimeSource.equals(domain.getCodeSource())) return null;
        try {
            var entry = entries.get(name);
            if (entry == null) {
                if (graphics.scan(bytes).isEmpty()) return null;
                return reject(name, "Graphics consumer was not part of startup preparation");
            }
            if (redefined != null || !Sha256.of(bytes).equals(entry.sha256()))
                return reject(name, "Consumer changed after startup preparation");
            LOG.log(System.Logger.Level.DEBUG, "Installing prepared class {0}", name);
            return entry.prepared().requireBytecode();
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.ERROR, "Cannot safely transform " + name, failure);
            return new byte[] {0};
        }
    }

    private static byte[] reject(String name, String reason) {
        LOG.log(System.Logger.Level.ERROR, "Refusing class {0}: {1}", name, reason);
        // Empty arrays allocate a null JVMTI buffer and are ignored. A nonempty invalid class fails definition closed.
        return new byte[] {0};
    }
}
