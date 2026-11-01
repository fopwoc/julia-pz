// Creates the isolated graphics runtime: adapting class loader plus typed dispatch session.
plugins {
    id("julia.java-conventions")
}

dependencies {
    api(project(":render-runtime"))
    implementation(project(":transform"))
    implementation(project(":gl-audit"))
    implementation(project(":utils"))
    testImplementation(libs.asm.tree)
    testImplementation(libs.log4j.api)
}

// The subprocess fixture exercises the real isolated runtime with the same library set as this test suite.
tasks.test {
    val libraries = sourceSets.test.map { it.runtimeClasspath }
    doFirst { systemProperty("logging.classpath", libraries.get().asPath) }
}
