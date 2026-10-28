package com.phasewake.app.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phasewake.app.shared.DoNotDisturb
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DayFormat
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.components.FlowingWave
import com.phasewake.app.wear.ui.components.WindowRing
import com.phasewake.app.wear.ui.theme.Brand
import com.phasewake.app.wear.ui.theme.PhaseWakeTheme
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Startbildschirm der Uhr: nächstes Weckfenster, Wecker ein/aus, Berechtigungen. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhaseWakeTheme { HomeScreen() } }
    }
}

/** Laufzeit-Berechtigungen der Uhr; der Puls heisst ab Wear OS 6 anders. */
private fun requiredPermissions(): List<String> = buildList {
    add(if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS)
    add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val app = context.wearApp
    val scope = rememberCoroutineScope()
    val alarms by app.alarms.alarms.collectAsStateWithLifecycle(initialValue = emptyList())
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    val zone = ZoneId.systemDefault()
    val plan = remember(alarms, now) { AlarmSchedule.earliestPlan(alarms, now, zone) }
    val goodNightSince by app.goodNight.collectAsStateWithLifecycle()
    val sleepAlarm = alarms.firstOrNull { it.id == plan?.alarmId && it.sleepDurationMinutes != null }

    fun missing() = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    var missingPermissions by remember { mutableStateOf(missing()) }
    var dndBlocks by remember { mutableStateOf(DoNotDisturb.blocksAlarms(context)) }
    LifecycleResumeEffect(Unit) {
        dndBlocks = DoNotDisturb.blocksAlarms(context)
        missingPermissions = missing()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missingPermissions = missing()
    }

    val listState = rememberScalingLazyListState()
    AppScaffold {
        ScreenScaffold(scrollState = listState) {
            ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                item { NextWindow(plan, alarms.firstOrNull { it.id == plan?.alarmId }, zone) }
                if (sleepAlarm != null) {
                    item {
                        GoodNightButton(
                            active = goodNightSince != null,
                            sleepMinutes = sleepAlarm.sleepDurationMinutes ?: 0,
                            onClick = {
                                if (goodNightSince != null) {
                                    WakeService.command(context, WakeService.ACTION_GOOD_NIGHT_CANCEL)
                                } else {
                                    WakeService.goodNight(context, sleepAlarm.id)
                                }
                            },
                        )
                    }
                }
                if (missingPermissions.isNotEmpty()) {
                    item {
                        Button(
                            onClick = { launcher.launch(missingPermissions.toTypedArray()) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.home_permissions)) },
                            secondaryLabel = { Text(stringResource(R.string.home_permissions_hint)) },
                        )
                    }
                }
                if (dndBlocks) {
                    item {
                        Button(
                            onClick = { DoNotDisturb.openSettings(context) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            label = { Text(stringResource(R.string.dnd_title)) },
                            secondaryLabel = { Text(stringResource(R.string.dnd_text)) },
                        )
                    }
                }
                if (alarms.isNotEmpty()) {
                    item { ListHeader { Text(stringResource(R.string.home_alarms)) } }
                }
                items(alarms.size) { index ->
                    val alarm = alarms[index]
                    AlarmSwitch(alarm) { enabled ->
                        scope.launch {
                            app.sync.publishAlarms(app.alarms.setEnabled(alarm.id, enabled))
                            app.scheduler.reschedule()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextWindow(plan: WakePlan?, alarm: AlarmSpec?, zone: ZoneId) {
    Box(Modifier.size(width = 188.dp, height = 188.dp), contentAlignment = Alignment.Center) {
        if (plan != null) {
            WindowRing(dialFraction(plan.windowStart, zone), dialFraction(plan.target, zone), Modifier.size(176.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (plan == null) {
                Text(stringResource(R.string.home_no_alarm), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.home_no_alarm_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(130.dp),
                )
            } else {
                val day = plan.target.atZone(zone).toLocalDate()
                Text(
                    stringResource(if (day == LocalDate.now(zone)) R.string.home_today else R.string.home_tomorrow),
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.Mist,
                )
                Text(
                    stringResource(R.string.home_window, formatTime(plan.windowStart, zone), formatTime(plan.target, zone)),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.home_window_length, alarm?.windowMinutes ?: AlarmSpec.DEFAULT_WINDOW_MINUTES),
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.Muted,
                )
                FlowingWave(Modifier.padding(top = 6.dp).width(96.dp).height(18.dp))
            }
        }
    }
}

@Composable
private fun GoodNightButton(active: Boolean, sleepMinutes: Int, onClick: () -> Unit) {
    val hours = sleepMinutes / 60
    val rest = sleepMinutes % 60
    val duration = if (rest == 0) "$hours h" else "$hours h $rest min"
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Brand.Deep else Brand.Echo,
            contentColor = Brand.Moon,
            secondaryContentColor = Brand.Star,
        ),
        label = { Text(stringResource(R.string.home_good_night)) },
        secondaryLabel = {
            Text(
                if (active) stringResource(R.string.home_good_night_active)
                else stringResource(R.string.home_good_night_hint, duration),
            )
        },
    )
}

@Composable
private fun AlarmSwitch(alarm: AlarmSpec, onToggle: (Boolean) -> Unit) {
    val time = LocalTime.of(alarm.hour, alarm.minute)
    val start = time.minusMinutes(alarm.windowMinutes.toLong())
    val days = DayFormat.format(
        alarm.days, Locale.getDefault(),
        stringResource(R.string.alarm_once), stringResource(R.string.alarm_every_day),
    )
    SwitchButton(
        checked = alarm.enabled,
        onCheckedChange = onToggle,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.home_window, formatTime(start), formatTime(time))) },
        secondaryLabel = { Text(listOf(days, alarm.label).filter { it.isNotBlank() }.joinToString(" · ")) },
    )
}

/** Position einer Uhrzeit auf dem 12-Stunden-Zifferblatt (0 = 12 Uhr). */
private fun dialFraction(instant: Instant, zone: ZoneId): Float {
    val t = instant.atZone(zone).toLocalTime()
    return ((t.hour % 12) * 60 + t.minute) / 720f
}

private val shortTime: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun formatTime(instant: Instant, zone: ZoneId): String = shortTime.format(instant.atZone(zone))

private fun formatTime(time: LocalTime): String = shortTime.format(time)
