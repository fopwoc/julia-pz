package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_ALIASED_LINE_WIDTH_RANGE;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12.GL_PACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL13.GL_CLAMP_TO_BORDER;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL40.GL_PATCH_VERTICES;
import static org.lwjgl.opengl.GL40.GL_TESS_CONTROL_SHADER;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

/** Calls PZ3D, Viewpoint and their add-ons make on paths SDL GPU covers only partly, checked with real pixels. */
final class ModPathChecks {
    private static final String FULLSCREEN =
            "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}";

    private ModPathChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("mod-paths.txt"));
        tessellation();
        int target = GL45.glCreateTextures(GL_TEXTURE_2D), fbo = glGenFramebuffers();
        try {
            GL45.glTextureStorage2D(target, 1, GL_RGBA8, 2, 2);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, 2, 2);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            borderWrap(target);
            unsignedUniform(target);
            multiDraw(target);
            subImage(target);
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
        }
        integerClear();
        bufferRanges();
        stateQueries();
        floatToUnorm();
        Files.writeString(
                output.resolve("mod-paths.txt"),
                "PASS\nchecks=tessellation compile failure,generic buffer binding queries,border wrap as edge clamp,border colour,unsigned uniforms,"
                        + "multi-draw base vertex,full-level sub-image readback,zero integer clears,"
                        + "indexed 64-bit buffer ranges,whole storage-buffer bindings,saved-state queries,float uploads into normalized arrays,back stencil reference and masks,indexed blend state,indexed scissor box,per-unit sampler bindings\n");
    }

    // WinterFX tries tessellated snow first and falls back when its program does not build.
    private static void tessellation() {
        int shader = glCreateShader(GL_TESS_CONTROL_SHADER);
        try {
            glShaderSource(shader, "#version 400 core\nlayout(vertices=3)out;void main(){}");
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_FALSE
                    || glGetShaderInfoLog(shader).isEmpty())
                throw new IllegalStateException("Tessellation stage must fail compilation with a log");
        } finally {
            glDeleteShader(shader);
        }
    }

    // PZ3D's shadow map clamps to a white border; SDL has no border, so the edge texel must win over repeating.
    private static void borderWrap(int target) {
        int texture = glGenTextures();
        int program = program(
                FULLSCREEN,
                "#version 330 core\nuniform sampler2D source;out vec4 color;void main(){color=texture(source,vec2(1.25,.5));}");
        try (var stack = MemoryStack.stackPush()) {
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA8,
                    2,
                    1,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    stack.bytes(
                            (byte) 255, (byte) 0, (byte) 0, (byte) 255, (byte) 0, (byte) 255, (byte) 0, (byte) 255));
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
            glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[] {1, 1, 1, 1});
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "source"), 0);
            glActiveTexture(GL_TEXTURE0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(target, 0, 255, 0, 255, "border wrap repeated instead of clamping to the edge");
        } finally {
            glUseProgram(0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteTextures(texture);
            glDeleteProgram(program);
        }
    }

    // Viewpoint's Iris layer sets unsigned uniforms for shaderpacks.
    private static void unsignedUniform(int target) {
        int program = program(
                FULLSCREEN,
                "#version 330 core\nuniform uint level;uniform float scale;out vec4 color;"
                        + "void main(){color=vec4(float(level)/255.*scale,0,0,1);}");
        try {
            glUseProgram(program);
            glUniform1f(glGetUniformLocation(program, "scale"), 1);
            glUniform1ui(glGetUniformLocation(program, "level"), 200);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(target, 200, 0, 0, 255, "unsigned uniform value differs");
            try {
                glUniform1ui(glGetUniformLocation(program, "scale"), 1);
                throw new IllegalStateException("Unsigned upload to a float uniform was accepted");
            } catch (IllegalArgumentException expected) {
            }
        } finally {
            glUseProgram(0);
            glDeleteProgram(program);
        }
    }

    // PZ3D's shadow map draws its slices with one call; the second draw only reaches the right half by base vertex.
    private static void multiDraw(int target) {
        int program = program(
                "#version 330 core\nlayout(location=0)in vec2 position;void main(){gl_Position=vec4(position,0,1);}",
                "#version 330 core\nout vec4 color;void main(){color=vec4(0,0,1,1);}");
        int vao = glGenVertexArrays(), vertices = glGenBuffers(), indices = glGenBuffers();
        try (var stack = MemoryStack.stackPush()) {
            glClearBufferfv(GL_COLOR, 0, new float[] {0, 0, 0, 0});
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vertices);
            glBufferData(GL_ARRAY_BUFFER, stack.floats(-1, -3, -1, 3, 0, 0, 1, -3, 1, 3, 0, 0), GL_STATIC_DRAW);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0);
            glEnableVertexAttribArray(0);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, stack.ints(0, 1, 2), GL_STATIC_DRAW);
            glUseProgram(program);
            GL32.glMultiDrawElementsBaseVertex(
                    GL_TRIANGLES, stack.ints(3, 3), GL_UNSIGNED_INT, stack.pointers(0L, 0L), stack.ints(0, 3));
            verify(target, 0, 0, 255, 255, "multi-draw with base vertices missed a half");
        } finally {
            glUseProgram(0);
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glDeleteVertexArrays(vao);
            glDeleteBuffers(vertices);
            glDeleteBuffers(indices);
            glDeleteProgram(program);
        }
    }

    // Immersive Weathers stages whole textures into a pixel-pack buffer through the sub-image entry point.
    private static void subImage(int target) {
        int pack = glGenBuffers();
        try (var stack = MemoryStack.stackPush()) {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pack);
            glBufferData(GL_PIXEL_PACK_BUFFER, 16, GL_STREAM_READ);
            GL45.glGetTextureSubImage(target, 0, 0, 0, 0, 2, 2, 1, GL_RGBA, GL_UNSIGNED_BYTE, 16, 0L);
            var pixels = stack.malloc(16);
            glGetBufferSubData(GL_PIXEL_PACK_BUFFER, 0, pixels);
            for (int i = 0; i < 16; i += 4)
                if (Byte.toUnsignedInt(pixels.get(i + 2)) != 255 || pixels.get(i) != 0)
                    throw new IllegalStateException("Sub-image readback differs at byte " + i);
            try {
                GL45.glGetTextureSubImage(target, 0, 1, 0, 0, 1, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, 4, 0L);
                throw new IllegalStateException("Partial sub-image readback was accepted");
            } catch (UnsupportedOperationException expected) {
            }
        } finally {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glDeleteBuffers(pack);
        }
    }

    // Viewpoint clears integer Iris targets; zero is the clear value every SDL backend reads the same way.
    private static void integerClear() {
        int texture = GL45.glCreateTextures(GL_TEXTURE_2D), fbo = glGenFramebuffers();
        int program = program(FULLSCREEN, "#version 330 core\nout uint value;void main(){value=77u;}");
        try {
            GL45.glTextureStorage2D(texture, 1, GL_R32UI, 2, 2);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            glViewport(0, 0, 2, 2);
            glUseProgram(program);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glClearBufferuiv(GL_COLOR, 0, new int[] {0, 0, 0, 0});
            // Clears stay pending until the target is used; the raw diagnostic readback needs it applied first.
            Upstream.clears()
                    .materializePendingClearForTexture(
                            Upstream.context(), Upstream.resources().getTextureHandle(texture));
            var values = ByteBuffer.wrap(ResourceReadback.layer(texture, 0))
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
            for (int i = 0; i < 4; i++)
                if (values.get(i) != 0) throw new IllegalStateException("Integer clear left " + values.get(i));
            try {
                glClearBufferuiv(GL_COLOR, 0, new int[] {1, 0, 0, 0});
                throw new IllegalStateException("Non-portable integer clear was accepted");
            } catch (UnsupportedOperationException expected) {
            }
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(texture);
            glDeleteProgram(program);
        }
    }

    // PZ3D saves and restores indexed uniform and storage bindings with their ranges.
    private static void bufferRanges() {
        int uniform = glGenBuffers(), storage = glGenBuffers();
        try {
            glBindBuffer(GL_UNIFORM_BUFFER, uniform);
            glBufferData(GL_UNIFORM_BUFFER, 512, GL_DYNAMIC_DRAW);
            glBindBufferRange(GL_UNIFORM_BUFFER, 2, uniform, 256, 64);
            if (GL32.glGetInteger64i(GL_UNIFORM_BUFFER_START, 2) != 256
                    || GL32.glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, 2) != 64)
                throw new IllegalStateException("Uniform buffer range query differs");
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, storage);
            glBufferData(GL_SHADER_STORAGE_BUFFER, 64, GL_DYNAMIC_DRAW);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, storage);
            if (glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 0) != storage
                    || GL32.glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 0) != 0
                    || GL32.glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 0) != 0)
                throw new IllegalStateException("Storage buffer binding query differs");
            // GrassierGrass saves the generic storage binding next to the indexed ones.
            int[] binding = {-1};
            glGetIntegerv(GL_SHADER_STORAGE_BUFFER_BINDING, binding);
            if (glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING) != storage
                    || binding[0] != storage
                    || glGetInteger(GL_UNIFORM_BUFFER_BINDING) != uniform
                    || glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING) != 0
                    || glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING) != 0
                    || glGetInteger(GL_DISPATCH_INDIRECT_BUFFER_BINDING) != 0)
                throw new IllegalStateException("Generic buffer binding queries differ");
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, storage, 0, 64);
            try {
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, storage, 16, 16);
                throw new IllegalStateException("Offset storage range was accepted");
            } catch (UnsupportedOperationException expected) {
            }
        } finally {
            glBindBufferBase(GL_UNIFORM_BUFFER, 2, 0);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, 0);
            glBindBuffer(GL_UNIFORM_BUFFER, 0);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
            glDeleteBuffers(uniform);
            glDeleteBuffers(storage);
        }
    }

    // PZ3D, WinterFX and GrassierGrass save this state before their passes and restore it after.
    // PZ3D fills RGBA8 array textures from float tables; GL clamps and rounds each component on upload.
    private static void floatToUnorm() {
        int texture = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
            GL12.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, 2, 1, 1, 0, GL_RGBA, GL_FLOAT, new float[] {
                0, .5f, 1, 2, -1, .25f, Float.NaN, 1
            });
            byte[] pixels = ResourceReadback.layer(texture, 0);
            int[] expected = {0, 128, 255, 255, 0, 64, 0, 255};
            for (int i = 0; i < expected.length; i++)
                if ((pixels[i] & 255) != expected[i])
                    throw new IllegalStateException("Float upload into RGBA8 byte " + i + " got " + (pixels[i] & 255)
                            + " expected " + expected[i]);
        } finally {
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
            glDeleteTextures(texture);
        }
    }

    private static void stateQueries() {
        glPolygonOffset(2, 3);
        float factor = glGetFloat(GL_POLYGON_OFFSET_FACTOR), units = glGetFloat(GL_POLYGON_OFFSET_UNITS);
        glPolygonOffset(0, 0);
        float[] lines = new float[2], skip = {-1};
        glGetFloatv(GL_ALIASED_LINE_WIDTH_RANGE, lines);
        glPixelStorei(GL_PACK_SKIP_IMAGES, 0);
        glGetFloatv(GL_PACK_SKIP_IMAGES, skip);
        if (factor != 2
                || units != 3
                || lines[0] != 1
                || lines[1] != 1
                || skip[0] != 0
                || glGetInteger(GL_PACK_IMAGE_HEIGHT) != 0
                || glGetInteger(GL_PATCH_VERTICES) != 3)
            throw new IllegalStateException("Raster or pack state query differs");
        try {
            glPixelStorei(GL_PACK_SKIP_IMAGES, 1);
            throw new IllegalStateException("Volume pack layout was accepted");
        } catch (UnsupportedOperationException expected) {
        }
        glStencilOpSeparate(GL_BACK, GL_KEEP, GL_INCR, GL_REPLACE);
        glStencilFuncSeparate(GL_BACK, GL_LEQUAL, 0, 0xff);
        boolean stencil = glGetInteger(GL_STENCIL_BACK_FAIL) == GL_KEEP
                && glGetInteger(GL_STENCIL_BACK_PASS_DEPTH_FAIL) == GL_INCR
                && glGetInteger(GL_STENCIL_BACK_PASS_DEPTH_PASS) == GL_REPLACE
                && glGetInteger(GL_STENCIL_BACK_FUNC) == GL_LEQUAL;
        // PZ3D saves the whole back-face stencil state, including its reference and masks.
        glStencilFuncSeparate(GL_BACK, GL_LEQUAL, 5, 0x3f);
        glStencilMaskSeparate(GL_BACK, 0x0f);
        stencil &= glGetInteger(GL_STENCIL_BACK_REF) == 5
                && glGetInteger(GL_STENCIL_BACK_VALUE_MASK) == 0x3f
                && glGetInteger(GL_STENCIL_BACK_WRITEMASK) == 0x0f;
        glStencilOpSeparate(GL_BACK, GL_KEEP, GL_KEEP, GL_KEEP);
        glStencilFuncSeparate(GL_BACK, GL_ALWAYS, 0, 0xff);
        glStencilMaskSeparate(GL_BACK, 0xff);
        if (!stencil) throw new IllegalStateException("Back stencil state query differs");
        // PZ3D also saves every draw buffer's blend state and the indexed scissor box.
        GL40.glBlendFuncSeparatei(1, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
        GL40.glBlendEquationSeparatei(1, GL_FUNC_ADD, GL_MAX);
        boolean blend = glGetIntegeri(GL_BLEND_SRC_RGB, 1) == GL_SRC_ALPHA
                && glGetIntegeri(GL_BLEND_DST_RGB, 1) == GL_ONE_MINUS_SRC_ALPHA
                && glGetIntegeri(GL_BLEND_SRC_ALPHA, 1) == GL_ONE
                && glGetIntegeri(GL_BLEND_DST_ALPHA, 1) == GL_ZERO
                && glGetIntegeri(GL_BLEND_EQUATION_RGB, 1) == GL_FUNC_ADD
                && glGetIntegeri(GL_BLEND_EQUATION_ALPHA, 1) == GL_MAX
                && glGetIntegeri(GL_BLEND_EQUATION_ALPHA, 0) == GL_FUNC_ADD;
        glBlendFunc(GL_ONE, GL_ZERO);
        glBlendEquation(GL_FUNC_ADD);
        if (!blend) throw new IllegalStateException("Indexed blend state query differs");
        int sampler = GL33.glGenSamplers();
        GL33.glBindSampler(2, sampler);
        boolean samplers =
                glGetIntegeri(GL33.GL_SAMPLER_BINDING, 2) == sampler && glGetIntegeri(GL33.GL_SAMPLER_BINDING, 1) == 0;
        GL33.glBindSampler(2, 0);
        GL33.glDeleteSamplers(sampler);
        if (!samplers) throw new IllegalStateException("Per-unit sampler binding query differs");
        int[] box = new int[4];
        glScissor(1, 2, 3, 4);
        glGetIntegeri_v(GL_SCISSOR_BOX, 0, box);
        if (box[0] != 1 || box[1] != 2 || box[2] != 3 || box[3] != 4)
            throw new IllegalStateException("Indexed scissor box query differs");
        int renderbuffer = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
        int bound = glGetInteger(GL_RENDERBUFFER_BINDING);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        glDeleteRenderbuffers(renderbuffer);
        if (bound != renderbuffer || glGetInteger(GL_RENDERBUFFER_BINDING) != 0)
            throw new IllegalStateException("Renderbuffer binding query differs");
        int first = GL45.glCreateTextures(GL_TEXTURE_2D), second = GL45.glCreateTextures(GL_TEXTURE_2D);
        int fbo = glGenFramebuffers();
        try {
            for (int texture : new int[] {first, second}) GL45.glTextureStorage2D(texture, 1, GL_RGBA8, 2, 2);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, first, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, second, 0);
            glDrawBuffers(new int[] {GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT0});
            if (glGetInteger(GL_DRAW_BUFFER0) != GL_COLOR_ATTACHMENT1
                    || glGetInteger(GL_DRAW_BUFFER1) != GL_COLOR_ATTACHMENT0
                    || glGetInteger(GL_DRAW_BUFFER2) != GL_NONE)
                throw new IllegalStateException("Draw buffer queries differ");
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(first);
            glDeleteTextures(second);
        }
    }

    private static void verify(int texture, int red, int green, int blue, int alpha, String failure) {
        byte[] pixels = ResourceReadback.layer(texture, 0);
        int[] expected = {red, green, blue, alpha};
        for (int i = 0; i < pixels.length; i++)
            if (Math.abs(Byte.toUnsignedInt(pixels[i]) - expected[i % 4]) > 1)
                throw new IllegalStateException(failure + ": byte=" + i + " actual=" + Byte.toUnsignedInt(pixels[i])
                        + " expected=" + expected[i % 4]);
    }

    private static int program(String vertex, String fragment) {
        int program = glCreateProgram();
        for (int type : new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(type);
            glShaderSource(shader, type == GL_VERTEX_SHADER ? vertex : fragment);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glDeleteShader(shader);
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetProgramInfoLog(program));
        return program;
    }
}
