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

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val SLEEP_GOAL = intPreferencesKey("sleep_goal_minutes")
        val BEDTIME_REMINDER = booleanPreferencesKey("bedtime_reminder")
        val BEDTIME_SHOWN_AT = longPreferencesKey("bedtime_shown_at")
        val SOUND_TIMER = intPreferencesKey("sound_timer_minutes")
        val SOUND = stringPreferencesKey("sleep_sound")
        const val NO_TIMER = 0
    }
}
