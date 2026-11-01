plugins { id("julia.renderer-conventions") }

dependencies {
    compileOnly("org.lwjgl:lwjgl:${libs.versions.lwjgl.get()}")
    compileOnly("org.lwjgl:lwjgl-opengl:${libs.versions.lwjgl.get()}")
    compileOnly(libs.asm.tree)
    compileOnly(rendererLibs.celeritas.common) { isTransitive = false }
    compileOnly(rendererLibs.annotations)
    compileOnly(libs.jsr305)
    compileOnly(rendererLibs.lombok)
    annotationProcessor(rendererLibs.lombok)
    compileOnly("org.lwjgl:lwjgl-shaderc:${libs.versions.lwjgl.get()}")
    compileOnly("org.lwjgl:lwjgl-spvc:${libs.versions.lwjgl.get()}")

    api(project(":gtnhlib"))
    api(rendererLibs.eventbus)
    api(rendererLibs.glsl.transformation.lib) { exclude(module = "antlr4") }
    api(rendererLibs.antlr4.runtime)
    implementation(rendererLibs.jcpp) {
        exclude(group = "org.apache.ant")
        exclude(group = "org.slf4j")
        exclude(group = "com.github.zafarkhaja")
        exclude(group = "com.google.code.findbugs")
    }
    implementation(libs.guava)
    api(libs.fastutil)
    api(libs.joml) { exclude(group = "org.jetbrains.kotlin") }
    implementation(libs.log4j.api)
}
