package com.phasewake.app.ui.settings

/**
 * Eine Lizenz mit ihrem Volltext und den Bestandteilen von Handy- und Uhr-App, die unter ihr stehen.
 * [file] liegt in `licenses/` (eine Quelle für Android und iPhone) und kommt beim Bauen als Asset ins APK.
 * Namen und Texte bleiben unübersetzt.
 */
internal class LicenseEntry(val title: String, val file: String, val components: List<String>)

/** Alle Lizenzen der ausgelieferten Apps (Handy und Uhr), aus den Release-Abhängigkeiten zusammengestellt. */
internal object Licenses {
    /** Ordner der Texte, relativ zum Repository; die Tests prüfen, dass jede Datei hier vorkommt. */
    const val FOLDER = "licenses"

    val entries: List<LicenseEntry> = listOf(
        LicenseEntry("MIT License", "MIT-phasewake.txt", listOf("Phase Wake © r1adBE")),
        LicenseEntry(
            "Apache License 2.0",
            "Apache-2.0.txt",
            listOf(
                "AndroidX / Jetpack (Compose, Core, Activity, Lifecycle, Navigation, DataStore, Health Connect, " +
                    "Health Services, Wear Compose, Tiles, ProtoLayout, Complications, Ongoing, Remote Interactions) " +
                    "© The Android Open Source Project",
                "Kotlin, kotlinx.coroutines, kotlinx.serialization, atomicfu © JetBrains s.r.o.",
                "MediaPipe Tasks Audio © The MediaPipe Authors",
                "YAMNet model © The TensorFlow Authors, Google LLC",
                "Guava © The Guava Authors",
                "Okio © Square, Inc.",
                "Google Data Transport, Firebase Encoders, Flogger © Google LLC",
                "Error Prone annotations, J2ObjC annotations, JSpecify, JetBrains annotations, javax.inject",
            ),
        ),
        LicenseEntry("NOTICE: Kotlin", "NOTICE-kotlin.txt", listOf("Kotlin, kotlinx.coroutines")),
        LicenseEntry("NOTICE: MediaPipe, YAMNet", "NOTICE-mediapipe.txt", listOf("MediaPipe Tasks Audio, YAMNet model")),
        LicenseEntry(
            "NOTICE: MediaPipe native libraries",
            "NOTICE-mediapipe-native.txt",
            listOf(
                "Native parts of MediaPipe Tasks (libmediapipe_tasks_jni, libmediapipe_tasks_audio_jni): TensorFlow Lite, " +
                    "XNNPACK, Eigen, Abseil, ruy, pthreadpool, cpuinfo, FlatBuffers, Protocol Buffers and others",
            ),
        ),
        LicenseEntry(
            "Google Play services",
            "NOTICE-play-services.txt",
            listOf("Google Play services (Base, Basement, Tasks, Wearable) and their third-party components"),
        ),
        LicenseEntry("BSD 3-Clause License", "BSD-3-protobuf.txt", listOf("Protocol Buffers © Google Inc.")),
        LicenseEntry("MIT License", "MIT-checker-framework.txt", listOf("Checker Framework annotations")),
        LicenseEntry("MIT License", "MIT-holidays.txt", listOf("Holiday data: Python package “holidays” © Vacanza Team and contributors")),
        LicenseEntry("SIL Open Font License 1.1", "Manrope-OFL.txt", listOf("Manrope font © The Manrope Project Authors")),
        LicenseEntry("CC0 1.0 Universal", "CC0-sounds.txt", listOf("Sounds from opengameart.org")),
    )

    /** Teilt einen Lizenztext in Absätze (Leerzeilen), damit lange Texte in einer Liste flüssig laufen. */
    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("(?:\n[ \t]*)+\n")).map { it.trimEnd() }.filter { it.isNotBlank() }
}
