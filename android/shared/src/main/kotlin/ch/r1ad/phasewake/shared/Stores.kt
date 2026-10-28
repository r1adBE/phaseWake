package ch.r1ad.phasewake.shared

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** Einziger DataStore der App (eine Instanz pro Prozess, wie von DataStore verlangt). */
val Context.phaseWakeStore: DataStore<Preferences> by preferencesDataStore(name = "phasewake")
