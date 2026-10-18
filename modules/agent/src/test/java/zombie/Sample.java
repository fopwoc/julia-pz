package zombie;

import org.lwjgl.opengl.GL11;

public final class Sample {
    private Sample() {}

    public static void main(String[] args) {
        if (GL11.glGetError() != 42) throw new AssertionError("Agent changed the application result");
        System.out.println("Original application behavior preserved");
    }
}
