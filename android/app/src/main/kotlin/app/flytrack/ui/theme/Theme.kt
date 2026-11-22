package app.flytrack.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Brief: 4dp (xs), 6dp (buttons/inputs), 10dp (cards), 14dp (large), 16dp (sheet top); chips use CircleShape. */
internal val FlyTrackShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * The app theme. Light (paper) is the primary look; dark is the brief's "Night" palette.
 * Dynamic colour is deliberately not used: the brand palette is the identity.
 */
@Composable
fun FlyTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalFlightStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
        LocalAccentText provides if (darkTheme) DarkAccentText else LightAccentText,
        LocalFlyTrackExtraType provides FlyTrackExtras,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = FlyTrackTypography,
            shapes = FlyTrackShapes,
            content = content,
        )
    }
}

/** Access to the theme's extensions; Material roles stay on `MaterialTheme`. */
object FlyTrackTheme {
    /** On-time / delayed / cancelled / active / estimated colour families. */
    val statusColors: FlightStatusColors
        @Composable @ReadOnlyComposable get() = LocalFlightStatusColors.current

    /** numeral, statusWord and caption text styles. */
    val type: FlyTrackExtraType
        @Composable @ReadOnlyComposable get() = LocalFlyTrackExtraType.current

    /** Terracotta that is legible as small text in both themes. */
    val accentText: Color
        @Composable @ReadOnlyComposable get() = LocalAccentText.current
}
