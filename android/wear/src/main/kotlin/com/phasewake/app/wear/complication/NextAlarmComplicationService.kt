package com.phasewake.app.wear.complication

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.shortTime
import com.phasewake.app.wear.wearApp
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Komplikation fürs Zifferblatt: Mond plus Beginn des nächsten Weckfensters (bei «Genau» die Zielzeit). */
class NextAlarmComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) data(previewText()) else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val plan = wearApp.scheduler.nextPlan()
            ?: return NoDataComplicationData()
        return data(shownText(if (plan.exact) plan.target else plan.windowStart, ZoneId.systemDefault()))
    }

    private fun data(text: String) = ShortTextComplicationData.Builder(
        PlainComplicationText.Builder(text).build(),
        PlainComplicationText.Builder(getString(R.string.tile_next) + " " + text).build(),
    )
        .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_moon)).build())
        .build()

    internal companion object {
        /** Gezeigte Zeit mit den Ziffern der Sprache ([shortTime], Audit UI-9); [locale] nur für Tests. */
        fun shownText(at: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String = shortTime(at, zone, locale)

        /** Vorschau in der Zifferblatt-Auswahl: «06:30» wie bisher, aber mit den Ziffern der Sprache. */
        fun previewText(locale: Locale = Locale.getDefault()): String = String.format(locale, "%02d:%02d", 6, 30)
    }
}
