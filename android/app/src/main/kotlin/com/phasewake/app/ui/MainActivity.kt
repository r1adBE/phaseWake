package com.phasewake.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.phasewake.app.ui.components.Intro
import com.phasewake.app.ui.components.Night
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.health.connect.client.PermissionController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.phasewake.app.R
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.DoNotDisturb
import com.phasewake.app.ui.editor.EditorScreen
import com.phasewake.app.ui.history.Export
import com.phasewake.app.ui.history.HistoryScreen
import com.phasewake.app.ui.home.HomeScreen
import com.phasewake.app.ui.settings.HealthState
import com.phasewake.app.ui.settings.SettingsScreen
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** Einzige Activity der Handy-App; Navigation zwischen Wecker, Verlauf, Einstellungen und Editor. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = phoneApp
        setContent {
            val mode by app.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            PhaseWakeTheme(mode) { PhaseWakeNav(mode) }
        }
    }
}

private object Routes {
    const val ALARMS = "alarms"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val EDITOR = "editor?id={id}"
    fun editor(id: String?) = if (id == null) "editor" else "editor?id=$id"
}

private data class Tab(val route: String, val label: Int, val icon: Int)

private val tabs = listOf(
    Tab(Routes.ALARMS, R.string.nav_alarms, R.drawable.ic_alarm),
    Tab(Routes.HISTORY, R.string.nav_history, R.drawable.ic_history),
    Tab(Routes.SETTINGS, R.string.nav_settings, R.drawable.ic_settings),
)

@Composable
private fun PhaseWakeNav(themeMode: ThemeMode) {
    val context = LocalContext.current
    val app = context.phoneApp
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val alarms by app.alarms.alarms.collectAsStateWithLifecycle(initialValue = emptyList())
    val nights by app.nights.nights.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.nights.load() }

    var needsPermission by remember { mutableStateOf(false) }
    var dndBlocks by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        needsPermission = missingAlarmAccess(context)
    }
    var health by remember { mutableStateOf(HealthState.UNAVAILABLE) }
    var healthCheck by remember { mutableStateOf(0) }
    LaunchedEffect(healthCheck) {
        health = when {
            !app.health.isAvailable -> HealthState.UNAVAILABLE
            app.health.isGranted() -> HealthState.CONNECTED
            else -> HealthState.AVAILABLE
        }
    }
    val healthPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        healthCheck++
    }
    LifecycleResumeEffect(Unit) {
        needsPermission = missingAlarmAccess(context)
        dndBlocks = DoNotDisturb.blocksAlarms(context)
        healthCheck++
        onPauseOrDispose { }
    }

    // Kopfbereich der Startseite ist immer Nachtblau: dort helle Statusleisten-Symbole.
    val view = LocalView.current
    val lightBars = themeMode == ThemeMode.LIGHT || (themeMode == ThemeMode.SYSTEM && !isSystemInDarkTheme())
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightBars && route != Routes.ALARMS
    }
    var intro by rememberSaveable { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (route != Routes.EDITOR) {
                val onNight = route == Routes.ALARMS && PhaseWakeTheme.isDark
                NavigationBar(containerColor = if (onNight) Night.Bottom.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (route == Routes.ALARMS && alarms.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.editor(null)) },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    text = { Text(stringResource(R.string.home_add_alarm)) },
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
        NavHost(
            nav,
            startDestination = Routes.ALARMS,
            enterTransition = { fadeIn() + slideInHorizontally { it / 12 } },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() + slideOutHorizontally { it / 12 } },
        ) {
            composable(Routes.ALARMS) {
                HomeScreen(
                    alarms = alarms,
                    lastNight = nights?.lastOrNull(),
                    needsPermission = needsPermission,
                    dndBlocks = dndBlocks,
                    onOpenDnd = { DoNotDisturb.openSettings(context) },
                    contentPadding = content,
                    onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(context)) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    },
                    onAdd = { nav.navigate(Routes.editor(null)) },
                    onEdit = { nav.navigate(Routes.editor(it)) },
                    onToggle = { alarm, enabled -> app.afterAlarmsChanged { app.alarms.setEnabled(alarm.id, enabled) } },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    nights.orEmpty(),
                    content,
                    loadStages = if (health == HealthState.CONNECTED) app.health::stagesFor else null,
                    onExport = { Export.share(context, nights.orEmpty()) },
                )
            }
            composable(Routes.SETTINGS) {
                val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
                SettingsScreen(
                    themeMode = themeMode,
                    version = version,
                    contentPadding = content,
                    onThemeMode = { app.appScope.launch { app.settings.setThemeMode(it) } },
                    onExport = { Export.share(context, nights.orEmpty()) },
                    health = health,
                    onConnectHealth = { healthPermission.launch(app.health.permissions) },
                )
            }
            composable(Routes.EDITOR) { entry ->
                val id = entry.arguments?.getString("id")
                EditorScreen(
                    existing = alarms.firstOrNull { it.id == id },
                    onBack = { nav.popBackStack() },
                    onSave = { spec ->
                        app.afterAlarmsChanged { app.alarms.upsert(spec) }
                        nav.popBackStack()
                    },
                    onDelete = { spec ->
                        app.afterAlarmsChanged { app.alarms.remove(spec.id) }
                        nav.popBackStack()
                    },
                )
            }
        }
    }
    if (intro) Intro(onFinished = { intro = false })
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(Routes.ALARMS) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun hasNotificationPermission(context: android.content.Context) =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Fehlt etwas, damit der Zweitwecker auffällig wecken kann? */
private fun missingAlarmAccess(context: android.content.Context): Boolean {
    if (!hasNotificationPermission(context)) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }
    return false
}
