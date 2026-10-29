plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
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
    finalizedBy(tasks.jacocoTestReport)
}

// Abdeckung: Der Kern muss mindestens 90 % der Zeilen testen (docs/ENTWICKLUNG.md, Abschnitt 4).
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = false
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
