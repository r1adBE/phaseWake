package com.phasewake.app.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.phasewake.app.R
import com.phasewake.app.companion.CompanionLink
import com.phasewake.app.companion.CompanionSession
import com.phasewake.app.phoneApp
import kotlinx.coroutines.launch

/**
 * Knopf «Als Begleit-App verbinden» (Nacht-Check, Einrichtung): fragt zuerst nach «Geräte in der Nähe», damit
 * Android die gekoppelte Uhr direkt anbietet (abgelehnt: Android sucht, der Nutzer wählt), dann die Bestätigung
 * des Systems. [onChanged]: danach (mit oder ohne Zuordnung) den Nacht-Check neu prüfen.
 */
@Composable
internal fun rememberCompanionConnect(onChanged: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changed = rememberUpdatedState(onChanged)
    val session = remember {
        CompanionSession(context) { notFound ->
            if (notFound) Toast.makeText(context, R.string.companion_failed, Toast.LENGTH_LONG).show()
            changed.value()
        }
    }
    val approval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        session.onApproval(result.resultCode)
    }
    SideEffect { session.approve = { sender -> approval.launch(IntentSenderRequest.Builder(sender).build()) } }
    val begin: () -> Unit = { scope.launch { session.start(context.phoneApp.sync.connectedNames()) } }
    val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { begin() }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !CompanionLink.canReadPaired(context)) {
            nearby.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            begin()
        }
    }
}
