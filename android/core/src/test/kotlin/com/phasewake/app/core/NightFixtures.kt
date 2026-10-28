package com.phasewake.app.core

import java.io.File

/** Eine Testnacht aus `shared/testdata`. */
internal data class NightFixture(
    val name: String,
    val expectedIndex: Int?,
    val expectedReason: WakeReason?,
    val epochs: List<Epoch>,
)

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

    fun load(file: File): NightFixture {
        var expectedIndex: Int? = null
        var expectedReason: WakeReason? = null
        var expectSeen = false
        var headerSeen = false
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
        return NightFixture(file.nameWithoutExtension, expectedIndex, expectedReason, epochs)
    }
}
