package app.flytrack.feature.friends

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.EmptyState
import app.flytrack.ui.theme.FlyTrackTheme

/** "Enter a friend code": type or paste the code (or the whole invite message). */
@Composable
fun AcceptFriendInviteRoute(prefillCode: String?, onDone: () -> Unit, onBack: () -> Unit) {
    val viewModel = hiltViewModel<AcceptInviteViewModel>()
    LaunchedEffect(prefillCode) { viewModel.prefill(prefillCode) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AcceptFriendInviteScreen(
        state = state,
        onInput = viewModel::onInput,
        onPaste = {
            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrBlank()) viewModel.onInput(text)
        },
        onSubmit = viewModel::submit,
        onDone = onDone,
        onBack = onBack,
    )
}

@Composable
fun AcceptFriendInviteScreen(
    state: AcceptInviteUiState,
    onInput: (String) -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.friends_accept_title), onBack = onBack) }) { padding ->
        val accepted = state.accepted
        if (accepted != null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = stringResource(R.string.friends_accept_done_title, friendName(accepted.displayName)),
                    message = stringResource(R.string.friends_accept_done_message),
                    icon = painterResource(R.drawable.ic_person),
                    actionLabel = stringResource(R.string.action_done),
                    onAction = onDone,
                )
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.friends_accept_message), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.input,
                onValueChange = onInput,
                label = { Text(stringResource(R.string.friends_code_label)) },
                placeholder = { Text("ABCD-EFGH") },
                singleLine = true,
                isError = state.error != null,
                supportingText = state.error?.let { error ->
                    {
                        Text(
                            acceptErrorText(error),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                textStyle = FlyTrackTheme.type.numeralSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onPaste) { Text(stringResource(R.string.friends_action_paste)) }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = stringResource(R.string.friends_action_add_friend),
                onClick = onSubmit,
                enabled = state.canSubmit,
                loading = state.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.friends_accept_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun acceptErrorText(error: AcceptError): String = stringResource(
    when (error) {
        AcceptError.INVALID_CODE -> R.string.friends_error_invalid_code
        AcceptError.OWN_CODE -> R.string.friends_error_own_code
        AcceptError.ALREADY_FRIENDS -> R.string.friends_error_already_friends
        AcceptError.FRIEND_LIMIT -> R.string.friends_error_limit
        AcceptError.OFFLINE -> R.string.friends_message_offline
        AcceptError.FAILED -> R.string.friends_message_failed
    },
)

@Preview(showBackground = true)
@Composable
private fun AcceptPreview() {
    FlyTrackTheme {
        AcceptFriendInviteScreen(
            AcceptInviteUiState(input = "ABCD-EFG", error = AcceptError.INVALID_CODE),
            onInput = {}, onPaste = {}, onSubmit = {}, onDone = {}, onBack = {},
        )
    }
}
