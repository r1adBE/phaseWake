plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Gemeinsame Testnächte, die auch die Swift-Umsetzung prüft.
    systemProperty("phasewake.testdata", rootProject.file("../shared/testdata").absolutePath)
}
