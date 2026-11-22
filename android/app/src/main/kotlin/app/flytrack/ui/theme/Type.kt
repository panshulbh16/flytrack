package app.flytrack.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font as DownloadableFont
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import app.flytrack.R

// Downloadable fonts (Google Play services). Until a font has arrived, or on phones without Play
// services, Compose falls back to a system font, so text is never missing.
private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val instrumentSerif = GoogleFont("Instrument Serif")
private val dmSans = GoogleFont("DM Sans")

/** Display and headline face; its italic is used for status words (*Delayed*, *On time*). */
val InstrumentSerifFamily = FontFamily(
    DownloadableFont(instrumentSerif, fontProvider, FontWeight.Normal, FontStyle.Normal),
    DownloadableFont(instrumentSerif, fontProvider, FontWeight.Normal, FontStyle.Italic),
    // Shown while the download is pending or impossible: keeps the serif look instead of Roboto.
    Font(DeviceFontFamilyName("serif"), FontWeight.Normal, FontStyle.Normal),
    Font(DeviceFontFamilyName("serif"), FontWeight.Normal, FontStyle.Italic),
)

/** UI face. */
val DmSansFamily = FontFamily(
    DownloadableFont(dmSans, fontProvider, FontWeight.Normal),
    DownloadableFont(dmSans, fontProvider, FontWeight.Medium),
    DownloadableFont(dmSans, fontProvider, FontWeight.SemiBold),
)

/** Tabular numerals: digits share one width, so times and counters do not jitter or misalign. */
private const val TABULAR_NUMERALS = "tnum"

private fun serif(size: Int, lineHeight: Int) = TextStyle(
    fontFamily = InstrumentSerifFamily,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

private fun sans(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
    fontFamily = DmSansFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = TABULAR_NUMERALS,
)

internal val FlyTrackTypography = Typography(
    displayLarge = serif(57, 64),
    displayMedium = serif(45, 52),
    displaySmall = serif(36, 44),
    headlineLarge = serif(32, 40),
    headlineMedium = serif(28, 36),
    headlineSmall = serif(24, 32),
    titleLarge = sans(22, 28, FontWeight.Medium),
    titleMedium = sans(17, 24, FontWeight.Medium),
    titleSmall = sans(14, 20, FontWeight.Medium),
    bodyLarge = sans(16, 24),
    bodyMedium = sans(14, 20),
    bodySmall = sans(12, 16),
    labelLarge = sans(14, 20, FontWeight.Medium),
    labelMedium = sans(12, 16, FontWeight.Medium),
    labelSmall = sans(11, 16, FontWeight.Medium, tracking = 1.2),
)

/** Text styles the brief adds on top of the Material type scale. Read them with `FlyTrackTheme.type`. */
@Immutable
data class FlyTrackExtraType(
    /** Departure/arrival times: DM Sans 400, 38sp, -1 tracking, tabular numerals. */
    val numeral: TextStyle,
    /** Smaller times (list rows, struck-through old times). */
    val numeralSmall: TextStyle,
    /** The key status word on a flight card: italic serif, 28sp. */
    val statusWord: TextStyle,
    /** Status word inside list rows: italic serif, 20sp. */
    val statusWordSmall: TextStyle,
    /** Uppercase section caption: 11sp, +1.2 tracking. Upper-case the text yourself or use SectionCaption. */
    val caption: TextStyle,
)

internal val FlyTrackExtras = FlyTrackExtraType(
    numeral = sans(38, 44, tracking = -1.0),
    numeralSmall = sans(22, 28, tracking = -0.4),
    statusWord = serif(28, 36).copy(fontStyle = FontStyle.Italic),
    statusWordSmall = serif(20, 26).copy(fontStyle = FontStyle.Italic),
    caption = sans(11, 16, FontWeight.Medium, tracking = 1.2),
)

internal val LocalFlyTrackExtraType = staticCompositionLocalOf { FlyTrackExtras }
