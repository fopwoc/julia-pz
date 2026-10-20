package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.lwjgl.system.MemoryStack;

/** Program interface queries preserve GL resource identities, active stage flags and caller buffer positions. */
final class ProgramInterfaceChecks {
    private ProgramInterfaceChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("program-interface.txt"));
        int program = glCreateProgram(),
                vertex = glCreateShader(GL_VERTEX_SHADER),
                fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int texture = glGenTextures(), fbo = glGenFramebuffers();
        try (var stack = MemoryStack.stackPush()) {
            compile(
                    vertex,
                    "#version 460 core\nuniform float sharedValue;uniform float vertexOnly;uniform sampler2D commonImage;out vec3 value;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);value=vec3(sharedValue,vertexOnly,texelFetch(commonImage,ivec2(0),0).r);}");
            compile(
                    fragment,
                    "#version 460 core\nuniform float sharedValue;uniform float fragmentOnly;uniform float unusedValue;uniform int imageIndex;uniform sampler2D commonImage;uniform sampler2D images[16];uniform sampler2D singleImage[1];uniform sampler2DArrayShadow shadows;uniform isampler3D signedVolume;in vec3 value;out vec4 color;void main(){color=texture(images[imageIndex],vec2(.5))+texture(singleImage[0],vec2(.5))+texture(commonImage,vec2(.5))+vec4(value+vec3(sharedValue,fragmentOnly,float(texelFetch(signedVolume,ivec3(0),0).r)),texture(shadows,vec4(.5,.5,0,.5)));}");
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            link(program);
            int count = glGetProgramInterfacei(program, GL_UNIFORM, GL_ACTIVE_RESOURCES);
            if (count != 9) throw new IllegalStateException("Active uniform resource count: " + count);
            if (glGetProgramResourceIndex(program, GL_UNIFORM, "unusedValue") != -1)
                throw new IllegalStateException("Inactive uniform included");
            check(program, "images", GL_SAMPLER_2D, 16, 0, 1);
            check(program, "singleImage", GL_SAMPLER_2D, 1, 0, 1);
            check(program, "shadows", GL_SAMPLER_2D_ARRAY_SHADOW, 1, 0, 1);
            check(program, "signedVolume", GL_INT_SAMPLER_3D, 1, 0, 1);
            check(program, "commonImage", GL_SAMPLER_2D, 1, 1, 1);
            check(program, "sharedValue", GL_FLOAT, 1, 1, 1);
            check(program, "vertexOnly", GL_FLOAT, 1, 1, 0);
            int index = glGetProgramResourceIndex(program, GL_UNIFORM, "images[0]");
            if (index != glGetProgramResourceIndex(program, GL_UNIFORM, "images"))
                throw new IllegalStateException("Array base resource alias missing");
            if (!glGetProgramResourceName(program, GL_UNIFORM, index, 100).equals("images[0]"))
                throw new IllegalStateException("Array resource name missing [0]");
            var props = stack.ints(-99, GL_TYPE, GL_ARRAY_SIZE, -99);
            props.position(1).limit(3);
            var length = stack.ints(-99, -99, -99);
            length.position(1).limit(2);
            var values = stack.ints(-99, -99, -99, -99);
            values.position(1).limit(3);
            glGetProgramResourceiv(program, GL_UNIFORM, index, props, length, values);
            if (props.position() != 1
                    || length.position() != 1
                    || values.position() != 1
                    || length.get(1) != 2
                    || values.get(1) != GL_SAMPLER_2D
                    || values.get(2) != 16
                    || values.get(0) != -99
                    || values.duplicate().clear().get(3) != -99)
                throw new IllegalStateException("Interface query consumed positions or output canaries");
            glGetProgramInterfaceiv(program, GL_UNIFORM, GL_ACTIVE_RESOURCES, length);
            if (length.position() != 1 || length.get(1) != count)
                throw new IllegalStateException("Interface buffer query position mismatch");
            int[] array = {-99, -99};
            glGetProgramInterfaceiv(program, GL_UNIFORM, GL_ACTIVE_RESOURCES, array);
            if (array[0] != count || array[1] != -99)
                throw new IllegalStateException("Interface array query overwrote its tail");
            int longest = 0;
            for (int i = 0; i < count; i++)
                longest = Math.max(
                        longest,
                        glGetProgramResourceName(program, GL_UNIFORM, i, 1000).length() + 1);
            if (glGetProgramInterfacei(program, GL_UNIFORM, GL_MAX_NAME_LENGTH) != longest)
                throw new IllegalStateException("Interface max-name length mismatch");
            // Public names must not expose the compatibility transform's internal gtexture rename.
            compile(vertex, "#version 120\nvoid main(){gl_Position=vec4(0,0,0,1);}");
            compile(
                    fragment,
                    "#version 120\nuniform sampler2D texture;void main(){gl_FragColor=texture2D(texture,vec2(.5));}");
            link(program);
            int legacy = glGetProgramResourceIndex(program, GL_UNIFORM, "texture");
            int[] legacyLength = {-1};
            glGetProgramResourceiv(program, GL_UNIFORM, legacy, new int[] {GL_NAME_LENGTH}, (int[]) null, legacyLength);
            int publicMaximum = 0;
            for (int i = 0; i < glGetProgramInterfacei(program, GL_UNIFORM, GL_ACTIVE_RESOURCES); i++)
                publicMaximum = Math.max(
                        publicMaximum,
                        glGetProgramResourceName(program, GL_UNIFORM, i, 1000).length() + 1);
            if (!glGetProgramResourceName(program, GL_UNIFORM, legacy, 100).equals("texture")
                    || legacyLength[0] != 8
                    || glGetProgramInterfacei(program, GL_UNIFORM, GL_MAX_NAME_LENGTH) != publicMaximum)
                throw new IllegalStateException("Compatibility uniform interface mismatch: name="
                        + glGetProgramResourceName(program, GL_UNIFORM, legacy, 100) + " length=" + legacyLength[0]
                        + " max=" + glGetProgramInterfacei(program, GL_UNIFORM, GL_MAX_NAME_LENGTH));
            compute();
            // Relinking changes the reflected interface; use the queried numeric location in a real Metal draw.
            compile(
                    vertex,
                    "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}");
            compile(fragment, "#version 330 core\nuniform vec4 tint;out vec4 color;void main(){color=tint;}");
            link(program);
            if (glGetProgramInterfacei(program, GL_UNIFORM, GL_ACTIVE_RESOURCES) != 1
                    || glGetProgramResourceIndex(program, GL_UNIFORM, "images") != -1)
                throw new IllegalStateException("Uniform interface cache survived relink");
            index = glGetProgramResourceIndex(program, GL_UNIFORM, "tint");
            int[] location = {-1};
            glGetProgramResourceiv(program, GL_UNIFORM, index, new int[] {GL_LOCATION}, (int[]) null, location);
            if (location[0] != glGetUniformLocation(program, "tint"))
                throw new IllegalStateException("Uniform location is not the writable GL location");
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            glViewport(0, 0, 8, 8);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glUseProgram(program);
            glUniform4f(location[0], .2f, .4f, .6f, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            byte[] pixels = ResourceReadback.layer(texture, 0);
            for (int i = 0; i < 64; i++)
                for (int channel = 0; channel < 4; channel++)
                    if ((pixels[i * 4 + channel] & 255) != (channel == 3 ? 255 : (channel + 1) * 51))
                        throw new IllegalStateException("Queried uniform location produced incorrect Metal pixels");
            Files.writeString(
                    output.resolve("program-interface.txt"),
                    "PASS\nverified_pixels=64\nchecks=active uniforms,sampler array sizes,stage references,shadow/integer types,scalar/array/buffer overloads,positions,unused removal,public names,compute/image/bool types,relink,queried location writes\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(texture);
            glDeleteProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }

    private static void compute() {
        int program = glCreateProgram(), shader = glCreateShader(GL_COMPUTE_SHADER);
        try {
            compile(
                    shader,
                    "#version 430 core\nlayout(local_size_x=1)in;layout(r32ui,binding=0)writeonly uniform uimage2D resultImage;uniform uint amount;uniform bool enabled;void main(){imageStore(resultImage,ivec2(0),uvec4(enabled?amount:0u));}");
            glAttachShader(program, shader);
            link(program);
            if (glGetProgramInterfacei(program, GL_UNIFORM, GL_ACTIVE_RESOURCES) != 3)
                throw new IllegalStateException("Compute uniform count mismatch");
            for (String name : new String[] {"resultImage", "amount", "enabled"}) {
                int[] values = new int[4];
                glGetProgramResourceiv(
                        program,
                        GL_UNIFORM,
                        glGetProgramResourceIndex(program, GL_UNIFORM, name),
                        new int[] {
                            GL_TYPE,
                            GL_REFERENCED_BY_VERTEX_SHADER,
                            GL_REFERENCED_BY_FRAGMENT_SHADER,
                            GL_REFERENCED_BY_COMPUTE_SHADER
                        },
                        (int[]) null,
                        values);
                int type = name.equals("resultImage")
                        ? GL_UNSIGNED_INT_IMAGE_2D
                        : name.equals("amount") ? GL_UNSIGNED_INT : GL_BOOL;
                if (!Arrays.equals(values, new int[] {type, 0, 0, 1}))
                    throw new IllegalStateException(
                            "Compute uniform interface mismatch for " + name + ": " + Arrays.toString(values));
            }
        } finally {
            glDeleteProgram(program);
            glDeleteShader(shader);
        }
    }

    private static void check(int program, String name, int type, int size, int vertex, int fragment) {
        int index = glGetProgramResourceIndex(program, GL_UNIFORM, name);
        int[] values = new int[4];
        glGetProgramResourceiv(
                program,
                GL_UNIFORM,
                index,
                new int[] {GL_TYPE, GL_ARRAY_SIZE, GL_REFERENCED_BY_VERTEX_SHADER, GL_REFERENCED_BY_FRAGMENT_SHADER},
                (int[]) null,
                values);
        if (!Arrays.equals(values, new int[] {type, size, vertex, fragment}))
            throw new IllegalStateException("Uniform stage/type mismatch for " + name + ": " + Arrays.toString(values));
    }

    private static void compile(int shader, String source) {
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(shader));
    }

    private static void link(int program) {
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
    }
}
