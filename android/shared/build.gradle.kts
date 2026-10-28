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
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.android)
    api(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    api(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
