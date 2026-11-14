package com.phasewake.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.BackupSettings
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.core.SleepSound
import com.phasewake.app.core.SleepSounds
import com.phasewake.app.core.TimeBounds
import com.phasewake.app.core.Transient
import com.phasewake.app.ui.theme.ThemeMode
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Einstellungen, die nur das Handy betreffen. [now]: Uhr (ms), im Test fest. */
class SettingsRepository(private val store: DataStore<Preferences>, private val now: () -> Long = System::currentTimeMillis) {

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
        store.edit {
            it[SLEEP_GOAL] = SleepGoal.clamp(minutes)
            it[SLEEP_GOAL_AT] = Instant.now().toEpochMilli()
        }
    }

    /**
     * Zeitpunkt der letzten Änderung des Schlafziels (ms), für den Abgleich mit der Uhr. Fehlt er (Ziel vor dieser
     * Version gesetzt oder nie geändert), wird jetzt einer gesetzt: So übernimmt die Uhr auch ein älteres Ziel einmal.
     */
    suspend fun sleepGoalStamp(): Long {
        var stamp = 0L
        store.edit { prefs -> stamp = prefs[SLEEP_GOAL_AT] ?: Instant.now().toEpochMilli().also { prefs[SLEEP_GOAL_AT] = it } }
        return stamp
    }

    /** Einschlaf-Messung am Abend auf der Galaxy Watch an oder aus (Standard an); geht mit Zeitpunkt an die Uhr. */
    val autoOnset: Flow<Boolean> = store.data.map { it[AUTO_ONSET] ?: true }

    suspend fun setAutoOnset(enabled: Boolean) {
        store.edit {
            it[AUTO_ONSET] = enabled
            it[AUTO_ONSET_AT] = Instant.now().toEpochMilli()
        }
    }

    /** Stand des Schalters mit Zeitpunkt der letzten Änderung (ms) für die Uhr; fehlt er, wird jetzt einer gesetzt (wie [sleepGoalStamp]). */
    suspend fun autoOnsetStamped(): Pair<Boolean, Long> {
        var result = true to 0L
        store.edit { prefs ->
            val stamp = prefs[AUTO_ONSET_AT] ?: Instant.now().toEpochMilli().also { prefs[AUTO_ONSET_AT] = it }
            result = (prefs[AUTO_ONSET] ?: true) to stamp
        }
        return result
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

    /** Leise Mitteilung «Nächster Wecker» auf dem Sperrbildschirm an oder aus (Standard an). */
    val nextAlarmNotice: Flow<Boolean> = store.data.map { it[NEXT_ALARM_NOTICE] ?: true }

    suspend fun setNextAlarmNotice(enabled: Boolean) {
        store.edit { it[NEXT_ALARM_NOTICE] = enabled }
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

    /** Ersteinrichtung abgeschlossen oder übersprungen. */
    val onboardingDone: Flow<Boolean> = store.data.map { it[ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingDone(done: Boolean) {
        store.edit { it[ONBOARDING_DONE] = done }
    }

    /**
     * Land und Region der Feiertage: `null` = noch nie gewählt (dann gilt die Region des Geräts),
     * leeres Land = keine Feiertage. Region leer = ganzes Land.
     */
    val holidayChoice: Flow<Pair<String, String>?> = store.data.map { prefs ->
        prefs[HOLIDAY_COUNTRY]?.let { it to (prefs[HOLIDAY_REGION] ?: "") }
    }

    suspend fun setHolidayChoice(country: String, region: String) {
        store.edit {
            it[HOLIDAY_COUNTRY] = country
            it[HOLIDAY_REGION] = region
        }
    }

    /** Diagnose freigeschaltet (7× auf die Versionszeile getippt). */
    val diagnostics: Flow<Boolean> = store.data.map { it[DIAGNOSTICS] ?: false }

    suspend fun setDiagnostics(shown: Boolean) {
        store.edit { it[DIAGNOSTICS] = shown }
    }

    /** Zuletzt gewählte Dauer des Powernaps in Minuten. */
    val napMinutes: Flow<Int> = store.data.map {
        (it[NAP_MINUTES] ?: Transient.NAP_DEFAULT_MINUTES).coerceIn(Transient.NAP_MIN_MINUTES, Transient.NAP_MAX_MINUTES)
    }

    suspend fun setNapMinutes(minutes: Int) {
        store.edit { it[NAP_MINUTES] = minutes }
    }

    /**
     * Zeitpunkt von «Verlauf löschen»; Nächte aus Health Connect, die bis dahin enden, bleiben ausgeblendet
     * (sie lassen sich dort nicht löschen). `null` = nie gelöscht. Ein Zeitpunkt in der Zukunft gilt als jetzt, ein
     * unplausibler als nie gelöscht ([clearedAt], Audit DA-1).
     */
    val historyClearedAt: Flow<Instant?> = store.data.map { prefs -> clearedAt(prefs[HISTORY_CLEARED_AT], now())?.let(Instant::ofEpochMilli) }

    suspend fun setHistoryClearedAt(at: Instant) {
        store.edit { it[HISTORY_CLEARED_AT] = at.toEpochMilli() }
    }

    /**
     * Alle Einstellungen für die Sicherung. [holiday]: aufgelöste Feiertagswahl (`HolidayRepository.choice`, Fix S-2):
     * Nie gewählt heisst «Land des Geräts», nicht «keine Feiertage»; gesichert wird darum das Land, das gilt (wie das
     * iPhone). Ohne: der gespeicherte Wert.
     */
    suspend fun backupSettings(holiday: Pair<String, String>? = null): BackupSettings {
        val prefs = store.data.first()
        return BackupSettings(
            sleepGoal = sleepGoal.first(),
            bedtimeReminder = prefs[BEDTIME_REMINDER] ?: false,
            weeklyReview = prefs[WEEKLY_REVIEW] ?: false,
            soundRecDefault = prefs[SOUND_REC_DEFAULT] ?: false,
            theme = themeMode.first().name,
            holidayCountry = holiday?.first ?: prefs[HOLIDAY_COUNTRY].orEmpty(),
            holidayRegion = holiday?.second ?: prefs[HOLIDAY_REGION].orEmpty(),
            historyClearedAt = clearedAt(prefs[HISTORY_CLEARED_AT], now()),
            nextAlarmNotice = prefs[NEXT_ALARM_NOTICE] ?: true,
            autoOnset = prefs[AUTO_ONSET] ?: true,
        )
    }

    /**
     * Übernimmt die Einstellungen einer Sicherung in [prefs] (Wiederherstellen in einem Schritt); ein unbekanntes Design
     * wird «System».
     */
    fun restoreInto(prefs: MutablePreferences, settings: BackupSettings) {
        prefs[SLEEP_GOAL] = SleepGoal.clamp(settings.sleepGoal)
        prefs[SLEEP_GOAL_AT] = Instant.now().toEpochMilli()
        prefs[BEDTIME_REMINDER] = settings.bedtimeReminder
        prefs[WEEKLY_REVIEW] = settings.weeklyReview
        prefs[SOUND_REC_DEFAULT] = settings.soundRecDefault
        prefs[THEME] = (ThemeMode.entries.firstOrNull { mode -> mode.name == settings.theme } ?: ThemeMode.SYSTEM).name
        prefs[HOLIDAY_COUNTRY] = settings.holidayCountry
        prefs[HOLIDAY_REGION] = settings.holidayRegion
        // Aus einer fremden oder manipulierten Sicherung: nie später als jetzt (Audit DA-1), sonst blieben auch alle
        // künftigen Nächte aus Health Connect ausgeblendet.
        val cleared = clearedAt(settings.historyClearedAt, now())
        if (cleared == null) prefs.remove(HISTORY_CLEARED_AT) else prefs[HISTORY_CLEARED_AT] = cleared
        prefs[NEXT_ALARM_NOTICE] = settings.nextAlarmNotice
        prefs[AUTO_ONSET] = settings.autoOnset
        prefs[AUTO_ONSET_AT] = Instant.now().toEpochMilli()
    }

    companion object {
        /**
         * Zeitpunkt von «Verlauf löschen» zur Verwendung (Audit DA-1): höchstens [nowMs] (eine verstellte Uhr oder eine
         * Sicherung «aus der Zukunft» blendete sonst auch alle künftigen Nächte aus), ausserhalb von [TimeBounds] `null`.
         */
        fun clearedAt(raw: Long?, nowMs: Long): Long? = TimeBounds.checked(raw)?.let { minOf(it, nowMs) }

        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val HOLIDAY_COUNTRY = stringPreferencesKey("holiday_country")
        private val HOLIDAY_REGION = stringPreferencesKey("holiday_region")
        private val DIAGNOSTICS = booleanPreferencesKey("diagnostics")
        private val NAP_MINUTES = intPreferencesKey("nap_minutes")
        private val SOUND_REC_DEFAULT = booleanPreferencesKey("sound_rec_default")
        private val SOUND_REC_NIGHT = stringPreferencesKey("sound_rec_night")
        private val WEEKLY_REVIEW = booleanPreferencesKey("weekly_review")
        private val WEEKLY_SHOWN_AT = longPreferencesKey("weekly_shown_at")
        private val WEEKLY_PREPARED = stringPreferencesKey("weekly_prepared")
        private val THEME = stringPreferencesKey("theme_mode")
        private val SLEEP_GOAL = intPreferencesKey("sleep_goal_minutes")
        private val SLEEP_GOAL_AT = longPreferencesKey("sleep_goal_updated_at")
        private val BEDTIME_REMINDER = booleanPreferencesKey("bedtime_reminder")
        private val BEDTIME_SHOWN_AT = longPreferencesKey("bedtime_shown_at")
        private val NEXT_ALARM_NOTICE = booleanPreferencesKey("next_alarm_notice")
        private val AUTO_ONSET = booleanPreferencesKey("auto_onset")
        private val AUTO_ONSET_AT = longPreferencesKey("auto_onset_updated_at")
        private val SOUND_TIMER = intPreferencesKey("sound_timer_minutes")
        private val SOUND = stringPreferencesKey("sleep_sound")
        private val HISTORY_CLEARED_AT = longPreferencesKey("history_cleared_at")
        private const val NO_TIMER = 0
    }
}
