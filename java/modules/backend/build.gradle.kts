// Host integration inside the isolated graphics runtime: lifecycle, window borrowing and logging.
plugins {
    id("julia.java-conventions")
}

dependencies {
    api(project(":render-runtime"))
    api(project(":sdl-gpu"))
    implementation(libs.log4j.to.jul)
    for (module in listOf("lwjgl", "lwjgl-sdl", "lwjgl-shaderc", "lwjgl-spvc", "lwjgl-opengl")) {
        val coordinates = "org.lwjgl:$module:${libs.versions.lwjgl.get()}"
        api(coordinates)
        // Julia always loads its own patched SDL build, never LWJGL's stock library.
        if (module != "lwjgl-sdl") for (target in NativeTarget.all) runtimeOnly("$coordinates:${target.classifier}")
    }
}
