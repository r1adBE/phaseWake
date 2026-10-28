plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.phasewake.app"
    compileSdk = 37

    defaultConfig {
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
    androidResources {
        generateLocaleConfig = true
        // Das Geräusch-Modell (YAMNet) wird unkomprimiert gelesen.
        noCompress += "tflite"
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.health.connect)
    // Geräusche in der Nacht einordnen (YAMNet, auf dem Gerät). Modell: src/main/assets/yamnet.tflite, siehe README.
    implementation(libs.mediapipe.tasks.audio)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.ui.tooling)
}

// Geräusch-Modell YAMNet (Apache-2.0) beim ersten Bauen laden; liegt nicht im Repo.
// Ohne Netz baut die App trotzdem: Geräusche gelten dann als «Anderes Geräusch».
val yamnetModel: File = layout.projectDirectory.file("src/main/assets/yamnet.tflite").asFile
val downloadYamnet by tasks.registering {
    description = "Lädt yamnet.tflite, falls es fehlt."
    val target = yamnetModel
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        try {
            target.parentFile.mkdirs()
            val url = java.net.URI("https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite").toURL()
            val temp = File(target.parentFile, "yamnet.tflite.part")
            url.openStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (!temp.renameTo(target)) temp.delete()
        } catch (e: Exception) {
            logger.warn("yamnet.tflite nicht geladen (${e.message}); Geräusche werden nicht eingeordnet.")
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadYamnet) }
