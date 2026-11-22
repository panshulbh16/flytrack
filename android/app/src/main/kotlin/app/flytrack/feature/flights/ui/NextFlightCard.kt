package app.flytrack.feature.flights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.feature.flights.presentation.Countdown
import app.flytrack.feature.flights.presentation.NextFlightUiModel
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.components.StatusPill
import app.flytrack.ui.theme.FlyTrackTheme
import kotlin.math.roundToInt

/**
 * The brief's countdown card: "NEXT TAKEOFF · IN 3 DAYS" in the terracotta caption style above
 * the route. For a flight in the air it counts down to landing and shows the progress line.
 */
@Composable
fun NextFlightCard(
    model: NextFlightUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = model.card
    val shape = MaterialTheme.shapes.medium
    val caption = stringResource(
        R.string.flights_joined,
        stringResource(if (model.countdown is Countdown.Lands) R.string.flights_next_in_the_air else R.string.flights_next_takeoff),
        countdownDistanceText(model.countdown),
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClickLabel = stringResource(R.string.flights_action_open_details), role = Role.Button, onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            SectionCaption(text = caption)
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.flights_route_cities, card.depCity, card.arrCity),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    card.designator,
                    flightDateText(card.departureDate, card.departureDay),
                    card.departure?.time,
                ).reduce { acc, part -> stringResource(R.string.flights_joined, acc, part) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            card.progress?.let { progress ->
                Spacer(Modifier.height(14.dp))
                FlightProgressLine(progress = progress, depCode = card.depCode, arrCode = card.arrCode)
            }
            Spacer(Modifier.height(12.dp))
            StatusPill(text = statusWordText(card.statusWord), tone = card.tone)
        }
    }
}

/**
 * A simple line from origin to destination with the plane at the fraction flown. The fraction
 * comes from the departure and arrival times, not from the aircraft's position.
 */
@Composable
fun FlightProgressLine(
    progress: Float,
    depCode: String,
    arrCode: String,
    modifier: Modifier = Modifier,
) {
    // Row weights must be positive, so the plane never sits exactly on either end.
    val flown = progress.coerceIn(0.02f, 0.98f)
    val percent = (progress.coerceIn(0f, 1f) * 100).roundToInt()
    val spoken = stringResource(R.string.flights_cd_progress, percent, depCode, arrCode)
    Row(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = depCode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(flown).height(2.dp).background(FlyTrackTheme.statusColors.active.content))
            Icon(
                painter = painterResource(R.drawable.ic_plane),
                contentDescription = null,
                modifier = Modifier.padding(horizontal = 2.dp).size(18.dp),
                tint = FlyTrackTheme.statusColors.active.content,
            )
            Box(Modifier.weight(1f - flown).height(2.dp).background(MaterialTheme.colorScheme.outline))
        }
        Spacer(Modifier.width(8.dp))
        Text(text = arrCode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
