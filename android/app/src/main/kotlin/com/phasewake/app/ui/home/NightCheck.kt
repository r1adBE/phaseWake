package com.phasewake.app.ui.home

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.phasewake.app.R
import com.phasewake.app.shared.DoNotDisturb

/**
 * Nacht-Check: Was muss stimmen, damit morgen sicher geweckt wird? Jede Prüfung hat einen Text
 * und – wenn der Nutzer etwas tun kann – einen Knopf, der direkt zur richtigen Einstellung führt.
 */
enum class Check(val title: Int, val problem: Int, val action: Int?) {
    WATCH(R.string.check_watch, R.string.check_watch_problem, null),
    NOTIFICATIONS(R.string.check_notifications, R.string.check_notifications_problem, R.string.check_allow),
    FULL_SCREEN(R.string.check_full_screen, R.string.check_full_screen_problem, R.string.check_allow),
    DND(R.string.check_dnd, R.string.dnd_text, R.string.dnd_action),
    BATTERY(R.string.check_battery, R.string.check_battery_problem, null),
}

/** Ergebnis aller Prüfungen; `failed` leer heisst: bereit für die Nacht. */
data class NightCheckState(val failed: List<Check>, val watchChecked: Boolean = true) {
    val ready: Boolean get() = failed.isEmpty()

    companion object {
        /** Unter diesem Akkustand (ohne Ladekabel) warnt der Check vor dem Zweitwecker. */
        const val LOW_BATTERY_PERCENT = 20

        /** Prüft alles, was ohne Uhr geht; die Uhr kommt aus [watchReachable]. */
        fun collect(context: Context, watchReachable: Boolean): NightCheckState = NightCheckState(
            buildList {
                if (!watchReachable) add(Check.WATCH)
                if (!hasNotificationPermission(context)) add(Check.NOTIFICATIONS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
                ) add(Check.FULL_SCREEN)
                if (DoNotDisturb.blocksAlarms(context)) add(Check.DND)
                if (lowBattery(context)) add(Check.BATTERY)
            },
        )

        fun hasNotificationPermission(context: Context) =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun lowBattery(context: Context): Boolean {
            val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
            val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            return !plugged && level >= 0 && level * 100 / scale < LOW_BATTERY_PERCENT
        }
    }
}

/** Karte auf der Startseite: grün und knapp, wenn alles stimmt; sonst jede offene Prüfung mit Knopf. */
@Composable
internal fun NightCheckCard(state: NightCheckState, containerColor: androidx.compose.ui.graphics.Color, onFix: (Check) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ready = state.ready
    val readyText = stringResource(R.string.check_ready)
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (ready) containerColor else MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (ready) Modifier.clickable(role = Role.Button) { expanded = !expanded } else Modifier)
                    .semantics { if (ready) stateDescription = readyText },
            ) {
                Icon(
                    painterResource(if (ready) R.drawable.ic_check else R.drawable.ic_close),
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        stringResource(if (ready) R.string.check_ready else R.string.check_not_ready),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (ready) {
                        Text(stringResource(R.string.check_ready_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            AnimatedVisibility(ready && expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Check.entries.forEach { check ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(stringResource(check.title), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            state.failed.forEach { check ->
                // Text über die ganze Breite, Knopf darunter rechts: lange Texte bleiben lesbar.
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(check.title), style = MaterialTheme.typography.labelLarge)
                    val problem = if (check == Check.BATTERY) {
                        stringResource(check.problem, NightCheckState.LOW_BATTERY_PERCENT)
                    } else {
                        stringResource(check.problem)
                    }
                    Text(problem, style = MaterialTheme.typography.bodySmall)
                    check.action?.let { action ->
                        FilledTonalButton(onClick = { onFix(check) }, modifier = Modifier.align(Alignment.End).padding(top = 6.dp)) {
                            Text(stringResource(action))
                        }
                    }
                }
            }
        }
    }
}
