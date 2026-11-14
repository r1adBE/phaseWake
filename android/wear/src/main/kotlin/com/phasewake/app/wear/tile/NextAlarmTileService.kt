package com.phasewake.app.wear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.MainActivity
import com.phasewake.app.wear.ui.shortTime
import com.phasewake.app.wear.wearApp
import com.google.common.util.concurrent.ListenableFuture
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/** Kachel: nächstes Weckfenster auf einen Blick; Tippen öffnet die App. */
class NextAlarmTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = scope.future {
        val texts = tileTexts(this@NextAlarmTileService, wearApp.scheduler.nextPlan(), ZoneId.systemDefault(), Instant.now())
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // Neu zeichnen, sobald sich das Fenster ändern kann (spätestens nach einer Stunde).
            .setFreshnessIntervalMillis(60 * 60 * 1000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(texts)))
            .build()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        scope.future { ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build() }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun layout(texts: TileTexts): LayoutElementBuilders.LayoutElement {
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(texts.header, 13f, MIST))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(text(texts.title, 24f, WHITE, bold = true))
        texts.line?.let {
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            column.addContent(text(it, 13f, MUTED))
        }
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(column.build())
            .build()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(2)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build(),
            )
            .build()

    /** Kopfzeile, Titel und (ohne «Genau») die Zeile darunter. */
    internal data class TileTexts(val header: String, val title: String, val line: String?)

    internal companion object {
        /**
         * Texte der Kachel; Zeiten mit den Ziffern der Sprache ([shortTime], Audit UI-9). Liegt der Wecker weder heute
         * noch morgen, steht sein Wochentag in der Kopfzeile, sonst sähe ein Wecker am Freitag am Dienstag aus wie
         * morgen; bei «Genau» keine Zeile darunter statt «Nächster Wecker» ein zweites Mal (Prüfrunde 2, B-5). [locale]
         * nur für Tests, sonst die der Uhr.
         */
        fun tileTexts(context: Context, plan: WakePlan?, zone: ZoneId, now: Instant, locale: Locale = Locale.getDefault()): TileTexts {
            fun time(at: Instant) = shortTime(at, zone, locale)
            val next = context.getString(R.string.tile_next)
            if (plan == null) return TileTexts(next, context.getString(R.string.home_no_alarm), context.getString(R.string.home_no_alarm_hint))
            val day = plan.target.atZone(zone).toLocalDate()
            val today = now.atZone(zone).toLocalDate()
            val header = if (day == today || day == today.plusDays(1)) next else "$next · " + DateTimeFormatter.ofPattern("EEE", locale).format(day)
            return if (plan.exact) {
                // «Genau»: kein Fenster, kein Versprechen zum leichten Schlaf.
                TileTexts(header, context.getString(R.string.window_exact_line, time(plan.target)), null)
            } else {
                TileTexts(header, context.getString(R.string.home_window, time(plan.windowStart), time(plan.target)), context.getString(R.string.tile_light_sleep))
            }
        }

        private const val RESOURCES_VERSION = "1"
        private const val WHITE = 0xFFFBF7F0.toInt()
        private const val MIST = 0xFFC9C3E6.toInt()
        private const val MUTED = 0xFFA9A3CF.toInt()
    }
}
