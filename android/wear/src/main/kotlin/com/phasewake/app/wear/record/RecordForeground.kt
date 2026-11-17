package com.phasewake.app.wear.record

import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ServiceCompat
import com.phasewake.app.core.DiagKind
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.wear.wake.Notifications
import com.phasewake.app.wear.wake.SensorAccess
import com.phasewake.app.wear.wearApp
import java.time.Instant

/**
 * Vordergrund des [RecordService] als «health» (braucht Sensorrechte) mit der Mitteilung ab [since]. Verweigert: nur
 * kurz als «shortService» in den Vordergrund (kein eigenes Recht nötig), denn nach startForegroundService muss der
 * Dienst das tun; dann `false` und [RecordService.refusal] mit dem Grund – der Dienst misst nicht und beendet sich
 * sofort («systemExempted» nur noch beim Wecken).
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
            ServiceCompat.startForeground(this, Notifications.ID_RECORD, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } catch (e2: RuntimeException) {
            Log.e(RecordService.TAG, "Vordergrund nicht möglich", e2)
        }
        false
    }
}

/**
 * «health» bei einem weiteren Start-Befehl oder beim Messbeginn verweigert ([RecordService.refusal]): Die
 * Aufzeichnung endet sauber ([RecordEnd.SERVICE]), statt unter dem kurzen Rückfall weiterzulaufen; läuft der Start
 * noch, sobald er fertig ist.
 */
internal fun RecordService.healthLost() {
    wearApp.diag.log(DiagKind.MEASURE_END, "record health refused: ${refusal.orEmpty()}")
    pendingStop = null
    finish(RecordEnd.SERVICE)
}
