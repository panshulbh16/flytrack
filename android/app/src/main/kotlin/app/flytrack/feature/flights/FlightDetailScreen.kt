package app.flytrack.feature.flights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.core.model.Cabin
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.result.TrackField
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.feature.billing.ui.ProFlightBadge
import app.flytrack.feature.airporthealth.AirportHealthBlock
import app.flytrack.feature.checkin.CheckinCard
import app.flytrack.feature.calendar.AddToCalendarButton
import app.flytrack.feature.forecast.ForecastBlock
import app.flytrack.feature.intel.FlightAircraftBlock
import app.flytrack.feature.intel.FlightConnectionsBlock
import app.flytrack.feature.intel.FlightPredictionBlock
import app.flytrack.feature.intel.FlightTimelineBlock
import app.flytrack.feature.friends.ShareWithFriendsRow
import app.flytrack.feature.leave.WhenToLeaveCard
import app.flytrack.feature.share.ShareRequest
import app.flytrack.feature.share.ShareTarget
import app.flytrack.feature.share.ShareFlightEffect
import app.flytrack.feature.share.ShareFlightBlock
import app.flytrack.feature.billing.ui.ProTeaser
import app.flytrack.feature.checkin.CheckinUiModel
import app.flytrack.feature.flights.presentation.AlertGroup
import app.flytrack.feature.flights.presentation.AlertToggleUiModel
import app.flytrack.feature.flights.presentation.BookingUiModel
import app.flytrack.feature.flights.presentation.EndpointUiModel
import app.flytrack.feature.flights.presentation.FlightDetailUiModel
import app.flytrack.feature.flights.presentation.FlightInfoUiModel
import app.flytrack.feature.flights.presentation.ProInsightUiModel
import app.flytrack.feature.flights.presentation.TrackingState
import app.flytrack.feature.flights.ui.FlightProgressLine
import app.flytrack.feature.flights.ui.FlightStatusBlock
import app.flytrack.feature.flights.ui.FlightTimesRow
import app.flytrack.feature.flights.ui.PreviewFlights
import app.flytrack.feature.flights.ui.alertGroupDescription
import app.flytrack.feature.flights.ui.alertGroupTitle
import app.flytrack.feature.flights.ui.cabinText
import app.flytrack.feature.flights.ui.flightDateText
import app.flytrack.feature.flights.ui.flightInfoLabel
import app.flytrack.feature.flights.ui.minutesAmountText
import app.flytrack.feature.map.FlightMapButton
import app.flytrack.feature.map.ProgressSourceRow
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.IconAction
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.components.StatusPill
import app.flytrack.ui.components.durationText
import app.flytrack.ui.components.updatedAgoText
import app.flytrack.ui.theme.FlyTrackTheme
import java.text.NumberFormat
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightDetailScreen(
    state: FlightDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAlertChange: (AlertGroup, Boolean) -> Unit,
    onTrack: () -> Unit,
    onArchiveChange: (archived: Boolean) -> Unit,
    onUntrack: () -> Unit,
    onEditBooking: () -> Unit,
    onPnrChange: (String) -> Unit,
    onSeatChange: (String) -> Unit,
    onSaveBooking: () -> Unit,
    onDismissBookingEditor: () -> Unit,
    onMessageShown: (FlightDetailMessage) -> Unit,
    onCabinChange: (Cabin?) -> Unit = {},
    onShare: (ShareTarget) -> Unit = {},
    onShareHandled: (ShareRequest) -> Unit = {},
) {
    val snackbars = remember { SnackbarHostState() }
    DetailSnackbar(state.message, snackbars, onMessageShown)
    ShareFlightEffect(state.share, onShareHandled)
    var confirmUntrack by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            FlyTrackTopBar(
                title = state.detail?.card?.designator ?: stringResource(R.string.flights_detail_title),
                onBack = onBack,
                actions = {
                    if (state.canShare) {
                        IconAction(
                            icon = painterResource(R.drawable.ic_share),
                            contentDescription = stringResource(R.string.share_action_more),
                            onClick = { onShare(ShareTarget.SHARE_SHEET) },
                            enabled = !state.isSharing,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        val detail = state.detail
        when {
            state.isLoading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            detail == null -> ErrorState(
                title = stringResource(R.string.flights_detail_not_found_title),
                message = stringResource(R.string.flights_detail_not_found_message),
                modifier = Modifier.fillMaxSize().padding(padding),
                onRetry = onRefresh,
            )
            else -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                DetailContent(
                    detail = detail,
                    checkin = state.checkin,
                    canShare = state.canShare,
                    isSharing = state.isSharing,
                    onShare = onShare,
                    isWorking = state.isWorking,
                    onAlertChange = onAlertChange,
                    onTrack = onTrack,
                    onArchiveChange = onArchiveChange,
                    onUntrack = { confirmUntrack = true },
                    onEditBooking = onEditBooking,
                )
            }
        }
    }

    if (confirmUntrack) {
        AlertDialog(
            onDismissRequest = { confirmUntrack = false },
            title = { Text(stringResource(R.string.flights_untrack_confirm_title)) },
            text = { Text(stringResource(R.string.flights_untrack_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUntrack = false
                    onUntrack()
                }) { Text(stringResource(R.string.flights_action_untrack)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUntrack = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    state.editor?.let { editor ->
        BookingEditorDialog(editor, onPnrChange, onSeatChange, onCabinChange, onSaveBooking, onDismissBookingEditor)
    }
}

@Composable
private fun DetailSnackbar(
    message: FlightDetailMessage?,
    snackbars: SnackbarHostState,
    onMessageShown: (FlightDetailMessage) -> Unit,
) {
    val text = when (message) {
        null -> null
        FlightDetailMessage.OFFLINE -> stringResource(R.string.flights_message_offline)
        FlightDetailMessage.REFRESH_FAILED -> stringResource(R.string.flights_message_refresh_failed)
        FlightDetailMessage.ACTION_FAILED -> stringResource(R.string.flights_message_action_failed)
        FlightDetailMessage.TRACKING_LIMIT_REACHED -> stringResource(R.string.flights_message_tracking_limit)
        FlightDetailMessage.SHARE_FAILED -> stringResource(R.string.share_message_failed)
        FlightDetailMessage.SHARE_NOT_TRACKED -> stringResource(R.string.share_message_not_tracked)
    }
    LaunchedEffect(message) {
        if (message == null || text == null) return@LaunchedEffect
        snackbars.showSnackbar(text)
        onMessageShown(message)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    detail: FlightDetailUiModel,
    checkin: CheckinUiModel?,
    canShare: Boolean,
    isSharing: Boolean,
    onShare: (ShareTarget) -> Unit,
    isWorking: Boolean,
    onAlertChange: (AlertGroup, Boolean) -> Unit,
    onTrack: () -> Unit,
    onArchiveChange: (Boolean) -> Unit,
    onUntrack: () -> Unit,
    onEditBooking: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        DetailHeader(detail)
        ProFlightBadge(Modifier.padding(top = 12.dp)) // billing: "Pro free for this flight" on the trial flight
        TrackingBanner(detail.trackingState, isWorking, onTrack, onRestore = { onArchiveChange(false) })
        checkin?.let { CheckinCard(it, Modifier.padding(top = 16.dp)) }
        Section {
            FlightTimesRow(detail.card)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.flights_detail_times_local),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LittleNumbers(detail)
        }
        Section { FlightMapButton(detail.card.flightId) } // map: opens the offline map sheet
        Section { EndpointBlock(stringResource(R.string.flights_departure), detail.departure) }
        Section { EndpointBlock(stringResource(R.string.flights_arrival), detail.arrival) }
        // P3 intelligence: delay prediction / usual gate / taxi times (Pro), connections (Pro) and the
        // timeline. Each draws its own hairline, and nothing when it has nothing to show.
        FlightPredictionBlock(detail.card.flightId)
        FlightConnectionsBlock(detail.card.flightId)
        FlightTimelineBlock(detail.card.flightId)
        // P2 Airport Health: draws its own hairline, and nothing once the flight has landed.
        AirportHealthBlock(depIata = detail.card.depCode, arrIata = detail.card.arrCode, phase = detail.card.phase)
        // P2 wave 2: when to leave (Pro, tracked upcoming flights) and the arrival forecast. Both draw their own hairline.
        WhenToLeaveCard(detail.card.flightId)
        ForecastBlock(detail.card.flightId)
        // P3: type for everyone, ICAO type, and for Pro the age and the swap history.
        Section { FlightAircraftBlock(detail.card.flightId, detail.aircraftModel, detail.aircraftReg) }
        if (detail.proInsight != ProInsightUiModel.None) {
            Section { ProInsightBlock(detail.proInsight) }
        }
        detail.booking?.let { booking ->
            Section { BookingBlock(booking, onEditBooking) }
        }
        if (detail.trackingState == TrackingState.TRACKED && detail.alerts.isNotEmpty()) {
            Section { AlertsBlock(detail.alerts, onAlertChange) }
        }
        if (canShare) {
            Section { ShareFlightBlock(isSharing = isSharing, onShare = { onShare(ShareTarget.WHATSAPP_FIRST) }) }
        }
        // P2 wave 2: who sees this flight among friends, and one event in the phone's calendar.
        ShareWithFriendsRow(detail.card.flightId, tracked = detail.trackingState == TrackingState.TRACKED)
        Section { AddToCalendarButton(detail.card.flightId) }
        if (detail.trackingState != TrackingState.NOT_TRACKED) {
            Section {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (detail.trackingState == TrackingState.TRACKED) {
                        SecondaryButton(
                            text = stringResource(R.string.flights_action_archive),
                            onClick = { onArchiveChange(true) },
                            enabled = !isWorking,
                            icon = painterResource(R.drawable.ic_archive),
                        )
                    }
                    SecondaryButton(
                        text = stringResource(R.string.flights_action_untrack),
                        onClick = onUntrack,
                        enabled = !isWorking,
                        icon = painterResource(R.drawable.ic_bell_off),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A block below a hairline: the brief's replacement for heavy cards. */
@Composable
private fun Section(content: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    HairlineDivider()
    Spacer(Modifier.height(20.dp))
    content()
}

@Composable
private fun DetailHeader(detail: FlightDetailUiModel) {
    val card = detail.card
    val date = flightDateText(card.departureDate, card.departureDay)
    Text(
        text = card.airlineName?.let { stringResource(R.string.flights_joined, it, date) } ?: date,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.flights_route_cities, card.depCity, card.arrCity),
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(12.dp))
    FlightStatusBlock(card, wordStyle = FlyTrackTheme.type.statusWord, lineStyle = MaterialTheme.typography.bodyLarge)
    detail.delayReason?.let { reason ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.flights_detail_delay_reason, reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    card.progress?.let { progress ->
        Spacer(Modifier.height(16.dp))
        FlightProgressLine(progress = progress, depCode = card.depCode, arrCode = card.arrCode)
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.flights_detail_progress, (progress.coerceIn(0f, 1f) * 100).roundToInt()),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // map: "Live" / "Estimated" and the time left, from the same fix as the progress (core/estimate).
        card.progressSource?.let { source -> ProgressSourceRow(source, card.progressArrival, card.asOf) }
    }
    card.updatedAt?.let { updatedAt ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = updatedAgoText(updatedAt, card.asOf),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackingBanner(state: TrackingState, isWorking: Boolean, onTrack: () -> Unit, onRestore: () -> Unit) {
    if (state == TrackingState.TRACKED) return
    val archived = state == TrackingState.ARCHIVED
    Spacer(Modifier.height(16.dp))
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(if (archived) R.string.flights_detail_archived else R.string.flights_detail_not_tracked),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = stringResource(if (archived) R.string.flights_action_restore else R.string.flights_action_track),
                onClick = if (archived) onRestore else onTrack,
                loading = isWorking,
            )
        }
    }
}

/** The brief's "little numbers": flight time and distance. */
@Composable
private fun LittleNumbers(detail: FlightDetailUiModel) {
    val duration = detail.card.duration
    val distance = detail.distanceKm
    if (duration == null && distance == null) return
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        duration?.let { LittleNumber(value = durationText(it), label = stringResource(R.string.flights_detail_flight_time)) }
        distance?.let {
            LittleNumber(
                value = stringResource(R.string.flights_detail_distance_km, NumberFormat.getIntegerInstance().format(it)),
                label = stringResource(R.string.flights_detail_distance),
            )
        }
    }
}

@Composable
private fun LittleNumber(value: String, label: String) {
    // Label first when read aloud: "Flight time, 2h 15m".
    val spoken = stringResource(R.string.flights_cd_labelled, label, value)
    Column(modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Text(text = value, style = FlyTrackTheme.type.numeralSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(text = label.uppercase(), style = FlyTrackTheme.type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EndpointBlock(heading: String, endpoint: EndpointUiModel) {
    SectionCaption(text = heading)
    Spacer(Modifier.height(6.dp))
    Text(
        // Without airport data the "city" is the code itself: do not print it twice.
        text = if (endpoint.city == endpoint.code) endpoint.code else stringResource(R.string.flights_detail_airport, endpoint.city, endpoint.code),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    endpoint.airportName?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    endpoint.time?.let { time ->
        Text(
            text = FlightTimeFormat.weekdayDayMonth(time.date),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val times = listOfNotNull(
        endpoint.scheduled?.let { stringResource(R.string.flights_time_scheduled) to it },
        endpoint.estimated?.let { stringResource(R.string.flights_time_estimated) to it },
        endpoint.actual?.let { stringResource(R.string.flights_time_actual) to it },
    )
    if (times.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, time) in times) LittleNumber(value = time, label = label)
        }
    }
    if (endpoint.info.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        for (item in endpoint.info) InfoRow(item)
    }
}

/** One "Gate · 42B" line. A missing value says so in words; a new one is bold and labelled. */
@Composable
private fun InfoRow(item: FlightInfoUiModel) {
    val label = flightInfoLabel(item.field)
    val value = item.value ?: stringResource(R.string.flights_detail_not_announced)
    val spoken = stringResource(R.string.flights_cd_labelled, label, value).let {
        if (item.isNew) stringResource(R.string.flights_cd_chip_new, it) else it
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.isNew) {
            Text(
                text = stringResource(R.string.flights_chip_new).uppercase(),
                style = FlyTrackTheme.type.caption,
                color = FlyTrackTheme.accentText,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = value,
            style = if (item.value != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (item.value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabelledRow(label: String, value: String?, placeholder: String) {
    val spoken = stringResource(R.string.flights_cd_labelled, label, value ?: placeholder)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value ?: placeholder,
            style = if (value != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ProInsightBlock(insight: ProInsightUiModel) {
    when (insight) {
        ProInsightUiModel.None -> Unit
        // billing: free users see the teaser (`has_pro_insight`); tapping it opens the paywall.
        ProInsightUiModel.Teaser -> ProTeaser()
        is ProInsightUiModel.Details -> ProDetails(insight)
    }
}

@Composable
private fun ProDetails(details: ProInsightUiModel.Details) {
    SectionCaption(text = stringResource(R.string.flights_pro_title))
    Spacer(Modifier.height(6.dp))
    val inbound = when {
        details.inboundFlight != null && details.inboundFrom != null ->
            stringResource(R.string.flights_pro_inbound, details.inboundFlight, details.inboundFrom)
        else -> stringResource(R.string.flights_pro_inbound_unknown)
    }
    val arrival = details.inboundArrival?.let {
        stringResource(if (details.inboundLanded) R.string.flights_pro_inbound_landed else R.string.flights_pro_inbound_lands, it)
    }
    Text(
        text = listOfNotNull(inbound, arrival).joinToString(" "),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    val late = minutesAmountText(details.lateMinutes)
    Text(
        text = when {
            !details.isLate -> stringResource(R.string.flights_pro_on_time)
            details.predictedDeparture != null -> stringResource(R.string.flights_pro_late_around, late, details.predictedDeparture)
            else -> stringResource(R.string.flights_pro_late, late)
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    details.reason?.let {
        Spacer(Modifier.height(4.dp))
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BookingBlock(booking: BookingUiModel, onEdit: () -> Unit) {
    val notAdded = stringResource(R.string.flights_booking_not_added)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionCaption(text = stringResource(R.string.flights_booking_title), modifier = Modifier.weight(1f))
        IconAction(
            icon = painterResource(R.drawable.ic_edit),
            contentDescription = stringResource(R.string.flights_booking_edit),
            onClick = onEdit,
        )
    }
    LabelledRow(stringResource(R.string.flights_booking_pnr), booking.pnr, notAdded)
    LabelledRow(stringResource(R.string.flights_booking_seat), booking.seat, notAdded)
    booking.cabin?.let { LabelledRow(stringResource(R.string.flights_booking_cabin), cabinText(it), notAdded) }
    booking.note?.let {
        Spacer(Modifier.height(4.dp))
        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AlertsBlock(alerts: List<AlertToggleUiModel>, onAlertChange: (AlertGroup, Boolean) -> Unit) {
    SectionCaption(text = stringResource(R.string.flights_alerts_title))
    Spacer(Modifier.height(4.dp))
    for (alert in alerts) AlertRow(alert, onAlertChange)
}

@Composable
private fun AlertRow(alert: AlertToggleUiModel, onAlertChange: (AlertGroup, Boolean) -> Unit) {
    val state = stringResource(
        when {
            alert.locked -> R.string.flights_alerts_state_pro
            alert.enabled -> R.string.flights_alerts_state_on
            else -> R.string.flights_alerts_state_off
        },
    )
    // The whole row is the switch: a 48dp+ target, and one TalkBack stop instead of three.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = alert.enabled,
                enabled = !alert.locked,
                role = Role.Switch,
                onValueChange = { onAlertChange(alert.group, it) },
            )
            .semantics { stateDescription = state }
            .defaultMinSize(minHeight = 56.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = alertGroupTitle(alert.group),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (alert.locked) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill(
                        text = stringResource(R.string.flights_pro_badge),
                        tone = FlightStatusTone.ACTIVE,
                        icon = painterResource(R.drawable.ic_lock),
                    )
                }
            }
            Text(
                text = alertGroupDescription(alert.group),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = alert.enabled, onCheckedChange = null, enabled = !alert.locked)
    }
}

@Composable
private fun BookingEditorDialog(
    editor: BookingEditorState,
    onPnrChange: (String) -> Unit,
    onSeatChange: (String) -> Unit,
    onCabinChange: (Cabin?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flights_booking_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = editor.pnr,
                    onValueChange = onPnrChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.flights_booking_pnr_label)) },
                    supportingText = {
                        Text(stringResource(if (editor.error == TrackField.PNR) R.string.flights_booking_pnr_error else R.string.flights_booking_pnr_hint))
                    },
                    isError = editor.error == TrackField.PNR,
                    singleLine = true,
                    enabled = !editor.isSaving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editor.seat,
                    onValueChange = onSeatChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.flights_booking_seat)) },
                    supportingText = {
                        Text(
                            if (editor.error == TrackField.SEAT) {
                                pluralStringResource(R.plurals.flights_booking_seat_error, TrackExtras.MAX_SEAT_LENGTH, TrackExtras.MAX_SEAT_LENGTH)
                            } else {
                                stringResource(R.string.flights_booking_seat_hint)
                            },
                        )
                    },
                    isError = editor.error == TrackField.SEAT,
                    singleLine = true,
                    enabled = !editor.isSaving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                )
                Spacer(Modifier.height(8.dp))
                CabinPicker(selected = editor.cabin, enabled = !editor.isSaving, onSelect = onCabinChange)
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !editor.isSaving) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !editor.isSaving) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Economy / premium economy / business / first, plus "not added". One choice at a time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CabinPicker(selected: Cabin?, enabled: Boolean, onSelect: (Cabin?) -> Unit) {
    Text(
        text = stringResource(R.string.flights_booking_cabin),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val options: List<Cabin?> = listOf(null) + Cabin.entries
        for (option in options) {
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                label = { Text(option?.let { cabinText(it) } ?: stringResource(R.string.flights_booking_not_added)) },
                modifier = Modifier.semantics { role = Role.RadioButton },
            )
        }
    }
}

@Preview(name = "Detail, light (free user)", showBackground = true, heightDp = 2200)
@Composable
private fun FlightDetailLightPreview() {
    FlyTrackTheme(darkTheme = false) { PreviewScreen(PreviewFlights.detail()) }
}

@Preview(name = "Detail, dark (Pro user)", showBackground = true, heightDp = 2200)
@Composable
private fun FlightDetailDarkPreview() {
    FlyTrackTheme(darkTheme = true) { PreviewScreen(PreviewFlights.detailPro()) }
}

@Preview(name = "Detail, in the air, light", showBackground = true, heightDp = 1800)
@Composable
private fun FlightDetailInAirPreview() {
    FlyTrackTheme(darkTheme = false) { PreviewScreen(PreviewFlights.inAirDetail()) }
}

@Preview(name = "Detail, in the air, dark", showBackground = true, heightDp = 1800)
@Composable
private fun FlightDetailInAirDarkPreview() {
    FlyTrackTheme(darkTheme = true) { PreviewScreen(PreviewFlights.inAirDetail()) }
}

@Composable
private fun PreviewScreen(detail: FlightDetailUiModel) {
    FlightDetailScreen(
        state = FlightDetailUiState(isLoading = false, detail = detail),
        onBack = {},
        onRefresh = {},
        onAlertChange = { _, _ -> },
        onTrack = {},
        onArchiveChange = {},
        onUntrack = {},
        onEditBooking = {},
        onPnrChange = {},
        onSeatChange = {},
        onSaveBooking = {},
        onDismissBookingEditor = {},
        onMessageShown = {},
        onCabinChange = {},
        onShare = {},
        onShareHandled = {},
    )
}
