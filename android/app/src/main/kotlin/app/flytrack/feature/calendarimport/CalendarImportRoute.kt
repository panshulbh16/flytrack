package app.flytrack.feature.calendarimport

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.billing.ui.LocalUpsellsHidden
import app.flytrack.feature.forecast.ProFeatureTeaser
import app.flytrack.ui.components.EmptyState
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.SectionCaption

/**
 * Entry point of the calendar import (P3 A3, Pro). [onAdd] opens add-flight filled in from a suggestion,
 * where the normal search-and-track path runs with source 'calendar'.
 */
@Composable
fun CalendarImportRoute(onAdd: (SuggestionAction) -> Unit, onBack: () -> Unit) {
    val viewModel = hiltViewModel<CalendarImportViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.scan()
        } else {
            val activity = context as? Activity
            val canAskAgain = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
            viewModel.permissionDenied(permanently = !canAskAgain)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshTracked() }
    val startScan = {
        // The permission is asked for here, on the user's tap, and nowhere else.
        if (viewModel.canScanWithoutAsking) viewModel.scan() else launcher.launch(Manifest.permission.READ_CALENDAR)
    }
    CalendarImportScreen(
        state = state,
        isPro = LocalProState.current.isPro,
        onScan = startScan,
        onOpenAppSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        onAdd = onAdd,
        onBack = onBack,
    )
}

@Composable
fun CalendarImportScreen(
    state: CalendarImportState,
    isPro: Boolean,
    onScan: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onAdd: (SuggestionAction) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.calendarimport_title), onBack = onBack) }) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        if (!isPro) {
            Column(modifier.padding(20.dp)) {
                Text(stringResource(R.string.calendarimport_intro), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                if (LocalUpsellsHidden.current) {
                    Text(
                        stringResource(R.string.calendarimport_pro_only),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ProFeatureTeaser(
                        title = stringResource(R.string.calendarimport_teaser_title),
                        body = stringResource(R.string.calendarimport_teaser_body),
                    )
                }
            }
            return@Scaffold
        }
        when (state) {
            CalendarImportState.Intro -> Intro(modifier, onScan)
            is CalendarImportState.PermissionDenied -> ErrorState(
                title = stringResource(R.string.calendarimport_denied_title),
                message = stringResource(R.string.calendarimport_denied_message),
                icon = painterResource(R.drawable.ic_calendar),
                modifier = modifier,
                retryLabel = stringResource(if (state.permanently) R.string.calendarimport_open_settings else R.string.calendarimport_try_again),
                onRetry = if (state.permanently) onOpenAppSettings else onScan,
            )
            CalendarImportState.Scanning -> LoadingState(modifier, label = stringResource(R.string.calendarimport_scanning))
            CalendarImportState.Unavailable -> ErrorState(
                title = stringResource(R.string.calendarimport_unavailable),
                modifier = modifier,
                retryLabel = stringResource(R.string.action_retry),
                onRetry = onScan,
            )
            is CalendarImportState.Results ->
                if (state.suggestions.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.calendarimport_empty_title),
                        message = pluralStringResource(R.plurals.calendarimport_empty_message, state.eventsRead, state.eventsRead),
                        icon = painterResource(R.drawable.ic_calendar),
                        modifier = modifier,
                        actionLabel = stringResource(R.string.calendarimport_scan_again),
                        onAction = onScan,
                    )
                } else {
                    Results(state, modifier, onAdd, onScan)
                }
        }
    }
}

@Composable
private fun Intro(modifier: Modifier, onScan: () -> Unit) {
    Column(modifier.padding(20.dp)) {
        Text(stringResource(R.string.calendarimport_intro), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.calendarimport_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = stringResource(R.string.calendarimport_scan),
            onClick = onScan,
            icon = painterResource(R.drawable.ic_calendar),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Results(state: CalendarImportState.Results, modifier: Modifier, onAdd: (SuggestionAction) -> Unit, onScan: () -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item(key = "header") {
            SectionCaption(pluralStringResource(R.plurals.calendarimport_found, state.suggestions.size, state.suggestions.size))
            Text(
                stringResource(R.string.calendarimport_results_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (state.truncated) {
                Text(
                    stringResource(R.string.calendarimport_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        items(state.suggestions, key = { it.id }) { suggestion ->
            HairlineDivider()
            SuggestionRow(suggestion, onAdd)
        }
        item(key = "footer") {
            HairlineDivider()
            SecondaryButton(
                text = stringResource(R.string.calendarimport_scan_again),
                onClick = onScan,
                icon = painterResource(R.drawable.ic_refresh),
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun SuggestionRow(s: CalendarSuggestion, onAdd: (SuggestionAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(suggestionTitle(s), style = MaterialTheme.typography.titleMedium)
            val details = listOfNotNull(
                s.date?.let { FlightTimeFormat.fullDate(it) },
                s.departTime?.let { stringResource(R.string.calendarimport_departs_at, it) },
                s.flight?.takeIf { s.from != null || s.to != null },
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.leg != null && s.legCount != null) {
                Text(
                    stringResource(R.string.calendarimport_leg_of, s.leg, s.legCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            issueText(s)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                s.alreadyTracked -> {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.calendarimport_tracked), style = MaterialTheme.typography.labelLarge)
                }
                s.action is SuggestionAction.NeedsDetails -> Text(
                    stringResource(R.string.calendarimport_needs_details),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> PrimaryButton(
                    text = stringResource(
                        if (s.action is SuggestionAction.ByRoute) R.string.calendarimport_action_find else R.string.calendarimport_action_add,
                    ),
                    onClick = { onAdd(s.action) },
                    icon = painterResource(if (s.action is SuggestionAction.ByRoute) R.drawable.ic_search else R.drawable.ic_add),
                )
            }
        }
    }
}

@Composable
private fun suggestionTitle(s: CalendarSuggestion): String {
    val from = s.from
    val to = s.to
    return when {
        from != null && to != null -> stringResource(R.string.calendarimport_title_route, from.iata, to.iata, from.city, to.city)
        to != null -> stringResource(R.string.calendarimport_title_to, to.city)
        from != null -> stringResource(R.string.calendarimport_title_from, from.city)
        s.flight != null -> s.flight
        else -> stringResource(R.string.calendarimport_title_unknown)
    }
}

/** The first thing worth checking, in words. */
@Composable
private fun issueText(s: CalendarSuggestion): String? = when {
    ReviewIssue.CONFLICT in s.issues -> stringResource(R.string.calendarimport_issue_conflict)
    ReviewIssue.SAME_AIRPORT in s.issues -> stringResource(R.string.calendarimport_issue_same_airport)
    s.flight == null && s.action is SuggestionAction.ByRoute -> stringResource(R.string.calendarimport_issue_no_number)
    ReviewIssue.UNTIMED in s.issues -> stringResource(R.string.calendarimport_issue_untimed)
    ReviewIssue.STATED_TIMES in s.issues -> stringResource(R.string.calendarimport_issue_stated_times)
    else -> null
}
