package app.flytrack.feature.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.feature.friends.friendName
import app.flytrack.feature.passport.CountedAirline
import app.flytrack.feature.passport.LongestFlight
import app.flytrack.feature.passport.PassportTab
import app.flytrack.ui.components.BackendNotConfiguredState
import app.flytrack.ui.components.EmptyState
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.OfflineState
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.theme.FlyTrackTheme
import java.text.NumberFormat

/** Entry point: "You vs Asha" Passport comparison (P3 B10). */
@Composable
fun FriendPassportRoute(friendId: String, friendName: String?, onBack: () -> Unit) {
    val viewModel = hiltViewModel<FriendPassportViewModel, FriendPassportViewModel.Factory>(key = "friend-passport:$friendId") {
        it.create(friendId, friendName)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FriendPassportScreen(state = state, onBack = onBack, onTab = viewModel::selectTab, onRetry = viewModel::retry)
}

@Composable
fun FriendPassportScreen(
    state: FriendPassportUiState,
    onBack: () -> Unit,
    onTab: (PassportTab) -> Unit,
    onRetry: () -> Unit,
) {
    val name = friendName(state.friendName)
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.social_compare_title, name), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val labels = listOf(
                PassportTab.OVERVIEW to stringResource(R.string.social_compare_tab_overview),
                PassportTab.YEAR to stringResource(R.string.social_compare_tab_year, state.currentYear),
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
            val modifier = Modifier.fillMaxSize()
            when (val content = state.content) {
                CompareContent.Loading -> LoadingState(modifier)
                CompareContent.Offline -> OfflineState(modifier, onRetry = onRetry)
                CompareContent.NotConfigured -> BackendNotConfiguredState(modifier)
                CompareContent.Failed -> ErrorState(
                    title = stringResource(R.string.social_compare_error),
                    modifier = modifier,
                    retryLabel = stringResource(R.string.action_retry),
                    onRetry = onRetry,
                )
                CompareContent.NotShared -> EmptyState(
                    title = stringResource(R.string.social_compare_not_shared_title, name),
                    message = stringResource(R.string.social_compare_not_shared_message, name),
                    icon = painterResource(R.drawable.ic_lock),
                    modifier = modifier,
                )
                is CompareContent.Loaded -> CompareView(content, name)
            }
        }
    }
}

@Composable
private fun CompareView(content: CompareContent.Loaded, name: String) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val you = stringResource(R.string.social_compare_you)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().semantics { heading() }) {
            Text(you, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.social_compare_vs),
                style = FlyTrackTheme.type.statusWordSmall,
                color = FlyTrackTheme.accentText,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Text(
                name,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (content.friend.proLimited) {
            Text(
                stringResource(R.string.social_compare_last12_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        content.rows.forEach { row ->
            CompareRowView(row, you, name, numbers)
            HairlineDivider()
        }
        if (content.sharedCountries.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                androidx.compose.ui.res.pluralStringResource(
                    R.plurals.social_compare_shared_countries,
                    content.sharedCountries.size,
                    content.sharedCountries.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = FlyTrackTheme.accentText,
            )
        }
        Spacer(Modifier.height(20.dp))
        SectionCaption(stringResource(R.string.social_compare_section_airlines))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AirlineList(content.mine.airlines, Modifier.weight(1f))
            AirlineList(content.friend.side.airlines, Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        SectionCaption(stringResource(R.string.social_compare_section_longest))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LongestView(content.mine.longest, numbers, Modifier.weight(1f))
            LongestView(content.friend.side.longest, numbers, Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CompareRowView(row: CompareRow, you: String, name: String, numbers: NumberFormat) {
    val label = compareLabel(row.kind)
    val mine = numbers.format(row.mine)
    val theirs = numbers.format(row.friend)
    val leaderText = when (row.leader) {
        CompareLeader.ME -> stringResource(R.string.social_compare_leader, you)
        CompareLeader.FRIEND -> stringResource(R.string.social_compare_leader, name)
        null -> stringResource(R.string.social_compare_tie)
    }
    val description = stringResource(R.string.social_compare_row_description, label, you, mine, name, theirs, leaderText)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).clearAndSetSemantics { contentDescription = description }) {
        Text(label.uppercase(), style = FlyTrackTheme.type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SideValue(mine, row.mineFraction, row.leader == CompareLeader.ME, alignEnd = false, modifier = Modifier.weight(1f))
            Spacer(Modifier.padding(horizontal = 8.dp))
            SideValue(theirs, row.friendFraction, row.leader == CompareLeader.FRIEND, alignEnd = true, modifier = Modifier.weight(1f))
        }
    }
}

/** A number with a bar under it; the leader's bar uses the accent colour, and the leader is also named in the description. */
@Composable
private fun SideValue(value: String, fraction: Float, leads: Boolean, alignEnd: Boolean, modifier: Modifier) {
    val barColor = if (leads) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(value, style = FlyTrackTheme.type.numeralSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Box(
                Modifier
                    .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart)
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun AirlineList(airlines: List<CountedAirline>, modifier: Modifier) {
    Column(modifier) {
        if (airlines.isEmpty()) {
            Text("—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        airlines.forEach { a ->
            Text(
                stringResource(R.string.social_compare_airline_row, a.name ?: a.carrier, a.count),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LongestView(longest: LongestFlight?, numbers: NumberFormat, modifier: Modifier) {
    Column(modifier) {
        if (longest == null) {
            Text("—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.addflight_leg_route, longest.dep, longest.arr), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.social_compare_longest_detail, "${longest.carrier} ${longest.number}", numbers.format(longest.distanceKm)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun compareLabel(kind: CompareKind): String = stringResource(
    when (kind) {
        CompareKind.FLIGHTS -> R.string.social_compare_flights
        CompareKind.DISTANCE_KM -> R.string.social_compare_km
        CompareKind.AIRPORTS -> R.string.social_compare_airports
        CompareKind.COUNTRIES -> R.string.social_compare_countries
        CompareKind.LONGEST_KM -> R.string.social_compare_longest_km
    },
)
