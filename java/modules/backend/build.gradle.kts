// Code that runs inside the isolated graphics runtime: hosted SDL backend, GL bridges, window binding.
plugins {
    id("julia.java-conventions")
}

val nativeClassifier = run {
    val arm = when (System.getProperty("os.arch")) {
        "aarch64", "arm64" -> true
        "amd64", "x86_64" -> false
        else -> throw GradleException("The graphics backend supports ARM64 and x86-64")
    }
    when (System.getProperty("os.name")) {
        "Mac OS X" -> if (arm) "natives-macos-arm64" else "natives-macos"
        "Linux" -> if (arm) "natives-linux-arm64" else "natives-linux"
        else -> throw GradleException("The graphics backend supports macOS and Linux")
    }
}

dependencies {
    api(project(":render-runtime"))
    api(libs.angelica.sdlgpu) {
        // The upstream publication carries the publishing host's Linux natives.
        exclude(group = "org.lwjgl")
    }
    for (module in listOf("lwjgl", "lwjgl-sdl", "lwjgl-shaderc", "lwjgl-spvc", "lwjgl-opengl")) {
        val coordinates = "org.lwjgl:$module:${libs.versions.lwjgl.get()}"
        api(coordinates)
        if (module != "lwjgl-sdl" || !nativeClassifier.startsWith("natives-macos")) {
            runtimeOnly("$coordinates:$nativeClassifier")
        }
    }
    implementation(variantOf(libs.lwjgl3ify) { classifier("dev") }) { isTransitive = false }

    // Angelica expects these to be supplied by its host application.
    runtimeOnly(libs.retrofuturabootstrap) { isTransitive = false }
    // ShaderManager's debug-environment check references Launch.blackboard. Never launch it.
    runtimeOnly(libs.launchwrapper) { isTransitive = false }
    runtimeOnly(libs.commons.lang3)
    runtimeOnly(libs.joml)
    runtimeOnly(libs.fastutil)
    runtimeOnly(libs.log4j.api)
    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.guava)
}


// Keep the extension and SDL on the same ABI. No private native layout is read from Java.
if (nativeClassifier.startsWith("natives-macos")) {
    val nativeRoot = rootProject.layout.projectDirectory.dir("../native/sdl")
    val nativeBuild = nativeRoot.dir("build")
    val nativeLibrary = nativeBuild.file("lib/libSDL3Julia.0.dylib")
    val configureSdl by tasks.registering(Exec::class) {
        inputs.file(nativeRoot.file("CMakeLists.txt"))
        inputs.file(nativeRoot.file("sampler_arguments.cmake"))
        inputs.file(nativeRoot.file("sparse_targets.cmake"))
        inputs.file(nativeRoot.file("visibility.cmake"))
        outputs.file(nativeBuild.file("CMakeCache.txt"))
        commandLine("cmake", "-S", nativeRoot.asFile.absolutePath, "-B", nativeBuild.asFile.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${nativeBuild.dir("lib").asFile.absolutePath}",
            "-DSDL_HIDAPI_LIBUSB=OFF",
            "-DCMAKE_OSX_ARCHITECTURES=${if (nativeClassifier.endsWith("arm64")) "arm64" else "x86_64"}")
    }
    val buildSdl by tasks.registering(Exec::class) {
        dependsOn(configureSdl)
        inputs.file(nativeRoot.file("CMakeLists.txt"))
        inputs.file(nativeRoot.file("gpu_timestamps.m"))
        inputs.file(nativeRoot.file("gpu_buffer_textures.m"))
        inputs.file(nativeRoot.file("gpu_vertex_input.m"))
        inputs.file(nativeRoot.file("gpu_texture_views.m"))
        inputs.file(nativeRoot.file("gpu_sampler_arguments.m"))
        inputs.file(nativeRoot.file("gpu_sparse_targets.m"))
        inputs.file(nativeRoot.file("gpu_visibility.m"))
        inputs.file(nativeRoot.file("sampler_arguments.cmake"))
        inputs.file(nativeRoot.file("sparse_targets.cmake"))
        inputs.file(nativeRoot.file("visibility.cmake"))
        outputs.file(nativeLibrary)
        commandLine("cmake", "--build", nativeBuild.asFile.absolutePath, "--target", "SDL3-shared", "--parallel", "8")
    }
    tasks.processResources {
        dependsOn(buildSdl)
        val architecture = if (nativeClassifier.endsWith("arm64")) "arm64" else "x64"
        inputs.property("nativeLibraryName", "libSDL3Julia.dylib")
        doFirst {
            delete(destinationDir.resolve("macos/$architecture/org/lwjgl/sdl/libSDL3.dylib"))
            delete(destinationDir.resolve("macos/$architecture/org/lwjgl/sdl/libSDL3-Julia.dylib"))
        }
        from(nativeLibrary) {
            into("macos/$architecture/org/lwjgl/sdl")
            rename { "libSDL3Julia.dylib" }
        }
        from(rootProject.file("../licenses/SDL.txt")) { into("META-INF/licenses") }
    }
}
