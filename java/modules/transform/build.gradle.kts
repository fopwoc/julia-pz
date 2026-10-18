// Bytecode passes that route OpenGL consumers to the backend's typed endpoints, plus offline audits.
plugins {
    id("julia.java-conventions")
}

dependencies {
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    implementation(project(":utils"))
    implementation(project(":gl-audit"))
    // Upstream redirector at compile time; endpoint ABIs are read from class resources at run time.
    implementation(project(":backend"))

    testImplementation(project(":render-runtime"))
}

tasks.test {
    val fixtureClasspath = sourceSets.test.map { it.runtimeClasspath }
    doFirst { systemProperty("fixture.classpath", fixtureClasspath.get().asPath) }
}
