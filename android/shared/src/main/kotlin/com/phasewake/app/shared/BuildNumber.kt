package com.phasewake.app.shared

import android.content.Context

/**
 * Build-Nummer der Testversion (Laufnummer der CI, `github.run_number`) für «Über», Diagnose und Nacht-Check
 * (Entscheid r1AD 14.11.2026). Einzige Stelle der Android-Apps, die sie liest (docs/ENTWICKLUNG.md, Abschnitt 3).
 * Die Uhr-App trägt [WATCH_OFFSET] dazu (Google Play verlangt verschiedene Nummern); angezeigt wird die reine Nummer.
 */
object BuildNumber {
    /** Abstand der Uhr-App zur Handy-App. */
    const val WATCH_OFFSET = 1_000_000L

    /** Nummer eines Builds ohne Laufnummer der CI (lokal gebaut): gilt als unbekannt. */
    const val LOCAL = 1L

    /** Reine Nummer aus dem Versions-Code [code]; [watch]: Code der Uhr-App. `null` bei lokalem Build oder ungültig. */
    fun of(code: Long, watch: Boolean): Int? {
        val number = if (watch) code - WATCH_OFFSET else code
        return number.takeIf { it > LOCAL && it < WATCH_OFFSET }?.toInt()
    }

    /** «16.2.2 (96)»; ohne bekannte Nummer nur «16.2.2». */
    fun label(version: String, build: Int?): String = if (build == null) version else "$version ($build)"

    /** Ob Handy und Uhr verschiedene Testversionen haben; ist eine Nummer unbekannt, nein. */
    fun differs(phone: Int?, watch: Int?): Boolean = phone != null && watch != null && phone != watch

    /** Eigene Nummer dieser App ([watch]: auf der Uhr); `null`, wenn unbekannt. */
    fun own(context: Context, watch: Boolean): Int? = runCatching {
        of(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode, watch)
    }.getOrNull()

    /** Eigene Versionsbezeichnung mit Nummer, z. B. «16.2.2 (96)». */
    fun ownLabel(context: Context, watch: Boolean): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        return label(version, own(context, watch))
    }
}
