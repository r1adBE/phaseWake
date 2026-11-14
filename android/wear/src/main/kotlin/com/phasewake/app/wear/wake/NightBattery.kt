package com.phasewake.app.wear.wake

import android.content.Context
import com.phasewake.app.core.Transient
import com.phasewake.app.shared.BatteryNight
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.wear.wearApp
import java.time.Instant
import kotlinx.coroutines.launch

// Akku je Nacht auf der Galaxy Watch (r1AD 14.11.2026): Messbeginn, Ende und Verwerfen; Rechnung in BatteryTrack.

/**
 * Die Uhr beginnt zu messen (Einschlaf-Messung, Aufzeichnung oder Weckfenster): Akkustand festhalten. Beginnt damit eine
 * neue Nacht, erfährt es das Handy ([DataLayer.PATH_NIGHT_BEGIN]) und misst ab dann seinen eigenen Akku. Probewecker und
 * Powernap zählen nicht. [alarmId] und [target] fehlen bei einer Aufzeichnung ohne bekannten Wecker.
 */
internal fun nightBatteryBegin(context: Context, alarmId: String?, target: Instant?) {
    if (alarmId != null && Transient.kindOf(alarmId) != null) return
    if (!BatteryNight.begin(context)) return
    val app = context.wearApp
    val message = RingMessage(alarmId, target?.toEpochMilli(), System.currentTimeMillis())
    app.appScope.launch { app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_NIGHT_BEGIN, message.encode()) }
}

/**
 * Eine Aufzeichnung endet: Läuft keine Weck-Nacht (weder Fenster noch Einschlaf-Messung), gehört der Akku zu keiner
 * Nacht im Verlauf und wird verworfen. Sonst endet er mit der Nacht ([WakeService.finishNight]).
 */
internal fun nightBatteryRecordEnded(context: Context) {
    if (WakeService.activePlan == null && !WakeService.goodNightActive) BatteryNight.drop(context, "recording ended without alarm night")
}
