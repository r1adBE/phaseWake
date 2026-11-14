package com.phasewake.app.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.MainActivity
import com.phasewake.app.wear.ui.shortTime
import com.phasewake.app.wear.wearApp
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Komplikation fürs Zifferblatt: Mond plus Zielzeit des nächsten Weckers, also spätestens geweckt (Prüfrunde 2, B-4: wie
 * die Apple-Komplikation, nicht der Beginn des Fensters). Tippen öffnet die App, wie die Kachel.
 */
class NextAlarmComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) data(this, previewText(), tap = null) else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val plan = wearApp.scheduler.nextPlan()
            ?: return NoDataComplicationData()
        return forPlan(this, plan, ZoneId.systemDefault())
    }

    internal companion object {
        /** Daten zum Plan [plan]: Zielzeit, Ansage «Nächster Wecker 07:00», Tippen öffnet die App. */
        fun forPlan(context: Context, plan: WakePlan, zone: ZoneId, locale: Locale = Locale.getDefault()): ComplicationData =
            data(context, shownText(plan.target, zone, locale), openApp(context))

        private fun data(context: Context, text: String, tap: PendingIntent?): ComplicationData = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(context.getString(R.string.tile_next) + " " + text).build(),
        )
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(context, R.drawable.ic_moon)).build())
            .setTapAction(tap)
            .build()

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Gezeigte Zeit mit den Ziffern der Sprache ([shortTime], Audit UI-9); [locale] nur für Tests. */
        fun shownText(at: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String = shortTime(at, zone, locale)

        /** Vorschau in der Zifferblatt-Auswahl: «06:30» wie bisher, aber mit den Ziffern der Sprache. */
        fun previewText(locale: Locale = Locale.getDefault()): String = String.format(locale, "%02d:%02d", 6, 30)
    }
}
