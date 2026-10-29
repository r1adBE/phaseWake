package com.phasewake.app.health

import android.content.Context
import android.content.pm.PackageManager
import com.phasewake.app.R

/** Name der App, die eine Schlafsitzung in Health Connect geschrieben hat (für «aus Samsung Health»). */
object HealthSource {
    /** Paket von Samsung Health; der Name ist fest, damit er auch ohne Sichtbarkeit des Pakets stimmt. */
    const val SAMSUNG_HEALTH: String = "com.sec.android.app.shealth"

    /**
     * Anzeigename zu [packageName]: «Samsung Health», sonst der App-Name aus dem PackageManager (sichtbar über
     * `<queries>` im Manifest). `null`, wenn unbekannt oder nicht auflösbar – dann zeigt der Verlauf «aus Gesundheitsdaten».
     */
    fun name(context: Context, packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return null
        if (packageName == SAMSUNG_HEALTH) return context.getString(R.string.health_samsung)
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().takeIf { it.isNotBlank() }
        } catch (_: PackageManager.NameNotFoundException) {
            // Bewusst still: App deinstalliert oder nicht sichtbar; die allgemeine Angabe genügt.
            null
        } catch (_: RuntimeException) {
            // Paketverwaltung nicht verfügbar (z. B. Vorschau) oder verweigert: ebenfalls die allgemeine Angabe.
            null
        }
    }
}
