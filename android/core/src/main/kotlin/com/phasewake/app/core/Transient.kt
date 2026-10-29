package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Art eines vorübergehenden Plans. */
enum class TransientKind {
    /** Probewecker «In 2 Minuten testen». */
    TEST,

    /** Powernap: Zielzeit nach der gewählten Dauer. */
    NAP,
}

/**
 * Ein einmaliger Plan, der kein gespeicherter Wecker ist: Probewecker oder Powernap. Die Uhr führt ihn wie
 * eine Nacht (Messen, Klingeln, Stopp), aber ohne Eintrag im Verlauf und ohne Einfluss auf die Wecker.
 *
 * @property spec Weckart (Handy, Uhr-Ton, Weckton, Sonnenaufgang); Kennung mit [Transient.TEST_PREFIX] bzw. [Transient.NAP_PREFIX].
 * @property plan Fenster und Zielzeit; gilt statt des Fensters von [spec].
 */
data class TransientPlan(val kind: TransientKind, val spec: AlarmSpec, val plan: WakePlan)

/** Warum ein Probewecker oder Powernap jetzt nicht starten darf ([Transient.conflict]). */
enum class TransientConflict {
    /** Ein Weckfenster läuft, oder eine Nacht klingelt bzw. schlummert: «Gerade läuft dein Wecker.» */
    ALARM_RUNNING,

    /** Das Weckfenster des nächsten Weckers beginnt vor der Zielzeit des Plans: «Dein Weckfenster beginnt schon vorher.» */
    ALARM_SOON,
}

/** Regeln und Format für [TransientPlan]. Auf Handy, Uhr, iPhone und Apple Watch gleich. */
object Transient {
    /** Präfix der Kennung eines Probeweckers; eine UUID beginnt nie mit «t», darum eindeutig. */
    const val TEST_PREFIX: String = "test-"

    /** Präfix der Kennung eines Powernaps; eine UUID beginnt nie mit «n». */
    const val NAP_PREFIX: String = "nap-"

    /** Probewecker: Das Fenster beginnt so viele Sekunden nach dem Start. */
    const val TEST_WINDOW_START_SECONDS: Long = 60L

    /** Probewecker: Zielzeit so viele Sekunden nach dem Start («In 2 Minuten testen»). */
    const val TEST_TARGET_SECONDS: Long = 120L

    /** Kürzester Powernap in Minuten. */
    const val NAP_MIN_MINUTES: Int = 10

    /** Längster Powernap in Minuten. */
    const val NAP_MAX_MINUTES: Int = 90

    /** Vorschlag für die Dauer in Minuten. */
    const val NAP_DEFAULT_MINUTES: Int = 20

    /** Schritt der Dauerwahl in Minuten. */
    const val NAP_STEP_MINUTES: Int = 5

    /** Weckfenster eines Powernaps: die letzten so vielen Minuten. */
    const val NAP_WINDOW_MINUTES: Int = 5

    /** Weckfenster kurzer Powernaps (bis [NAP_SHORT_LIMIT_MINUTES]): die letzten so vielen Minuten. */
    const val NAP_SHORT_WINDOW_MINUTES: Int = 3

    /** Bis zu dieser Dauer (Minuten) gilt das kurze Fenster. */
    const val NAP_SHORT_LIMIT_MINUTES: Int = 15

    /**
     * So lange nach der Zielzeit gilt ein Plan noch (Klingeln, Schlummern, Stopp); danach wird er verworfen.
     * Gleich der Höchstdauer einer Nacht auf der Uhr.
     */
    val ACTIVE_AFTER_TARGET: Duration = Duration.ofHours(2)

    /**
     * So lange nach der Zielzeit wird ein Plan noch geplant (auch nach einem Neustart). 60 s wie die Rückfall-Frist
     * der Apple Watch (`SmartAlarm.fallbackDelay`): Ein Probewecker oder Powernap, dessen Ende die Uhr verpasst hat
     * (Akku leer, Stopp am Handy ohne Verbindung), klingelt nie Stunden später nach. Ein schon klingelnder Plan
     * bleibt davon unberührt; seine Weckart gilt bis [ACTIVE_AFTER_TARGET].
     */
    val PLAN_AFTER_TARGET: Duration = Duration.ofSeconds(60)

    private const val HEADER = "phasewake-transient 1"
    private const val SEP = "|"

    /**
     * Probewecker ab [now]: Fenster in 1 Minute, Zielzeit in 2 Minuten. Weckart wie [base] (der nächste
     * echte Wecker) samt Sonnenaufgang; ohne [base] die Vorgaben. Beenden immer durch Halten.
     */
    fun test(base: AlarmSpec?, now: Instant, zone: ZoneId, uuid: String): TransientPlan {
        val plan = WakePlan(TEST_PREFIX + uuid, now.plusSeconds(TEST_WINDOW_START_SECONDS), now.plusSeconds(TEST_TARGET_SECONDS))
        val spec = specFor(plan, base, zone).copy(sunrise = base?.sunrise ?: Sunrise.OFF, sunriseDevice = base?.sunriseDevice ?: SunriseDevice.PHONE)
        return TransientPlan(TransientKind.TEST, spec, plan)
    }

    /** Powernap ab [now] mit [minutes] Dauer (auf [NAP_MIN_MINUTES]–[NAP_MAX_MINUTES] begrenzt), Weckart wie [base], ohne Licht. */
    fun nap(base: AlarmSpec?, now: Instant, zone: ZoneId, minutes: Int, uuid: String): TransientPlan {
        val length = minutes.coerceIn(NAP_MIN_MINUTES, NAP_MAX_MINUTES)
        val target = now.plus(Duration.ofMinutes(length.toLong()))
        val plan = WakePlan(NAP_PREFIX + uuid, target.minus(Duration.ofMinutes(napWindowMinutes(length).toLong())), target)
        return TransientPlan(TransientKind.NAP, specFor(plan, base, zone), plan)
    }

    /** Fenster eines Powernaps in Minuten: bis [NAP_SHORT_LIMIT_MINUTES] kurz, sonst [NAP_WINDOW_MINUTES]. */
    fun napWindowMinutes(minutes: Int): Int =
        if (minutes <= NAP_SHORT_LIMIT_MINUTES) NAP_SHORT_WINDOW_MINUTES else NAP_WINDOW_MINUTES

    /** Art nach der Kennung; `null` für gespeicherte Wecker (oder ohne Kennung). */
    fun kindOf(alarmId: String?): TransientKind? = when {
        alarmId == null -> null
        alarmId.startsWith(TEST_PREFIX) -> TransientKind.TEST
        alarmId.startsWith(NAP_PREFIX) -> TransientKind.NAP
        else -> null
    }

    /** Ob Schlummern erlaubt ist: nicht beim Probewecker (er prüft genau einen Weckvorgang). */
    fun allowsSnooze(alarmId: String?): Boolean = kindOf(alarmId) != TransientKind.TEST

    /** Ob der Plan noch gilt: bis [ACTIVE_AFTER_TARGET] nach der Zielzeit. */
    fun isActive(t: TransientPlan, now: Instant): Boolean = now.isBefore(t.plan.target.plus(ACTIVE_AFTER_TARGET))

    /** Ob der Plan noch geplant wird: bis [PLAN_AFTER_TARGET] nach der Zielzeit. */
    fun isPlannable(t: TransientPlan, now: Instant): Boolean = now.isBefore(t.plan.target.plus(PLAN_AFTER_TARGET))

    /**
     * Welcher Plan als Nächstes gilt: [real] (nächster echter Wecker) oder [transient], wenn dieser noch geplant
     * wird ([isPlannable]); der mit der früheren Zielzeit, bei Gleichstand die kleinere Kennung. Auf allen Geräten
     * gleich: Ein Powernap verdrängt nie einen früheren echten Wecker. `null`, wenn keiner gilt.
     */
    fun choose(real: WakePlan?, transient: TransientPlan?, now: Instant): WakePlan? {
        val extra = transient?.takeIf { isPlannable(it, now) }?.plan
        return listOfNotNull(real, extra).minWithOrNull(compareBy({ it.target }, { it.alarmId }))
    }

    /**
     * Ob der neue Plan [t] (gerade erzeugt, Start [now]) abgelehnt wird. [next]: nächster echter Plan
     * ([AlarmSchedule.earliestPlan] mit erledigten Nächten); [nightActive]: eine Nacht klingelt oder schlummert.
     * - [TransientConflict.ALARM_RUNNING]: [nightActive], oder [now] liegt im Fenster von [next] (Fensterbeginn bis
     *   Zielzeit) – sonst bräche der Plan die Messung des echten Weckers ab.
     * - [TransientConflict.ALARM_SOON]: Die Zielzeit von [t] liegt nach dem Fensterbeginn von [next]; der Plan
     *   würde das Fenster überdecken.
     * Sonst `null`.
     */
    fun conflict(t: TransientPlan, next: WakePlan?, nightActive: Boolean, now: Instant): TransientConflict? = when {
        nightActive -> TransientConflict.ALARM_RUNNING
        next == null -> null
        !now.isBefore(next.windowStart) && now.isBefore(next.target) -> TransientConflict.ALARM_RUNNING
        t.plan.target.isAfter(next.windowStart) -> TransientConflict.ALARM_SOON
        else -> null
    }

    /**
     * Schreibt den Plan: Kopfzeile, «ART|fensterbeginnMs|zielzeitMs», danach der Wecker im Format von
     * [Codec.encodeAlarms]. Inhalt der Nachrichten an die Uhr und des Speichers.
     */
    fun encode(t: TransientPlan): String = buildString {
        append(HEADER).append('\n')
        append(listOf(t.kind.name, t.plan.windowStart.toEpochMilli(), t.plan.target.toEpochMilli()).joinToString(SEP)).append('\n')
        append(Codec.encodeAlarms(listOf(t.spec)))
    }

    /** Liest [encode]; fremdes oder kaputtes Format wirft [CodecException]. */
    fun decode(text: String): TransientPlan {
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) throw CodecException("Unbekanntes Format des vorübergehenden Plans")
        val fields = lines.getOrElse(1) { "" }.split(SEP)
        val kind = TransientKind.entries.firstOrNull { it.name == fields.getOrNull(0) } ?: throw CodecException("Art fehlt")
        val start = fields.getOrNull(1)?.toLongOrNull() ?: throw CodecException("Fensterbeginn fehlt")
        val target = fields.getOrNull(2)?.toLongOrNull() ?: throw CodecException("Zielzeit fehlt")
        val spec = Codec.decodeAlarms(lines.drop(2).joinToString("\n")).firstOrNull() ?: throw CodecException("Wecker fehlt")
        if (kindOf(spec.id) != kind || start > target) throw CodecException("Plan passt nicht zur Kennung")
        return TransientPlan(kind, spec, WakePlan(spec.id, Instant.ofEpochMilli(start), Instant.ofEpochMilli(target)))
    }

    /** Wecker zum Plan: Weckart aus [base], Zielzeit als Uhrzeit, einmalig, ohne Aufgabe, Licht und Namen. */
    private fun specFor(plan: WakePlan, base: AlarmSpec?, zone: ZoneId): AlarmSpec {
        val local = plan.target.atZone(zone)
        return AlarmSpec(
            id = plan.alarmId,
            hour = local.hour,
            minute = local.minute,
            windowMinutes = AlarmSpec.MIN_WINDOW_MINUTES,
            phone = base?.phone ?: PhoneAlert.SOUND,
            watchSound = base?.watchSound ?: false,
            sound = base?.sound.orEmpty(),
        )
    }
}

/** Schritte, die der Probewecker prüft (Ergebnis am Handy). */
enum class TestStep {
    /** Die Uhr hat den Probewecker empfangen (Bestätigung der Uhr). */
    RECEIVED,

    /** Die Uhr hat geklingelt (Meldung «klingeln»). */
    WATCH_RANG,

    /** Das Handy hat mitgeklingelt (Weckdienst des Handys gestartet). */
    PHONE_RANG,

    /** Das Beenden (Halten auf der Uhr) kam am Handy an (Meldung «stop»). */
    STOP_ARRIVED,
}

/**
 * Ein laufender oder beendeter Probewecker am Handy.
 *
 * @property target Zielzeit des Probeweckers.
 * @property phoneAlert Weckart des Handys; bei [PhoneAlert.OFF] wird «Handy hat mitgeklingelt» nicht geprüft.
 * @property seen Zeitpunkt, zu dem das Handy einen Schritt zuerst gesehen hat.
 */
data class TestRun(val id: String, val target: Instant, val phoneAlert: PhoneAlert, val seen: Map<TestStep, Instant> = emptyMap())

/** Auswertung und Format von [TestRun]. */
object TestRuns {
    /** So lange nach der Zielzeit wird auf Ereignisse gewartet, bevor das Ergebnis ohne «stop» gilt. */
    val RESULT_AFTER_TARGET: Duration = Duration.ofMinutes(3)

    /** Merkt [step] zum Zeitpunkt [at]; das erste Auftreten zählt. */
    fun record(run: TestRun, step: TestStep, at: Instant): TestRun =
        if (step in run.seen) run else run.copy(seen = run.seen + (step to at))

    /** Ob das Ergebnis feststeht: «stop» kam an, oder [RESULT_AFTER_TARGET] nach der Zielzeit ist vorbei. */
    fun isFinished(run: TestRun, now: Instant): Boolean =
        TestStep.STOP_ARRIVED in run.seen || !now.isBefore(run.target.plus(RESULT_AFTER_TARGET))

    /** Ergebnis eines Schritts: erfüllt, nicht erfüllt, oder `null` = nicht geprüft (Handy-Wecker aus). */
    fun result(run: TestRun, step: TestStep): Boolean? =
        if (step == TestStep.PHONE_RANG && run.phoneAlert == PhoneAlert.OFF) null else step in run.seen

    /** «kennung|zielzeitMs|HANDY|SCHRITT=ms,SCHRITT=ms». */
    fun encode(run: TestRun): String = listOf(
        run.id, run.target.toEpochMilli().toString(), run.phoneAlert.name,
        run.seen.entries.sortedBy { it.key }.joinToString(",") { "${it.key.name}=${it.value.toEpochMilli()}" },
    ).joinToString("|")

    /** Liest [encode]; leer oder unlesbar ergibt `null`, unbekannte Schritte fallen weg. */
    fun decode(text: String?): TestRun? {
        val f = text?.split("|") ?: return null
        if (f.size < 4) return null
        val target = f[1].toLongOrNull() ?: return null
        val alert = PhoneAlert.entries.firstOrNull { it.name == f[2] } ?: PhoneAlert.SOUND
        val seen = f[3].split(",").mapNotNull { pair ->
            val step = TestStep.entries.firstOrNull { it.name == pair.substringBefore('=') } ?: return@mapNotNull null
            pair.substringAfter('=').toLongOrNull()?.let { step to Instant.ofEpochMilli(it) }
        }.toMap()
        return TestRun(f[0], Instant.ofEpochMilli(target), alert, seen)
    }
}
