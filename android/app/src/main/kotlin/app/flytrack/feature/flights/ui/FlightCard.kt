package app.flytrack.feature.flights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.core.model.FlightPhase
import app.flytrack.feature.flights.presentation.FlightCardUiModel
import app.flytrack.feature.flights.presentation.FlightInfoUiModel
import app.flytrack.feature.flights.presentation.FlightTimeUiModel
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.NumeralTimeText
import app.flytrack.ui.components.StatusWord
import app.flytrack.ui.components.durationText
import app.flytrack.ui.components.updatedAgoText
import app.flytrack.ui.theme.FlyTrackTheme

/**
 * The flight card: route, status word, plain-language status, times in each airport's local time,
 * terminal/gate/belt chips and the age of the data. Stateless.
 *
 * @param onClick makes the whole card a button (the list); null leaves it static
 * @param menu optional slot at the top end, for an overflow menu
 */
@Composable
fun FlightCard(
    model: FlightCardUiModel,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    menu: (@Composable () -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.medium
    val openLabel = stringResource(R.string.flights_action_open_details)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 14.dp)) {
            RouteHeader(model, menu)
            Spacer(Modifier.height(12.dp))
            FlightStatusBlock(model)
            Spacer(Modifier.height(12.dp))
            HairlineDivider()
            Spacer(Modifier.height(12.dp))
            FlightTimesRow(model)
            if (model.info.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlightInfoChips(model.info)
            }
            Spacer(Modifier.height(12.dp))
            CardFootnote(model)
        }
    }
}

@Composable
private fun RouteHeader(model: FlightCardUiModel, menu: (@Composable () -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlaneBadge(model.phase)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            RouteCodes(model.depCode, model.arrCode, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.flights_route_cities, model.depCity, model.arrCity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (menu != null) menu()
    }
}

/** "DEL → BOM", read aloud as "DEL to BOM". */
@Composable
fun RouteCodes(depCode: String, arrCode: String, style: TextStyle, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.flights_route_cities, depCode, arrCode)
    Text(
        text = stringResource(R.string.flights_route_codes, depCode, arrCode),
        modifier = modifier.semantics { contentDescription = spoken },
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}

/** The brief's 44dp outlined circle. Decorative: the status word next to it carries the meaning. */
@Composable
fun PlaneBadge(phase: FlightPhase, modifier: Modifier = Modifier) {
    val icon = when (phase) {
        FlightPhase.LANDED -> R.drawable.ic_landing
        FlightPhase.CANCELLED, FlightPhase.DIVERTED -> R.drawable.ic_warning
        FlightPhase.UPCOMING, FlightPhase.CHECK_IN, FlightPhase.BOARDING, FlightPhase.IN_AIR -> R.drawable.ic_plane
    }
    Box(
        modifier = modifier
            .size(44.dp)
            .border(1.dp, MaterialTheme.colorScheme.tertiary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Status word in italic serif, the plain-language sentence, and the short countdown when one applies. */
@Composable
fun FlightStatusBlock(
    model: FlightCardUiModel,
    modifier: Modifier = Modifier,
    wordStyle: TextStyle = FlyTrackTheme.type.statusWordSmall,
    lineStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Column(modifier = modifier) {
        StatusWord(text = statusWordText(model.statusWord), tone = model.tone, style = wordStyle)
        Text(
            text = statusLineText(model.statusLine, model.arrCity),
            style = lineStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        countdownSentence(model.countdown)?.let { countdown ->
            Spacer(Modifier.height(2.dp))
            Text(text = countdown, style = MaterialTheme.typography.labelLarge, color = FlyTrackTheme.accentText)
        }
    }
}

/** Departure time, duration, arrival time. Each time is in its own airport's zone. */
@Composable
fun FlightTimesRow(model: FlightCardUiModel, modifier: Modifier = Modifier) {
    // Two 38sp times do not fit side by side once the user enlarges text.
    val timeStyle = if (LocalDensity.current.fontScale > 1.3f) FlyTrackTheme.type.numeralSmall else FlyTrackTheme.type.numeral
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        TimeColumn(
            code = model.depCode,
            spokenHeading = stringResource(R.string.flights_time_heading, stringResource(R.string.flights_departure), model.depCode),
            time = model.departure,
            isDeparture = true,
            timeStyle = timeStyle,
            alignment = Alignment.Start,
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            model.duration?.let { duration ->
                val text = durationText(duration)
                val spoken = stringResource(R.string.flights_cd_duration, text)
                Text(
                    text = text,
                    modifier = Modifier.semantics { contentDescription = spoken },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                HairlineDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        TimeColumn(
            code = model.arrCode,
            spokenHeading = stringResource(R.string.flights_time_heading, stringResource(R.string.flights_arrival), model.arrCode),
            time = model.arrival,
            isDeparture = false,
            timeStyle = timeStyle,
            alignment = Alignment.End,
        )
    }
}

@Composable
private fun TimeColumn(
    code: String,
    spokenHeading: String,
    time: FlightTimeUiModel?,
    isDeparture: Boolean,
    timeStyle: TextStyle,
    alignment: Alignment.Horizontal,
) {
    val description = timeDescription(spokenHeading, time, isDeparture)
    // One spoken sentence instead of four fragments, with the struck-through time explained.
    Column(modifier = Modifier.clearAndSetSemantics { contentDescription = description }, horizontalAlignment = alignment) {
        Text(text = code, style = FlyTrackTheme.type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (time == null) {
            NumeralTimeText(time = stringResource(R.string.flights_time_unknown), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.Top) {
                NumeralTimeText(time = time.time, style = timeStyle, color = MaterialTheme.colorScheme.onSurface)
                if (time.dayOffset != 0) {
                    Text(
                        text = stringResource(R.string.flights_day_offset, time.dayOffset),
                        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = FlyTrackTheme.accentText,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                time.replacedTime?.let { old ->
                    NumeralTimeText(
                        time = old,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        struckThrough = true,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = timeKindText(time.kind, isDeparture),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun timeDescription(heading: String, time: FlightTimeUiModel?, isDeparture: Boolean): String {
    if (time == null) return stringResource(R.string.flights_cd_time_unknown, heading)
    val base = stringResource(R.string.flights_cd_time, heading, time.time, timeKindText(time.kind, isDeparture))
    val days = time.dayOffset
    val withDay = when {
        days > 0 -> stringResource(R.string.flights_cd_joined, base, pluralStringResource(R.plurals.flights_cd_days_later, days, days))
        days < 0 -> stringResource(R.string.flights_cd_joined, base, pluralStringResource(R.plurals.flights_cd_days_earlier, -days, -days))
        else -> base
    }
    return time.replacedTime?.let {
        stringResource(R.string.flights_cd_joined, withDay, stringResource(R.string.flights_cd_time_replaced, it))
    } ?: withDay
}

/** Terminal, gate and belt as chips. A value that just became known is filled, bold and labelled "new". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlightInfoChips(info: List<FlightInfoUiModel>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (item in info) {
            val value = item.value
            if (value != null) FlightInfoChip(text = flightInfoChipText(item.field, value), isNew = item.isNew)
        }
    }
}

@Composable
private fun FlightInfoChip(text: String, isNew: Boolean) {
    val spoken = if (isNew) stringResource(R.string.flights_cd_chip_new, text) else text
    Surface(
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        shape = CircleShape,
        color = if (isNew) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
        contentColor = if (isNew) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (isNew) FlyTrackTheme.accentText else MaterialTheme.colorScheme.outline),
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isNew) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            if (isNew) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.flights_chip_new).uppercase(),
                    style = FlyTrackTheme.type.caption,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CardFootnote(model: FlightCardUiModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(
                R.string.flights_joined,
                model.designator,
                flightDateText(model.departureDate, model.departureDay),
            ),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        model.updatedAt?.let { updatedAt ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = updatedAgoText(updatedAt, model.asOf),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}
