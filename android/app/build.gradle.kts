import java.net.URI
import java.security.MessageDigest
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.screenshot)
    jacoco
}

android {
    namespace = "com.phasewake.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.phasewake.app"
        minSdk = 30
        targetSdk = 36
        // Version aus gradle.properties (eine Quelle für alle Geräte); Build-Nummer = Laufnummer der CI, steigt mit
        // jedem Build, damit sich Testversionen und Store-Updates ohne Deinstallieren installieren lassen.
        val build = providers.environmentVariable("PHASEWAKE_BUILD").orNull?.toIntOrNull()
        versionCode = build ?: 1
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
    }

    buildTypes {
        // Abdeckung der Unit-Tests, nur zur Information in der CI (Bericht: createDebugUnitTestCoverageReport).
        debug {
            enableUnitTestCoverage = true
        }
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
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric liest das zusammengeführte Manifest und die Ressourcen.
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin-Warnungen brechen den Build ab, im App- wie im Testcode (Review K-14).
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.health.connect)
    // Play Store auf der Uhr öffnen, wenn dort Phase Wake fehlt.
    implementation(libs.wear.remote.interactions)
    // Geräusche in der Nacht einordnen (YAMNet, auf dem Gerät). Modell: src/main/assets/yamnet.tflite, siehe README.
    implementation(libs.mediapipe.tasks.audio)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Sicherheit (Review K-01, OSV): Guava 32.0.x und protobuf-javalite 4.26.1 kommen transitiv mit; nur die Version
    // anheben, nichts zusätzlich einbinden.
    constraints {
        implementation("com.google.guava:guava:${libs.versions.guava.get()}") { because("OSV: Guava 32.0.x mit bekannten Schwachstellen") }
        implementation("com.google.protobuf:protobuf-javalite:${libs.versions.protobufJavalite.get()}") { because("OSV: protobuf-javalite 4.26.1 (CVE-2024-7254)") }
    }

    // Entscheidungen des Zweitweckers (Nachfrist, Ablösen, «stop», Schlummern) in Unit-Tests; Robolectric nur zum Testen.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.robolectric)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.ui.tooling)
}

// Geräusch-Modell YAMNet (Apache-2.0) beim ersten Bauen laden; liegt nicht im Repo.
// Ohne Netz baut die Debug-App trotzdem (nur Warnung): Geräusche gelten dann als «Anderes Geräusch».
// Ein Release bricht ohne Modell ab (checkYamnet), damit keine App ohne Einordnung ausgeliefert wird.
// Prüfsumme: Eine geladene oder vorhandene Datei mit anderem SHA-256 bricht jeden Build ab (Review K-02).
val yamnetModel: File = layout.projectDirectory.file("src/main/assets/yamnet.tflite").asFile

// SHA-256 von yamnet.tflite (float32/1, 4 126 810 Byte), aus den bisherigen CI-Builds (assets/yamnet.tflite im APK).
val yamnetSha256 = "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf"
val downloadYamnet = tasks.register("downloadYamnet") {
    description = "Lädt yamnet.tflite, falls es fehlt, und prüft die Prüfsumme."
    val target = yamnetModel
    val expected = yamnetSha256
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, "yamnet.tflite.part")
        val loaded = try {
            val url = URI("https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite").toURL()
            url.openStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            true
        } catch (e: Exception) {
            temp.delete()
            logger.warn("yamnet.tflite nicht geladen (${e.message}); Geräusche werden nicht eingeordnet.")
            false
        }
        if (loaded) {
            // Ausserhalb des try: Eine veränderte Datei ist kein Netzfehler und bricht den Build ab.
            val digest = MessageDigest.getInstance("SHA-256")
            temp.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                val size = temp.length()
                temp.delete()
                throw GradleException("yamnet.tflite: SHA-256 $actual statt $expected ($size Byte). Datei verworfen, Build abgebrochen.")
            }
            if (!temp.renameTo(target)) temp.delete()
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadYamnet) }
val checkYamnet = tasks.register("checkYamnet") {
    description = "Bricht ab, wenn yamnet.tflite fehlt oder eine andere Prüfsumme hat (nur für Release-Builds)."
    val target = yamnetModel
    val expected = yamnetSha256
    dependsOn(downloadYamnet)
    doLast {
        if (!target.isFile || target.length() == 0L) {
            throw GradleException("yamnet.tflite fehlt in src/main/assets (Download fehlgeschlagen?). Release ohne Modell abgebrochen, siehe README.")
        }
        // Auch eine schon vorhandene Datei (lokal liegen geblieben) muss stimmen.
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw GradleException("yamnet.tflite: SHA-256 $actual statt $expected. Datei löschen und neu bauen (lädt neu), siehe README.")
        }
    }
}
// Nur für Release-Varianten; die Aufgabe entsteht erst mit den Varianten, darum über configureEach.
tasks.configureEach { if (name == "preReleaseBuild") dependsOn(checkYamnet) }
