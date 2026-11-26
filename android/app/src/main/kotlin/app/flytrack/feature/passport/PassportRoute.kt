package app.flytrack.feature.passport

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.feature.billing.ui.LocalPaywallLauncher
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.share.ShareTarget
import app.flytrack.ui.components.BackendNotConfiguredState
import app.flytrack.ui.components.EmptyState
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.OfflineState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.theme.FlyTrackTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.time.Duration.Companion.minutes
import java.time.format.TextStyle
import java.util.Locale

/** Entry point of the Passport screen (P2 contract, section 2). */
@Composable
fun PassportRoute(onBack: () -> Unit, viewModel: PassportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val paywall = LocalPaywallLauncher.current
    val isPro = LocalProState.current.isPro
    PassportScreen(
        state = state,
        overviewAllTime = state.overviewIsAllTime || isPro,
        onBack = onBack,
        onTab = viewModel::selectTab,
        onRefresh = viewModel::refresh,
        onUpgrade = paywall::openPaywall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportScreen(
    state: PassportUiState,
    overviewAllTime: Boolean,
    onBack: () -> Unit,
    onTab: (PassportTab) -> Unit,
    onRefresh: () -> Unit,
    onUpgrade: () -> Unit,
) {
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.passport_title), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(state, overviewAllTime, onTab)
            val content = state.content
            val modifier = Modifier.fillMaxSize()
            when (content) {
                PassportContent.Loading -> LoadingState(modifier)
                PassportContent.Offline -> OfflineState(modifier, onRetry = onRefresh)
                PassportContent.NotConfigured -> BackendNotConfiguredState(modifier)
                PassportContent.Failed -> ErrorState(
                    title = stringResource(R.string.passport_error_title),
                    modifier = modifier,
                    retryLabel = stringResource(R.string.action_retry),
                    onRetry = onRefresh,
                )
                is PassportContent.Loaded -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier) {
                    val passport = content.passport
                    if (passport.isEmpty) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            if (passport.proLimited && state.tab == PassportTab.OVERVIEW) {
                                ProUpsell(onUpgrade, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                            }
                            EmptyState(
                                title = stringResource(R.string.passport_empty_title),
                                message = if (state.tab == PassportTab.YEAR) {
                                    stringResource(R.string.passport_empty_year_message, state.currentYear)
                                } else {
                                    stringResource(R.string.passport_empty_message)
                                },
                                icon = painterResource(R.drawable.ic_plane),
                            )
                        }
                    } else {
                        PassportContentView(passport, state, onUpgrade)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabRow(state: PassportUiState, overviewAllTime: Boolean, onTab: (PassportTab) -> Unit) {
    val labels = listOf(
        PassportTab.OVERVIEW to stringResource(if (overviewAllTime) R.string.passport_tab_overview_all else R.string.passport_tab_overview_last12),
        PassportTab.YEAR to stringResource(R.string.passport_tab_year, state.currentYear),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        labels.forEachIndexed { index, (tab, label) ->
            SegmentedButton(
                selected = state.tab == tab,
                onClick = { onTab(tab) },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun PassportContentView(passport: Passport, state: PassportUiState, onUpgrade: () -> Unit) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = when (passport.scope) {
                PassportScope.ALL -> stringResource(R.string.passport_heading_all)
                PassportScope.LAST_12_MONTHS -> stringResource(R.string.passport_heading_last12)
                PassportScope.YEAR -> stringResource(R.string.passport_heading_year, passport.year)
            },
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        passport.firstFlightDate?.let { iso ->
            runCatching { kotlinx.datetime.LocalDate.parse(iso) }.getOrNull()?.let { date ->
                Text(
                    stringResource(R.string.passport_since, FlightTimeFormat.fullDate(date)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (passport.proLimited) {
            ProUpsell(onUpgrade, Modifier.padding(top = 16.dp))
        }
        Spacer(Modifier.height(16.dp))
        StatGrid(passport, numbers)
        passport.earthLaps?.let { laps ->
            Text(
                text = stringResource(R.string.passport_earth_laps, "%.1f".format(Locale.getDefault(), laps)),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = FlyTrackTheme.accentText,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        state.yearCard?.let { card ->
            Section { YearCardBlock(card) }
        }
        Section {
            SectionCaption(stringResource(R.string.passport_section_months))
            Spacer(Modifier.height(12.dp))
            MonthChart(passport.monthBars(state.today))
        }
        Section { Highlights(passport, numbers) }
        if (passport.airports.isNotEmpty()) {
            Section {
                CountList(stringResource(R.string.passport_section_airports), passport.airports.take(PASSPORT_LIST_LIMIT).map { it.iata to it.count })
            }
        }
        if (passport.airlines.isNotEmpty()) {
            Section {
                CountList(
                    stringResource(R.string.passport_section_airlines),
                    passport.airlines.take(PASSPORT_LIST_LIMIT).map { (it.name?.let { n -> "$n (${it.carrier})" } ?: it.carrier) to it.count },
                )
            }
        }
        if (passport.aircraft.isNotEmpty()) {
            Section {
                CountList(stringResource(R.string.passport_section_aircraft), passport.aircraft.take(PASSPORT_LIST_LIMIT).map { it.model to it.count })
            }
        }
        if (passport.countries.isNotEmpty()) {
            Section {
                SectionCaption(stringResource(R.string.passport_section_countries))
                Text(
                    passport.countries.joinToString(" · ") { countryName(it) },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Section { Co2Block(passport, numbers) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    HairlineDivider()
    Spacer(Modifier.height(16.dp))
    content()
}

@Composable
private fun StatGrid(passport: Passport, numbers: NumberFormat) {
    val tiles = passport.statTiles()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile -> StatTileView(numbers.format(tile.value), statLabel(tile.kind), Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatTileView(value: String, label: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = FlyTrackTheme.type.numeralSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
        Text(label.uppercase(), style = FlyTrackTheme.type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun statLabel(kind: StatKind): String = stringResource(
    when (kind) {
        StatKind.FLIGHTS -> R.string.passport_tile_flights
        StatKind.DISTANCE_KM -> R.string.passport_tile_km
        StatKind.HOURS -> R.string.passport_tile_hours
        StatKind.AIRPORTS -> R.string.passport_tile_airports
        StatKind.COUNTRIES -> R.string.passport_tile_countries
        StatKind.AIRLINES -> R.string.passport_tile_airlines
    },
)

@Composable
private fun MonthChart(bars: List<MonthBar>) {
    val locale = Locale.getDefault()
    val barColor = MaterialTheme.colorScheme.tertiary
    val emptyColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth().height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEach { bar ->
            val monthName = bar.month.month.getDisplayName(TextStyle.SHORT, locale)
            val description = stringResource(
                R.string.passport_month_bar,
                "$monthName ${bar.month.year}",
                pluralStringResource(R.plurals.passport_times, bar.flights, bar.flights),
            )
            Column(
                Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics { contentDescription = description },
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (bar.flights > 0) {
                    Text(bar.flights.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (bar.flights == 0) 2.dp else (6 + 58 * bar.fraction).dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (bar.flights == 0) emptyColor else barColor),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    bar.month.month.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Highlights(passport: Passport, numbers: NumberFormat) {
    SectionCaption(stringResource(R.string.passport_section_highlights))
    Spacer(Modifier.height(4.dp))
    passport.longest?.let {
        Highlight(
            stringResource(R.string.passport_longest),
            stringResource(R.string.passport_longest_value, it.carrier, it.number, it.dep, it.arr, numbers.format(it.distanceKm)),
        )
    }
    passport.topRoute?.let {
        Highlight(
            stringResource(R.string.passport_top_route),
            pluralStringResource(R.plurals.passport_top_route_value, it.count, it.dep, it.arr, it.count),
        )
    }
    passport.delay.onTimePct?.let {
        Highlight(stringResource(R.string.passport_on_time), stringResource(R.string.passport_on_time_value, it))
    }
    if (passport.delay.totalMin > 0) {
        Highlight(stringResource(R.string.passport_total_delay), minutesText(passport.delay.totalMin))
    }
    passport.delay.worst?.takeIf { it.depDelayMin > 0 }?.let {
        Highlight(stringResource(R.string.passport_worst_delay), stringResource(R.string.passport_worst_delay_value, it.carrier, it.number, minutesText(it.depDelayMin)))
    }
}

@Composable
private fun minutesText(minutes: Int): String =
    app.flytrack.ui.components.durationText(minutes.minutes)

@Composable
private fun Highlight(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun CountList(title: String, rows: List<Pair<String, Int>>) {
    SectionCaption(title)
    Spacer(Modifier.height(4.dp))
    rows.forEach { (name, count) ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                pluralStringResource(R.plurals.passport_times, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Co2Block(passport: Passport, numbers: NumberFormat) {
    SectionCaption(stringResource(R.string.passport_section_co2))
    Text(
        stringResource(R.string.passport_co2_value, numbers.format(passport.co2Kg)),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        stringResource(R.string.passport_co2_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ProUpsell(onUpgrade: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onUpgrade,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.passport_pro_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.passport_pro_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.passport_pro_action), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The card on screen, recorded into a [GraphicsLayer] so "Share" can turn exactly that into a PNG. */
@Composable
private fun YearCardBlock(card: YearInReviewCardModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var sharing by remember { mutableStateOf(false) }
    val palette = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) CardPalette.Night else CardPalette.Light
    val numbers = NumberFormat.getIntegerInstance()
    val text = stringResource(R.string.passport_share_text, card.year, numbers.format(card.flights), numbers.format(card.distanceKm))
    val subject = stringResource(R.string.passport_share_subject, card.year)
    val chooser = stringResource(R.string.passport_share_chooser)

    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
    ) {
        YearInReviewCard(card, palette)
    }
    Spacer(Modifier.height(12.dp))
    PrimaryButton(
        text = stringResource(if (sharing) R.string.passport_share_preparing else R.string.passport_share_action),
        onClick = {
            if (sharing) return@PrimaryButton
            sharing = true
            scope.launch {
                val ok = try {
                    val bitmap = layer.toImageBitmap().asAndroidBitmap()
                    val uri = writeCardPng(context, bitmap, card.year)
                    uri != null && shareCardImage(context, uri, text, subject, chooser, ShareTarget.SHARE_SHEET)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                sharing = false
                if (!ok) Toast.makeText(context, R.string.passport_share_failed, Toast.LENGTH_LONG).show()
            }
        },
        loading = sharing,
        enabled = !sharing,
        icon = painterResource(R.drawable.ic_share),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "IN" → "India" in the phone's language; the code itself when unknown. */
private fun countryName(code: String): String {
    val region = try {
        Locale.Builder().setRegion(code).build()
    } catch (_: java.util.IllformedLocaleException) {
        return code
    }
    return region.getDisplayCountry(Locale.getDefault()).ifBlank { code }
}
