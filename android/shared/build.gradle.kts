import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.android.library)
    jacoco
}

android {
    namespace = "com.phasewake.app.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric liest das zusammengeführte Manifest und die Ressourcen der Bibliotheken.
        unitTests.isIncludeAndroidResources = true
    }

    // Abdeckung der gemeinsamen Formate und Nachrichten (Bericht: createDebugUnitTestCoverageReport).
    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
    }
}

// Kotlin-Warnungen brechen den Build ab, im App- wie im Testcode (Review K-14).
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.android)
    api(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    api(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)

    // Sicherheit (Review K-01, Audit 17.11. CODE-05): wie in app und wear; hebt Guava in den Test-Classpaths an
    // (Robolectric bringt 33.6.0-jre mit), bindet nichts zusätzlich ein.
    constraints {
        implementation("com.google.guava:guava:${libs.versions.guava.get()}") { because("OSV: GHSA-xxph-c9ww-hj94") }
    }

    // Android-Bibliothek: Testrahmen ausdrücklich (JUnit), sonst fehlt kotlin.test.Test.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    // Android-Klassen (AtomicFile, NotificationManager, DataMap) in Unit-Tests, nur zum Testen (docs/ENTSCHEIDE.md).
    testImplementation(libs.robolectric)
}

apply(from = rootProject.file("gradle/robolectric.gradle.kts"))

// Robolectric lädt die Klassen in einer eigenen Umgebung: ohne diese Einstellung zählt JaCoCo sie nicht mit.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}
