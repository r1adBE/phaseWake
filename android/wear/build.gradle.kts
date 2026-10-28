plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.phasewake.app.wear"
    compileSdk = 37

    defaultConfig {
        // Gleiche Kennung wie die Handy-App: Pflicht für den Wearable Data Layer.
        applicationId = "com.phasewake.app"
        minSdk = 30
        targetSdk = 36
        // Testversionen aus GitHub bekommen die Laufnummer, damit sie sich ohne Deinstallieren aktualisieren lassen.
        val build = providers.environmentVariable("PHASEWAKE_BUILD").orNull?.toIntOrNull()
        versionCode = build ?: 1
        versionName = if (build != null) "1.0-test.$build" else "1.0"
    }

    signingConfigs {
        // Fester Test-Schlüssel (öffentlich, nur für Testversionen): Handy und Uhr brauchen dieselbe
        // Signatur für den Data Layer, und Updates müssen gleich signiert sein wie die erste Installation.
        getByName("debug") {
            storeFile = rootProject.file("testing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true }
    // Lint: die CI gibt den Textbericht (build/reports/lint-results-debug.txt) vollständig aus.
    lint {
        abortOnError = true
    }
    // Sprachliste für die Sprachwahl pro App (Android 13+) automatisch aus den Ressourcen.
    androidResources { generateLocaleConfig = true }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.ongoing)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.complications.data.source)
    implementation(libs.health.services)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.coroutines.play.services)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.ui.tooling)
}
