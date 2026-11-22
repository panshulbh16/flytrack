package app.flytrack.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.ui.theme.FlyTrackTheme

/** The brief's 1px hairline, used instead of heavy cards and shadows. */
@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = color)
}

/** Uppercase terracotta caption above a section ("NEXT TAKEOFF"). Announced as a heading. */
@Composable
fun SectionCaption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FlyTrackTheme.accentText,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier.semantics { heading() },
        style = FlyTrackTheme.type.caption,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Small rounded status badge. The [text] carries the meaning ("Delayed 25 min"); the colour of
 * [tone] only reinforces it. [FlightStatusTone.ESTIMATED] is drawn as an outline.
 */
@Composable
fun StatusPill(
    text: String,
    tone: FlightStatusTone,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
) {
    val colors = FlyTrackTheme.statusColors.forTone(tone)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colors.container,
        contentColor = colors.onContainer,
        border = colors.outline?.let { BorderStroke(1.dp, it) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(painter = icon, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The key status word of a flight (*Delayed*, *On time*, *Boarding*) in italic serif, coloured by [tone]. */
@Composable
fun StatusWord(
    text: String,
    tone: FlightStatusTone,
    modifier: Modifier = Modifier,
    style: TextStyle = FlyTrackTheme.type.statusWord,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = FlyTrackTheme.statusColors.forTone(tone).content,
    )
}

/**
 * A clock time ("18:50") in the tabular numeral style.
 * @param struckThrough for a time that no longer applies (the old schedule next to the new estimate);
 *   pass a [contentDescription] such as "Scheduled 18:50, no longer valid" so the meaning is not visual only
 */
@Composable
fun NumeralTimeText(
    time: String,
    modifier: Modifier = Modifier,
    style: TextStyle = FlyTrackTheme.type.numeral,
    color: Color = LocalContentColor.current,
    struckThrough: Boolean = false,
    contentDescription: String? = null,
) {
    Text(
        text = time,
        modifier = if (contentDescription != null) {
            modifier.semantics { this.contentDescription = contentDescription }
        } else {
            modifier
        },
        style = style,
        color = color,
        textDecoration = if (struckThrough) TextDecoration.LineThrough else null,
        maxLines = 1,
        softWrap = false,
    )
}

private val ButtonMinHeight = 48.dp
private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

@Composable
private fun RowScope.ButtonContent(text: String, loading: Boolean, icon: Painter?) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = LocalContentColor.current,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(10.dp))
    } else if (icon != null) {
        Icon(painter = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Filled forest-green button for the one main action of a screen. Disabled while [loading]. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: Painter? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ButtonMinHeight),
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.small,
        contentPadding = ButtonPadding,
    ) {
        ButtonContent(text, loading, icon)
    }
}

/** Outlined button for secondary actions. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: Painter? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ButtonMinHeight),
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        contentPadding = ButtonPadding,
    ) {
        ButtonContent(text, loading, icon)
    }
}

/** A 48dp icon button. [contentDescription] is required: an icon alone carries no meaning for TalkBack. */
@Composable
fun IconAction(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(painter = icon, contentDescription = contentDescription)
    }
}

/** Flat top bar on the paper background with an optional back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlyTrackTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                IconAction(
                    icon = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}
