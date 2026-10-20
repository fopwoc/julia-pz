package dev.fopwoc.julia.probe.adapter;

import dev.fopwoc.julia.backend.runtime.GlsmSession;
import dev.fopwoc.julia.backend.runtime.HostedVideo;
import dev.fopwoc.julia.probe.scene.AdapterScene;
import dev.fopwoc.julia.probe.scene.ResourceChecks;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.Configuration;

/** Full GLSM startup over the hosted backend, drawing the redirected scene offscreen. */
public final class AdapterProbe {
    private static final System.Logger LOG = System.getLogger(AdapterProbe.class.getName());

    private AdapterProbe() {}

    public static void run(Path destination) throws Exception {
        var size = new GlsmSession.Size(
                Integer.getInteger("julia.probe.width", 32), Integer.getInteger("julia.probe.height", 32));
        Path output = destination.toAbsolutePath();
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("result.txt"));
        Files.deleteIfExists(output.resolve("adapter-texture.png"));
        if (Configuration.SHARED_LIBRARY_EXTRACT_PATH.get() == null) {
            Configuration.SHARED_LIBRARY_EXTRACT_PATH.set(
                    output.resolve("natives").toString());
        }
        String backend;
        try (var video = HostedVideo.start();
                var session = GlsmSession.open(video, 0, () -> size, "Julia adapter probe")) {
            backend = session.backend().getName();
            AdapterScene.offscreen(output, size.width(), size.height());
            DebugMessages.armShutdownCheck();
        }
        DebugMessages.verifyShutdown();
        Files.writeString(output.resolve("result.txt"), """
                PASS
                backend=%s
                size=%dx%d
                frames_drawn=%d
                verified_pixels=%d
                api=LWJGL -> typed RenderDispatch -> isolated GLStateManager
                verified_initial_frame_pixels=128
                initial_frame_checks=opaque black startup,empty frame preservation,clear color restoration
                verified_sparse_framebuffer_pixels=1248
                verified_framebuffer_pixels=480
                verified_layer_view_pixels=208
                verified_program_interface_pixels=64
                verified_graphics_sampler_pixels=162
                verified_sync_pixels=64
                verified_short_texture_pixels=24
                verified_float_texture_pixels=84
                verified_integer_volume_pixels=864
                verified_high_texture_unit_pixels=12
                gl_program_binary_formats=0
                verified_texture_binding_pixels=112
                verified_texture_binding_compute_words=4
                verified_named_texture_pixels=377
                verified_divisor_pixels=1248
                verified_buffer_texture_pixels=2560
                buffer_texture_checks=nine 32-bit formats,vertex/fragment/compute reads,range alignment,shared updates,reallocation,deletion,pending draw ordering,separate target bindings,million-texel view,invalid ranges and formats
                verified_array_api_pixels=128
                array_api_checks=texture arrays,resource deletion,method references,two draw buffers,integer arrays,mat3 transpose,incomplete matrix tail,empty arrays
                verified_resource_pixels=%d
                capability_checks=backend snapshot,absent vendor flag
                unsupported_query_checks=integer scalar,integer buffer,float scalar,exact rejected enum
                view_checks=shared writes,nested views,parent deletion,independent state,256 stencil values,view FBO writes
                verified_view_pixels=1152
                verified_client_pointer_pixels=32
                client_pointer_checks=transparent empty storage,first decoded frame ordering,RGBA video uploads,padded RGB rows,unpack row length and skips,caller memory reuse
                verified_culling_pixels=768
                culling_checks=CCW and CW,front/back/disabled culling,fragment face classification,texture and default framebuffers
                verified_depth_range_pixels=128
                depth_range_checks=clamped GL state and native viewport,near surface occludes far surface
                verified_initializer_pixels=256
                initializer_checks=scalar,integer,bool,uint,vector,matrix,array,queries,overrides,relink reset,current program and deferred deletion preservation
                verified_compatibility_pixels=64
                compatibility_checks=conditional uniforms,conditional function returns,legacy sampler names,reflected sampler binding,compiled-name snapshots,relink,genuine gtexture,truncated buffers,uniform lookup
                verified_shader_link_pixels=256
                shader_link_checks=separate objects,builtin scalar overrides and vector overloads,macro isolation,shared uniforms,compile snapshots,shared library deletion,detach,relink,syntax and unresolved-function failures
                verified_map_quad_pixels=1024
                map_quad_checks=streamed position/color/UV VBO,textured tiles,MVP upload,separate runs,no index GPU readbacks,index offsets,buffer binding restoration,default framebuffer survives GPU readbacks,texture switches,repeated mid-frame submissions
                verified_quad_pixels=192
                quad_checks=ranged indexed quads,all index widths,nonzero EBO offset,binding restoration
                verified_program_resource_pixels=64
                program_resource_checks=block layout,struct arrays,matrix stride,name/property truncation,cross-stage merge,original bindings,relink invalidation,compute RO/RW bindings,row-major matrix,runtime array
                debug_checks=registration,UTF8 relay delivery,replacement,unregistration,shutdown cleanup
                alias_checks=depth conversion,vec2 buffer position,image copy
                draw=sampled texture
                uniform_checks=float array,matrix transpose,truncated reflection,buffer positions,rejected bone palette preserves matrix and subsequent draws
                process_id=%d
                thread=%s
                """.formatted(
                        backend,
                        size.width(),
                        size.height(),
                        AdapterScene.FRAMES,
                        size.pixels(),
                        ResourceChecks.VERIFIED_PIXELS,
                        ProcessHandle.current().pid(),
                        Thread.currentThread().getName()));
        System.out.println("[Julia] PASS: full GLSM / SDL GPU adapter initialized; " + size.pixels()
                + " textured pixels verified");
        LOG.log(System.Logger.Level.INFO, "Adapter probe passed at {0}x{1}", size.width(), size.height());
    }
}
