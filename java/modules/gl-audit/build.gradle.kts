plugins {
    id("julia.java-conventions")
    application
}

dependencies {
    implementation(project(":utils"))
}

application {
    mainClass = "dev.fopwoc.julia.audit.cli.AuditMain"
    applicationName = "gl-audit"
}
