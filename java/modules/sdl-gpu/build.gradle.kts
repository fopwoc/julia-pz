plugins { id("julia.renderer-conventions") }

dependencies {
    api(project(":glsm"))
    compileOnly(project(path = ":glsm", configuration = "stubsElements"))
    compileOnly(rendererLibs.celeritas.common) { isTransitive = false }
    compileOnly(rendererLibs.annotations)
    compileOnly(libs.log4j.api)
    for (module in listOf("lwjgl", "lwjgl-opengl", "lwjgl-sdl", "lwjgl-spvc", "lwjgl-shaderc")) {
        compileOnly("org.lwjgl:$module:${libs.versions.lwjgl.get()}")
    }
}
