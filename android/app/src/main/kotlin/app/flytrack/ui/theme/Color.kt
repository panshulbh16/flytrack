package app.flytrack.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.flytrack.core.model.FlightStatusTone

// "Aubergine and champagne" from docs/design-brief.md: plum ink on warm ivory, champagne gold accents.
// Dark is plum-black with gold as the action colour. Contrast ratios are in the brief.

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF4B2347),
    onPrimary = Color(0xFFF7F1E8),
    primaryContainer = Color(0xFFEFE1EA),
    onPrimaryContainer = Color(0xFF3A1D38),
    inversePrimary = Color(0xFFD9BC85),
    secondary = Color(0xFF6B5468),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEEE4E6),
    onSecondaryContainer = Color(0xFF3A1D38),
    tertiary = Color(0xFF7E5F22),
    onTertiary = Color(0xFFFFFBF4),
    tertiaryContainer = Color(0xFFF3E7CF),
    onTertiaryContainer = Color(0xFF5C4416),
    error = Color(0xFF9E2B3B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6DFE2),
    onErrorContainer = Color(0xFF6E1A26),
    background = Color(0xFFF7F1E8),
    onBackground = Color(0xFF3A1D38),
    surface = Color(0xFFF7F1E8),
    onSurface = Color(0xFF3A1D38),
    surfaceVariant = Color(0xFFEFE6DA),
    onSurfaceVariant = Color(0xFF6B5468),
    surfaceTint = Color(0xFF4B2347),
    inverseSurface = Color(0xFF2A1A2C),
    inverseOnSurface = Color(0xFFF3EBDD),
    outline = Color(0xFF988390),
    outlineVariant = Color(0xFFE4D8C6),
    scrim = Color(0xFF1C1220),
    surfaceBright = Color(0xFFFFFBF4),
    surfaceDim = Color(0xFFE9DFD2),
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = Color(0xFFFFFBF4),
    surfaceContainer = Color(0xFFF2EADF),
    surfaceContainerHigh = Color(0xFFEFE6DA),
    surfaceContainerHighest = Color(0xFFE9DFD2),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFD9BC85),
    onPrimary = Color(0xFF1C1220),
    primaryContainer = Color(0xFF4B2347),
    onPrimaryContainer = Color(0xFFF3E7CF),
    inversePrimary = Color(0xFF4B2347),
    secondary = Color(0xFFBFAEBB),
    onSecondary = Color(0xFF2A1A2C),
    secondaryContainer = Color(0xFF3D2C40),
    onSecondaryContainer = Color(0xFFEFE6E9),
    tertiary = Color(0xFFE3C995),
    onTertiary = Color(0xFF2A1A0A),
    tertiaryContainer = Color(0xFF4A3A1E),
    onTertiaryContainer = Color(0xFFF3E7CF),
    error = Color(0xFFF0A2A9),
    onError = Color(0xFF4A1219),
    errorContainer = Color(0xFF6B2430),
    onErrorContainer = Color(0xFFF6DFE2),
    background = Color(0xFF1C1220),
    onBackground = Color(0xFFF3EBDD),
    surface = Color(0xFF1C1220),
    onSurface = Color(0xFFF3EBDD),
    surfaceVariant = Color(0xFF3D2C40),
    onSurfaceVariant = Color(0xFFBFAEBB),
    surfaceTint = Color(0xFFD9BC85),
    inverseSurface = Color(0xFFF3EBDD),
    inverseOnSurface = Color(0xFF3A1D38),
    outline = Color(0xFF7A6679),
    outlineVariant = Color(0xFF3D2C40),
    scrim = Color(0xFF0E0810),
    surfaceBright = Color(0xFF3D2E41),
    surfaceDim = Color(0xFF1C1220),
    surfaceContainerLowest = Color(0xFF170E1A),
    surfaceContainerLow = Color(0xFF221626),
    surfaceContainer = Color(0xFF271A2B),
    surfaceContainerHigh = Color(0xFF2F2133),
    surfaceContainerHighest = Color(0xFF382A3C),
)

/**
 * One flight-status colour family.
 * @property content for a status word or icon directly on the screen background
 * @property container pill/badge background
 * @property onContainer text on [container]; darker than [content] in the light theme so small
 *   pill text reaches WCAG AA contrast, which the brief's raw pairs do not
 * @property outline non-null only for "estimated", which is drawn as an outline instead of a fill
 */
@Immutable
data class StatusColor(
    val content: Color,
    val container: Color,
    val onContainer: Color,
    val outline: Color? = null,
)

/** The brief's extended flight-status palette. Read it with `FlyTrackTheme.statusColors`. */
@Immutable
data class FlightStatusColors(
    val onTime: StatusColor,
    val delayed: StatusColor,
    val cancelled: StatusColor,
    /** Boarding or in the air. */
    val active: StatusColor,
    /** No live data, schedule only: outline style. */
    val estimated: StatusColor,
) {
    fun forTone(tone: FlightStatusTone): StatusColor = when (tone) {
        FlightStatusTone.ON_TIME -> onTime
        FlightStatusTone.DELAYED -> delayed
        FlightStatusTone.CANCELLED -> cancelled
        FlightStatusTone.ACTIVE -> active
        FlightStatusTone.ESTIMATED -> estimated
    }
}

internal val LightStatusColors = FlightStatusColors(
    onTime = StatusColor(content = Color(0xFF2E6A4C), container = Color(0xFFE3EFE7), onContainer = Color(0xFF1F4D36)),
    delayed = StatusColor(content = Color(0xFF8A5512), container = Color(0xFFF5E6CF), onContainer = Color(0xFF6A400B)),
    cancelled = StatusColor(content = Color(0xFF9E2B3B), container = Color(0xFFF6DFE2), onContainer = Color(0xFF741E2A)),
    active = StatusColor(content = Color(0xFF7E5F22), container = Color(0xFFF3E7CF), onContainer = Color(0xFF5C4416)),
    estimated = StatusColor(
        content = Color(0xFF6B5468),
        container = Color.Transparent,
        onContainer = Color(0xFF6B5468),
        outline = Color(0xFF988390),
    ),
)

internal val DarkStatusColors = FlightStatusColors(
    onTime = StatusColor(content = Color(0xFF8FCBA8), container = Color(0xFF22402F), onContainer = Color(0xFFD6EEDF)),
    delayed = StatusColor(content = Color(0xFFE7BE7A), container = Color(0xFF45321A), onContainer = Color(0xFFF5E6CF)),
    cancelled = StatusColor(content = Color(0xFFF0A2A9), container = Color(0xFF4A2128), onContainer = Color(0xFFF8D9DC)),
    active = StatusColor(content = Color(0xFFD9BC85), container = Color(0xFF45361C), onContainer = Color(0xFFF3E7CF)),
    estimated = StatusColor(
        content = Color(0xFFBFAEBB),
        container = Color.Transparent,
        onContainer = Color(0xFFBFAEBB),
        outline = Color(0xFF7A6679),
    ),
)

internal val LocalFlightStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * Champagne gold for small accent TEXT (uppercase captions). Decorative champagne (#C4A26A) is
 * 2.1:1 on ivory, so light uses antique gold (5.3:1); dark uses champagne (9.9:1 on plum-black).
 */
internal val LightAccentText = Color(0xFF7E5F22)
internal val DarkAccentText = Color(0xFFD9BC85)

internal val LocalAccentText = staticCompositionLocalOf { LightAccentText }
