package com.phasewake.app.wear

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText

/**
 * Feste Uhrzeit oben am Rand für die Bildschirmfotos: Der AppScaffold zeigt sonst die echte Uhrzeit des Rechners, und
 * jedes Bild wich bei der Prüfung ab.
 */
private object FixedTimeSource : TimeSource {
    @Composable
    override fun currentTime(): String = "06:52"
}

/** Uhrzeit für `AppScaffold(timeText = …)` in allen Vorschauen der Uhr. */
@Composable
internal fun FixedTimeText() {
    TimeText(timeSource = FixedTimeSource) { time -> timeTextCurvedText(time) }
}
