plugins {
    alias(libs.plugins.android.library)
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
}
