package app.flytrack.feature.addflight

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.core.model.statusTone
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.feature.importticket.ScanBoardingPassButton
import app.flytrack.ui.components.BackendNotConfiguredState
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.NumeralTimeText
import app.flytrack.ui.components.OfflineState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.components.StatusWord
import app.flytrack.ui.components.durationText
import app.flytrack.ui.theme.FlyTrackTheme
import kotlin.time.Instant

/** Everything below the form's hairline: what the last search led to. */
@Composable
internal fun SearchOutcome(
    state: AddFlightUiState,
    onRetry: () -> Unit,
    onPickDate: () -> Unit,
    onTrack: (flightId: String) -> Unit,
    onOpenTracked: (flightId: String) -> Unit,
    onImportTicket: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowMyFlights: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (val search = state.search) {
            SearchUiState.Idle -> when {
                !state.isBackendConfigured -> BackendNotConfiguredState()
                !state.isFromTicket -> ImportPrompt(onImportTicket)
            }

            is SearchUiState.Searching -> {
                QueryCaption(search.query, state)
                LoadingState(label = stringResource(R.string.addflight_searching, search.query.flight))
            }

            is SearchUiState.Results -> Results(search, state, onRetry, onTrack, onOpenTracked, onShowMyFlights)

            is SearchUiState.NotFound -> {
                QueryCaption(search.query, state)
                ErrorState(
                    title = stringResource(
                        R.string.addflight_not_found_title,
                        search.query.flight,
                        formDateText(search.query.date, state.today),
                    ),
                    message = stringResource(R.string.addflight_not_found_message),
                    icon = painterResource(R.drawable.ic_search),
                    retryLabel = stringResource(R.string.addflight_not_found_change_date),
                    onRetry = onPickDate,
                )
            }

            SearchUiState.RateLimited -> {
                val message = stringResource(R.string.addflight_rate_limited_message)
                val hint = stringResource(R.string.addflight_rate_limited_google_hint)
                ErrorState(
                    title = stringResource(R.string.addflight_rate_limited_title),
                    message = if (state.canRaiseSearchLimit) "$message\n\n$hint" else message,
                    icon = painterResource(R.drawable.ic_clock),
                    retryLabel = stringResource(R.string.addflight_rate_limited_open_settings),
                    onRetry = onOpenSettings.takeIf { state.canRaiseSearchLimit },
                )
            }

            SearchUiState.ProviderError -> ErrorState(
                title = stringResource(R.string.addflight_provider_error_title),
                message = stringResource(R.string.addflight_provider_error_message),
                onRetry = onRetry,
            )

            SearchUiState.Offline -> OfflineState(onRetry = onRetry)

            SearchUiState.NotConfigured -> BackendNotConfiguredState()

            SearchUiState.Rejected -> ErrorState(
                title = stringResource(R.string.addflight_rejected_title),
                message = stringResource(R.string.addflight_rejected_message),
                onRetry = onRetry,
            )
        }
    }
}

/** "6E 2341 · WED, 25 NOV": which search the block below belongs to. */
@Composable
private fun QueryCaption(query: SearchQuery, state: AddFlightUiState) {
    SectionCaption(
        text = stringResource(R.string.addflight_results_caption, query.flight, formDateText(query.date, state.today)),
        modifier = Modifier.padding(top = 20.dp),
    )
}

@Composable
private fun ImportPrompt(onImportTicket: () -> Unit) {
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(
            text = stringResource(R.string.addflight_import_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        SecondaryButton(
            text = stringResource(R.string.addflight_import),
            onClick = onImportTicket,
            icon = painterResource(R.drawable.ic_ticket),
        )
        ScanBoardingPassButton()
    }
}

@Composable
private fun Results(
    results: SearchUiState.Results,
    state: AddFlightUiState,
    onSearchAgain: () -> Unit,
    onTrack: (flightId: String) -> Unit,
    onOpenTracked: (flightId: String) -> Unit,
    onShowMyFlights: () -> Unit,
) {
    val legs = results.legs
    QueryCaption(results.query, state)
    Spacer(Modifier.height(6.dp))
    val headline = if (legs.size == 1) {
        val key = stringResource(R.string.addflight_results_headline_single_key)
        emphasised(stringResource(R.string.addflight_results_headline_single, key), key)
    } else {
        val key = stringResource(R.string.addflight_results_headline_legs_key)
        emphasised(pluralStringResource(R.plurals.addflight_results_headline_legs, legs.size, legs.size, key), key)
    }
    Text(
        text = headline,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    if (legs.size > 1) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.addflight_results_legs_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    legs.forEach { leg ->
        HairlineDivider()
        LegRow(
            flight = leg,
            isTracked = state.isTracked(leg.id),
            isBusy = state.trackingFlightId == leg.id,
            enabled = !state.isTracking,
            problem = state.trackNotice?.takeIf { it.flightId == leg.id }?.problem,
            onTrack = { onTrack(leg.id) },
            onOpen = { onOpenTracked(leg.id) },
            onSearchAgain = onSearchAgain,
            onShowMyFlights = onShowMyFlights,
        )
    }
    HairlineDivider()
    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(R.string.addflight_results_times_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LegRow(
    flight: Flight,
    isTracked: Boolean,
    isBusy: Boolean,
    enabled: Boolean,
    problem: TrackProblem?,
    onTrack: () -> Unit,
    onOpen: () -> Unit,
    onSearchAgain: () -> Unit,
    onShowMyFlights: () -> Unit,
) {
    val depCity = flight.depAirport?.displayCity ?: flight.depIata
    val arrCity = flight.arrAirport?.displayCity ?: flight.arrIata

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        // One TalkBack stop for the facts of the leg; the button below is a second stop.
        Row(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            PlaneBadge()
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                val routeDescription = stringResource(R.string.addflight_leg_route_description, flight.depIata, flight.arrIata)
                Text(
                    text = stringResource(R.string.addflight_leg_route, flight.depIata, flight.arrIata),
                    modifier = Modifier.semantics { contentDescription = routeDescription },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.addflight_leg_cities, depCity, arrCity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    LegTime(
                        label = stringResource(R.string.addflight_leg_departs),
                        instant = flight.listedDeparture,
                        tz = flight.depTz,
                        // Without a departure time the flight's own date is still known.
                        fallbackDate = FlightTimeFormat.weekdayDayMonth(flight.flightDate),
                        modifier = Modifier.weight(1f),
                    )
                    LegTime(
                        label = stringResource(R.string.addflight_leg_arrives),
                        instant = flight.listedArrival,
                        tz = flight.arrTz,
                        fallbackDate = null,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = legMeta(flight),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                StatusWord(
                    text = legStatusText(flight.legStatus),
                    tone = flight.statusTone,
                    style = FlyTrackTheme.type.statusWordSmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (isTracked) {
                val onTime = FlyTrackTheme.statusColors.forTone(FlightStatusTone.ON_TIME).content
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = onTime,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.addflight_leg_tracked),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val description = stringResource(R.string.addflight_leg_open_description, flight.designator, depCity, arrCity)
                SecondaryButton(
                    text = stringResource(R.string.addflight_leg_open),
                    onClick = onOpen,
                    modifier = Modifier.semantics { contentDescription = description },
                    enabled = enabled,
                    loading = isBusy,
                    icon = painterResource(R.drawable.ic_chevron_right),
                )
            } else {
                Spacer(Modifier.weight(1f))
                val description = stringResource(R.string.addflight_leg_track_description, flight.designator, depCity, arrCity)
                PrimaryButton(
                    text = stringResource(R.string.addflight_leg_track),
                    onClick = onTrack,
                    modifier = Modifier.semantics { contentDescription = description },
                    enabled = enabled,
                    loading = isBusy,
                    icon = painterResource(R.drawable.ic_add),
                )
            }
        }

        if (problem != null) {
            Spacer(Modifier.height(12.dp))
            TrackProblemNotice(problem, onSearchAgain, onShowMyFlights)
        }
    }
}

/** The brief's flight-row mark: a 44dp outlined circle with the plane. */
@Composable
private fun PlaneBadge() {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(R.drawable.ic_plane), contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

/** A labelled clock time with its date, both in the airport's own time zone [tz]. */
@Composable
private fun LegTime(label: String, instant: Instant?, tz: String?, fallbackDate: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = FlyTrackTheme.type.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (instant != null) {
            NumeralTimeText(time = FlightTimeFormat.time(instant, tz), style = FlyTrackTheme.type.numeralSmall)
        } else {
            Text(
                text = stringResource(R.string.addflight_leg_time_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val date = instant?.let { FlightTimeFormat.weekdayDayMonth(it, tz) } ?: fallbackDate
        if (date != null) {
            Text(text = date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "IndiGo · 6E 2341 · 2h 15m", leaving out what is unknown. */
@Composable
private fun legMeta(flight: Flight): String {
    val parts = listOfNotNull(
        flight.airlineName?.takeIf { it.isNotBlank() },
        flight.designator,
        flight.listedDuration?.let { durationText(it) },
    )
    return when (parts.size) {
        3 -> stringResource(R.string.addflight_leg_meta_three, parts[0], parts[1], parts[2])
        2 -> stringResource(R.string.addflight_leg_meta_two, parts[0], parts[1])
        else -> parts.first()
    }
}

@Composable
@ReadOnlyComposable
internal fun legStatusText(status: LegStatus): String = when (status) {
    LegStatus.Scheduled -> stringResource(R.string.addflight_status_scheduled)
    LegStatus.OnTime -> stringResource(R.string.addflight_status_on_time)
    is LegStatus.Late ->
        if (status.minutes < 60) {
            pluralStringResource(R.plurals.addflight_status_late_minutes, status.minutes, status.minutes)
        } else {
            stringResource(R.string.addflight_status_late_hours_minutes, status.minutes / 60, status.minutes % 60)
        }
    LegStatus.Boarding -> stringResource(R.string.addflight_status_boarding)
    LegStatus.InAir -> stringResource(R.string.addflight_status_in_air)
    LegStatus.Landed -> stringResource(R.string.addflight_status_landed)
    LegStatus.Cancelled -> stringResource(R.string.addflight_status_cancelled)
    LegStatus.Diverted -> stringResource(R.string.addflight_status_diverted)
    LegStatus.ScheduleOnly -> stringResource(R.string.addflight_status_schedule_only)
}

@Composable
private fun TrackProblemNotice(problem: TrackProblem, onSearchAgain: () -> Unit, onShowMyFlights: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    when (problem) {
        // Not an error of the app or the user: the accent colour, with a way forward.
        is TrackProblem.LimitReached -> NoticeSurface(
            title = stringResource(R.string.addflight_limit_title),
            text = if (problem.proLimit != null) {
                pluralStringResource(R.plurals.addflight_limit_message_free, problem.limit, problem.limit, problem.proLimit)
            } else {
                pluralStringResource(R.plurals.addflight_limit_message_pro, problem.limit, problem.limit)
            },
            icon = painterResource(R.drawable.ic_archive),
            container = colors.tertiaryContainer,
            content = colors.onTertiaryContainer,
            actionLabel = stringResource(R.string.addflight_limit_action),
            onAction = onShowMyFlights,
        )

        TrackProblem.FlightGone -> NoticeSurface(
            text = stringResource(R.string.addflight_track_gone),
            icon = painterResource(R.drawable.ic_refresh),
            container = colors.errorContainer,
            content = colors.onErrorContainer,
            actionLabel = stringResource(R.string.addflight_track_gone_action),
            onAction = onSearchAgain,
        )

        TrackProblem.Offline -> ErrorNotice(stringResource(R.string.addflight_track_offline), R.drawable.ic_cloud_off)
        TrackProblem.NotConfigured -> ErrorNotice(stringResource(R.string.state_not_configured_banner), R.drawable.ic_info)
        TrackProblem.InvalidDetails -> ErrorNotice(stringResource(R.string.addflight_track_invalid_details), R.drawable.ic_edit)
        TrackProblem.DetailsNotSaved -> ErrorNotice(stringResource(R.string.addflight_track_details_not_saved), R.drawable.ic_warning)
        TrackProblem.Failed -> ErrorNotice(stringResource(R.string.addflight_track_failed), R.drawable.ic_warning)
    }
}

@Composable
private fun ErrorNotice(text: String, @DrawableRes iconRes: Int) {
    NoticeSurface(
        text = text,
        icon = painterResource(iconRes),
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    )
}
