package app.flytrack.feature.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.nav.LocalAppNavigator
import java.time.LocalTime

/** Settings rows for quiet hours and the alert language (stateful; [SettingsRoute] passes it in as a slot). */
@Composable
fun AlertPrefsSection(viewModel: AlertPrefsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.saveFailed) {
        if (state.saveFailed) {
            Toast.makeText(context, R.string.settings_alert_prefs_failed, Toast.LENGTH_SHORT).show()
            viewModel.saveFailedShown()
        }
    }
    AlertPrefsContent(
        state = state,
        onQuietEnabled = viewModel::setQuietHoursEnabled,
        onQuietStart = viewModel::setQuietStart,
        onQuietEnd = viewModel::setQuietEnd,
        onLanguage = viewModel::setLanguage,
    )
}

@Composable
fun AlertPrefsContent(
    state: AlertPrefsUiState,
    onQuietEnabled: (Boolean) -> Unit,
    onQuietStart: (LocalTime) -> Unit,
    onQuietEnd: (LocalTime) -> Unit,
    onLanguage: (AlertLanguage) -> Unit,
) {
    val prefs = state.prefs ?: AlertPrefs()
    val enabled = state.editable
    Column {
        SectionCaption(stringResource(R.string.settings_section_quiet_hours))
        state.problem?.let { problem ->
            Text(
                text = stringResource(
                    when (problem) {
                        AlertPrefsProblem.OFFLINE -> R.string.settings_alert_prefs_offline
                        AlertPrefsProblem.NOT_CONFIGURED -> R.string.settings_alert_prefs_unavailable
                        AlertPrefsProblem.FAILED -> R.string.settings_failed
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        val quietTitle = stringResource(R.string.settings_quiet_title)
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(quietTitle, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_quiet_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = prefs.quietHoursEnabled,
                onCheckedChange = onQuietEnabled,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = quietTitle },
            )
        }
        if (prefs.quietHoursEnabled) {
            TimeRow(
                label = stringResource(R.string.settings_quiet_start),
                pickerTitle = stringResource(R.string.settings_quiet_pick_start),
                time = prefs.quietStart,
                enabled = enabled,
                onPick = onQuietStart,
            )
            TimeRow(
                label = stringResource(R.string.settings_quiet_end),
                pickerTitle = stringResource(R.string.settings_quiet_pick_end),
                time = prefs.quietEnd,
                enabled = enabled,
                onPick = onQuietEnd,
            )
            Text(
                text = stringResource(R.string.settings_quiet_zone, prefs.quietTz),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        HairlineDivider()
        Spacer(Modifier.height(16.dp))
        SectionCaption(stringResource(R.string.settings_section_alert_language))
        Text(
            text = stringResource(R.string.settings_alert_language_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        val options = listOf(
            AlertLanguage.EN to R.string.settings_alert_language_en,
            AlertLanguage.HI to R.string.settings_alert_language_hi,
            AlertLanguage.HINGLISH to R.string.settings_alert_language_hinglish,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = prefs.language == value,
                    onClick = { onLanguage(value) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(stringResource(label)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(label: String, pickerTitle: String, time: LocalTime, enabled: Boolean, onPick: (LocalTime) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val shown = formatPgTime(time)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, onClickLabel = stringResource(R.string.settings_quiet_time_open)) { picking = true }
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(shown, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    if (picking) {
        val pickerState = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(pickerTitle) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    onPick(LocalTime.of(pickerState.hour, pickerState.minute))
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** "Passport and Year in Review" row of Settings. */
@Composable
fun PassportSettingsEntry() {
    val navigator = LocalAppNavigator.current
    Column {
        SectionCaption(stringResource(R.string.settings_section_travel))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clickable { navigator.openPassport() }
                .semantics(mergeDescendants = true) {}
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_open_passport), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_open_passport_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
