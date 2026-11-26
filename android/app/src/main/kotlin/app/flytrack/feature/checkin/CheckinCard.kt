package app.flytrack.feature.checkin

import android.widget.Toast
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.core.time.relativeTime
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.relativeTimeText
import app.flytrack.ui.theme.FlyTrackTheme
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Web check-in card: "Web check-in opens in 6 h", or "Check in now →" while the window is open.
 * The button opens the airline's page in the browser.
 */
@Composable
fun CheckinCard(model: CheckinUiModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val window = model.window
    val open = window is CheckinWindow.Open
    val closingSoon = window is CheckinWindow.Open && window.closingSoon
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (open) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (open) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(R.drawable.ic_ticket), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.checkin_title).uppercase(),
                    style = FlyTrackTheme.type.caption,
                    modifier = Modifier.weight(1f),
                )
                if (closingSoon) {
                    Text(
                        text = stringResource(R.string.checkin_closing_soon).uppercase(),
                        style = FlyTrackTheme.type.caption,
                        color = FlyTrackTheme.statusColors.delayed.content,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(text = headline(model), style = MaterialTheme.typography.titleMedium)
            detail(model)?.let {
                Spacer(Modifier.height(2.dp))
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            if (!model.verified) {
                Spacer(Modifier.height(6.dp))
                Text(text = stringResource(R.string.checkin_unverified), style = MaterialTheme.typography.bodySmall)
            }
            val url = model.checkinUrl
            if (url != null && window !is CheckinWindow.Closed) {
                Spacer(Modifier.height(12.dp))
                val noBrowser = stringResource(R.string.checkin_no_browser)
                val onClick = {
                    if (!openCheckinPage(context, url)) Toast.makeText(context, noBrowser, Toast.LENGTH_LONG).show()
                }
                if (model.canCheckInNow) {
                    PrimaryButton(text = stringResource(R.string.checkin_now), onClick = onClick)
                } else {
                    SecondaryButton(text = stringResource(R.string.checkin_open_airline_page), onClick = onClick)
                }
            }
        }
    }
}

@Composable
private fun headline(model: CheckinUiModel): String = when (val window = model.window) {
    is CheckinWindow.NotOpenYet -> stringResource(R.string.checkin_opens_in, relativeTimeText(relativeTime(window.opensAt, model.now)))
    is CheckinWindow.Open -> stringResource(R.string.checkin_open)
    is CheckinWindow.Closed -> stringResource(R.string.checkin_closed)
}

@Composable
private fun detail(model: CheckinUiModel): String? = when (val window = model.window) {
    is CheckinWindow.NotOpenYet -> stringResource(
        R.string.checkin_opens_at,
        FlightTimeFormat.weekdayDayMonth(window.opensAt, model.depTz),
        FlightTimeFormat.time(window.opensAt, model.depTz),
    )
    is CheckinWindow.Open -> if (window.closingSoon) {
        stringResource(R.string.checkin_closes_in, relativeTimeText(relativeTime(window.closesAt, model.now)))
    } else {
        stringResource(R.string.checkin_closes_at, FlightTimeFormat.time(window.closesAt, model.depTz))
    }
    is CheckinWindow.Closed -> null
}

private val PREVIEW_NOW = Instant.parse("2026-11-25T06:00:00Z")

@Preview(name = "Check-in opens later", showBackground = true)
@Composable
private fun CheckinNotOpenPreview() {
    FlyTrackTheme(darkTheme = false) {
        CheckinCard(
            CheckinUiModel(
                CheckinWindow.NotOpenYet(PREVIEW_NOW + 6.hours, PREVIEW_NOW + 53.hours),
                "https://www.goindigo.in/web-check-in.html", verified = true, depTz = "Asia/Kolkata", now = PREVIEW_NOW,
            ),
            Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Check-in open, dark", showBackground = true)
@Composable
private fun CheckinOpenPreview() {
    FlyTrackTheme(darkTheme = true) {
        CheckinCard(
            CheckinUiModel(
                CheckinWindow.Open(PREVIEW_NOW - 40.hours, PREVIEW_NOW + 20.minutes, closingSoon = true),
                "https://www.spicejet.com/check-in", verified = false, depTz = "Asia/Kolkata", now = PREVIEW_NOW,
            ),
            Modifier.padding(16.dp),
        )
    }
}
