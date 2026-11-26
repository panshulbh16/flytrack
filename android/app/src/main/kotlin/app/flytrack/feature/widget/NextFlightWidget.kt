package app.flytrack.feature.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontStyle
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.flytrack.MainActivity
import app.flytrack.R
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.data.repo.FlightRepository
import app.flytrack.feature.live.LiveStrings
import app.flytrack.ui.nav.openFlightIntent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/** What the widget needs from the Hilt graph (a GlanceAppWidget cannot be constructor-injected). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NextFlightWidgetEntryPoint {
    fun flights(): FlightRepository
    fun clock(): Clock
    fun liveStrings(): LiveStrings
}

/** Redraws every placed Next flight widget. Cheap when none is placed. */
@Singleton
class NextFlightWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun updateAll() = NextFlightWidget().updateAll(context)
}

/** Home-screen widget (free): the next or current flight. Tapping it opens the flight. */
class NextFlightWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, REGULAR))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, NextFlightWidgetEntryPoint::class.java)
        val clock = graph.clock()
        val builder = NextFlightWidgetModelBuilder(graph.liveStrings())
        val trackedFlow = graph.flights().observeTrackedFlights()
        val initial = trackedFlow.first()
        provideContent {
            // While a session is running, update()/updateAll() do not call provideGlance again, so
            // the content follows Room itself; otherwise a second write within the session's
            // lifetime would leave the widget showing the first one.
            val tracked by trackedFlow.collectAsState(initial = initial)
            NextFlightContent(builder.build(tracked, clock.now()))
        }
    }

    companion object {
        val COMPACT = DpSize(160.dp, 60.dp)
        val REGULAR = DpSize(200.dp, 120.dp)
    }
}

class NextFlightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextFlightWidget()
}

// docs/design-brief.md: warm paper, forest ink, terracotta accent; dark = night green (same values as ui/theme/Color.kt).
private object WidgetColors {
    val background = ColorProvider(day = Color(0xFFF6F5EF), night = Color(0xFF182E25))
    val ink = ColorProvider(day = Color(0xFF244735), night = Color(0xFFF6F4E7))
    val muted = ColorProvider(day = Color(0xFF5A6C5A), night = Color(0xFFC0CDBB))
    val accent = ColorProvider(day = Color(0xFFA34E2E), night = Color(0xFFE88A62))

    fun tone(tone: FlightStatusTone): ColorProvider = when (tone) {
        FlightStatusTone.ON_TIME -> ColorProvider(day = Color(0xFF4C7B41), night = Color(0xFFA9CFA0))
        FlightStatusTone.DELAYED -> ColorProvider(day = Color(0xFF8D542D), night = Color(0xFFE8C98A))
        FlightStatusTone.CANCELLED -> ColorProvider(day = Color(0xFFA34831), night = Color(0xFFF2BDA3))
        FlightStatusTone.ACTIVE -> ColorProvider(day = Color(0xFFA34E2E), night = Color(0xFFE88A62))
        FlightStatusTone.ESTIMATED -> muted
    }
}

@Composable
private fun NextFlightContent(model: NextFlightWidgetModel) {
    val context = LocalContext.current
    val compact = LocalSize.current.height < NextFlightWidget.REGULAR.height
    val action: Action = when (model) {
        NextFlightWidgetModel.Empty -> actionStartActivity(Intent(context, MainActivity::class.java))
        is NextFlightWidgetModel.Upcoming -> actionStartActivity(openFlightIntent(context, model.flightId))
    }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.background)
            .cornerRadius(16.dp)
            .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp)
            .clickable(action),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (model) {
            NextFlightWidgetModel.Empty -> EmptyContent(context)
            is NextFlightWidgetModel.Upcoming -> if (compact) CompactFlight(context, model) else RegularFlight(context, model)
        }
    }
}

@Composable
private fun EmptyContent(context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(R.drawable.ic_plane),
            contentDescription = null,
            modifier = GlanceModifier.size(20.dp),
            colorFilter = ColorFilter.tint(WidgetColors.accent),
        )
        Spacer(GlanceModifier.width(8.dp))
        Column {
            Text(
                text = context.getString(R.string.live_widget_empty_title),
                style = TextStyle(color = WidgetColors.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(
                text = context.getString(R.string.live_widget_empty_message),
                style = TextStyle(color = WidgetColors.muted, fontSize = 12.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CompactFlight(context: Context, model: NextFlightWidgetModel.Upcoming) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = model.route,
            style = TextStyle(color = WidgetColors.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = model.statusText,
            style = TextStyle(color = WidgetColors.tone(model.tone), fontSize = 15.sp, fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif),
            maxLines = 1,
        )
    }
    Text(
        text = listOfNotNull(model.timeText, model.gateBeltText).joinToString(context.getString(R.string.live_separator)),
        style = TextStyle(color = WidgetColors.muted, fontSize = 12.sp),
        maxLines = 1,
    )
}

@Composable
private fun RegularFlight(context: Context, model: NextFlightWidgetModel.Upcoming) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = (model.dateText ?: context.getString(R.string.live_widget_caption)).uppercase(),
            style = TextStyle(color = WidgetColors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = model.designator,
            style = TextStyle(color = WidgetColors.muted, fontSize = 12.sp),
            maxLines = 1,
        )
    }
    Spacer(GlanceModifier.height(4.dp))
    Text(
        text = model.route,
        style = TextStyle(color = WidgetColors.ink, fontSize = 22.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
    )
    Text(
        text = if (model.estimated) {
            model.statusText + context.getString(R.string.live_separator) + context.getString(R.string.live_estimated)
        } else {
            model.statusText
        },
        style = TextStyle(color = WidgetColors.tone(model.tone), fontSize = 18.sp, fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif),
        maxLines = 1,
    )
    Spacer(GlanceModifier.height(4.dp))
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        model.timeText?.let {
            Text(text = it, style = TextStyle(color = WidgetColors.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
        Spacer(GlanceModifier.defaultWeight())
        model.gateBeltText?.let {
            Text(text = it, style = TextStyle(color = WidgetColors.ink, fontSize = 13.sp), maxLines = 1)
        }
    }
    model.countdownText?.let {
        Text(text = it, style = TextStyle(color = WidgetColors.muted, fontSize = 12.sp), maxLines = 1)
    }
}
