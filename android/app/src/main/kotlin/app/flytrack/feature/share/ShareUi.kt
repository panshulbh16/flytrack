package app.flytrack.feature.share

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SectionCaption

/** "Share flight" block of the detail screen: WhatsApp first, the share sheet otherwise. */
@Composable
fun ShareFlightBlock(isSharing: Boolean, onShare: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionCaption(text = stringResource(R.string.share_action))
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.share_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = stringResource(R.string.share_action_whatsapp),
            onClick = onShare,
            loading = isSharing,
            enabled = !isSharing,
            icon = painterResource(R.drawable.ic_share),
        )
    }
}

/** Hands a ready [request] to WhatsApp or the share sheet once, then reports it handled. */
@Composable
fun ShareFlightEffect(request: ShareRequest?, onHandled: (ShareRequest) -> Unit) {
    val context = LocalContext.current
    val hinglish = stringResource(R.string.share_text_hinglish)
    val english = stringResource(R.string.share_text_english)
    val chooserTitle = stringResource(R.string.share_chooser_title)
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        val flight = request.flight
        val text = shareMessage(hinglish, english, flight, request.url)
        val subject = context.getString(R.string.share_subject, "${flight.carrierIata} ${flight.flightNumber}")
        if (!shareFlightText(context, text, subject, chooserTitle, request.target)) {
            Toast.makeText(context, R.string.share_message_no_app, Toast.LENGTH_LONG).show()
        }
        onHandled(request)
    }
}
