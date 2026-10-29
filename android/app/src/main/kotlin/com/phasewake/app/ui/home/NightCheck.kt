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
import com.phasewake.app.companion.CompanionLink
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.shared.DoNotDisturb

/**
 * Nacht-Check: Was muss stimmen, damit morgen sicher geweckt wird? Jede Prüfung hat einen Text
 * und – wenn der Nutzer etwas tun kann – einen Knopf, der direkt zur richtigen Einstellung führt.
 */
/**
 * Eine Prüfung des Nacht-Checks. [advisory]: nur ein Hinweis – die Karte bleibt «Bereit», weil der Wecker trotzdem
 * zuverlässig klingelt; der Hinweis steht mit Knopf darunter und fehlt im Widget.
 */
enum class Check(
    val title: Int,
    val problem: Int,
    val action: Int?,
    val problemTitle: Int = title,
    val inReadyList: Boolean = true,
    val advisory: Boolean = false,
) {
    WATCH(R.string.check_watch, R.string.check_watch_problem, null),

    /** Uhr verbunden, aber Phase Wake fehlt dort: Knopf öffnet den Play Store auf der Uhr. */
    WATCH_APP(R.string.check_watch_app, R.string.check_watch_app_problem, R.string.check_watch_app_action, inReadyList = false),
    WATCH_SETUP(R.string.check_watch_setup, R.string.check_watch_setup_problem, null),

    /**
     * Akku der Uhr unter [NightCheckState.LOW_BATTERY_PERCENT]: Sie hält die Nacht womöglich nicht durch.
     * Nicht in der Bereit-Liste: Ältere Uhren melden keinen Akkustand, ein Häkchen wäre dann geraten.
     */
    WATCH_BATTERY(R.string.check_watch_battery, R.string.check_watch_battery_problem, null, inReadyList = false),

    /**
     * Phase Wake auf der Uhr ist älter und kennt «Überspringen» und «Feiertage» nicht (`Codec.watchKnowsSkip`): Die Uhr
     * weckt dann trotzdem. Nicht in der Bereit-Liste; das Handy blendet beides aus, bis die Uhr aktualisiert ist.
     */
    WATCH_UPDATE(R.string.check_watch_update, R.string.check_watch_update_problem, null, inReadyList = false),
    NOTIFICATIONS(R.string.check_notifications, R.string.check_notifications_problem, R.string.check_allow),
    FULL_SCREEN(R.string.check_full_screen, R.string.check_full_screen_problem, R.string.check_allow),

    /**
     * Uhr erreichbar, Phase Wake aber nicht Begleit-App der Uhr ([CompanionLink]): Bei benutztem Handy erscheint der
     * Weckbildschirm sonst nur als Mitteilung oben. Nicht in der Bereit-Liste: ohne Uhr oder ohne Unterstützung
     * des Systems gibt es nichts zu prüfen.
     */
    COMPANION(R.string.check_companion, R.string.check_companion_problem, R.string.companion_connect, inReadyList = false, advisory = true),
    DND(R.string.check_dnd, R.string.dnd_text, R.string.dnd_action, problemTitle = R.string.check_dnd_blocked),

    /** «Nicht stören» ist an, aber ohne Sonderrecht nicht lesbar, ob Wecker durchkommen: ehrlich sagen. */
    DND_UNKNOWN(R.string.check_dnd, R.string.check_dnd_unknown_problem, R.string.dnd_action, problemTitle = R.string.check_dnd_unknown, inReadyList = false),
    BATTERY(R.string.check_battery, R.string.check_battery_problem, null),

    /** Wecker-Lautstärke auf 0: Der Zweitwecker klingelt dann lautlos. */
    VOLUME(R.string.check_volume, R.string.check_volume_problem, R.string.dnd_action),

    /** Exakte Alarme: nur auf Android 12/12L entziehbar (ab 13 gilt USE_EXACT_ALARM), nur dort gelistet. */
    EXACT_ALARMS(
        R.string.check_exact_alarms, R.string.check_exact_alarms_problem, R.string.check_allow,
        inReadyList = Build.VERSION.SDK_INT in Build.VERSION_CODES.S..Build.VERSION_CODES.S_V2,
    ),
}

/** Ergebnis aller Prüfungen; ohne offene Prüfung (Hinweise zählen nicht) heisst: bereit für die Nacht. */
data class NightCheckState(val failed: List<Check>, val watchChecked: Boolean = true) {
    val ready: Boolean get() = failed.none { !it.advisory }

    companion object {
        /** Unter diesem Akkustand (Handy ohne Ladekabel, Uhr laut ihrer Meldung) warnt der Check. */
        const val LOW_BATTERY_PERCENT = 20

        /**
         * Prüft alles, was ohne Uhr geht; die Uhr kommt aus [watchReachable], [watchReady],
         * [watchBattery] (Prozent, `null` = unbekannt oder zu alt) und [watchKnowsSkip] (Version der Uhr).
         */
        fun collect(
            context: Context,
            watchReachable: Boolean,
            watchReady: Boolean?,
            watchBattery: Int? = null,
            watchAppMissing: Boolean = false,
            watchKnowsSkip: Boolean = true,
        ): NightCheckState = NightCheckState(
            buildList {
                when {
                    watchAppMissing && !watchReachable -> add(Check.WATCH_APP)
                    !watchReachable -> add(Check.WATCH)
                    watchReady == false -> add(Check.WATCH_SETUP)
                }
                if (watchReachable && watchBattery != null && watchBattery < LOW_BATTERY_PERCENT) add(Check.WATCH_BATTERY)
                if (!watchKnowsSkip) add(Check.WATCH_UPDATE)
                if (!hasNotificationPermission(context)) add(Check.NOTIFICATIONS)
                if (!BackupAlarm.canScheduleExact(context)) add(Check.EXACT_ALARMS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
                ) add(Check.FULL_SCREEN)
                if (watchReachable && CompanionLink.supported(context) && !CompanionLink.isAssociated(context)) add(Check.COMPANION)
                when (DoNotDisturb.alarms(context)) {
                    DoNotDisturb.Alarms.BLOCKED -> add(Check.DND)
                    DoNotDisturb.Alarms.UNKNOWN -> add(Check.DND_UNKNOWN)
                    DoNotDisturb.Alarms.ALLOWED -> Unit
                }
                if (lowBattery(context)) add(Check.BATTERY)
                val audio = context.getSystemService(android.media.AudioManager::class.java)
                if (audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM) == 0) add(Check.VOLUME)
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
internal fun NightCheckCard(
    state: NightCheckState,
    containerColor: androidx.compose.ui.graphics.Color,
    onFix: (Check) -> Unit,
    /** «In 2 Minuten testen»; `null` = kein Knopf. */
    onTest: (() -> Unit)? = null,
    /** Zielzeit eines laufenden Probeweckers. */
    testAt: java.time.Instant? = null,
) {
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
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (ready) {
                        Text(stringResource(R.string.check_ready_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            AnimatedVisibility(ready && expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Check.entries.filter { it.inReadyList }.forEach { check ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(stringResource(check.title), style = MaterialTheme.typography.bodySmall, color = PhaseWakeTheme.brand.mutedSoft, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            TestButton(onTest, testAt)
            state.failed.forEach { check ->
                // Text über die ganze Breite, Knopf darunter rechts: lange Texte bleiben lesbar.
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(check.problemTitle), style = MaterialTheme.typography.titleSmall)
                    val problem = if (check == Check.BATTERY || check == Check.WATCH_BATTERY) {
                        stringResource(check.problem, NightCheckState.LOW_BATTERY_PERCENT)
                    } else {
                        stringResource(check.problem)
                    }
                    Text(problem, style = MaterialTheme.typography.bodyMedium)
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

/** Probewecker: Knopf, oder während er läuft seine Zeit. */
@Composable
private fun TestButton(onTest: (() -> Unit)?, testAt: java.time.Instant?) {
    when {
        testAt != null -> Text(
            stringResource(R.string.test_running, com.phasewake.app.ui.Format.time(testAt)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        onTest != null -> androidx.compose.material3.OutlinedButton(onClick = onTest) { Text(stringResource(R.string.test_button)) }
    }
}
