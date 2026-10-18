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
        runtimeOnly("$coordinates:$nativeClassifier")
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
