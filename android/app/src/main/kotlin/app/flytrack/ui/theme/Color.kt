package app.flytrack.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.flytrack.core.model.FlightStatusTone

// Tokens from docs/design-brief.md: warm paper, forest-green ink, terracotta accent.
// Roles the brief leaves open (marked "derived") follow the same hues.

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF244735),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9DFC8),
    onPrimaryContainer = Color(0xFF244735), // derived
    inversePrimary = Color(0xFFA0B99F),
    secondary = Color(0xFF607260),
    onSecondary = Color(0xFFFFFFFF), // derived
    secondaryContainer = Color(0xFFE9EDE3),
    onSecondaryContainer = Color(0xFF244735), // derived
    tertiary = Color(0xFFC86842),
    onTertiary = Color(0xFFFFFFFF), // derived
    tertiaryContainer = Color(0xFFFBF3EA),
    onTertiaryContainer = Color(0xFF8B422A),
    error = Color(0xFFA34831),
    onError = Color(0xFFFFFFFF), // derived
    errorContainer = Color(0xFFF5E6D9),
    onErrorContainer = Color(0xFF7A3220), // derived
    background = Color(0xFFF6F5EF),
    onBackground = Color(0xFF244735),
    surface = Color(0xFFF6F5EF),
    onSurface = Color(0xFF244735),
    surfaceVariant = Color(0xFFEAEEE1), // derived: = surfaceContainerHigh
    onSurfaceVariant = Color(0xFF607260),
    surfaceTint = Color(0xFF244735),
    inverseSurface = Color(0xFF182E25),
    inverseOnSurface = Color(0xFFF6F4E7),
    outline = Color(0xFFBEC9B9),
    outlineVariant = Color(0xFFD6DCCF),
    scrim = Color(0xFF142B24),
    surfaceBright = Color(0xFFFFFDF6), // derived
    surfaceDim = Color(0xFFE4E8DB), // derived
    surfaceContainerLowest = Color(0xFFFFFDF6),
    surfaceContainerLow = Color(0xFFFBFAF5),
    surfaceContainer = Color(0xFFF3F2EA),
    surfaceContainerHigh = Color(0xFFEAEEE1),
    surfaceContainerHighest = Color(0xFFE4E8DB),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFA0B99F),
    onPrimary = Color(0xFF173F2A),
    primaryContainer = Color(0xFF35533E),
    onPrimaryContainer = Color(0xFFD9DFC8), // derived
    inversePrimary = Color(0xFF244735),
    secondary = Color(0xFFC0CDBB),
    onSecondary = Color(0xFF173F2A), // derived
    secondaryContainer = Color(0xFF253E36),
    onSecondaryContainer = Color(0xFFE9EDE3), // derived
    tertiary = Color(0xFFDF754E),
    onTertiary = Color(0xFF3A1608), // derived
    tertiaryContainer = Color(0xFF5A2E1E), // derived
    onTertiaryContainer = Color(0xFFFBF3EA), // derived
    error = Color(0xFFF2BDA3), // derived
    onError = Color(0xFF4A1A0C), // derived
    errorContainer = Color(0xFF6E2E1E), // derived
    onErrorContainer = Color(0xFFF5E6D9), // derived
    background = Color(0xFF182E25),
    onBackground = Color(0xFFF6F4E7),
    surface = Color(0xFF182E25),
    onSurface = Color(0xFFF6F4E7),
    surfaceVariant = Color(0xFF253E36), // derived
    onSurfaceVariant = Color(0xFFC0CDBB),
    surfaceTint = Color(0xFFA0B99F),
    inverseSurface = Color(0xFFF6F4E7),
    inverseOnSurface = Color(0xFF244735),
    outline = Color(0xFF647C68),
    outlineVariant = Color(0xFF4C6450),
    scrim = Color(0xFF142B24),
    surfaceBright = Color(0xFF2C473D), // derived
    surfaceDim = Color(0xFF142B24), // derived
    surfaceContainerLowest = Color(0xFF142B24), // derived
    surfaceContainerLow = Color(0xFF1B3329), // derived
    surfaceContainer = Color(0xFF1F3A2F),
    surfaceContainerHigh = Color(0xFF253E36),
    surfaceContainerHighest = Color(0xFF2C473D), // derived
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
    onTime = StatusColor(content = Color(0xFF4C7B41), container = Color(0xFFDFE7D9), onContainer = Color(0xFF2F5A28)),
    delayed = StatusColor(content = Color(0xFF8D542D), container = Color(0xFFEFD9A8), onContainer = Color(0xFF6B3D1C)),
    cancelled = StatusColor(content = Color(0xFFA34831), container = Color(0xFFF2BDA3), onContainer = Color(0xFF6E2A18)),
    active = StatusColor(content = Color(0xFFC86842), container = Color(0xFFFBF3EA), onContainer = Color(0xFF8B422A)),
    estimated = StatusColor(
        content = Color(0xFF607260),
        container = Color.Transparent,
        onContainer = Color(0xFF607260),
        outline = Color(0xFFBEC9B9),
    ),
)

internal val DarkStatusColors = FlightStatusColors(
    onTime = StatusColor(content = Color(0xFFA9CFA0), container = Color(0xFF2E4A2B), onContainer = Color(0xFFDFE7D9)),
    delayed = StatusColor(content = Color(0xFFE8C98A), container = Color(0xFF4A3518), onContainer = Color(0xFFEFD9A8)),
    cancelled = StatusColor(content = Color(0xFFF2BDA3), container = Color(0xFF5C2618), onContainer = Color(0xFFF5D3C2)),
    active = StatusColor(content = Color(0xFFDF754E), container = Color(0xFF4A2516), onContainer = Color(0xFFFBF3EA)),
    estimated = StatusColor(
        content = Color(0xFFC0CDBB),
        container = Color.Transparent,
        onContainer = Color(0xFFC0CDBB),
        outline = Color(0xFF647C68),
    ),
)

internal val LocalFlightStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * Terracotta for small accent TEXT (uppercase captions). The light theme's `tertiary` is too pale
 * for 11sp text on paper (3.5:1), so the darker brand terracotta is used there.
 */
internal val LightAccentText = Color(0xFF8B422A)
internal val DarkAccentText = Color(0xFFDF754E)

internal val LocalAccentText = staticCompositionLocalOf { LightAccentText }
