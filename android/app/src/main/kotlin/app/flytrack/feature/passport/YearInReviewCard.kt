package app.flytrack.feature.passport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.ui.theme.DmSansFamily
import app.flytrack.ui.theme.InstrumentSerifFamily
import java.text.NumberFormat
import java.time.YearMonth

/**
 * Fixed palettes of the share card (docs/design-brief.md): HeyTurbulence's paper card and its "Night"
 * card. The card keeps its own colours so the shared image looks the same whatever the app theme.
 */
data class CardPalette(
    val paper: Color,
    val ink: Color,
    val muted: Color,
    val hairline: Color,
    val decoration: Color,
    val accentText: Color,
) {
    companion object {
        val Light = CardPalette(
            paper = Color(0xFFF6F5EF),
            ink = Color(0xFF244735),
            muted = Color(0xFF5A6C5A),
            hairline = Color(0xFFD6DCCF),
            decoration = Color(0xFFC86842),
            accentText = Color(0xFFA34E2E),
        )
        val Night = CardPalette(
            paper = Color(0xFF182E25),
            ink = Color(0xFFF6F4E7),
            muted = Color(0xFFC0CDBB),
            hairline = Color(0xFF4C6450),
            decoration = Color(0xFFDF754E),
            accentText = Color(0xFFE88A62),
        )
    }
}

/** Share card proportions (Instagram portrait, also fine in WhatsApp). */
const val YEAR_CARD_ASPECT = 4f / 5f

/**
 * The Year in Review card, adapted from HeyTurbulence's share card: caption row, hairline, a big serif
 * title, the little numbers, a by-month strip and three highlights. Everything scales with the card's
 * width, so the captured bitmap has the same layout on every phone.
 */
@Composable
fun YearInReviewCard(model: YearInReviewCardModel, palette: CardPalette, modifier: Modifier = Modifier) {
    val numbers = NumberFormat.getIntegerInstance()
    val description = stringResource(
        R.string.passport_share_text,
        model.year,
        numbers.format(model.flights),
        numbers.format(model.distanceKm),
    )
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(YEAR_CARD_ASPECT)
            .background(palette.paper)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val u = maxWidth / 360f // one design unit: the layout is drawn for a 360-unit-wide card
        val density = LocalDensity.current
        // Text follows the card's width, not the font-size setting, so the shared image never overflows.
        val s: (Float) -> TextUnit = { units -> with(density) { (u * units).toSp() } }
        val d: (Float) -> Dp = { units -> u * units }
        val sans = TextStyle(fontFamily = DmSansFamily, fontFeatureSettings = "tnum")
        val serif = TextStyle(fontFamily = InstrumentSerifFamily)

        Column(Modifier.fillMaxSize().padding(horizontal = d(24f), vertical = d(22f))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "FLYTRACK",
                    style = sans.copy(fontSize = s(9f), fontWeight = FontWeight.Medium, letterSpacing = s(1.4f)),
                    color = palette.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.passport_card_caption).uppercase(),
                    style = sans.copy(fontSize = s(8f), letterSpacing = s(1.2f)),
                    color = palette.muted,
                )
            }
            Spacer(Modifier.height(d(9f)))
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hairline))
            Spacer(Modifier.height(d(14f)))
            Text(
                stringResource(R.string.passport_card_title_year, model.year),
                style = serif.copy(fontSize = s(64f), lineHeight = s(64f)),
                color = palette.ink,
            )
            Text(
                stringResource(R.string.passport_card_title_tail),
                style = serif.copy(fontSize = s(30f), lineHeight = s(32f), fontStyle = FontStyle.Italic),
                color = palette.accentText,
            )
            Spacer(Modifier.height(d(16f)))
            Row(horizontalArrangement = Arrangement.spacedBy(d(12f))) {
                CardNumber(numbers.format(model.flights), stringResource(R.string.passport_tile_flights), palette, sans, s, Modifier.weight(1f))
                CardNumber(numbers.format(model.distanceKm), stringResource(R.string.passport_tile_km), palette, sans, s, Modifier.weight(1.4f))
                CardNumber(numbers.format(model.hours), stringResource(R.string.passport_tile_hours), palette, sans, s, Modifier.weight(1.2f))
            }
            Spacer(Modifier.height(d(10f)))
            Row(horizontalArrangement = Arrangement.spacedBy(d(12f))) {
                CardNumber(numbers.format(model.airports), stringResource(R.string.passport_tile_airports), palette, sans, s, Modifier.weight(1f))
                CardNumber(numbers.format(model.countries), stringResource(R.string.passport_tile_countries), palette, sans, s, Modifier.weight(2.6f))
            }
            Spacer(Modifier.height(d(14f)))
            MonthStrip(model.bars, palette, sans, s, d)
            Spacer(Modifier.weight(1f))
            model.topRoute?.let {
                CardHighlight(
                    stringResource(R.string.passport_card_top_route),
                    pluralStringResource(R.plurals.passport_top_route_value, it.count, it.dep, it.arr, it.count),
                    palette, sans, s,
                )
            }
            model.longest?.let {
                CardHighlight(
                    stringResource(R.string.passport_card_longest),
                    "${it.dep} → ${it.arr} · ${numbers.format(it.distanceKm)} km",
                    palette, sans, s,
                )
            }
            model.topAirline?.let {
                CardHighlight(stringResource(R.string.passport_card_airline), it, palette, sans, s)
            }
            Spacer(Modifier.height(d(10f)))
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hairline))
            Spacer(Modifier.height(d(8f)))
            Text(
                stringResource(R.string.passport_card_footer),
                style = sans.copy(fontSize = s(8.5f)),
                color = palette.muted,
            )
        }
    }
}

@Composable
private fun CardNumber(
    value: String,
    label: String,
    palette: CardPalette,
    sans: TextStyle,
    s: (Float) -> TextUnit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(value, style = sans.copy(fontSize = s(24f), lineHeight = s(26f), letterSpacing = s(-0.5f)), color = palette.ink, maxLines = 1)
        Text(
            label.uppercase(),
            style = sans.copy(fontSize = s(7.5f), letterSpacing = s(1f)),
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MonthStrip(
    bars: List<MonthBar>,
    palette: CardPalette,
    sans: TextStyle,
    s: (Float) -> TextUnit,
    d: (Float) -> Dp,
) {
    Row(Modifier.fillMaxWidth().height(d(42f)), horizontalArrangement = Arrangement.spacedBy(d(4f)), verticalAlignment = Alignment.Bottom) {
        bars.forEach { bar ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                val height = if (bar.flights == 0) d(1.5f) else d(4f + 24f * bar.fraction)
                Box(
                    Modifier
                        .width(d(11f))
                        .height(height)
                        .clip(RoundedCornerShape(topStart = d(2f), topEnd = d(2f)))
                        .background(if (bar.flights == 0) palette.hairline else palette.decoration),
                )
                Spacer(Modifier.height(d(3f)))
                Text(monthInitial(bar.month), style = sans.copy(fontSize = s(7f)), color = palette.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CardHighlight(label: String, value: String, palette: CardPalette, sans: TextStyle, s: (Float) -> TextUnit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.uppercase(),
            style = sans.copy(fontSize = s(7.5f), letterSpacing = s(1f)),
            color = palette.muted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(value, style = sans.copy(fontSize = s(11f)), color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "J", "F", … in the phone's language. */
private fun monthInitial(month: YearMonth): String =
    month.month.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault())

@Preview(widthDp = 360)
@Composable
private fun YearInReviewCardPreview() {
    YearInReviewCard(
        model = YearInReviewCardModel(
            year = 2026,
            flights = 14,
            distanceKm = 18_234,
            hours = 30,
            airports = 7,
            countries = 2,
            topRoute = TopRoute("DEL", "BOM", 5),
            longest = LongestFlight(null, "AI", "101", "DEL", "JFK", 11_752),
            topAirline = "IndiGo",
            bars = (1..12).map { MonthBar(YearMonth.of(2026, it), it % 4, (it % 4) / 3f) },
        ),
        palette = CardPalette.Light,
    )
}
