package dev.fopwoc.julia.backend.layer;

import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;

import java.nio.IntBuffer;

/** Timer and occlusion queries share one GL name space owned by {@link dev.fopwoc.julia.backend.query.Queries}. */
public abstract class QueryLayer extends JuliaLayer {
    @Override
    public int genQueries() {
        return subsystems().queries().generate();
    }

    @Override
    public void genQueries(IntBuffer names) {
        for (int i = names.position(); i < names.limit(); i++) names.put(i, genQueries());
    }

    @Override
    public void deleteQueries(int name) {
        subsystems().queries().delete(name);
    }

    @Override
    public boolean isQuery(int name) {
        return subsystems().queries().isQuery(name);
    }

    @Override
    public void queryCounter(int name, int target) {
        subsystems().queries().timestamp(name, target);
    }

    @Override
    public void beginQuery(int target, int name) {
        subsystems().queries().begin(target, name);
    }

    @Override
    public void endQuery(int target) {
        subsystems().queries().end(target);
    }

    @Override
    public int getQueryObjecti(int name, int parameter) {
        return (int) subsystems().queries().get(name, parameter);
    }

    @Override
    public long getQueryObjectui64(int name, int parameter) {
        return subsystems().queries().get(name, parameter);
    }

    @Override
    public void getQueryObjectui(int name, int parameter, IntBuffer result) {
        result.put(result.position(), getQueryObjecti(name, parameter));
    }
}
