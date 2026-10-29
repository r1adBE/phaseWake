package com.phasewake.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.phasewake.app.R
import com.phasewake.app.shared.DoNotDisturb
import com.phasewake.app.ui.home.Check

// Wege in die Einstellungen des Systems (Nacht-Check) und auf die Uhr (Play Store).

/**
 * Öffnet zu einer offenen Prüfung des Nacht-Checks die passende Einstellungsseite. Mitteilungen
 * ([Check.NOTIFICATIONS]) und die Begleit-App ([Check.COMPANION]) fragt [PhaseWakeNav] selbst an; Prüfungen ohne
 * Einstellung tun nichts.
 * [missingOn]: Uhren ohne Phase Wake.
 */
internal fun fixCheck(context: Context, item: Check, missingOn: List<String>) {
    when (item) {
        Check.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            openSettings(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri()))
        }
        Check.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            openSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
        }
        Check.DND, Check.DND_UNKNOWN -> DoNotDisturb.openSettings(context)
        Check.VOLUME -> openSettings(context, Intent(Settings.ACTION_SOUND_SETTINGS))
        Check.WATCH_APP -> missingOn.firstOrNull()?.let { node -> openStoreOnWatch(context, node) }
        Check.NOTIFICATIONS, Check.COMPANION, Check.WATCH, Check.WATCH_SETUP, Check.WATCH_BATTERY, Check.WATCH_UPDATE, Check.BATTERY -> Unit
    }
}

/**
 * Ob das System nach dem Mitteilungsrecht noch fragt: ab Android 13, beim ersten Mal ([asked] `false`)
 * oder solange es eine Begründung zeigen will. Sonst ist es dauerhaft abgelehnt.
 */
internal fun canAskNotifications(context: Context, asked: Boolean): Boolean {
    val activity = context as? android.app.Activity
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        (!asked || activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true)
}

/** Dauerhaft abgelehnt: Android fragt nicht mehr, also direkt zu den Mitteilungs-Einstellungen der App. */
internal fun openNotificationSettings(context: Context) = openSettings(
    context,
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
)

/** Ob Animationen laufen; mit «Animationen entfernen» nicht. */
internal fun animationsEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** Öffnet eine Einstellungsseite; fehlt sie auf dem Gerät, die Seite der App, ersatzweise die Einstellungen. */
private fun openSettings(context: Context, intent: Intent) {
    val fallbacks = listOf(
        intent,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (candidate in fallbacks) {
        try {
            context.startActivity(candidate)
            return
        } catch (_: android.content.ActivityNotFoundException) {
            // Nächste Seite versuchen.
        }
    }
}

/**
 * Öffnet den Play-Store-Eintrag von Phase Wake auf der Uhr, damit dort die Uhr-App dazukommt. Der Aufruf läuft
 * über die Verbindung zur Uhr und kann dauern oder scheitern; beides bekommt eine kurze Rückmeldung am Handy,
 * damit der Knopf nie «nichts tut».
 */
private fun openStoreOnWatch(context: Context, nodeId: String) {
    val intent = Intent(Intent.ACTION_VIEW, "market://details?id=${context.packageName}".toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val app = context.applicationContext
    fun tell(text: Int) = android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
    try {
        val result = androidx.wear.remote.interactions.RemoteActivityHelper(app).startRemoteActivity(intent, nodeId)
        result.addListener({
            try {
                result.get()
                tell(R.string.watch_store_opened)
            } catch (e: java.util.concurrent.ExecutionException) {
                android.util.Log.w("PhaseWake", "Play Store auf der Uhr nicht geöffnet", e)
                tell(R.string.watch_store_failed)
            }
        }, ContextCompat.getMainExecutor(app))
    } catch (e: IllegalArgumentException) {
        android.util.Log.w("PhaseWake", "Play Store auf der Uhr nicht geöffnet", e)
        tell(R.string.watch_store_failed)
    }
}
