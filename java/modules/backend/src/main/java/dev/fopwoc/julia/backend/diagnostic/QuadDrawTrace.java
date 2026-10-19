package dev.fopwoc.julia.backend.diagnostic;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;

import dev.fopwoc.julia.backend.BackendInternals;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Opt-in, bounded CPU-state diagnostics; never reads GPU memory or changes GL bindings. */
public final class QuadDrawTrace {
    private static final System.Logger LOG = System.getLogger(QuadDrawTrace.class.getName());
    private static final int LIMIT = Math.clamp(Integer.getInteger("julia.trace.quads", 0), 0, 4096);
    private static int remaining = LIMIT;

    private QuadDrawTrace() {}

    public static void record(int start, int end, int count, int type, long offset) {
        if (remaining == 0) return;
        remaining--;
        var state = BackendInternals.context();
        var resources = BackendInternals.resources();
        var frame = BackendInternals.frames().frame();
        var program = state.boundProgramObj;
        var vao = state.currentVao;
        int ebo = state.getBoundBuffer(GL_ELEMENT_ARRAY_BUFFER);
        var text = new StringBuilder("quad ")
                .append(LIMIT - remaining)
                .append(" frame=")
                .append(frame.frameNumber)
                .append(" submits=")
                .append(frame.midFrameSubmitsThisFrame)
                .append(" program=")
                .append(state.boundProgram)
                .append(" fbo=")
                .append(state.boundFboId)
                .append(" range=")
                .append(start)
                .append(':')
                .append(end)
                .append(" count=")
                .append(count)
                .append(" ebo=")
                .append(ebo)
                .append(" offset=")
                .append(offset)
                .append(" viewport=")
                .append(state.viewportX)
                .append(',')
                .append(state.viewportY)
                .append(',')
                .append(state.viewportW)
                .append(',')
                .append(state.viewportH)
                .append(" scissor=")
                .append(state.scissorEnabled)
                .append(':')
                .append(state.scissorX)
                .append(',')
                .append(state.scissorY)
                .append(',')
                .append(state.scissorW)
                .append(',')
                .append(state.scissorH);
        for (int unit = 0; unit < 2; unit++) {
            int texture = state.boundTextures[unit];
            text.append(" tex")
                    .append(unit)
                    .append('=')
                    .append(texture)
                    .append(':')
                    .append(resources.getTextureHandle(texture))
                    .append(':')
                    .append(resources.getTextureMeta(texture));
        }
        if (program != null) {
            text.append(" samplers=").append(program.samplerTextureUnits);
            for (var entry : program.locationToName.int2ObjectEntrySet())
                if (entry.getValue().equals("ModelViewProjection")
                        || entry.getValue().equals("userDepth")) {
                    var data = state.uniformStaging(program).uniformDataBySlot[entry.getIntKey()];
                    text.append(' ').append(entry.getValue()).append('=').append(Arrays.toString(data));
                }
        }
        var source = resources.getEboShadow(ebo);
        int width = type == GL_UNSIGNED_BYTE ? 1 : type == GL_UNSIGNED_SHORT ? 2 : type == GL_UNSIGNED_INT ? 4 : 0;
        if (source != null && width != 0 && count >= 4 && offset >= 0 && offset + 4L * width <= source.limit()) {
            var indices = source.duplicate().order(ByteOrder.nativeOrder());
            for (int i = 0; i < 4; i++) {
                int at = Math.toIntExact(offset + (long) i * width);
                int vertex = width == 1
                        ? indices.get(at) & 255
                        : width == 2 ? indices.getShort(at) & 65535 : indices.getInt(at);
                text.append(" vertex").append(i).append('=').append(vertex);
                for (int attribute = 0; attribute < 3; attribute++) {
                    int vbo = vao.attribVBO[attribute];
                    text.append(" attr")
                            .append(attribute)
                            .append('@')
                            .append(vbo)
                            .append(':')
                            .append(vao.attribEnabled[attribute])
                            .append(':')
                            .append(vao.attribSize[attribute])
                            .append(':')
                            .append(vao.attribType[attribute]);
                    var vertices = resources.getArrayShadow(vbo);
                    int stride = vao.attribStride[attribute] == 0
                            ? vao.attribSize[attribute] * 4
                            : vao.attribStride[attribute];
                    long position = vao.attribOffset[attribute] + (long) vertex * stride;
                    if (vertices != null
                            && vao.attribType[attribute] == GL_FLOAT
                            && position >= 0
                            && position + vao.attribSize[attribute] * 4L <= vertices.limit()) {
                        var data = vertices.duplicate().order(ByteOrder.nativeOrder());
                        for (int c = 0; c < vao.attribSize[attribute]; c++)
                            text.append(',').append(data.getFloat(Math.toIntExact(position + c * 4L)));
                    }
                }
            }
        } else text.append(" indexShadow=unavailable");
        LOG.log(System.Logger.Level.INFO, text.toString());
    }
}
