package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL41.GL_NUM_PROGRAM_BINARY_FORMATS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** A source-compilation consumer can query the absence of program binaries without a fatal unknown query. */
final class ProgramBinaryChecks {
    private ProgramBinaryChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("program-binary-capability.txt"));
        if (glGetInteger(GL_NUM_PROGRAM_BINARY_FORMATS) != 0)
            throw new IllegalStateException("Julia advertises a GL program binary format without an implementation");
        int[] values = {37, 38};
        glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, values);
        if (values[0] != 0 || values[1] != 38)
            throw new IllegalStateException("Program-binary capability array query corrupted its output");
        try (var stack = MemoryStack.stackPush()) {
            var buffer = stack.ints(37, 38, 39);
            buffer.position(1).limit(2);
            glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, buffer);
            if (buffer.position() != 1 || buffer.get(0) != 37 || buffer.get(1) != 0)
                throw new IllegalStateException("Program-binary capability buffer query corrupted its output");
        }
        int vertex = glCreateShader(GL_VERTEX_SHADER),
                fragment = glCreateShader(GL_FRAGMENT_SHADER),
                program = glCreateProgram();
        try {
            glShaderSource(vertex, "#version 330 core\nvoid main(){gl_Position=vec4(0,0,0,1);}");
            glShaderSource(fragment, "#version 330 core\nout vec4 color;void main(){color=vec4(1);}");
            for (int shader : new int[] {vertex, fragment}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            Files.writeString(
                    output.resolve("program-binary-capability.txt"),
                    "PASS\nformats=0\nqueries=scalar,array,buffer\nsource_program_linked=true\n");
        } finally {
            glDeleteProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }
}
