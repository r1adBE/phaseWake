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

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.android)
    api(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    api(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)

    // Android-Bibliothek: Testrahmen ausdrücklich (JUnit), sonst fehlt kotlin.test.Test.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    // Android-Klassen (AtomicFile, NotificationManager, DataMap) in Unit-Tests, nur zum Testen (docs/ENTSCHEIDE.md).
    testImplementation(libs.robolectric)
}

// Robolectric lädt die Klassen in einer eigenen Umgebung: ohne diese Einstellung zählt JaCoCo sie nicht mit.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}
