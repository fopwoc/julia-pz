package zombie;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import java.util.function.IntSupplier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** Synthetic graphics consumer; the original native methods must never execute. */
public final class RuntimeDispatchFixture {
    private RuntimeDispatchFixture() {}

    public static int draw() {
        return GL11.glGetError();
    }

    public static IntSupplier supplier() {
        return GLStateManager::glGetError;
    }

    public static void upload(float[] values) {
        GL20.glUniform1fv(3, values);
    }
}
