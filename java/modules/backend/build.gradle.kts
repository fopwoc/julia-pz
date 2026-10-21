// Code that runs inside the isolated graphics runtime: hosted SDL backend, GL bridges, window binding.
plugins {
    id("julia.java-conventions")
}

/** A platform the one agent jar serves, as LWJGL's native classifier and its in-jar library directory. */
data class NativeTarget(val classifier: String, val os: String, val arch: String) {
    val directory get() = "$os/$arch"
    val sdlLibrary get() = when (os) {
        "macos" -> "libSDL3Julia.dylib"
        "windows" -> "SDL3Julia.dll"
        else -> "libSDL3Julia.so"
    }
}

// Every platform PZ ships on. LWJGL's own natives are packed for all of them; Julia's SDL build comes from the host
// and from -PjuliaNatives=<dir> holding other hosts' builds as <os>/<arch>/<library>.
val nativeTargets = listOf(
    NativeTarget("natives-macos-arm64", "macos", "arm64"),
    NativeTarget("natives-macos", "macos", "x64"),
    NativeTarget("natives-linux", "linux", "x64"),
    NativeTarget("natives-windows", "windows", "x64"),
)

val hostTarget = run {
    val arm = when (System.getProperty("os.arch")) {
        "aarch64", "arm64" -> true
        "amd64", "x86_64" -> false
        else -> throw GradleException("The graphics backend supports ARM64 and x86-64")
    }
    val os = System.getProperty("os.name").lowercase()
    when {
        os.startsWith("mac") -> nativeTargets.first { it.os == "macos" && (it.arch == "arm64") == arm }
        os.startsWith("linux") && !arm -> nativeTargets.first { it.os == "linux" }
        os.startsWith("windows") && !arm -> nativeTargets.first { it.os == "windows" }
        else -> throw GradleException("No native target for $os on ${System.getProperty("os.arch")}")
    }
}

dependencies {
    api(project(":render-runtime"))
    api(libs.angelica.sdlgpu) {
        // The upstream publication carries the publishing host's Linux natives.
        exclude(group = "org.lwjgl")
        // A Forge coremod that deploys a duplicate fastutil and other libraries into Minecraft; never activated here.
        exclude(group = "com.github.GTNewHorizons", module = "GTNHExtLib")
        // jcpp needs Ant, slf4j and semver only for its build task and command line; findbugs only for annotations.
        // jopt-simple stays: RetroFuturaBootstrap and launchwrapper load it.
        exclude(group = "org.apache.ant")
        exclude(group = "org.slf4j")
        exclude(group = "com.github.zafarkhaja")
        exclude(group = "com.google.code.findbugs")
        // GTNHLib's mixin loader; GLSM only uses its plain buffer and vertex helpers.
        exclude(group = "io.github.legacymoddingmc", module = "unimixins")
        // Leaked from GLSM's own tests at compile scope.
        exclude(group = "org.junit.jupiter")
    }
    for (module in listOf("lwjgl", "lwjgl-sdl", "lwjgl-shaderc", "lwjgl-spvc", "lwjgl-opengl")) {
        val coordinates = "org.lwjgl:$module:${libs.versions.lwjgl.get()}"
        api(coordinates)
        // Julia always loads its own patched SDL build, never LWJGL's stock library.
        if (module != "lwjgl-sdl") for (target in nativeTargets) runtimeOnly("$coordinates:${target.classifier}")
    }
    implementation(variantOf(libs.lwjgl3ify) { classifier("dev") }) { isTransitive = false }

    // Angelica expects these to be supplied by its host application.
    runtimeOnly(libs.retrofuturabootstrap) { isTransitive = false }
    // ShaderManager's debug-environment check references Launch.blackboard. Never launch it.
    runtimeOnly(libs.launchwrapper) { isTransitive = false }
    runtimeOnly(libs.commons.lang3)
    // Kotlin backs only JOML's Kotlin extension functions, which Java code cannot call.
    runtimeOnly(libs.joml) { exclude(group = "org.jetbrains.kotlin") }
    runtimeOnly(libs.fastutil)
    runtimeOnly(libs.log4j.api)
    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.guava)
}


// Keep the extension and SDL on the same ABI. No private native layout is read from Java.
val nativeRoot = rootProject.layout.projectDirectory.dir("../native/sdl")
val nativeBuild = nativeRoot.dir("build")
// SDL names its shared library per OS; the unversioned name is what LWJGL loads.
val hostLibrary = nativeBuild.file(
    when (hostTarget.os) {
        "macos" -> "lib/libSDL3Julia.0.dylib"
        "windows" -> "lib/SDL3Julia.dll"
        else -> "lib/libSDL3Julia.so"
    })
// Every patch script and extension source; the build directory is CMake's own output.
val nativeSources = fileTree(nativeRoot) { exclude("build/**") }
val configureSdl by tasks.registering(Exec::class) {
    inputs.files(nativeSources)
    outputs.file(nativeBuild.file("CMakeCache.txt"))
    val output = nativeBuild.dir("lib").asFile.absolutePath
    val arguments = mutableListOf(
        "cmake", "-S", nativeRoot.asFile.absolutePath, "-B", nativeBuild.asFile.absolutePath,
        // -U drops a stale override from older build trees that patched a machine-local SDL checkout.
        "-UFETCHCONTENT_SOURCE_DIR_SDL", "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$output", "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=$output",
        "-DSDL_HIDAPI_LIBUSB=OFF")
    if (hostTarget.os == "macos")
        arguments += "-DCMAKE_OSX_ARCHITECTURES=${if (hostTarget.arch == "arm64") "arm64" else "x86_64"}"
    commandLine(arguments)
}
val buildSdl by tasks.registering(Exec::class) {
    dependsOn(configureSdl)
    inputs.files(nativeSources)
    outputs.file(hostLibrary)
    commandLine("cmake", "--build", nativeBuild.asFile.absolutePath, "--target", "SDL3-shared", "--config", "Release",
        "--parallel", "8")
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
    if (prebuiltNatives.isPresent) for (target in nativeTargets - hostTarget) {
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
        val missing = nativeTargets.filter { target ->
            !resources.get().resolve("${target.directory}/org/lwjgl/sdl/${target.sdlLibrary}").isFile
        }
        if (missing.isNotEmpty()) throw GradleException(
            "Missing universal SDL natives: ${missing.joinToString { it.directory }}. " +
                "Run tools/build-universal.sh or supply -PjuliaNatives=<directory>."
        )
    }
}
