package com.phasewake.app.shared

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Einziger DataStore der App (eine Instanz pro Prozess, wie von DataStore verlangt).
 * Ist die Datei beschädigt, beginnt er leer statt bei jedem Lesen zu scheitern; die Wecker kommen
 * dann mit dem nächsten Abgleich von der Gegenseite zurück (deren Stand ist neuer).
 */
val Context.phaseWakeStore: DataStore<Preferences> by preferencesDataStore(
    name = "phasewake",
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Log.w("Stores", "Speicher beschädigt, beginne leer", e)
        emptyPreferences()
    },
)
