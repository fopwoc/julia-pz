package dev.julia.probe.adapter;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Queries actual hosted-backend values for ordinary uniforms; outputs preserve their positions. */
public final class UniformQueryBridge {
    private UniformQueryBridge() { }
    public static float glGetUniformf(int program, int location) { return ShaderStateAccess.read(program, location).scalarFloat(); }
    public static int glGetUniformi(int program, int location) { return ShaderStateAccess.read(program, location).scalarInt(); }
    public static void glGetUniformfv(int program, int location, FloatBuffer values) { ShaderStateAccess.read(program, location).write(values); }
    public static void glGetUniformfv(int program, int location, float[] values) { ShaderStateAccess.read(program, location).write(values); }
    public static void glGetUniformiv(int program, int location, IntBuffer values) { ShaderStateAccess.read(program, location).write(values); }
    public static void glGetUniformiv(int program, int location, int[] values) { ShaderStateAccess.read(program, location).write(values); }
}
