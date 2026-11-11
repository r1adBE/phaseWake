package com.phasewake.app.ring

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import com.phasewake.app.QuietApp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.MathChallenge
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.WakeTask
import com.phasewake.app.phoneApp
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Weckbildschirm des Handys ([PhoneRingActivity]) mit echtem Weckdienst: Der Wecker klingelt über [PhoneRingService]
 * (Weckart, Aufgabe und Licht aus dem gespeicherten Wecker), der Bildschirm zeigt das an, und was er antippt, geht als
 * Befehl an den Dienst zurück. Hochformat, Querformat, kleines Handy mit grosser Schrift, Weckaufgabe und Sonnenaufgang.
 * Solange es klingelt, bleibt der Bildschirm offen; ist der Wecker aus, schliesst er sich.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class PhoneRingScreenTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-03T05:40:00Z")
    private var service: ServiceController<PhoneRingService>? = null
    private var screen: ActivityController<PhoneRingActivity>? = null
    private var startId = 1

    @Before
    fun fixedClock() {
        ComposeTree.prepareFrames()
        PhoneRingService.clock = { now.toEpochMilli() }
        PhoneSnooze.clear(context)
        SnoozeSafety.clear(context)
    }

    @After
    fun cleanUp() {
        screen?.pause()?.stop()?.destroy()
        service?.destroy()
        ComposeTree.drain()
        PhoneRingService.clock = System::currentTimeMillis
        RuntimeEnvironment.setFontScale(1f)
        runBlocking { context.phoneApp.alarms.remove(ALARM) }
    }

    /** Wecker [ALARM] klingelt am Handy (nur Vibration, ohne Ton), wie ihn [spec] einstellt. */
    private fun ring(spec: AlarmSpec) {
        runBlocking { context.phoneApp.alarms.upsert(spec) }
        val intent = Intent(context, PhoneRingService::class.java)
            .setAction(PhoneRingService.ACTION_RING)
            .putExtra(PhoneRingService.EXTRA_ALARM_ID, ALARM)
            .putExtra(PhoneRingService.EXTRA_TARGET, now.toEpochMilli())
        service = Robolectric.buildService(PhoneRingService::class.java, intent).create().startCommand(0, startId++)
        // Aufgabe und Licht ergänzt der Dienst im Hintergrund, nachdem er den Wecker gelesen hat.
        until("Wecker klingelt wie eingestellt") {
            val state = PhoneRingService.ringState.value as? PhoneRingService.State.Ringing
            state != null && state.wakeTask == spec.wakeTask && state.sunrise == spec.phoneSunrise
        }
    }

    private fun alarm(wakeTask: WakeTask = WakeTask.NONE, sunrise: Sunrise = Sunrise.OFF) =
        AlarmSpec(id = ALARM, hour = 7, minute = 0, phone = PhoneAlert.VIBRATE, wakeTask = wakeTask, sunrise = sunrise)

    /** Öffnet den Weckbildschirm und lässt ihn eine Sekunde laufen (Uhrzeit, Animationen, Effekte). */
    private fun open(): ComposeTree {
        shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()
        val controller = Robolectric.buildActivity(PhoneRingActivity::class.java).setup()
        screen = controller
        return ComposeTree(controller.get()).also { it.idle(1_000) }
    }

    private val activity: PhoneRingActivity get() = screen!!.get()

    /** Befehle, die der Bildschirm an den Dienst geschickt hat (in Reihenfolge); danach ist die Liste leer. */
    private fun sentCommands(): List<Intent> = generateSequence { shadowOf(RuntimeEnvironment.getApplication()).nextStartedService }
        .filter { it.component?.className == PhoneRingService::class.java.name }
        .toList()

    /** Stellt die Befehle des Bildschirms dem laufenden Dienst zu (wie das System) und gibt ihre Aktionen zurück. */
    private fun deliver(tree: ComposeTree): List<String?> = sentCommands().map { intent ->
        service!!.get().onStartCommand(intent, 0, startId++)
        intent.action
    }.also { tree.idle() }

    private fun until(what: String, check: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!check()) {
            if (System.nanoTime() > end) fail(what)
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val holdLabel: String get() = string(R.string.ring_hold_to_stop)

    private val snoozeLabel: String
        get() = context.resources.getQuantityString(R.plurals.ring_snooze, Snooze.MAX_COUNT, Snooze.MINUTES, Snooze.MAX_COUNT)

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun hochformatBleibtOffenBisVollGehaltenUndSchliesstDann() {
        ring(alarm())
        val tree = open()
        // Hochformat mit viel Platz: Gruss, Halten und Schlummern, Licht nicht verändert.
        assertTrue(tree.shows(string(R.string.ring_greeting)), "Gruss fehlt: ${tree.texts()}")
        assertTrue(tree.enabled(snoozeLabel))
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, activity.window.attributes.screenBrightness)
        assertTrue(PhoneRingActivity.shown)
        // Ein kurzer Tipp im Halbschlaf stoppt nicht; der Bildschirm bleibt offen, solange es klingelt.
        tree.hold(holdLabel, 200)
        tree.idle(2_000)
        assertEquals(emptyList(), deliver(tree))
        assertFalse(activity.isFinishing)
        // Volles Halten: «Stopp» an den Dienst, der Wecker ist aus, der Bildschirm schliesst sich.
        tree.hold(holdLabel, HOLD_MILLIS + 300L)
        assertEquals(listOf(PhoneRingService.ACTION_STOP), deliver(tree))
        assertEquals(PhoneRingService.State.Idle, PhoneRingService.ringState.value)
        assertTrue(activity.isFinishing)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun querformatZweispaltigMitSchlummernUndHaltenImSchlummern() {
        ring(alarm())
        val tree = open()
        // Querformat, sehr knapp: alles erreichbar (Uhrzeit, Halten, Schlummern).
        assertTrue(tree.shows(string(R.string.ring_greeting)))
        tree.clickable(holdLabel)
        // Schlummern: Der Dienst schlummert, der Bildschirm bleibt mit Hinweis offen und lässt Halten weiter zu.
        tree.click(snoozeLabel)
        assertEquals(listOf(PhoneRingService.ACTION_SNOOZE), deliver(tree))
        assertTrue(PhoneRingService.ringState.value is PhoneRingService.State.Snoozed)
        assertTrue(tree.shows(string(R.string.ring_snoozed, Snooze.MINUTES)), "Schlummer-Hinweis fehlt: ${tree.texts()}")
        assertFalse(activity.isFinishing)
        // Mit Bildschirmleser genügt ein Doppeltipp auf «Halten»: auch aus dem Schlummern ganz beendet.
        tree.click(holdLabel)
        assertEquals(listOf(PhoneRingService.ACTION_STOP), deliver(tree))
        assertTrue(activity.isFinishing)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port")
    fun kleinesHandyMitGrosserSchriftLoestDieRechenaufgabe() {
        RuntimeEnvironment.setFontScale(2f)
        ring(alarm(wakeTask = WakeTask.MATH))
        val tree = open()
        // Mit Aufgabe kein Halten, dafür die Tastatur; Schlummern geht weiter ohne Aufgabe.
        assertFalse(tree.nodes().any { tree.text(it) == holdLabel }, "mit Aufgabe kein Halten")
        assertTrue(tree.shows(string(R.string.wake_task_solve, 1, MathChallenge.STEPS)), "Schritt fehlt: ${tree.texts()}")
        assertTrue(tree.enabled(snoozeLabel))
        val check = string(R.string.wake_task_check)
        assertFalse(tree.enabled(check), "ohne Eingabe nichts zu prüfen")
        // Tippen und Löschen: Die Eingabe erscheint in der Aufgabe und verschwindet wieder; jede Taste macht leiser.
        val (a, b) = problem(tree)
        tree.click("7")
        assertTrue(tree.shows("$a + $b = 7"), "Eingabe fehlt: ${tree.texts()}")
        tree.click(string(R.string.wake_task_delete))
        assertTrue(tree.shows("$a + $b = ?"))
        assertTrue(deliver(tree).all { it == PhoneRingService.ACTION_TYPING })
        // Falsche Antwort: Hinweis, neue Aufgabe im selben Schritt, kein Stopp.
        type(tree, a + b + 1)
        tree.click(check)
        assertTrue(tree.shows(string(R.string.wake_task_wrong)), "Hinweis fehlt: ${tree.texts()}")
        assertTrue(tree.shows(string(R.string.wake_task_solve, 1, MathChallenge.STEPS)))
        assertFalse(PhoneRingService.ACTION_STOP in deliver(tree))
        assertFalse(activity.isFinishing)
        // Alle Schritte richtig: erst nach dem letzten «Stopp» an den Dienst, dann schliesst sich der Bildschirm.
        repeat(MathChallenge.STEPS) { index ->
            assertTrue(tree.shows(string(R.string.wake_task_solve, index + 1, MathChallenge.STEPS)), "Schritt ${index + 1}")
            val (x, y) = problem(tree)
            type(tree, x + y)
            tree.click(check)
            val sent = deliver(tree)
            assertEquals(index == MathChallenge.STEPS - 1, PhoneRingService.ACTION_STOP in sent, "Stopp nach Schritt ${index + 1}")
        }
        assertEquals(PhoneRingService.State.Idle, PhoneRingService.ringState.value)
        assertTrue(activity.isFinishing)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun querformatMitAufgabeBleibtBedienbar() {
        ring(alarm(wakeTask = WakeTask.MATH))
        val tree = open()
        // Sehr knappe Höhe: alle Tasten der Tastatur und Schlummern bleiben da.
        ("0123456789").forEach { tree.clickable(it.toString()) }
        tree.clickable(string(R.string.wake_task_delete))
        tree.clickable(string(R.string.wake_task_check))
        tree.clickable(snoozeLabel)
        assertFalse(activity.isFinishing)
        // Der Wecker endet anderswo (z. B. auf der Uhr): Der Bildschirm schliesst sich ohne eigenes Zutun.
        service!!.get().onStartCommand(Intent(context, PhoneRingService::class.java).setAction(PhoneRingService.ACTION_STOP_SILENT), 0, startId++)
        tree.idle()
        assertTrue(activity.isFinishing)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun sonnenaufgangMachtDenBildschirmHellerUndBleibtBeimSchlummern() {
        ring(alarm(sunrise = Sunrise.RING))
        val tree = open()
        // Licht beginnt dunkel und wird heller, nie zurück.
        val first = activity.window.attributes.screenBrightness
        assertTrue(first >= Sunrise.brightness(0f), "Helligkeit gesetzt: $first")
        tree.idle(Sunrise.RING_SECONDS * 100L)
        val later = activity.window.attributes.screenBrightness
        assertTrue(later > first, "heller geworden: $first → $later")
        assertTrue(tree.shows(string(R.string.ring_greeting)))
        // Geschlummert: Der Bildschirm bleibt offen, das Licht bleibt hell; Stopp per Halten beendet.
        tree.click(snoozeLabel)
        assertEquals(listOf(PhoneRingService.ACTION_SNOOZE), deliver(tree))
        assertFalse(activity.isFinishing)
        assertTrue(activity.window.attributes.screenBrightness >= later)
        tree.hold(holdLabel, HOLD_MILLIS + 300L)
        assertEquals(listOf(PhoneRingService.ACTION_STOP), deliver(tree))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun startetDerBildschirmOhneKlingelnSchliesstErSichSofort() {
        // Veraltete Mitteilung oder Erinnerung: Nichts klingelt, also nichts zu zeigen.
        assertEquals(PhoneRingService.State.Idle, PhoneRingService.ringState.value)
        open()
        assertTrue(activity.isFinishing)
        // Ein weiterer Start (Vollbild) kommt beim selben Bildschirm an.
        screen!!.newIntent(Intent(context, PhoneRingActivity::class.java).putExtra(PhoneRingActivity.EXTRA_VIA, PhoneRingActivity.VIA_TAP))
        assertEquals(PhoneRingActivity.VIA_TAP, activity.intent.getStringExtra(PhoneRingActivity.EXTRA_VIA))
    }

    /** Die gezeigte Aufgabe «a + b = …» als Zahlenpaar. */
    private fun problem(tree: ComposeTree): Pair<Int, Int> {
        val match = tree.texts().firstNotNullOfOrNull { PROBLEM.find(it) } ?: fail("keine Aufgabe: ${tree.texts()}")
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    /** Tippt [value] Ziffer für Ziffer auf der Tastatur. */
    private fun type(tree: ComposeTree, value: Int) = value.toString().forEach { tree.click(it.toString()) }

    private companion object {
        const val ALARM = "screen"
        val PROBLEM = Regex("""^(\d+) \+ (\d+) = """)
    }
}
