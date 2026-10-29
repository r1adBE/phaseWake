package com.phasewake.app.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import com.phasewake.app.R

/**
 * Gerätesperre vor Schritten, die Daten löschen oder ersetzen (Verlauf löschen, Wiederherstellen):
 * Fingerabdruck, Gesicht (stark) oder PIN/Muster/Passwort des Geräts. Ohne Gerätesperre gilt nur die
 * Rückfrage davor. Plattform-BiometricPrompt (ab Android 11 mit Geräte-PIN), keine zusätzliche Bibliothek.
 */
object DeviceAuth {

    /** Ob eine Gerätesperre eingerichtet ist. */
    fun isSecure(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /**
     * Fragt nach der Gerätesperre und ruft danach [onSuccess]; Abbruch oder Fehler: nichts. Ohne Gerätesperre
     * (oder ohne Activity) sofort [onSuccess] – die Rückfrage davor bleibt dann die einzige Sicherung.
     */
    fun confirm(context: Context, reason: String, onSuccess: () -> Unit) {
        val activity = context.findActivity()
        if (activity == null || !isSecure(context)) {
            onSuccess()
            return
        }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.auth_title))
            .setSubtitle(reason)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            },
        )
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
