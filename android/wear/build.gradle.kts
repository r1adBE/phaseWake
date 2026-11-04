import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.screenshot)
    jacoco
}

android {
    namespace = "com.phasewake.app.wear"
    compileSdk = 37

    defaultConfig {
        // Gleiche Kennung wie die Handy-App: Pflicht für den Wearable Data Layer.
        applicationId = "com.phasewake.app"
        minSdk = 30
        targetSdk = 36
        // Version aus gradle.properties (eine Quelle für alle Geräte); Build-Nummer = Laufnummer der CI, steigt mit
        // jedem Build, damit sich Testversionen und Store-Updates ohne Deinstallieren installieren lassen.
        val build = providers.environmentVariable("PHASEWAKE_BUILD").orNull?.toIntOrNull()
        // Uhr: eigener Bereich, weil Play für Handy und Uhr derselben App verschiedene Build-Nummern verlangt.
        versionCode = 1_000_000 + (build ?: 1)
        versionName = providers.gradleProperty("phasewake.version").get()
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
        // Upload-Schlüssel für Google Play (docs/PLAY.md): nur, wenn alle vier Angaben gesetzt sind (Workflow
        // «Store-Build»). Ohne sie bleibt der Release-Build unsigniert wie bisher; nur ein Teil davon bricht ab.
        val upload = listOf("KEYSTORE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
            .associateWith { providers.environmentVariable("PHASEWAKE_UPLOAD_$it").orNull?.takeIf(String::isNotEmpty) }
        if (upload.values.any { it != null }) {
            val missing = upload.filterValues { it == null }.keys
            if (missing.isNotEmpty()) throw GradleException("Upload-Schlüssel unvollständig, es fehlt: ${missing.joinToString { "PHASEWAKE_UPLOAD_$it" }}")
            create("upload") {
                storeFile = file(upload.getValue("KEYSTORE")!!)
                storePassword = upload.getValue("STORE_PASSWORD")
                keyAlias = upload.getValue("KEY_ALIAS")
                keyPassword = upload.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Abdeckung der Unit-Tests, nur zur Information in der CI (Bericht: createDebugUnitTestCoverageReport).
        debug {
            enableUnitTestCoverage = true
            // Testversion mit eigener Kennung (com.phasewake.app.test), neben der Store-Version installierbar. Handy
            // und Uhr brauchen dieselbe (Data Layer): darum in beiden Apps gleich (android/testing/README.md).
            applicationIdSuffix = ".test"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Nur mit Upload-Schlüssel signiert (oben); sonst unsigniert.
            signingConfigs.findByName("upload")?.let { signingConfig = it }
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
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric liest das zusammengeführte Manifest und die Ressourcen.
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

// Kotlin-Warnungen brechen den Build ab, im App- wie im Testcode (Review K-14).
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

// Lizenztexte aus licenses/ (wie auf dem Handy) als Assets auch ins Uhr-APK; angezeigt werden sie in der Handy-App.
androidComponents {
    onVariants { variant -> variant.sources.assets?.addStaticSourceDirectory(rootProject.file("../licenses").path) }
}

// Robolectric lädt die Klassen in einer eigenen Umgebung: ohne diese Einstellung zählt JaCoCo sie nicht mit.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
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
    // Bildschirme der Uhr (Start, Editor, Uhrzeit) mit Wischen zurück.
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.ongoing)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.complications.data.source)
    implementation(libs.health.services)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.coroutines.play.services)

    // Sicherheit (Review K-01, OSV): Guava 32.0.x und protobuf-javalite 4.26.1 kommen transitiv mit; nur die Version
    // anheben, nichts zusätzlich einbinden.
    constraints {
        implementation("com.google.guava:guava:${libs.versions.guava.get()}") { because("OSV: Guava 32.0.x mit bekannten Schwachstellen") }
        implementation("com.google.protobuf:protobuf-javalite:${libs.versions.protobufJavalite.get()}") { because("OSV: protobuf-javalite 4.26.1 (CVE-2024-7254)") }
    }

    // Planung der Uhr (Einschlaf-Messung, Direct Boot, Wiederaufnahme) in Unit-Tests; Robolectric nur zum Testen.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.ui.tooling)
}
