package com.phasewake.app.ui

import android.Manifest
import com.phasewake.app.QuietApp
import com.phasewake.app.shared.PermissionAsks
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Prüfrunde 2, B-6: «Schon gefragt» galt nur im laufenden Prozess. Nach «Nicht mehr fragen» bewirkte darum der erste
 * Tipp auf «Erlauben» nach jedem Start sichtbar nichts (das System lehnt still ab). Jetzt übersteht der Merker das
 * Prozessende; ohne gewünschte Begründung öffnet schon der erste Tipp die Einstellungen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class PermissionAsksTest {
    private val permission = Manifest.permission.POST_NOTIFICATIONS

    @Test
    fun askedSurvivesNewActivityAndProcess() {
        val first = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        assertTrue(canAskNotifications(first), "Noch nie gefragt: Das System zeigt die Anfrage")
        PermissionAsks.markAsked(first, listOf(permission))
        // Neuer Start: eine neue Activity, der Merker liegt in den Einstellungen der App, nicht im Speicher.
        val later = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        assertTrue(PermissionAsks.asked(later, permission))
        assertFalse(canAskNotifications(later), "Dauerhaft abgelehnt: gleich die Einstellungen öffnen")
    }

    @Test
    fun rationaleAllowsAskingAgain() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        PermissionAsks.markAsked(activity, listOf(permission))
        org.robolectric.Shadows.shadowOf(activity.packageManager).setShouldShowRequestPermissionRationale(permission, true)
        assertTrue(canAskNotifications(activity), "Einmal abgelehnt: Das System fragt noch einmal")
    }
}
