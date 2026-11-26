package app.flytrack.feature.calendar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.feature.friends.copyText
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.SectionCaption

/** "Add to calendar" on the detail screen: one event through the system calendar's insert screen. */
@Composable
fun AddToCalendarButton(flightId: String, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<AddToCalendarViewModel, AddToCalendarViewModel.Factory>(key = "calendar:$flightId") { it.create(flightId) }
    val flight by viewModel.flight.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val terminalTemplate = stringResource(R.string.calendar_location_terminal)
    val description = stringResource(R.string.calendar_event_description)
    val event = flight?.let { calendarEventFor(it, terminalTemplate, description) } ?: return
    SecondaryButton(
        text = stringResource(R.string.calendar_action_add),
        onClick = { openInsertEvent(context, event) },
        icon = painterResource(R.drawable.ic_calendar),
        modifier = modifier,
    )
}

private fun openInsertEvent(context: Context, event: CalendarEventSpec) {
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMillis)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMillis)
        .putExtra(CalendarContract.Events.TITLE, event.title)
        .putExtra(CalendarContract.Events.DESCRIPTION, event.description)
        .putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
    event.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
    event.timeZone?.let { intent.putExtra(CalendarContract.Events.EVENT_TIMEZONE, it) }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.calendar_no_app, Toast.LENGTH_LONG).show()
    }
}

/**
 * Settings → "Calendar": subscribe in Google Calendar, copy the feed URL (Apple Calendar, Outlook), share it,
 * or reset it when it leaked. The URL is secret: anyone with it can read the user's flight list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarSettingsSection(modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<CalendarFeedViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRotate by rememberSaveable { mutableStateOf(false) }

    val copyLabel = stringResource(R.string.calendar_feed_label)
    val shareTitle = stringResource(R.string.calendar_share_title)
    LaunchedEffect(state.pending) {
        val (action, url) = state.pending ?: return@LaunchedEffect
        when (action) {
            FeedAction.SUBSCRIBE_GOOGLE -> openUrl(context, googleCalendarSubscribeUrl(url))
            FeedAction.COPY -> copyText(context, copyLabel, url, R.string.calendar_copied)
            FeedAction.SHARE -> try {
                context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, webcalUrl(url)), shareTitle),
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.calendar_no_app, Toast.LENGTH_LONG).show()
            }
        }
        viewModel.actionHandled()
    }
    val messageText = when (state.message) {
        null -> null
        FeedMessage.OFFLINE -> stringResource(R.string.calendar_message_offline)
        FeedMessage.FAILED -> stringResource(R.string.calendar_message_failed)
        FeedMessage.NOT_CONFIGURED -> stringResource(R.string.calendar_message_not_configured)
        FeedMessage.ROTATED -> stringResource(R.string.calendar_message_rotated)
    }
    LaunchedEffect(state.message) {
        if (messageText == null) return@LaunchedEffect
        Toast.makeText(context, messageText, Toast.LENGTH_LONG).show()
        viewModel.messageShown()
    }

    Column(modifier) {
        SectionCaption(stringResource(R.string.calendar_section))
        Text(
            stringResource(R.string.calendar_feed_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        PrimaryButton(
            text = stringResource(R.string.calendar_action_subscribe_google),
            onClick = { viewModel.act(FeedAction.SUBSCRIBE_GOOGLE) },
            loading = state.isWorking,
            enabled = !state.isWorking,
            icon = painterResource(R.drawable.ic_calendar),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = stringResource(R.string.calendar_action_copy),
                onClick = { viewModel.act(FeedAction.COPY) },
                enabled = !state.isWorking,
            )
            SecondaryButton(
                text = stringResource(R.string.calendar_action_share),
                onClick = { viewModel.act(FeedAction.SHARE) },
                enabled = !state.isWorking,
                icon = painterResource(R.drawable.ic_share),
            )
        }
        Text(
            stringResource(R.string.calendar_apple_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (state.url != null) {
            TextButton(onClick = { confirmRotate = true }, enabled = !state.isWorking) {
                Text(stringResource(R.string.calendar_action_rotate))
            }
        }
    }

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text(stringResource(R.string.calendar_rotate_title)) },
            text = { Text(stringResource(R.string.calendar_rotate_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRotate = false
                    viewModel.rotate()
                }) { Text(stringResource(R.string.calendar_action_rotate)) }
            },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.calendar_no_app, Toast.LENGTH_LONG).show()
    }
}
