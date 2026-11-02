package com.phasewake.app.ring

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import java.time.Duration
import java.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Zweitwecker vor dem ersten Entsperren ([BackupDirectBoot]): Nach einem Zweitwecker steht der nächste abgelegte,
 * statt dass bis zum Entsperren keiner mehr klingelt. Schlichte [Application] wie in [PhoneSnoozeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupDirectBootTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    private val first = Instant.parse("2026-10-28T05:30:00Z")
    private val second = Instant.parse("2026-10-28T06:00:00Z")
    private val nextDay = Instant.parse("2026-10-29T05:30:00Z")
    private val grace = Duration.ofMinutes(2)

    private fun entry(id: String, target: Instant) = BackupDirectBoot.Entry(target.plus(grace), id, target)

    @Test
    fun savedPlansSurviveInOrder() {
        BackupDirectBoot.save(context, listOf(entry("a", nextDay), entry("a", first), entry("b|x", second)))
        assertEquals(listOf(entry("a", first), entry("b|x", second), entry("a", nextDay)), BackupDirectBoot.load(context))
        BackupDirectBoot.clear(context)
        assertEquals(emptyList(), BackupDirectBoot.load(context))
    }

    @Test
    fun afterRingingTheNextOneIsSet() {
        BackupDirectBoot.save(context, listOf(entry("a", first), entry("b", second), entry("a", nextDay)))
        BackupDirectBoot.restore(context, now = first.minus(Duration.ofHours(1)))
        assertEquals(first.plus(grace).toEpochMilli(), alarms.nextScheduledAlarm?.triggerAtMs)
        // Der erste klingelt vor dem Entsperren: der zweite Wecker am selben Morgen steht.
        BackupDirectBoot.advance(context, "a", first.toEpochMilli(), now = first.plus(grace))
        assertEquals(listOf(entry("b", second), entry("a", nextDay)), BackupDirectBoot.load(context))
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtMs == second.plus(grace).toEpochMilli() })
        // Dann die nächste Nacht.
        BackupDirectBoot.advance(context, "b", second.toEpochMilli(), now = second.plus(grace))
        assertEquals(listOf(entry("a", nextDay)), BackupDirectBoot.load(context))
    }

    @Test
    fun rangBeforeUnlockIsKeptPerAlarm() {
        BackupDirectBoot.markRang(context, "a", first.toEpochMilli())
        BackupDirectBoot.markRang(context, "b", second.toEpochMilli())
        assertEquals(setOf("a" to first, "b" to second), BackupDirectBoot.takeRang(context).toSet())
        assertEquals(emptyList(), BackupDirectBoot.takeRang(context))
    }
}
