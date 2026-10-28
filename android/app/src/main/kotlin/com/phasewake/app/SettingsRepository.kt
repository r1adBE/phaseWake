package com.phasewake.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.core.SleepSound
import com.phasewake.app.core.SleepSounds
import com.phasewake.app.ui.theme.ThemeMode
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Einstellungen, die nur das Handy betreffen. */
class SettingsRepository(private val store: DataStore<Preferences>) {

    val themeMode: Flow<ThemeMode> = store.data.map { prefs ->
        prefs[THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    /** Schlafziel in Minuten (Standard 7½ h). */
    val sleepGoal: Flow<Int> = store.data.map { SleepGoal.clamp(it[SLEEP_GOAL] ?: SleepGoal.DEFAULT_MINUTES) }

    suspend fun sleepGoalNow(): Int = sleepGoal.first()

    suspend fun setSleepGoal(minutes: Int) {
        store.edit { it[SLEEP_GOAL] = SleepGoal.clamp(minutes) }
    }

    /** Bettzeit-Erinnerung an oder aus (Standard aus). */
    val bedtimeReminder: Flow<Boolean> = store.data.map { it[BEDTIME_REMINDER] ?: false }

    suspend fun setBedtimeReminder(enabled: Boolean) {
        store.edit { it[BEDTIME_REMINDER] = enabled }
    }

    /** Zeitpunkt der zuletzt gezeigten Bettzeit-Erinnerung (Millisekunden), damit sie nicht doppelt kommt. */
    suspend fun bedtimeShownAt(): Long = store.data.first()[BEDTIME_SHOWN_AT] ?: 0L

    suspend fun setBedtimeShownAt(millis: Long) {
        store.edit { it[BEDTIME_SHOWN_AT] = millis }
    }

    /** Timer der Einschlafklänge in Minuten; `null` = ohne Timer. */
    val soundTimer: Flow<Int?> = store.data.map { prefs ->
        when (val minutes = prefs[SOUND_TIMER] ?: SleepSounds.DEFAULT_TIMER_MINUTES) {
            NO_TIMER -> null
            in SleepSounds.TIMER_CHOICES -> minutes
            else -> SleepSounds.DEFAULT_TIMER_MINUTES
        }
    }

    suspend fun setSoundTimer(minutes: Int?) {
        store.edit { it[SOUND_TIMER] = minutes ?: NO_TIMER }
    }

    /** Zuletzt gewählter Klang. */
    val sound: Flow<SleepSound> = store.data.map { prefs ->
        prefs[SOUND]?.let { name -> SleepSound.entries.firstOrNull { it.name == name } } ?: SleepSound.PINK
    }

    suspend fun setSound(sound: SleepSound) {
        store.edit { it[SOUND] = sound.name }
    }

    /** Geräusche aufnehmen: Vorgabe für neue Nächte (Standard aus). */
    val soundRecDefault: Flow<Boolean> = store.data.map { it[SOUND_REC_DEFAULT] ?: false }

    suspend fun setSoundRecDefault(enabled: Boolean) {
        store.edit { it[SOUND_REC_DEFAULT] = enabled }
    }

    /**
     * Geräusche aufnehmen für eine bestimmte Nacht: (Nachtdatum, an/aus), wenn der Nutzer für diese
     * Nacht selbst gewählt (oder die Aufnahme gestoppt) hat; sonst gilt [soundRecDefault].
     */
    val soundRecChoice: Flow<Pair<LocalDate, Boolean>?> = store.data.map { prefs ->
        val text = prefs[SOUND_REC_NIGHT] ?: return@map null
        val date = runCatching { LocalDate.parse(text.substringBefore('|')) }.getOrNull() ?: return@map null
        date to (text.substringAfter('|', "") == "1")
    }

    suspend fun setSoundRecChoice(night: LocalDate, enabled: Boolean) {
        store.edit { it[SOUND_REC_NIGHT] = "$night|" + if (enabled) "1" else "0" }
    }

    /** Wochenrückblick am Sonntag um 18:00 (Standard aus). */
    val weeklyReview: Flow<Boolean> = store.data.map { it[WEEKLY_REVIEW] ?: false }

    suspend fun setWeeklyReview(enabled: Boolean) {
        store.edit { it[WEEKLY_REVIEW] = enabled }
    }

    /** Zeitpunkt des zuletzt gezeigten Wochenrückblicks (Millisekunden), damit er nicht doppelt kommt. */
    suspend fun weeklyShownAt(): Long = store.data.first()[WEEKLY_SHOWN_AT] ?: 0L

    suspend fun setWeeklyShownAt(millis: Long) {
        store.edit { it[WEEKLY_SHOWN_AT] = millis }
    }

    /**
     * Im Vordergrund vorberechneter Text des nächsten Wochenrückblicks: (letztes Nachtdatum der Woche, Text).
     * Leerer Text: nichts zu berichten. Im Hintergrund liest Health Connect ohne eigene Berechtigung nichts.
     */
    suspend fun weeklyPrepared(): Pair<LocalDate, String>? {
        val raw = store.data.first()[WEEKLY_PREPARED] ?: return null
        val date = runCatching { LocalDate.parse(raw.substringBefore('|')) }.getOrNull() ?: return null
        return date to raw.substringAfter('|', "")
    }

    suspend fun setWeeklyPrepared(weekEnd: LocalDate, text: String) {
        store.edit { it[WEEKLY_PREPARED] = "$weekEnd|$text" }
    }

    private companion object {
        val SOUND_REC_DEFAULT = booleanPreferencesKey("sound_rec_default")
        val SOUND_REC_NIGHT = stringPreferencesKey("sound_rec_night")
        val WEEKLY_REVIEW = booleanPreferencesKey("weekly_review")
        val WEEKLY_SHOWN_AT = longPreferencesKey("weekly_shown_at")
        val WEEKLY_PREPARED = stringPreferencesKey("weekly_prepared")
        val THEME = stringPreferencesKey("theme_mode")
        val SLEEP_GOAL = intPreferencesKey("sleep_goal_minutes")
        val BEDTIME_REMINDER = booleanPreferencesKey("bedtime_reminder")
        val BEDTIME_SHOWN_AT = longPreferencesKey("bedtime_shown_at")
        val SOUND_TIMER = intPreferencesKey("sound_timer_minutes")
        val SOUND = stringPreferencesKey("sleep_sound")
        const val NO_TIMER = 0
    }
}
