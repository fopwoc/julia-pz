// Code that runs inside the isolated graphics runtime: hosted SDL backend, GL bridges, window binding.
plugins {
    id("julia.java-conventions")
}

dependencies {
    api(project(":render-runtime"))
    api(project(":sdl-gpu")) {
        // Platform natives are supplied by Julia, independently of the source-built renderer.
        exclude(group = "org.lwjgl")
        // jcpp needs Ant, slf4j and semver only for its build task and command line; findbugs only for annotations.
        // jopt-simple stays for jcpp's command-line support.
        exclude(group = "org.apache.ant")
        exclude(group = "org.slf4j")
        exclude(group = "com.github.zafarkhaja")
        exclude(group = "com.google.code.findbugs")
        // Leaked from GLSM's own tests at compile scope.
        exclude(group = "org.junit.jupiter")
    }
    for (module in listOf("lwjgl", "lwjgl-sdl", "lwjgl-shaderc", "lwjgl-spvc", "lwjgl-opengl")) {
        val coordinates = "org.lwjgl:$module:${libs.versions.lwjgl.get()}"
        api(coordinates)
        // Julia always loads its own patched SDL build, never LWJGL's stock library.
        if (module != "lwjgl-sdl") for (target in NativeTarget.all) runtimeOnly("$coordinates:${target.classifier}")
    }

    // Angelica expects these to be supplied by its host application.
    runtimeOnly(libs.commons.lang3)
    // Kotlin backs only JOML's Kotlin extension functions, which Java code cannot call.
    runtimeOnly(libs.joml) { exclude(group = "org.jetbrains.kotlin") }
    runtimeOnly(libs.fastutil)
    runtimeOnly(libs.log4j.api)
    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.guava)
}
