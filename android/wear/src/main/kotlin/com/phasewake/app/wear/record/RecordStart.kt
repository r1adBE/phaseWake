package com.phasewake.app.wear.record

import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Epoch
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.wear.diag.WatchDiag
import com.phasewake.app.wear.wake.SensorAccess
import com.phasewake.app.wear.wearApp
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Start und Wiederaufnahme des RecordService, ausgelagert aus RecordService.kt.

/**
 * Neue Aufzeichnung ab jetzt (vom Knopf der Uhr, vom Handy oder von der Lernphase über [RecordControl]; [by] fürs
 * Diagnose-Protokoll). Lernphase: [learnUntil] = eingestellte Weckzeit, siehe [LearnMark]. Von Hand durchgehend nur
 * mit freigeschalteter Diagnose ([WatchDiag.shown], vom Handy [phoneDiag]).
 */
internal fun RecordService.begin(by: String, learnUntil: Instant? = null, phoneDiag: Boolean = false) {
    val app = wearApp
    val battery = RecordControl.battery(this)
    if (active != null || starting) {
        if (!goForeground(active?.start ?: RecordService.clock())) healthLost()
        return
    }
    val low = battery != null && battery < NightRecording.MIN_BATTERY
    if (!goForeground(RecordService.clock()) || low) {
        val why = if (low) "battery $battery%" else "no sensors: ${refusal.orEmpty()}"
        app.diag.log(if (low) DiagKind.BATTERY else DiagKind.MEASURE_START, "record not started ($by): $why")
        write { app.recordSync.publishStatus(null) }
        stopSelf()
        return
    }
    starting = true
    lifecycleScope.launch {
        // Eine liegengebliebene Aufzeichnung (Prozessende ohne Wiederaufnahme) zuerst abschliessen (ganz, auch wenn
        // der Dienst dabei endet; erst danach die neue Datei).
        write { RecordControl.finishOrphan(app, RecordEnd.SERVICE) }.join()
        val start = Instant.ofEpochMilli(RecordService.clock().toEpochMilli())
        val continuous = learnUntil == null && (phoneDiag || WatchDiag.shown(this@begin).first())
        app.recordings.begin(start)
        if (continuous) app.learnMark.setContinuous(start)
        // Ohne gespeicherte Marke endet sie trotzdem beim Wecken, solange der Dienst läuft (nur nach einem
        // Prozessende nicht).
        var until = learnUntil
        if (learnUntil != null) {
            try {
                app.learnMark.set(LearnMark(start, learnUntil))
                // Seit dem Auftrag neu geplant (Wecker verschoben): gleich folgen; spätere Änderungen sehen die
                // Marke.
                until = RecordControl.followPlan(this@begin, app.scheduler.nextAlarmPlan(), RecordService.clock()) ?:
                    learnUntil
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(RecordService.TAG, "Marke der Lernphase nicht gespeichert oder nicht nachgeführt", e)
            }
        }
        starting = false
        measure(
            RecordService.Active(start, mutableListOf(), 0, until, sampled = !continuous),
            "record start by $by bat=${battery ?: "-"} ${SensorAccess.of(this@begin).line()}",
        )
    }
}

/** Nach einem Prozessende (Prüf-Alarm): gespeicherte Aufzeichnung weiterführen, Lücke mit leeren Epochen füllen. */
internal fun RecordService.resume() {
    val app = wearApp
    if (active != null || starting) {
        if (!goForeground(active?.start ?: RecordService.clock())) healthLost()
        return
    }
    starting = true
    val allowed = goForeground(RecordService.clock())
    lifecycleScope.launch {
        val saved = app.recordings.active()
        starting = false
        if (saved == null) {
            stopSelf()
            return@launch
        }
        if (!allowed) {
            app.diag.log(DiagKind.MEASURE_END, "record resume refused: ${refusal.orEmpty()}")
            write { RecordControl.finishOrphan(app, RecordEnd.SERVICE) }.join()
            stopSelf()
            return@launch
        }
        val now = RecordService.clock()
        val battery = RecordControl.battery(this@resume)
        NightRecording.endNow(saved.start, now, battery)?.let { reason ->
            app.diag.log(
                DiagKind.MEASURE_END,
                "record end ${RecordService.reasonText(reason, battery)} (on resume) epochs=${saved.epochs.size}",
            )
            // Abschliessen und ans Handy einreihen übersteht das Ende des Dienstes (wie [finish]).
            write { RecordControl.finishOrphan(app, reason) }.join()
            stopSelf()
            return@launch
        }
        val epochs = saved.epochs.toMutableList()
        val gap = NightRecording.epochsUntil(saved.start, now) - epochs.size
        repeat(gap.coerceAtLeast(0)) { epochs += Epoch(null, null) }
        // Aufzeichnung der Lernphase: endet weiter beim Wecken.
        val learnUntil = app.learnMark.get()?.takeIf { it.start == saved.start }?.until
        val sampled = learnUntil != null || !app.learnMark.isContinuous(saved.start)
        measure(
            RecordService.Active(saved.start, epochs, saved.epochs.size, learnUntil, sampled),
            "record resume gap=${gap.coerceAtLeast(0)} bat=${battery ?: "-"}",
        )
    }
}
