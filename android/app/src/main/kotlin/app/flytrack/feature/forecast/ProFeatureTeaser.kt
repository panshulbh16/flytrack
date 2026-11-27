package app.flytrack.feature.forecast

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.feature.billing.ui.LocalPaywallLauncher
import app.flytrack.feature.billing.ui.LocalUpsellsHidden
import app.flytrack.ui.theme.FlyTrackTheme

/**
 * A Pro feature the user does not have: what it does in one line, and the way to the paywall. Same look
 * as the billing teaser, with the feature's own words (forecast, when to leave, email forwarding).
 */
@Composable
fun ProFeatureTeaser(title: String, body: String, modifier: Modifier = Modifier) {
    // P3 B12: crew mode hides every upsell.
    if (LocalUpsellsHidden.current) return
    val launcher = LocalPaywallLauncher.current
    Surface(
        onClick = launcher::openPaywall,
        modifier = modifier.fillMaxWidth().semantics { role = Role.Button },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.billing_badge_pro).uppercase(), style = FlyTrackTheme.type.caption)
                Spacer(Modifier.height(2.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.billing_action_see_plans), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
