package app.flytrack.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.flytrack.R

@Composable
private fun StateBlock(
    title: String,
    message: String?,
    icon: Painter?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                modifier = Modifier.widthIn(max = 360.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            PrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

/** "Nothing here yet" block with an optional call to action. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: Painter? = painterResource(R.drawable.ic_plane),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateBlock(title, message, icon, actionLabel, onAction, modifier)
}

/** "Something went wrong" block. Announced by TalkBack when it appears. Shows "Try again" when [onRetry] is given. */
@Composable
fun ErrorState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: Painter? = painterResource(R.drawable.ic_warning),
    retryLabel: String = stringResource(R.string.action_retry),
    onRetry: (() -> Unit)? = null,
) {
    StateBlock(
        title = title,
        message = message,
        icon = icon,
        actionLabel = retryLabel.takeIf { onRetry != null },
        onAction = onRetry,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** "No internet" variant of [ErrorState]. */
@Composable
fun OfflineState(
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    ErrorState(
        title = stringResource(R.string.state_offline_title),
        message = stringResource(R.string.state_offline_message),
        modifier = modifier,
        icon = painterResource(R.drawable.ic_cloud_off),
        onRetry = onRetry,
    )
}

/** Centered spinner with an optional line of text. */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String? = stringResource(R.string.state_loading),
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        if (label != null) {
            Spacer(Modifier.height(16.dp))
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Full "backend not configured" explanation, for a screen whose command returned `NotConfigured`.
 * (The app shell also shows [BackendNotConfiguredBanner] on every screen in that case.)
 */
@Composable
fun BackendNotConfiguredState(modifier: Modifier = Modifier) {
    ErrorState(
        title = stringResource(R.string.state_not_configured_title),
        message = stringResource(R.string.state_not_configured_message),
        modifier = modifier,
        icon = painterResource(R.drawable.ic_info),
    )
}

/** One-line strip shown by the app shell above all screens while the build has no backend keys. */
@Composable
fun BackendNotConfiguredBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painter = painterResource(R.drawable.ic_info), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text = stringResource(R.string.state_not_configured_banner), style = MaterialTheme.typography.bodySmall)
        }
    }
}
