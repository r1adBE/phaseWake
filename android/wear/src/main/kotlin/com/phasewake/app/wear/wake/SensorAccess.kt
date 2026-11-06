package com.phasewake.app.wear.wake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.phasewake.app.shared.WatchPulse

/**
 * Sensorrechte der Uhr für das Diagnose-Protokoll und den Status ans Handy: Puls ([heartRate]), Puls im Hintergrund
 * ([background], nachts nötig, weil der Messdienst ohne sichtbare App startet) und Bewegung ([activity]).
 * Vor Android 13 gibt es kein eigenes Hintergrundrecht; dort deckt das Pulsrecht beides ab.
 */
internal data class SensorAccess(val heartRate: Boolean, val background: Boolean, val activity: Boolean, val sdk: Int) {

    /** Kurzform fürs Diagnose-Protokoll, z. B. «hr=yes bg=no act=yes sdk=36». */
    fun line(): String = "hr=${yesNo(heartRate)} bg=${yesNo(background)} act=${yesNo(activity)} sdk=$sdk"

    /** Pulsrechte für das Status-Paket ans Handy. */
    fun pulse(): WatchPulse = WatchPulse(heartRate, background)

    companion object {
        /** Höchstlänge der Fehlermeldung im Protokoll, damit die Rechte dahinter in die Zeile passen. */
        const val MAX_MESSAGE = 80

        /** Recht für den Puls im Vordergrund; heisst ab Wear OS 6 (API 36) anders. */
        fun heartRatePermission(sdk: Int = Build.VERSION.SDK_INT): String =
            if (sdk >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

        /** Recht für den Puls im Hintergrund (ab Android 13, ab API 36 unter neuem Namen); vorher `null`. */
        fun backgroundPermission(sdk: Int = Build.VERSION.SDK_INT): String? = when {
            sdk >= 36 -> "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
            sdk >= Build.VERSION_CODES.TIRAMISU -> "android.permission.BODY_SENSORS_BACKGROUND"
            else -> null
        }

        /** Liest die Rechte der App auf dieser Uhr. */
        fun of(context: Context): SensorAccess {
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            val sdk = Build.VERSION.SDK_INT
            val heartRate = granted(heartRatePermission(sdk))
            val background = backgroundPermission(sdk)?.let(::granted) ?: heartRate
            return SensorAccess(heartRate, background, granted(Manifest.permission.ACTIVITY_RECOGNITION), sdk)
        }

        /**
         * Grund, warum der Messdienst nicht als «health» starten durfte, fürs Diagnose-Protokoll:
         * «SecurityException «…» hr=yes bg=no act=yes sdk=36» (Fehlerklasse, gekürzte Meldung, Rechte).
         */
        fun refusal(error: Throwable, access: SensorAccess): String {
            val name = error.javaClass.simpleName.ifEmpty { "Exception" }
            return "$name «${shortMessage(error.message)}» ${access.line()}"
        }

        /**
         * Meldung ohne Prozessangaben (Kennungen von Prozess und Nutzer), ohne Zeilenumbrüche und Anführungszeichen,
         * höchstens [MAX_MESSAGE] Zeichen (gekürzt mit «…»).
         */
        fun shortMessage(message: String?): String {
            val clean = message.orEmpty()
                .replace(Regex("ProcessRecord\\{[^}]*\\}"), "…")
                .replace(Regex("\\b(pid|uid|userId)=\\d+"), "\$1=…")
                .replace('«', '"').replace('»', '"')
                .replace(Regex("\\s+"), " ")
                .trim()
            return if (clean.length <= MAX_MESSAGE) clean else clean.take(MAX_MESSAGE - 1).trimEnd() + "…"
        }

        private fun yesNo(value: Boolean) = if (value) "yes" else "no"
    }
}
