package com.phasewake.app.companion

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.phasewake.app.core.CompanionPick
import com.phasewake.app.core.DiagKind
import com.phasewake.app.phoneApp

/**
 * Ein Verbinden als Begleit-App: Anfragen aus [CompanionLink.requests] der Reihe nach, bis eine die Uhr findet.
 * Die Bestätigung des Nutzers zeigt das System; [approve] startet sie (Activity-Ergebnis an [onApproval]).
 * [onFinished]: Ende mit oder ohne Zuordnung; `notFound` = keine Anfrage fand die Uhr (nicht: Nutzer lehnte ab).
 */
internal class CompanionSession(private val context: Context, private val onFinished: (notFound: Boolean) -> Unit) {
    /** Startet die Bestätigung des Systems (`startIntentSenderForResult`); setzt der Bildschirm, der sie zeigt. */
    var approve: (IntentSender) -> Unit = {}

    private var attempts: List<AssociationRequest> = emptyList()

    /** Stelle der laufenden Anfrage in [attempts]. */
    private var position = 0

    /** Kennung der laufenden Anfrage (zählt über alle Starts hoch); Rückmeldungen früherer werden übergangen. */
    private var serial = 0

    /** Ob das System für die laufende Anfrage schon eine Bestätigung angeboten hat. */
    private var offered = false

    /** Anfrage, zu der die zuletzt gestartete Bestätigung gehört (ihr Ergebnis kann nach der Rückmeldung kommen). */
    private var offeredFor = -1

    /** Beginnt neu mit den Namen [nodeNames] der verbundenen Uhren (Wear OS); liest dafür die gekoppelten Geräte. */
    fun start(nodeNames: List<String>) {
        val device = CompanionPick.choose(CompanionLink.paired(context), nodeNames)
        attempts = CompanionLink.requests(device, nodeNames)
        log("request paired=${CompanionLink.canReadPaired(context)} device=${device != null} names=${nodeNames.size}")
        run(0)
    }

    /** Ergebnis der Bestätigung; ab Android 14 meldet das System «nichts gefunden» als eigenes Ergebnis. */
    fun onApproval(resultCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            resultCode == CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT
        ) {
            next(offeredFor)
        } else {
            // Erfolg oder Ablehnung: Der Nacht-Check liest die Zuordnung neu.
            onFinished(false)
        }
    }

    private fun run(index: Int) {
        position = index
        serial++
        offered = false
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        if (manager == null) {
            onFinished(true)
            return
        }
        val callback = Callback(serial)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.associate(attempts[index], context.mainExecutor, callback)
            } else {
                manager.associate(attempts[index], callback, Handler(Looper.getMainLooper()))
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Anfrage als Begleit-App abgelehnt", e)
            next(serial)
        }
    }

    /** Anfrage [request] fand nichts: die nächste, sonst Ende mit «nicht gefunden». */
    private fun next(request: Int) {
        if (request != serial) return
        if (position + 1 < attempts.size) {
            log("attempt ${position + 1} found nothing, next")
            run(position + 1)
        } else {
            serial++
            log("no watch found")
            onFinished(true)
        }
    }

    private fun offer(request: Int, sender: IntentSender) {
        if (request != serial) return
        offered = true
        offeredFor = request
        try {
            approve(sender)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Bestätigung als Begleit-App nicht startbar", e)
            onFinished(false)
        }
    }

    private fun failed(request: Int, error: CharSequence?) {
        if (request != serial) return
        val reason = error?.toString().orEmpty()
        log("failed: ${reason.take(MAX_REASON)}")
        // Ohne angebotene Bestätigung (bis Android 12L sucht das System vorher) oder nach Ablauf der Suche
        // (Android 13: nur am Text erkennbar) fand die Anfrage nichts; sonst lehnte der Nutzer ab.
        if (!offered || reason == REASON_DISCOVERY_TIMEOUT) next(request) else onFinished(false)
    }

    private fun log(text: String) = context.phoneApp.diag.log(DiagKind.RING_SCREEN, "companion $text")

    /** Rückmeldungen des Systems zu Anfrage [request]; bis Android 12L über einen Handler, ab 13 über einen Executor. */
    private inner class Callback(private val request: Int) : CompanionDeviceManager.Callback() {
        // Bis Android 12L der einzige Weg; ab 13 ruft das System onAssociationPending.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onDeviceFound(intentSender: IntentSender) = offer(request, intentSender)

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onAssociationPending(intentSender: IntentSender) = offer(request, intentSender)

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onAssociationCreated(associationInfo: AssociationInfo) {
            if (request != serial) return
            serial++
            log("created")
            onFinished(false)
        }

        override fun onFailure(error: CharSequence?) = failed(request, error)
    }

    private companion object {
        const val TAG = "CompanionSession"

        /** Text des Systems bei abgelaufener Suche (AOSP `CompanionDeviceManager.REASON_DISCOVERY_TIMEOUT`, verborgen). */
        const val REASON_DISCOVERY_TIMEOUT = "discovery_timeout"

        /** Höchstlänge des Fehlertexts im Protokoll. */
        const val MAX_REASON = 40
    }
}
