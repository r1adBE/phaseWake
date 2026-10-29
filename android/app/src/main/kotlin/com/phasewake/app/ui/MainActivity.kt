package com.phasewake.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.phasewake.app.R
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.PhoneRingActivity
import com.phasewake.app.ring.PhoneRingService
import com.phasewake.app.ui.components.Intro
import com.phasewake.app.ui.components.Night
import com.phasewake.app.ui.history.Export
import com.phasewake.app.ui.history.ExportDialog
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.home.MAX_ALARMS
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode
import com.phasewake.app.core.TestRuns
import com.phasewake.app.ui.home.TestResultDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Einzige Activity der Handy-App; Navigation zwischen Wecker, Verlauf, Einstellungen und Editor. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = phoneApp
        // Aus dem Wochenrückblick geöffnet: gleich der Verlauf.
        val openHistory = intent?.getStringExtra(EXTRA_OPEN) == OPEN_HISTORY
        setContent {
            val mode by app.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            PhaseWakeTheme(mode) { PhaseWakeNav(mode, openHistory) }
        }
    }

    companion object {
        /** Was beim Öffnen gezeigt wird (z. B. aus einer Mitteilung). */
        const val EXTRA_OPEN = "open"
        const val OPEN_HISTORY = "history"
    }
}

/** Ziele der Navigation; der Editor nimmt die Kennung des Weckers (ohne: neuer Wecker). */
internal object Routes {
    const val ALARMS = "alarms"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val EDITOR = "editor?id={id}"
    const val SOUNDS = "sounds"
    fun editor(id: String?) = if (id == null) "editor" else "editor?id=$id"
}

private data class Tab(val route: String, val label: Int, val icon: Int)

private val tabs = listOf(
    Tab(Routes.ALARMS, R.string.nav_alarms, R.drawable.ic_alarm),
    Tab(Routes.HISTORY, R.string.nav_history, R.drawable.ic_history),
    Tab(Routes.SETTINGS, R.string.nav_settings, R.drawable.ic_settings),
)

@Composable
private fun PhaseWakeNav(themeMode: ThemeMode, openHistory: Boolean = false) {
    val context = LocalContext.current
    val app = context.phoneApp
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val data = collectAppData(app)

    // Beim Fortsetzen hochgezählt: Nacht-Check und Aufnahme (checkRun), Health Connect (healthCheck) neu prüfen.
    var checkRun by remember { mutableIntStateOf(0) }
    var healthCheck by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        Format.update(context)
        checkRun++
        healthCheck++
        onPauseOrDispose { }
    }
    // Nächte aus Health Connect sind ein Spiegel der Quelle: auch bei jedem Öffnen des Verlaufs neu lesen.
    LaunchedEffect(route) { if (route == Routes.HISTORY) healthCheck++ }
    val insights = rememberInsights(app, data, healthCheck)
    val soundRec = rememberSoundRec(app, data, checkRun)
    val nightCheck = rememberNightCheck(app, checkRun)

    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checkRun++ }
    val healthPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        healthCheck++
    }
    // Als Begleit-App der Uhr verbinden; danach den Nacht-Check neu prüfen.
    val connectCompanion = rememberCompanionConnect { checkRun++ }
    // Teilen des Verlaufs: zuerst ein Satz dazu, was die Datei enthält.
    var askExport by rememberSaveable { mutableStateOf(false) }
    val actions = NavActions(
        nav = nav,
        onExport = { askExport = true },
        askNotificationsIfMissing = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        connectHealth = { healthPermission.launch(app.health.permissions) },
        onFix = { item ->
            when {
                item == Check.COMPANION -> connectCompanion()
                item != Check.NOTIFICATIONS -> fixCheck(context, item, nightCheck.missingOn)
                // Die Versionsprüfung steht hier sichtbar für Lint; canAskNotifications prüft sie ebenfalls.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canAskNotifications(context, askedNotifications) -> {
                    askedNotifications = true
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> openNotificationSettings(context)
            }
        },
    )

    // Aus dem Wochenrückblick: einmal zum Verlauf.
    var openedHistory by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openHistory) {
        if (openHistory && !openedHistory) {
            openedHistory = true
            nav.switchTab(Routes.HISTORY)
        }
    }
    // Erster Start: Einrichtung. Bestehende Installationen (Wecker oder Verlauf vorhanden) überspringen sie.
    LaunchedEffect(data.onboardingDone) {
        if (data.onboardingDone != false || openHistory) return@LaunchedEffect
        if (app.alarms.current().alarms.isNotEmpty() || app.nights.load().isNotEmpty()) {
            app.settings.setOnboardingDone(true)
        } else if (route != ExtraRoutes.ONBOARDING) {
            nav.navigate(ExtraRoutes.ONBOARDING)
        }
    }
    // Ergebnis des Probeweckers, sobald es feststeht (Stopp angekommen oder 3 Minuten nach der Zielzeit).
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(data.testRun) {
        while (data.testRun != null) {
            delay(TEST_TICK_MS)
            tick++
        }
    }
    val testResult = data.testRun?.takeIf { tick >= 0 && TestRuns.isFinished(it, java.time.Instant.now()) }
    // Klingelt es, ohne dass der Weckbildschirm erscheinen durfte (Vollbild nicht erlaubt): beim Öffnen zeigen.
    val ringing by PhoneRingService.ringState.collectAsStateWithLifecycle()
    LaunchedEffect(ringing) {
        if (ringing is PhoneRingService.State.Ringing) context.startActivity(Intent(context, PhoneRingActivity::class.java))
    }
    // Kopfbereich der Startseite ist immer Nachthimmel: dort helle Statusleisten-Symbole.
    val view = LocalView.current
    val lightBars = themeMode == ThemeMode.LIGHT || (themeMode == ThemeMode.SYSTEM && !isSystemInDarkTheme())
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightBars && route != Routes.ALARMS
    }
    // Wer «Animationen entfernen» eingeschaltet hat, bekommt keine Start-Animation.
    var intro by rememberSaveable { mutableStateOf(animationsEnabled(context)) }

    Box(Modifier.fillMaxSize()) {
        AppScaffold(app, NavState(data, insights, soundRec, nightCheck, themeMode), actions, route)
        if (askExport) {
            ExportDialog(
                onShare = {
                    askExport = false
                    Export.share(context, data.nights.orEmpty(), data.notes)
                },
                onDismiss = { askExport = false },
            )
        }
        testResult?.let { run ->
            TestResultDialog(run, nightCheck.check, onFix = actions.onFix, onClose = { app.appScope.launch { app.testRuns.clear() } })
        }
        if (intro) Intro(onFinished = { intro = false })
    }
}

/** Gerüst mit unterer Navigation, «Neuer Wecker» auf der Startseite und den Bildschirmen. */
@Composable
private fun AppScaffold(app: com.phasewake.app.PhaseWakeApp, state: NavState, actions: NavActions, route: String?) {
    val alarms = state.data.alarms
    // Scrollstand der Startseite: «Neuer Wecker» ist nur ganz oben ausgeschrieben, beim Scrollen ein
    // kleiner runder Knopf, damit er ruhiger über den Karten liegt.
    val homeList = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { !homeList.canScrollBackward } }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (route !in FULL_SCREEN_ROUTES) {
                val onNight = route == Routes.ALARMS && PhaseWakeTheme.isDark
                NavigationBar(containerColor = if (onNight) Night.Bar else MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { actions.nav.switchTab(tab.route) },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (route == Routes.ALARMS && alarms.isNotEmpty() && alarms.size < MAX_ALARMS) {
                // Lavendel mit dunkler Schrift (dunkel) bzw. Indigo mit Weiss (hell): Farben der Hauptfarbe.
                ExtendedFloatingActionButton(
                    onClick = { actions.nav.navigate(Routes.editor(null)) },
                    expanded = fabExpanded,
                    icon = {
                        // Eingeklappt bleibt der Name für Bildschirmleser erhalten.
                        val label = stringResource(R.string.home_add_alarm)
                        Icon(painterResource(R.drawable.ic_add), contentDescription = if (fabExpanded) null else label, modifier = Modifier.size(20.dp))
                    },
                    text = { Text(stringResource(R.string.home_add_alarm)) },
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val content = PaddingValues(
            start = padding.calculateStartPadding(direction),
            end = padding.calculateEndPadding(direction),
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 12.dp,
        )
        AppNavHost(app, state, actions, content, homeList)
    }
}

/** Bildschirme ohne untere Navigationsleiste. */
private val FULL_SCREEN_ROUTES = setOf(Routes.EDITOR, Routes.SOUNDS, ExtraRoutes.DIAGNOSTICS, ExtraRoutes.ONBOARDING)

/** So oft wird geprüft, ob das Ergebnis des Probeweckers feststeht. */
private const val TEST_TICK_MS = 5_000L

/** Wechselt den Reiter; jeder Reiter behält seinen Zustand (Scrollstand). */
internal fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(Routes.ALARMS) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
