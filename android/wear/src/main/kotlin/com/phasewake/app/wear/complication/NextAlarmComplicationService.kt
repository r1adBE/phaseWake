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
import com.phasewake.app.wear.wearApp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Komplikation fürs Zifferblatt: Mond plus Beginn des nächsten Weckfensters. */
class NextAlarmComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) data("06:30") else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val zone = ZoneId.systemDefault()
        val plan = wearApp.scheduler.nextPlan()
            ?: return NoDataComplicationData()
        return data(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(plan.windowStart.atZone(zone)))
    }

    private fun data(text: String) = ShortTextComplicationData.Builder(
        PlainComplicationText.Builder(text).build(),
        PlainComplicationText.Builder(getString(R.string.tile_next) + " " + text).build(),
    )
        .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_moon)).build())
        .build()
}
