package dev.fopwoc.julia.backend.compat;

/** Legacy state inherited by GTNHLib's real DirectTessellator; no Minecraft rendering is implemented. */
public class TessellatorState {
    public boolean defaultTexture;
    public int[] rawBuffer;
    public int rawBufferSize;
    public int textureID;
    public int rawBufferIndex;
    public int vertexCount;
    public int addedVertices;
    public int drawMode;
    public boolean isDrawing;
    public boolean isColorDisabled;
    public boolean hasColor;
    public boolean hasTexture;
    public boolean hasBrightness;
    public boolean hasNormals;
    public double textureU;
    public double textureV;
    public int brightness;
    public int color;
    public int normal;
    public double xOffset;
    public double yOffset;
    public double zOffset;
    public int bufferSize;

    public int draw() {
        throw unsupported();
    }

    public void reset() {
        throw unsupported();
    }

    public void startDrawing(int mode) {
        throw unsupported();
    }

    public void setColorRGBA_I(int color, int alpha) {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Minecraft tessellator operations are unavailable in the Julia host");
    }
}
