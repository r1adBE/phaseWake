package com.phasewake.app.core

import java.io.File

/** Eine Testnacht aus `shared/testdata`. */
internal data class NightFixture(
    val name: String,
    val expectedIndex: Int?,
    val expectedReason: WakeReason?,
    val epochs: List<Epoch>,
    /** Startwerte, mit den gelernten Werten aus `@floor` und `@resting` (Lernphase), falls angegeben. */
    val config: DetectorConfig = DetectorConfig(),
    /** `@rearm-until`: Jeder Moment vor dieser Epoche wird übergangen ([WakeDetector.rearm], kurze Nacht); `null` = keiner. */
    val rearmUntil: Int? = null,
) {
    /** Erste weckende Epoche und Grund mit [config] und [rearmUntil]; `null`, wenn keine weckt. */
    fun firstWake(detector: WakeDetector = WakeDetector(config)): Pair<Int, WakeReason>? {
        for ((index, epoch) in epochs.withIndex()) {
            val decision = detector.onEpoch(epoch)
            if (decision !is Decision.Wake) continue
            if (rearmUntil != null && index < rearmUntil) {
                detector.rearm()
                continue
            }
            return index to decision.reason
        }
        return null
    }
}

/** Liest die gemeinsamen Testnächte (Format siehe `docs/PHASENERKENNUNG.md`, Abschnitt 5). */
internal object NightFixtures {

    /** Ordner der Testnächte; Gradle setzt ihn als Systemeigenschaft, sonst relativ zum Modul. */
    fun directory(): File =
        File(System.getProperty("phasewake.testdata") ?: "../../shared/testdata")

    fun all(): List<NightFixture> =
        directory().listFiles { file -> file.extension == "night" }
            .orEmpty()
            .sortedBy { it.name }
            .map(::load)

    /** Dateien laut `MANIFEST` (eine je Zeile, `#` = Kommentar); Tests prüfen, dass genau diese da sind. */
    fun manifest(): List<String> =
        File(directory(), "MANIFEST").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Alle Dateien des Ordners ausser `MANIFEST`, `README.md` und versteckten, sortiert. */
    fun files(): List<String> =
        directory().listFiles().orEmpty().filter { it.isFile }.map { it.name }
            .filter { it != "MANIFEST" && it != "README.md" && !it.startsWith(".") }.sorted()

    fun load(file: File): NightFixture {
        var expectedIndex: Int? = null
        var expectedReason: WakeReason? = null
        var expectSeen = false
        var headerSeen = false
        var personalFloor: Double? = null
        var restingHeartRate: Double? = null
        var rearmUntil: Int? = null
        val epochs = mutableListOf<Epoch>()

        file.readLines().forEachIndexed { lineNumber, raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("@expect") -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.getOrNull(1) != "none") {
                        expectedIndex = parts[1].toInt()
                        expectedReason = WakeReason.valueOf(parts[2])
                    }
                    expectSeen = true
                }
                line.startsWith("@floor") -> personalFloor = line.split(Regex("\\s+"))[1].toDouble()
                line.startsWith("@resting") -> restingHeartRate = line.split(Regex("\\s+"))[1].toDouble()
                line.startsWith("@rearm-until") -> rearmUntil = line.split(Regex("\\s+"))[1].toInt()
                !headerSeen -> {
                    check(line == "motion,hr") { "${file.name}:${lineNumber + 1}: Kopfzeile 'motion,hr' erwartet" }
                    headerSeen = true
                }
                else -> {
                    val fields = line.split(",", limit = 2)
                    check(fields.size == 2) { "${file.name}:${lineNumber + 1}: zwei Felder erwartet" }
                    epochs += Epoch(
                        motion = fields[0].ifEmpty { null }?.toDouble(),
                        heartRate = fields[1].ifEmpty { null }?.toDouble(),
                    )
                }
            }
        }
        check(expectSeen) { "${file.name}: @expect fehlt" }
        val config = DetectorConfig(personalFloor = personalFloor, restingHeartRate = restingHeartRate)
        return NightFixture(file.nameWithoutExtension, expectedIndex, expectedReason, epochs, config, rearmUntil)
    }
}
