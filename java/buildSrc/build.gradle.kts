plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.spotless.plugin)
    implementation(libs.asm.tree)
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
