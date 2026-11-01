plugins { id("julia.renderer-conventions") }

dependencies {
    api(project(":glsm"))
    compileOnly(rendererLibs.celeritas.common) { isTransitive = false }
    compileOnly(rendererLibs.annotations)
    compileOnly(libs.log4j.api)
    for (module in listOf("lwjgl", "lwjgl-opengl", "lwjgl-sdl", "lwjgl-spvc", "lwjgl-shaderc")) {
        compileOnly("org.lwjgl:$module:${libs.versions.lwjgl.get()}")
    }
}

// The renderer's patched SDL: CMake sources live beside the module in the fork, build trees stay in Julia's build.
// Keep the extensions and SDL on the same ABI; no private native layout is read from Java.
val nativeRoot = rootProject.layout.projectDirectory.dir("../third_party/angelica/sdl-gpu/native")
val nativeBuild = layout.buildDirectory.dir("sdl")
val hostTarget = NativeTarget.host
// SDL names its shared library per OS; the unversioned name is what LWJGL loads.
val hostLibrary = nativeBuild.map {
    it.file(
        when (hostTarget.os) {
            "macos" -> "lib/libSDL3Julia.0.dylib"
            "windows" -> "lib/SDL3Julia.dll"
            else -> "lib/libSDL3Julia.so"
        })
}
val configureSdl by tasks.registering(Exec::class) {
    inputs.dir(nativeRoot)
    outputs.file(nativeBuild.map { it.file("CMakeCache.txt") })
    val build = nativeBuild.get().asFile.absolutePath
    val arguments = mutableListOf(
        "cmake", "-S", nativeRoot.asFile.absolutePath, "-B", build, "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$build/lib", "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=$build/lib",
        "-DSDL_HIDAPI_LIBUSB=OFF")
    if (hostTarget.os == "macos")
        arguments += "-DCMAKE_OSX_ARCHITECTURES=${if (hostTarget.arch == "arm64") "arm64" else "x86_64"}"
    commandLine(arguments)
}
val buildSdl by tasks.registering(Exec::class) {
    dependsOn(configureSdl)
    inputs.dir(nativeRoot)
    outputs.file(hostLibrary)
    commandLine("cmake", "--build", nativeBuild.get().asFile.absolutePath, "--target", "SDL3-shared",
        "--config", "Release", "--parallel", "8")
}
val prebuiltNatives = providers.gradleProperty("juliaNatives")
tasks.processResources {
    dependsOn(buildSdl)
    inputs.property("prebuiltNatives", prebuiltNatives.orElse(""))
    doFirst { delete(destinationDir.resolve("macos"), destinationDir.resolve("linux"), destinationDir.resolve("windows")) }
    from(hostLibrary) {
        into("${hostTarget.directory}/org/lwjgl/sdl")
        rename { hostTarget.sdlLibrary }
    }
    if (prebuiltNatives.isPresent) for (target in NativeTarget.all - hostTarget) {
        from(file(prebuiltNatives.get()).resolve(target.directory)) {
            include(target.sdlLibrary)
            into("${target.directory}/org/lwjgl/sdl")
        }
    }
    from(rootProject.file("../licenses/SDL.txt")) { into("META-INF/licenses") }
}

// Validate generated resources, so a partial build cannot masquerade as a universal distribution.
tasks.register("verifyUniversalNatives") {
    dependsOn(tasks.processResources)
    val resources = tasks.processResources.map { it.destinationDir }
    inputs.dir(resources)
    doLast {
        val missing = NativeTarget.all.filter { target ->
            !resources.get().resolve("${target.directory}/org/lwjgl/sdl/${target.sdlLibrary}").isFile
        }
        if (missing.isNotEmpty()) throw GradleException(
            "Missing universal SDL natives: ${missing.joinToString { it.directory }}. " +
                "Run tools/build-universal.sh or supply -PjuliaNatives=<directory>."
        )
    }
}
