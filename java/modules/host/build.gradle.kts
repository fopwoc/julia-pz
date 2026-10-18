// Creates the isolated graphics runtime: adapting class loader plus typed dispatch session.
plugins {
    id("julia.java-conventions")
}

dependencies {
    api(project(":render-runtime"))
    implementation(project(":transform"))
}
