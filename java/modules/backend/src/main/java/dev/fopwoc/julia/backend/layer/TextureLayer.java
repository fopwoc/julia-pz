package dev.fopwoc.julia.backend.layer;

import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;

import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.texture.ClientPixels;
import dev.fopwoc.julia.backend.texture.NamedTextureUploads;
import dev.fopwoc.julia.backend.texture.TextureBindings;
import dev.fopwoc.julia.backend.texture.TextureWrap;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Logical per-target texture bindings, views, buffer textures, and upload paths SDL needs adjusted. */
public abstract class TextureLayer extends FramebufferLayer {
    @Override
    public void generateTextureMipmap(int name) {
        subsystems().textureObjects().prepareMipmaps(name);
        super.generateTextureMipmap(name);
    }

    @Override
    public int createTextures(int target) {
        return subsystems().textureObjects().create(target);
    }

    @Override
    public void pixelStorei(int parameter, int value) {
        if (parameter == GL_UNPACK_IMAGE_HEIGHT || parameter == GL_UNPACK_SKIP_IMAGES) {
            if (value < 0) throw new IllegalArgumentException("Negative pixel unpack value");
            if (parameter == GL_UNPACK_IMAGE_HEIGHT) subsystems().volumeUnpack().imageHeight = value;
            else subsystems().volumeUnpack().skipImages = value;
        } else if (parameter == GL_UNPACK_SWAP_BYTES) subsystems().volumeUnpack().swapBytes = value != 0;
        else if (parameter == GL_PACK_IMAGE_HEIGHT || parameter == GL_PACK_SKIP_IMAGES) {
            // Volume readback is not implemented, so only the default layout can be honoured; callers restore it.
            if (value != 0) throw new UnsupportedOperationException("3D pixel-pack layout not implemented");
        } else super.pixelStorei(parameter, value);
    }

    @Override
    public int getTexLevelParameteri(int target, int level, int parameter) {
        if (target == GL_TEXTURE_BUFFER)
            return subsystems()
                    .bufferTextures()
                    .levelParameter(
                            subsystems().bufferTextures().boundTexture(Upstream.context().activeTextureUnit),
                            level,
                            parameter);
        if (parameter == GL_TEXTURE_DEPTH && TextureBindings.selectable(target)) {
            var state = Upstream.context();
            var meta = Upstream.resources()
                    .getTextureMeta(subsystems().textureBindings().get(state, state.activeTextureUnit, target));
            if (meta == null || level < 0 || level >= meta.levels()) return 0;
            return target == GL_TEXTURE_3D ? Math.max(1, meta.depth() >> Math.min(level, 30)) : meta.depth();
        }
        return super.getTexLevelParameteri(target, level, parameter);
    }

    @Override
    public int genTextures() {
        int name = super.genTextures();
        subsystems().textureViews().generated(name);
        return name;
    }

    @Override
    public void genTextures(IntBuffer names) {
        for (int i = names.position(); i < names.limit(); i++) names.put(i, genTextures());
    }

    @Override
    public void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        if (subsystems().bufferTextures().contains(texture))
            throw new UnsupportedOperationException("Buffer texture image load/store is not implemented");
        super.bindImageTexture(unit, texture, level, layered, layer, access, format);
    }

    @Override
    public boolean isTexture(int name) {
        return subsystems().textureObjects().contains(name)
                || subsystems().bufferTextures().contains(name)
                || super.isTexture(name);
    }

    @Override
    public void bindTextureUnit(int unit, int name) {
        var state = Upstream.context();
        if (name == 0) {
            subsystems().textureBindings().unbindUnit(state, unit);
            subsystems().bufferTextures().bindTexture(unit, 0);
        } else if (subsystems().bufferTextures().contains(name))
            subsystems().bufferTextures().bindTexture(unit, name);
        else
            subsystems()
                    .textureBindings()
                    .bind(state, unit, subsystems().textureObjects().target(name), name);
        super.bindTextureUnit(unit, name);
    }

    @Override
    public void bindTexture(int target, int name) {
        // Sprite batches rebind known textures constantly; only a name's first bind needs object bookkeeping.
        if (!subsystems().textureObjects().known(name, target)) {
            subsystems().bufferTextures().ordinaryTextureBound(name);
            subsystems().textureObjects().bound(name, target);
            subsystems().textureViews().bound(name);
        }
        var state = Upstream.context();
        subsystems().textureBindings().bind(state, state.activeTextureUnit, target, name);
        super.bindTexture(target, name);
    }

    @Override
    public void texImage2D(
            int target,
            int level,
            int format,
            int width,
            int height,
            int border,
            int pixelFormat,
            int type,
            ByteBuffer data) {
        int storageFormat = format == GL_RED && type == GL_UNSIGNED_BYTE ? GL_R8 : format;
        if (data == null
                && target == GL_TEXTURE_2D
                && (format == GL_RGBA || format == GL_RGBA8)
                && pixelFormat == GL_RGBA
                && type == GL_UNSIGNED_BYTE
                && width > 0
                && height > 0) {
            // PZ samples empty video storage before decoding its first frame. Keep initialization
            // in the same upload queue as decoded frames so it cannot overwrite a later frame.
            try (var empty = ClientPixels.transparent(width, height, Upstream.context().pixelStore)) {
                super.texImage2D(target, level, storageFormat, width, height, border, pixelFormat, type, empty.data());
            }
        } else {
            super.texImage2D(target, level, storageFormat, width, height, border, pixelFormat, type, data);
        }
    }

    @Override
    public void texImage2D(
            int target,
            int level,
            int format,
            int width,
            int height,
            int border,
            int pixelFormat,
            int type,
            long pixels) {
        var state = Upstream.context();
        if (state.boundPixelUnpackBuffer != 0) {
            super.texImage2D(target, level, format, width, height, border, pixelFormat, type, pixels);
            return;
        }
        if (pixels == 0) {
            texImage2D(target, level, format, width, height, border, pixelFormat, type, (ByteBuffer) null);
            return;
        }
        try (var client = ClientPixels.borrow(pixels, width, height, pixelFormat, type, state.pixelStore)) {
            texImage2D(target, level, format, width, height, border, client.format(), type, client.data());
        }
    }

    @Override
    public void texImage3D(
            int target,
            int level,
            int format,
            int width,
            int height,
            int depth,
            int border,
            int pixelFormat,
            int type,
            ByteBuffer data) {
        if (target == GL_TEXTURE_2D_ARRAY && data != null) {
            super.texImage3D(target, level, format, width, height, depth, border, pixelFormat, type, (ByteBuffer) null);
            var state = Upstream.context();
            int name = subsystems().textureBindings().get(state, state.activeTextureUnit, target);
            NamedTextureUploads.upload(name, level, 0, 0, 0, width, height, depth, pixelFormat, type, data, true);
            return;
        }
        if (format != GL_RGB32I && format != GL_RGB32UI) {
            super.texImage3D(target, level, format, width, height, depth, border, pixelFormat, type, data);
            return;
        }
        if (level != 0 || border != 0 || (target != GL_TEXTURE_3D && target != GL_TEXTURE_2D_ARRAY))
            throw new UnsupportedOperationException(
                    "RGB32 integer allocation requires a borderless base-level volume or array");
        // SDL stores RGB32 integer pixels as RGBA32; the generic upstream uploader copies only three components.
        super.texImage3D(target, level, format, width, height, depth, border, pixelFormat, type, (ByteBuffer) null);
        if (data != null) {
            var state = Upstream.context();
            int name = subsystems().textureBindings().get(state, state.activeTextureUnit, target);
            NamedTextureUploads.upload(name, level, 0, 0, 0, width, height, depth, pixelFormat, type, data, true);
        }
    }

    @Override
    public void texStorage2D(int target, int levels, int format, int width, int height) {
        super.texStorage2D(target, levels, format, width, height);
        var context = Upstream.context();
        int name = context.boundTextures[context.activeTextureUnit];
        if (Upstream.resources().getTextureHandle(name) != 0)
            subsystems().textureViews().immutable(name);
    }

    @Override
    public void deleteTextures(int name) {
        subsystems().textureBindings().deleted(Upstream.context(), name);
        subsystems().textureObjects().deleted(name);
        subsystems().bufferTextures().deleteTexture(name);
        long handle = Upstream.resources().getTextureHandle(name);
        subsystems().textureViews().beforeDelete(name);
        super.deleteTextures(name);
        subsystems().textureViews().deleted(name, handle);
        subsystems().textures().deleted(name);
    }

    @Override
    public void texParameteri(int target, int parameter, int value) {
        value = TextureWrap.sampled(parameter, value);
        var context = Upstream.context();
        if (!subsystems()
                .textureViews()
                .parameter(
                        subsystems().textureBindings().get(context, context.activeTextureUnit, target),
                        parameter,
                        value)) {
            super.texParameteri(target, parameter, value);
        }
    }

    @Override
    public void textureParameteri(int name, int target, int parameter, int value) {
        value = TextureWrap.sampled(parameter, value);
        if (!subsystems().textureViews().parameter(name, parameter, value))
            super.textureParameteri(name, target, parameter, value);
    }

    @Override
    public int getTexParameteri(int target, int parameter) {
        var context = Upstream.context();
        var value = subsystems()
                .textureViews()
                .parameter(subsystems().textureBindings().get(context, context.activeTextureUnit, target), parameter);
        return value != null ? value : super.getTexParameteri(target, parameter);
    }

    @Override
    public void texParameterf(int target, int parameter, float value) {
        if (parameter == GL_TEXTURE_WRAP_S || parameter == GL_TEXTURE_WRAP_T || parameter == GL_TEXTURE_WRAP_R)
            texParameteri(target, parameter, Math.round(value));
        else if (parameter == GL_DEPTH_STENCIL_TEXTURE_MODE
                || parameter == GL_TEXTURE_BASE_LEVEL
                || parameter == GL_TEXTURE_MAX_LEVEL) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite texture parameter");
            texParameteri(target, parameter, Math.round(value));
        } else super.texParameterf(target, parameter, value);
    }

    @Override
    public void textureParameterf(int name, int target, int parameter, float value) {
        if (parameter == GL_DEPTH_STENCIL_TEXTURE_MODE
                || parameter == GL_TEXTURE_BASE_LEVEL
                || parameter == GL_TEXTURE_MAX_LEVEL) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite texture parameter");
            textureParameteri(name, target, parameter, Math.round(value));
        } else super.textureParameterf(name, target, parameter, value);
    }

    @Override
    public float getTexParameterf(int target, int parameter) {
        var context = Upstream.context();
        var value = subsystems()
                .textureViews()
                .parameter(subsystems().textureBindings().get(context, context.activeTextureUnit, target), parameter);
        return value != null ? value.floatValue() : super.getTexParameterf(target, parameter);
    }

    @Override
    public void dispatchCompute(int x, int y, int z) {
        subsystems().textureViews().beforeCompute();
        super.dispatchCompute(x, y, z);
    }

    @Override
    public void dispatchComputeIndirect(long offset) {
        subsystems().textureViews().beforeCompute();
        super.dispatchComputeIndirect(offset);
    }
}
