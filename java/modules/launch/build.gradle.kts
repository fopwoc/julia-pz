// The startup both entries share: report directory, nested runtime loader, and handing over to the host.
plugins {
    id("julia.java-conventions")
}

dependencies {
    implementation(project(":utils"))
}
