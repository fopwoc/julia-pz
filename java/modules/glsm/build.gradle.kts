plugins { id("julia.renderer-conventions") }

val stubs = sourceSets.create("stubs") {
    java.srcDir(rootProject.layout.projectDirectory.dir("../third_party/angelica/glsm/src/stubs/java"))
}
val stubsElements = configurations.create("stubsElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(stubsElements.name, stubs.output.classesDirs.singleFile) {
    builtBy(tasks.named(stubs.classesTaskName))
}

dependencies {
    compileOnly(stubs.output)
    compileOnly(rendererLibs.lwjgl2)
    compileOnly(rendererLibs.lwjgl2.util)
    compileOnly(libs.asm.tree)
    compileOnly(rendererLibs.celeritas.common) { isTransitive = false }
    compileOnly(variantOf(libs.lwjgl3ify) { classifier("dev") }) { isTransitive = false }
    compileOnly(rendererLibs.annotations)
    compileOnly(libs.jsr305)
    compileOnly(rendererLibs.lombok)
    annotationProcessor(rendererLibs.lombok)
    compileOnly("org.lwjgl:lwjgl-shaderc:${libs.versions.lwjgl.get()}")
    compileOnly("org.lwjgl:lwjgl-spvc:${libs.versions.lwjgl.get()}")

    api(variantOf(rendererLibs.gtnhlib) { classifier("dev") }) { isTransitive = false }
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
