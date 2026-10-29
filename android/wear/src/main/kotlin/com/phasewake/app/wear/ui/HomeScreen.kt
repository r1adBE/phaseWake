package com.phasewake.app.wear.ui

import android.Manifest
import com.phasewake.app.wear.wake.RingUi
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.phasewake.app.core.DiagUnlock
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientKind
import com.phasewake.app.wear.R
import com.phasewake.app.wear.diag.WatchDiag
import com.phasewake.app.wear.sync.TransientInbox
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phasewake.app.shared.DoNotDisturb
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Startbildschirm der Uhr mit Zustand: Wecker, Rechte, «Gute Nacht», Weckbildschirm nachholen.

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
internal fun HomeScreen(onAdd: () -> Unit, onEdit: (AlarmSpec) -> Unit, onNap: () -> Unit = {}, onDiagnostics: () -> Unit = {}) {
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

    val extras = homeExtras(now, zone, onNap, onDiagnostics)
    HomeContent(
        extras = extras,
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
        onAdd = onAdd,
        onEdit = onEdit,
    )
}

/** Powernap, Überspringen und Diagnose (7× auf das Weckfenster, ab dem 4. Mal «Noch N Mal»). */
@Composable
private fun homeExtras(now: Instant, zone: ZoneId, onNap: () -> Unit, onDiagnostics: () -> Unit): WatchHomeExtras {
    val context = LocalContext.current
    val app = context.wearApp
    val res = LocalResources.current
    val transient by app.transient.stored.collectAsStateWithLifecycle(initialValue = null)
    val diagFlow = remember { WatchDiag.shown(context) }
    val diagnostics by diagFlow.collectAsStateWithLifecycle(initialValue = false)
    var taps by remember { mutableIntStateOf(0) }
    val locale = LocalConfiguration.current.locales[0]
    return WatchHomeExtras(
        nap = transient?.takeIf { it.kind == TransientKind.NAP && Transient.isActive(it, now) },
        onNap = onNap,
        onStopNap = { id -> app.appScope.launch { TransientInbox.cancelled(app, id, fromWatch = true) } },
        skipped = { alarm ->
            AlarmSchedule.skippedDate(alarm, now, zone)?.let {
                java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdM"), locale).format(it)
            }
        },
        onWindowTap = {
            if (!diagnostics) {
                taps++
                DiagUnlock.remaining(taps)?.let { Toast.makeText(context, res.getQuantityString(R.plurals.diag_taps_left, it, it), Toast.LENGTH_SHORT).show() }
                if (DiagUnlock.unlocked(taps)) app.appScope.launch { WatchDiag.setShown(context, true) }
            }
        },
        diagnostics = diagnostics,
        onDiagnostics = onDiagnostics,
    )
}

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
