package app.flytrack.push

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.flytrack.R
import app.flytrack.data.repo.SettingsStore
import app.flytrack.ui.components.SecondaryButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Asks for notification permission once, and afterwards points at system settings when alerts
 * are blocked. Hosted on the flights screen.
 */
@Composable
fun NotificationPermissionPrompt(
    viewModel: NotificationPermissionViewModel = hiltViewModel(),
) {
    val asked by viewModel.asked.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var runtimeGranted by remember {
        mutableStateOf(isNotificationPermissionGranted(context))
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        runtimeGranted = granted
        viewModel.markAsked()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        runtimeGranted = isNotificationPermissionGranted(context)
        viewModel.refresh()
    }

    val showRationale = asked == false && shouldShowNotificationRationale(
        sdkInt = Build.VERSION.SDK_INT,
        runtimeGranted = runtimeGranted,
        alreadyAsked = false,
    )
    if (showRationale) {
        AlertDialog(
            onDismissRequest = viewModel::markAsked,
            title = { Text(stringResource(R.string.push_permission_title)) },
            text = { Text(stringResource(R.string.push_permission_message)) },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.markAsked()
                    }
                }) { Text(stringResource(R.string.push_permission_allow)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::markAsked) { Text(stringResource(R.string.push_permission_later)) }
            },
        )
    }

    val blocked = health.notificationsBlocked || health.anyChannelBlocked
    if (!showRationale && asked == true && blocked) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.push_permission_blocked_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.push_permission_blocked_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            SecondaryButton(
                text = stringResource(R.string.push_permission_open_settings),
                onClick = viewModel::openNotificationSettings,
            )
        }
    }
}

internal fun isNotificationPermissionGranted(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

@HiltViewModel
class NotificationPermissionViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val healthSource: NotificationHealthSource,
) : ViewModel() {

    /** Null until DataStore has emitted, so the dialog does not flash before we know we already asked. */
    val asked: StateFlow<Boolean?> = settings.notificationPermissionAsked
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val healthState = MutableStateFlow(healthSource.read())
    val health: StateFlow<NotificationHealth> = healthState.asStateFlow()

    fun refresh() {
        healthState.value = healthSource.read()
    }

    fun markAsked() {
        viewModelScope.launch { settings.setNotificationPermissionAsked(true) }
    }

    fun openNotificationSettings() {
        healthSource.openNotificationSettings()
    }
}
