package app.flytrack.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.flytrack.core.model.FlightStatusTone

// "Midnight navy and platinum" from docs/design-brief.md: navy ink on porcelain, platinum accents.
// Dark is midnight navy with platinum as the action colour. Contrast ratios are in the brief.

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1B335F),
    onPrimary = Color(0xFFF4F2ED),
    primaryContainer = Color(0xFFDEE6F4),
    onPrimaryContainer = Color(0xFF172849),
    inversePrimary = Color(0xFFD3D7DE),
    secondary = Color(0xFF4F5D76),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E7EF),
    onSecondaryContainer = Color(0xFF172849),
    tertiary = Color(0xFF555B66),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6E8EC),
    onTertiaryContainer = Color(0xFF3A3F48),
    error = Color(0xFF9E2B3B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6DFE2),
    onErrorContainer = Color(0xFF6E1A26),
    background = Color(0xFFF4F2ED),
    onBackground = Color(0xFF172849),
    surface = Color(0xFFF4F2ED),
    onSurface = Color(0xFF172849),
    surfaceVariant = Color(0xFFEAE7E1),
    onSurfaceVariant = Color(0xFF4F5D76),
    surfaceTint = Color(0xFF1B335F),
    inverseSurface = Color(0xFF152135),
    inverseOnSurface = Color(0xFFEFECE5),
    outline = Color(0xFF7F8B9F),
    outlineVariant = Color(0xFFDDD9D1),
    scrim = Color(0xFF0E1625),
    surfaceBright = Color(0xFFFDFBF8),
    surfaceDim = Color(0xFFE3E0DA),
    surfaceContainerLowest = Color(0xFFFEFDFB),
    surfaceContainerLow = Color(0xFFFDFBF8),
    surfaceContainer = Color(0xFFEEEBE6),
    surfaceContainerHigh = Color(0xFFEAE7E1),
    surfaceContainerHighest = Color(0xFFE3E0DA),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFD3D7DE),
    onPrimary = Color(0xFF0E1625),
    primaryContainer = Color(0xFF1B335F),
    onPrimaryContainer = Color(0xFFEFECE5),
    inversePrimary = Color(0xFF1B335F),
    secondary = Color(0xFFAAB4C6),
    onSecondary = Color(0xFF152135),
    secondaryContainer = Color(0xFF27334A),
    onSecondaryContainer = Color(0xFFE5E9F0),
    tertiary = Color(0xFFDDE0E6),
    onTertiary = Color(0xFF1C1F24),
    tertiaryContainer = Color(0xFF3A3E46),
    onTertiaryContainer = Color(0xFFE6E8EC),
    error = Color(0xFFF0A2A9),
    onError = Color(0xFF4A1219),
    errorContainer = Color(0xFF6B2430),
    onErrorContainer = Color(0xFFF6DFE2),
    background = Color(0xFF0E1625),
    onBackground = Color(0xFFEFECE5),
    surface = Color(0xFF0E1625),
    onSurface = Color(0xFFEFECE5),
    surfaceVariant = Color(0xFF27334A),
    onSurfaceVariant = Color(0xFFAAB4C6),
    surfaceTint = Color(0xFFD3D7DE),
    inverseSurface = Color(0xFFEFECE5),
    inverseOnSurface = Color(0xFF172849),
    outline = Color(0xFF616E85),
    outlineVariant = Color(0xFF27334A),
    scrim = Color(0xFF060B13),
    surfaceBright = Color(0xFF293449),
    surfaceDim = Color(0xFF0E1625),
    surfaceContainerLowest = Color(0xFF0B121F),
    surfaceContainerLow = Color(0xFF121B2C),
    surfaceContainer = Color(0xFF152032),
    surfaceContainerHigh = Color(0xFF1C273B),
    surfaceContainerHighest = Color(0xFF253044),
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
    active = StatusColor(content = Color(0xFF555B66), container = Color(0xFFE6E8EC), onContainer = Color(0xFF3A3F48)),
    estimated = StatusColor(
        content = Color(0xFF4F5D76),
        container = Color.Transparent,
        onContainer = Color(0xFF4F5D76),
        outline = Color(0xFF7F8B9F),
    ),
)

internal val DarkStatusColors = FlightStatusColors(
    onTime = StatusColor(content = Color(0xFF8FCBA8), container = Color(0xFF22402F), onContainer = Color(0xFFD6EEDF)),
    delayed = StatusColor(content = Color(0xFFE7BE7A), container = Color(0xFF45321A), onContainer = Color(0xFFF5E6CF)),
    cancelled = StatusColor(content = Color(0xFFF0A2A9), container = Color(0xFF4A2128), onContainer = Color(0xFFF8D9DC)),
    active = StatusColor(content = Color(0xFFD3D7DE), container = Color(0xFF3A3E46), onContainer = Color(0xFFEEF0F3)),
    estimated = StatusColor(
        content = Color(0xFFAAB4C6),
        container = Color.Transparent,
        onContainer = Color(0xFFAAB4C6),
        outline = Color(0xFF616E85),
    ),
)

internal val LocalFlightStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * Platinum for small accent TEXT (uppercase captions). Decorative platinum (#B4B9C2) is 1.8:1 on
 * porcelain, so light uses dark platinum (6.1:1); dark uses light platinum (12.5:1 on midnight navy).
 */
internal val LightAccentText = Color(0xFF555B66)
internal val DarkAccentText = Color(0xFFD3D7DE)

internal val LocalAccentText = staticCompositionLocalOf { LightAccentText }
