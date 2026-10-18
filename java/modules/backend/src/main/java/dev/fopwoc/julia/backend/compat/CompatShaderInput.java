package dev.fopwoc.julia.backend.compat;

import com.gtnewhorizons.angelica.glsm.CompatShaderTransformer;
import com.gtnewhorizons.angelica.glsm.GlslTransformUtils;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.HostedBackend;
import java.util.regex.Pattern;

/** Resolves conditional GLSL before the upstream syntax-tree compatibility rewrite. */
public final class CompatShaderInput {
    private static final Pattern RENAMED_TEXTURE = Pattern.compile("\\bangelica_renamed_texture\\b");

    private CompatShaderInput() {}

    public static String transform(String source, int type, boolean fragment) {
        BackendInternals.context();
        if (!(BackendManager.RENDER_BACKEND instanceof HostedBackend backend))
            throw new IllegalStateException("Expected Julia's hosted SDL backend");
        if (source.endsWith("\0")) source = source.substring(0, source.length() - 1);
        String resolved = backend.preprocessShaderSource(type, source);
        // Upstream already maps public uniform queries from texture to gtexture. Use that
        // identifier before it replaces texture2D calls, even on its core-shader fast path.
        resolved = RENAMED_TEXTURE
                .matcher(GlslTransformUtils.replaceTexture(resolved))
                .replaceAll("gtexture");
        return CompatShaderTransformer.transform(resolved, type, fragment);
    }
}
