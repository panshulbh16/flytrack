package app.flytrack.feature.leave

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.feature.billing.FlightProAccess
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.forecast.ProFeatureTeaser
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.NumeralTimeText
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.theme.FlyTrackTheme
import kotlin.time.Instant

/**
 * "When to leave" on the flight detail screen (P2 wave 2, section 5): "Leave by 05:40", what it is made of,
 * the remembered travel time to this airport, directions, and a local reminder 15 min before. Pro (the trial
 * flight included); the teaser for free users. Draws its own hairline; nothing for flights that left.
 */
@Composable
fun WhenToLeaveCard(flightId: String, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<WhenToLeaveViewModel, WhenToLeaveViewModel.Factory>(key = "leave:$flightId") { it.create(flightId) }
    val pro = LocalProState.current.accessFor(flightId) != FlightProAccess.LOCKED
    LaunchedEffect(pro) { viewModel.setProAccess(pro) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WhenToLeaveContent(state, onTravelMinutes = viewModel::setTravelMinutes, modifier = modifier)
}

@Composable
fun WhenToLeaveContent(state: WhenToLeaveUiState, onTravelMinutes: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (state == WhenToLeaveUiState.Hidden) return
    Column(modifier) {
        Spacer(Modifier.height(20.dp))
        HairlineDivider()
        Spacer(Modifier.height(16.dp))
        SectionCaption(stringResource(R.string.leave_title))
        Spacer(Modifier.height(12.dp))
        when (state) {
            WhenToLeaveUiState.Hidden -> Unit
            WhenToLeaveUiState.Locked -> ProFeatureTeaser(
                title = stringResource(R.string.leave_teaser_title),
                body = stringResource(R.string.leave_teaser_body),
            )
            is WhenToLeaveUiState.Ready -> ReadyContent(state, onTravelMinutes)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyContent(state: WhenToLeaveUiState.Ready, onTravelMinutes: (Int) -> Unit) {
    val plan = state.plan
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(false) }
    val leaveAt = FlightTimeFormat.time(plan.leaveBy, plan.depTz)
    val leaveDay = FlightTimeFormat.weekdayDayMonth(plan.leaveBy, plan.depTz)

    Text(stringResource(R.string.leave_by), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    NumeralTimeText(time = leaveAt, contentDescription = stringResource(R.string.leave_cd_leave_by, leaveAt, leaveDay))
    Text(leaveDay, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(
            R.string.leave_breakdown,
            FlightTimeFormat.time(plan.departure, plan.depTz),
            pluralStringResource(R.plurals.leave_minutes, plan.bufferMinutes, plan.bufferMinutes),
            pluralStringResource(R.plurals.leave_minutes, plan.travelMinutes, plan.travelMinutes),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (plan.international || plan.fog || plan.peak) {
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (plan.international) Reason(stringResource(R.string.leave_reason_international))
            if (plan.fog) Reason(stringResource(R.string.leave_reason_fog, LeaveRules.FOG_EXTRA_MIN, state.depCity))
            if (plan.peak) Reason(stringResource(R.string.leave_reason_peak, LeaveRules.PEAK_EXTRA_MIN))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.leave_travel_time, state.depCity, pluralStringResource(R.plurals.leave_minutes, plan.travelMinutes, plan.travelMinutes)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { editing = true }) { Text(stringResource(R.string.leave_action_change)) }
    }
    if (state.reminderAhead) {
        Text(
            stringResource(R.string.leave_reminder, FlightTimeFormat.time(plan.reminderAt, plan.depTz)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    SecondaryButton(
        text = stringResource(R.string.leave_action_directions),
        onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.directionsUrl)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.leave_no_maps, Toast.LENGTH_LONG).show()
            }
        },
        icon = painterResource(R.drawable.ic_map_route),
    )
    if (editing) {
        TravelTimeDialog(
            airport = state.depCity,
            current = plan.travelMinutes,
            onSave = {
                editing = false
                onTravelMinutes(it)
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun Reason(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = FlyTrackTheme.accentText)
}

/** Travel time to the airport in minutes: quick choices plus a free field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TravelTimeDialog(airport: String, current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    val value = text.trim().toIntOrNull()
    val valid = value != null && value in LeaveRules.MIN_TRAVEL_MIN..LeaveRules.MAX_TRAVEL_MIN
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.leave_dialog_title, airport)) },
        text = {
            Column {
                Text(stringResource(R.string.leave_dialog_message), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_CHOICES.forEach { minutes ->
                        FilterChip(
                            selected = value == minutes,
                            onClick = { text = minutes.toString() },
                            label = { Text(pluralStringResource(R.plurals.leave_minutes, minutes, minutes)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.leave_dialog_field)) },
                    singleLine = true,
                    isError = !valid,
                    supportingText = if (valid) null else {
                        { Text(stringResource(R.string.leave_dialog_error, LeaveRules.MAX_TRAVEL_MIN)) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = valid) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private val QUICK_CHOICES = listOf(30, 45, 60, 90, 120)

@Preview(showBackground = true)
@Composable
private fun WhenToLeavePreview() {
    FlyTrackTheme {
        Column(Modifier.padding(16.dp)) {
            WhenToLeaveContent(
                WhenToLeaveUiState.Ready(
                    plan = LeavePlan(
                        leaveBy = Instant.parse("2026-11-25T00:10:00Z"),
                        departure = Instant.parse("2026-11-25T03:20:00Z"),
                        depTz = "Asia/Kolkata",
                        travelMinutes = 60,
                        bufferMinutes = 170,
                        international = false,
                        fog = true,
                        peak = true,
                    ),
                    depIata = "DEL",
                    depCity = "New Delhi",
                    directionsUrl = "https://www.google.com/maps/dir/?api=1&destination=DEL",
                    reminderAhead = true,
                ),
                onTravelMinutes = {},
            )
            WhenToLeaveContent(WhenToLeaveUiState.Locked, onTravelMinutes = {})
        }
    }
}
