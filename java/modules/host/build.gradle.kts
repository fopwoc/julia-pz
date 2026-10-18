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
}
