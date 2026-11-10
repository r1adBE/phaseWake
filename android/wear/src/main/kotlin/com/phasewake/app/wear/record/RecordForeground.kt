package com.phasewake.app.wear.record

import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ServiceCompat
import com.phasewake.app.wear.wake.Notifications
import com.phasewake.app.wear.wake.SensorAccess
import java.time.Instant

/**
 * Vordergrund des [RecordService] als «health» (braucht Sensorrechte) mit der Mitteilung ab [since]. Verweigert: trotzdem
 * kurz in den Vordergrund (als Wecker-App), denn nach startForegroundService muss der Dienst das tun; dann `false` und
 * [RecordService.refusal] mit dem Grund.
 */
// Die Diensttypen (API 34) setzt ServiceCompat auf älteren Versionen nicht; das Einbetten ist unbedenklich.
@android.annotation.SuppressLint("InlinedApi")
internal fun RecordService.goForeground(since: Instant): Boolean {
    val shown = RecordControl.notification(this, since)
    return try {
        ServiceCompat.startForeground(this, Notifications.ID_RECORD, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        refusal = null
        true
    } catch (e: RuntimeException) {
        Log.w(RecordService.TAG, "Aufzeichnen nicht erlaubt", e)
        refusal = SensorAccess.refusal(e, SensorAccess.of(this))
        try {
            ServiceCompat.startForeground(this, Notifications.ID_RECORD, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } catch (e2: RuntimeException) {
            Log.e(RecordService.TAG, "Vordergrund nicht möglich", e2)
        }
        false
    }
}
