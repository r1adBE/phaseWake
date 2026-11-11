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
            // Testversion nur für ARM-Handys (Audit F-13): Die nativen Bibliotheken von MediaPipe für x86/x86_64
            // (Emulatoren) machen sonst gut die Hälfte des APK aus. Der Store-Build (AAB) bleibt unverändert, Play teilt ihn ohnehin.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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

// Lizenztexte aus licenses/ (eine Quelle für Android und iPhone) als Assets ins APK; «Über» → «Lizenzen» zeigt sie.
androidComponents {
    onVariants { variant -> variant.sources.assets?.addStaticSourceDirectory(rootProject.file("../licenses").path) }
}

// licenses/MIT-phasewake.txt ist eine Kopie von LICENSE: weicht sie ab, bricht der Build ab (eine Lizenz, ein Text).
val checkLicenseCopy = tasks.register("checkLicenseCopy") {
    description = "Prüft, dass licenses/MIT-phasewake.txt gleich LICENSE ist."
    val original = rootProject.file("../LICENSE")
    val copy = rootProject.file("../licenses/MIT-phasewake.txt")
    inputs.files(original, copy)
    doLast {
        if (!copy.isFile || !original.readBytes().contentEquals(copy.readBytes())) {
            throw GradleException("licenses/MIT-phasewake.txt weicht von LICENSE ab: LICENSE nach licenses/MIT-phasewake.txt kopieren.")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkLicenseCopy) }

apply(from = rootProject.file("gradle/robolectric.gradle.kts"))

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
// Prüfsumme (Review K-02): Eine frisch geladene Datei mit anderem SHA-256 bricht jeden Build ab. Eine schon
// vorhandene Datei prüft nur der Release-Build (checkYamnet); Debug-Builds nehmen sie ungeprüft (Audit 071597f DA-7).
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
