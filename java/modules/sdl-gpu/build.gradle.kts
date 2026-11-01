plugins { id("julia.renderer-conventions") }

dependencies {
    api(project(":glsm"))
    compileOnly(project(path = ":glsm", configuration = "stubsElements"))
    compileOnly(variantOf(libs.lwjgl3ify) { classifier("dev") }) { isTransitive = false }
    compileOnly(rendererLibs.celeritas.common) { isTransitive = false }
    compileOnly(rendererLibs.annotations)
    compileOnly(libs.log4j.api)
    for (module in listOf("lwjgl", "lwjgl-opengl", "lwjgl-sdl", "lwjgl-spvc", "lwjgl-shaderc")) {
        compileOnly("org.lwjgl:$module:${libs.versions.lwjgl.get()}")
    }
}

// Upstream uses this marker to distinguish LWJGL3 classes from the legacy GLSM namespace.
tasks.named<JavaCompile>("compileJava") {
    doLast { injectLwjgl3Aware(destinationDirectory.get().asFile) }
}
tasks.jar { manifest.attributes("Lwjgl3ify-Aware" to "true") }
