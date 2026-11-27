package app.flytrack.feature.terminalmap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.feature.billing.FlightProAccess
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.billing.ui.LocalUpsellsHidden
import app.flytrack.feature.forecast.ProFeatureTeaser
import app.flytrack.ui.components.EmptyState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.SectionCaption

/** Entry point of the terminal map (route `TerminalMap`). */
@Composable
fun TerminalMapRoute(iata: String, gate: String?, belt: String?, flightId: String?, onBack: () -> Unit) {
    val pro = LocalProState.current
    // Pro (contract section 4); the user's trial flight opens it too.
    val allowed = pro.isPro || (flightId != null && pro.accessFor(flightId) != FlightProAccess.LOCKED)
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.terminalmap_title, iata.uppercase()), onBack = onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (allowed) {
                TerminalMapContentRoute(iata, gate, belt)
            } else {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
                    if (LocalUpsellsHidden.current) {
                        EmptyState(title = stringResource(R.string.terminalmap_pro_locked_crew), icon = painterResource(R.drawable.ic_terminalmap))
                    } else {
                        ProFeatureTeaser(
                            title = stringResource(R.string.terminalmap_pro_title),
                            body = stringResource(R.string.terminalmap_pro_message),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalMapContentRoute(iata: String, gate: String?, belt: String?, viewModel: TerminalMapViewModel = hiltViewModel()) {
    LaunchedEffect(iata, gate, belt) { viewModel.start(iata, gate, belt) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TerminalMapScreen(
        state = state,
        onQuery = viewModel::setQuery,
        onCategory = viewModel::setCategory,
        onSelect = viewModel::select,
    )
}

@Composable
fun TerminalMapScreen(
    state: TerminalMapUiState,
    onQuery: (String) -> Unit,
    onCategory: (TerminalKind?) -> Unit,
    onSelect: (String?) -> Unit,
) {
    when (val content = state.content) {
        TerminalMapContent.Loading -> LoadingState(Modifier.fillMaxSize())
        TerminalMapContent.NotAvailable -> EmptyState(
            title = stringResource(R.string.terminalmap_not_available_title),
            message = stringResource(R.string.terminalmap_not_available_message, state.iata),
            icon = painterResource(R.drawable.ic_terminalmap),
            modifier = Modifier.fillMaxSize().padding(24.dp),
        )
        is TerminalMapContent.Ready -> TerminalMapReady(state, content, onQuery, onCategory, onSelect)
    }
}

@Composable
private fun TerminalMapReady(
    state: TerminalMapUiState,
    content: TerminalMapContent.Ready,
    onQuery: (String) -> Unit,
    onCategory: (TerminalKind?) -> Unit,
    onSelect: (String?) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val labels = kindLabels()
    val pois = remember(content.data, state.query, state.category, content.myGate) {
        TerminalMapLogic.filterPois(content.data.pois, state.query, state.category, { labels.getValue(it) }, content.myGate?.point)
    }
    val categories = remember(content.data) { TerminalMapLogic.categories(content.data.pois) }
    val selected = content.data.pois.firstOrNull { it.id == state.selectedId }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            TerminalMapView(
                data = content.data,
                myGate = content.myGate,
                myBelt = content.myBelt,
                selected = selected,
                dark = dark,
                contentDescription = stringResource(R.string.terminalmap_cd_map, state.iata),
                modifier = Modifier.fillMaxSize(),
            )
            // ODbL: the credit stays visible on the map at all times.
            Text(
                text = stringResource(R.string.terminalmap_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    GateLine(state, content)
                    BeltLine(state, content)
                    Text(
                        text = stringResource(R.string.terminalmap_offline_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HairlineDivider()
            }
            item(key = "search") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    SectionCaption(stringResource(R.string.terminalmap_section_places))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQuery,
                        label = { Text(stringResource(R.string.terminalmap_search_label)) },
                        singleLine = true,
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                        trailingIcon = if (state.query.isNotEmpty()) {
                            {
                                IconButton(onClick = { onQuery("") }) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.terminalmap_search_clear))
                                }
                            }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (categories.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            categories.forEach { kind ->
                                FilterChip(
                                    selected = state.category == kind,
                                    onClick = { onCategory(kind) },
                                    label = { Text(labels.getValue(kind)) },
                                )
                            }
                        }
                    }
                }
            }
            if (pois.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (content.data.pois.isEmpty() || (state.query.isBlank() && state.category == null)) {
                            stringResource(R.string.terminalmap_no_places)
                        } else {
                            stringResource(R.string.terminalmap_no_match, state.query.ifBlank { state.category?.let { labels.getValue(it) }.orEmpty() })
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(pois, key = { it.id }) { poi ->
                    PoiRow(poi, labels.getValue(poi.kind), content.myGate, selected = poi.id == state.selectedId, onClick = { onSelect(poi.id) })
                    HairlineDivider(Modifier.padding(start = 20.dp))
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun GateLine(state: TerminalMapUiState, content: TerminalMapContent.Ready) {
    val gate = state.gate
    val text = when {
        gate == null -> stringResource(R.string.terminalmap_gate_unknown)
        content.myGate != null -> stringResource(R.string.terminalmap_your_gate, gate)
        else -> stringResource(R.string.terminalmap_gate_not_on_map, gate)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (content.myGate != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(12.dp)) {}
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = if (content.myGate != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (content.myGate != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BeltLine(state: TerminalMapUiState, content: TerminalMapContent.Ready) {
    val belt = state.belt ?: return
    Text(
        text = stringResource(R.string.terminalmap_belt, belt) +
            if (content.myBelt != null) " · " + stringResource(R.string.terminalmap_belt_area) else "",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun PoiRow(poi: TerminalPlace, kindLabel: String, myGate: TerminalPlace?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClickLabel = stringResource(R.string.terminalmap_show_on_map), onClick = onClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = poiDotColor(poi.kind), modifier = Modifier.size(10.dp)) {}
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(poi.name ?: kindLabel, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            val details = listOfNotNull(
                kindLabel.takeIf { poi.name != null },
                poi.level?.let { stringResource(R.string.terminalmap_level, it) },
                myGate?.let { stringResource(R.string.terminalmap_metres_from_gate, TerminalMapLogic.metresBetween(it.point, poi.point)) },
            )
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun poiDotColor(kind: TerminalKind): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val p = TerminalPalette.of(dark)
    val hex = when (kind) {
        TerminalKind.FOOD -> p.poiFood
        TerminalKind.TOILETS -> p.poiToilets
        TerminalKind.LOUNGE -> p.poiLounge
        TerminalKind.ATM -> p.poiAtm
        TerminalKind.PRAYER_ROOM -> p.poiPrayer
        else -> p.poiOther
    }
    return Color(android.graphics.Color.parseColor(hex))
}

/** Localised category words, also used by the search. */
@Composable
private fun kindLabels(): Map<TerminalKind, String> = mapOf(
    TerminalKind.FOOD to stringResource(R.string.terminalmap_kind_food),
    TerminalKind.TOILETS to stringResource(R.string.terminalmap_kind_toilets),
    TerminalKind.LOUNGE to stringResource(R.string.terminalmap_kind_lounge),
    TerminalKind.ATM to stringResource(R.string.terminalmap_kind_atm),
    TerminalKind.PRAYER_ROOM to stringResource(R.string.terminalmap_kind_prayer_room),
    TerminalKind.PHARMACY to stringResource(R.string.terminalmap_kind_pharmacy),
    TerminalKind.CHARGING to stringResource(R.string.terminalmap_kind_charging),
    TerminalKind.SHOP to stringResource(R.string.terminalmap_kind_shop),
).let { poiLabels ->
    // Non-POI kinds never reach the list; give them the shop word so getValue never fails.
    TerminalKind.entries.associateWith { poiLabels[it] ?: poiLabels.getValue(TerminalKind.SHOP) }
}
