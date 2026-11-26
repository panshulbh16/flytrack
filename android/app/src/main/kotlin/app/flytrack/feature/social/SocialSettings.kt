package app.flytrack.feature.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.flytrack.R
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.billing.ui.familyProText
import app.flytrack.feature.friends.RemoteResult
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.nav.LocalAppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A problem with the last switch change; the switch shows the server's value again. */
enum class SocialMessage { OFFLINE, FAILED }

data class SocialSettingsUiState(
    val crewMode: Boolean = false,
    val sharePassport: Boolean = true,
    /** False until the server answered once: the switches wait for the real values. */
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val message: SocialMessage? = null,
)

/** Crew mode (B12) and Passport sharing (B10) switches. */
@HiltViewModel
class SocialSettingsViewModel @Inject constructor(
    private val flags: ProfileFlagsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SocialSettingsUiState())
    val uiState: StateFlow<SocialSettingsUiState> = _uiState.asStateFlow()

    init {
        // The cached crew flag answers at once; the server read below confirms both switches.
        viewModelScope.launch { flags.crewMode.collect { on -> _uiState.update { it.copy(crewMode = on) } } }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { apply(flags.refresh(), reportErrors = false) }
    }

    fun setCrewMode(enabled: Boolean) = change { flags.setCrewMode(enabled) }

    fun setSharePassport(enabled: Boolean) = change { flags.setSharePassport(enabled) }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    private fun change(call: suspend () -> RemoteResult<ProfileFlags>) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch { apply(call(), reportErrors = true) }
    }

    private fun apply(result: RemoteResult<ProfileFlags>, reportErrors: Boolean) {
        _uiState.update { state ->
            when (result) {
                is RemoteResult.Ok -> state.copy(
                    crewMode = result.value.crewMode,
                    sharePassport = result.value.sharePassportWithFriends,
                    loaded = true,
                    busy = false,
                )
                RemoteResult.Offline -> state.copy(busy = false, message = SocialMessage.OFFLINE.takeIf { reportErrors })
                RemoteResult.NotConfigured -> state.copy(busy = false)
                is RemoteResult.Refused, is RemoteResult.Failed -> state.copy(busy = false, message = SocialMessage.FAILED.takeIf { reportErrors })
            }
        }
    }
}

/**
 * Settings rows of P3: Family sharing (B13), crew mode (B12) and Passport sharing (B10).
 * Placed in Settings' travel section, after the Friends rows.
 */
@Composable
fun SocialSettingsSection(modifier: Modifier = Modifier, viewModel: SocialSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val pro = LocalProState.current
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clickable(onClick = navigator::openFamily)
                .semantics(mergeDescendants = true) {}
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_social_family), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.family_settings_row), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (pro.isViaFamily) familyProText(pro.familyOwnerName) else stringResource(R.string.family_settings_row_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        SectionCaption(stringResource(R.string.social_section))
        SwitchRow(
            title = stringResource(R.string.social_share_passport_title),
            message = stringResource(R.string.social_share_passport_message),
            checked = state.sharePassport,
            enabled = state.loaded && !state.busy,
            onChange = viewModel::setSharePassport,
        )
        SwitchRow(
            title = stringResource(R.string.social_crew_title),
            message = stringResource(R.string.social_crew_message),
            checked = state.crewMode,
            enabled = !state.busy,
            onChange = viewModel::setCrewMode,
        )
        if (state.crewMode) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Row(Modifier.padding(12.dp)) {
                    Icon(painterResource(R.drawable.ic_bell_off), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.social_crew_muted), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.message?.let { message ->
            Text(
                stringResource(if (message == SocialMessage.OFFLINE) R.string.social_message_offline else R.string.social_message_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, message: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
