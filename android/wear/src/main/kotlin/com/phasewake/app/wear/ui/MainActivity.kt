package com.phasewake.app.wear.ui

import android.Manifest
import com.phasewake.app.wear.wake.RingUi
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
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
import com.phasewake.app.core.WakeReason
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.DayFormat
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.components.FlowingWave
import com.phasewake.app.wear.ui.components.WindowRing
import com.phasewake.app.wear.ui.theme.Brand
import com.phasewake.app.wear.ui.theme.PhaseWakeTheme
import com.phasewake.app.wear.ui.theme.nightGlow
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

/** Puls im Hintergrund (ab Wear OS 4); das System verlangt es getrennt und erst nach dem Pulsrecht. */
private fun backgroundPermission(): String? = when {
    Build.VERSION.SDK_INT >= 36 -> "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "android.permission.BODY_SENSORS_BACKGROUND"
    else -> null
}

/** Das Vordergrundrecht für den Puls, ohne das es kein Hintergrundrecht gibt. */
private fun heartRatePermission(): String =
    if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val app = context.wearApp
    val scope = rememberCoroutineScope()
    // `null`, bis die Wecker geladen sind: so blinkt beim Öffnen nicht kurz «Kein Wecker gestellt».
    val loaded by app.alarms.alarms.collectAsStateWithLifecycle(initialValue = null)
    val alarms = loaded.orEmpty()
    val nights by app.nights.nights.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.nights.load() }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    val zone = ZoneId.systemDefault()
    // Wie Kachel und Komplikation: mit erkanntem Einschlafen, ohne eine schon erledigte Nacht.
    var plan by remember { mutableStateOf<WakePlan?>(null) }
    LaunchedEffect(loaded, now) { plan = app.scheduler.nextPlan() }
    // Aus dem Speicher: zeigt «Gute Nacht» auch nach einem Neustart des Prozesses richtig an.
    val goodNightSince by app.goodNight.since.collectAsStateWithLifecycle(initialValue = null)
    val sleepAlarm = alarms.firstOrNull { it.id == plan?.alarmId && it.sleepDurationMinutes != null }
    // «Gute Nacht» nur für die kommende Nacht (eingestellte Weckzeit höchstens 16 Std. entfernt).
    fun goodNightTooFar(at: Instant): Boolean {
        val latest = sleepAlarm?.let { AlarmSchedule.nextTarget(it, at, zone) } ?: return true
        return Duration.between(at, latest) > WakeService.GOOD_NIGHT_MAX_AHEAD
    }

    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun missing() = requiredPermissions().filterNot(::granted)
    // Hintergrundrecht erst, wenn das Pulsrecht erteilt ist (sonst lehnt das System es ab).
    fun missingBackground() = backgroundPermission()?.takeIf { granted(heartRatePermission()) && !granted(it) }
    var missingPermissions by remember { mutableStateOf(missing()) }
    var backgroundMissing by remember { mutableStateOf(missingBackground()) }
    fun fullScreenMissing() = !NotificationManagerCompat.from(context).canUseFullScreenIntent()
    var fullScreenBlocked by remember { mutableStateOf(fullScreenMissing()) }
    // Nach einer Ablehnung fragt das System oft nicht mehr: dann die App-Einstellungen öffnen.
    var askedOnce by remember { mutableStateOf(false) }
    var dndBlocks by remember { mutableStateOf(DoNotDisturb.blocksAlarms(context)) }
    LifecycleResumeEffect(Unit) {
        fullScreenBlocked = fullScreenMissing()
        dndBlocks = DoNotDisturb.blocksAlarms(context)
        missingPermissions = missing()
        backgroundMissing = missingBackground()
        // Wecker vom Handy abholen (falls eine Änderung verpasst wurde), dann neu planen; das meldet
        // auch den Status (Rechte) ans Handy, für dessen Nacht-Check.
        app.appScope.launch {
            app.sync.fetchAlarms()?.let { app.alarms.applyRemote(it) }
            app.scheduler.reschedule()
        }
        onPauseOrDispose { }
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        backgroundMissing = missingBackground()
        askedOnce = true
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        missingPermissions = missing()
        backgroundMissing = missingBackground()
        // Gleich danach das Hintergrundrecht: Es lässt sich nur getrennt anfragen.
        backgroundMissing?.let { backgroundLauncher.launch(it) }
        app.appScope.launch { app.scheduler.reschedule() }
    }
    // Klingelt es, ohne dass der Weckbildschirm erscheinen durfte (Mitteilungen oder Vollbild nicht erlaubt): beim Öffnen zeigen.
    val ring by app.ringState.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ring) {
        if (ring is RingUi.Ringing) context.startActivity(Intent(context, RingActivity::class.java))
    }

    HomeContent(
        alarms = alarms,
        loading = loaded == null,
        lastNight = nights?.lastOrNull(),
        plan = plan,
        zone = zone,
        goodNightActive = goodNightSince != null,
        goodNightTooFar = goodNightTooFar(now),
        permissionsMissing = missingPermissions.isNotEmpty() || backgroundMissing != null || fullScreenBlocked,
        dndBlocks = dndBlocks,
        onGoodNight = {
            val alarm = sleepAlarm ?: return@HomeContent
            if (goodNightSince != null) {
                WakeService.cancelGoodNight(context)
            } else if (!goodNightTooFar(Instant.now())) {
                WakeService.goodNight(context, alarm.id)
            }
        },
        onRequestPermissions = {
            val background = backgroundMissing
            when {
                (missingPermissions.isNotEmpty() || background != null) && askedOnce -> openAppSettings(context)
                missingPermissions.isNotEmpty() -> launcher.launch(missingPermissions.toTypedArray())
                background != null -> backgroundLauncher.launch(background)
                fullScreenBlocked -> openFullScreenSettings(context)
            }
        },
        onOpenDnd = { DoNotDisturb.openSettings(context) },
        onToggle = { alarm, enabled ->
            scope.launch {
                app.sync.publishAlarms(app.alarms.setEnabled(alarm.id, enabled))
                app.scheduler.reschedule()
            }
        },
    )
}

/** Startbildschirm ohne eigenen Zustand; so auch für Vorschau und Bildschirmfotos nutzbar. */
@Composable
internal fun HomeContent(
    alarms: List<AlarmSpec>,
    plan: WakePlan?,
    loading: Boolean = false,
    lastNight: NightRecord? = null,
    zone: ZoneId,
    goodNightActive: Boolean,
    goodNightTooFar: Boolean = false,
    permissionsMissing: Boolean,
    dndBlocks: Boolean,
    onGoodNight: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenDnd: () -> Unit,
    onToggle: (AlarmSpec, Boolean) -> Unit,
) {
    val sleepAlarm = alarms.firstOrNull { it.id == plan?.alarmId && it.sleepDurationMinutes != null }
    val listState = rememberScalingLazyListState()
    AppScaffold {
        ScreenScaffold(scrollState = listState) {
            ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize().nightGlow(strength = 0.55f)) {
                item { NextWindow(plan, alarms.firstOrNull { it.id == plan?.alarmId }, zone, loading) }
                if (sleepAlarm != null) {
                    item {
                        GoodNightButton(
                            active = goodNightActive,
                            tooFar = goodNightTooFar && !goodNightActive,
                            sleepMinutes = sleepAlarm.sleepDurationMinutes ?: 0,
                            onClick = onGoodNight,
                        )
                    }
                }
                if (permissionsMissing) {
                    item {
                        Button(
                            onClick = onRequestPermissions,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.home_permissions)) },
                            secondaryLabel = { Text(stringResource(R.string.home_permissions_hint)) },
                        )
                    }
                }
                if (dndBlocks) {
                    item {
                        Button(
                            onClick = onOpenDnd,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            label = { Text(stringResource(R.string.dnd_title)) },
                            secondaryLabel = { Text(stringResource(R.string.dnd_text)) },
                        )
                    }
                }
                if (lastNight != null) {
                    item { LastNight(lastNight, zone) }
                }
                if (alarms.isNotEmpty()) {
                    item { ListHeader { Text(stringResource(R.string.home_alarms)) } }
                }
                items(alarms.size) { index ->
                    val alarm = alarms[index]
                    AlarmSwitch(alarm) { enabled -> onToggle(alarm, enabled) }
                }
            }
        }
    }
}

@Composable
private fun NextWindow(plan: WakePlan?, alarm: AlarmSpec?, zone: ZoneId, loading: Boolean = false) {
    // Etwas kleiner als das Display, damit darunter «Gute Nacht» schon beim Öffnen zu sehen ist.
    Box(Modifier.size(width = 164.dp, height = 164.dp), contentAlignment = Alignment.Center) {
        if (loading) return@Box
        if (plan != null) {
            WindowRing(dialFraction(plan.windowStart, zone), dialFraction(plan.target, zone), Modifier.size(156.dp))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.semantics(mergeDescendants = true) { },
        ) {
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
                val today = LocalDate.now(zone)
                val locale = LocalConfiguration.current.locales[0]
                Text(
                    when (day) {
                        today -> stringResource(R.string.home_today)
                        today.plusDays(1) -> stringResource(R.string.home_tomorrow)
                        else -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
                    },
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

/** Letzte Nacht in einer Zeile: wann und warum geweckt wurde. */
@Composable
private fun LastNight(night: NightRecord, zone: ZoneId) {
    val reason = stringResource(
        when (night.reason) {
            WakeReason.LIGHT_SLEEP -> R.string.ring_reason_light
            WakeReason.AWAKE -> R.string.ring_reason_awake
            WakeReason.DEADLINE -> R.string.ring_reason_deadline
        },
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { },
    ) {
        Text(stringResource(R.string.home_last_night), style = MaterialTheme.typography.labelSmall, color = Brand.Muted)
        Text(formatTime(night.wokeAt, zone), style = MaterialTheme.typography.titleMedium)
        Text(reason, style = MaterialTheme.typography.bodySmall, color = Brand.Mist, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GoodNightButton(active: Boolean, tooFar: Boolean, sleepMinutes: Int, onClick: () -> Unit) {
    val hours = sleepMinutes / 60
    val rest = sleepMinutes % 60
    val duration = if (rest == 0) {
        stringResource(R.string.duration_hours, hours)
    } else {
        stringResource(R.string.duration_hours_minutes, hours, rest)
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        // Weckzeit zu weit weg: Hinweis statt Messung.
        enabled = !tooFar,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Brand.Deep else Brand.Echo,
            contentColor = Brand.Moon,
            secondaryContentColor = Brand.Star,
        ),
        label = { Text(stringResource(R.string.home_good_night)) },
        secondaryLabel = {
            Text(
                when {
                    active -> stringResource(R.string.home_good_night_active)
                    tooFar -> stringResource(R.string.home_good_night_too_far)
                    else -> stringResource(R.string.home_good_night_hint, duration)
                },
            )
        },
    )
}

@Composable
private fun AlarmSwitch(alarm: AlarmSpec, onToggle: (Boolean) -> Unit) {
    val time = LocalTime.of(alarm.hour, alarm.minute)
    val start = time.minusMinutes(alarm.windowMinutes.toLong())
    val days = DayFormat.format(
        alarm.days, LocalConfiguration.current.locales[0],
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

/** App-Einstellungen (Berechtigungen), wenn das System nicht mehr nachfragt. */
private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        // Ohne Einstellungsseite bleibt nur der Hinweis.
    }
}

/** Erlaubnis für den Weckbildschirm (Vollbild-Mitteilung); sonst die App-Einstellungen. */
private fun openFullScreenSettings(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.fromParts("package", context.packageName, null))
        try {
            context.startActivity(intent)
            return
        } catch (_: android.content.ActivityNotFoundException) {
            // weiter unten
        }
    }
    openAppSettings(context)
}
