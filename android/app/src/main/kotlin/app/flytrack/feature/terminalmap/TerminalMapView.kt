package app.flytrack.feature.terminalmap

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.flytrack.core.estimate.GeoPoint
import app.flytrack.feature.radar.MapLabelBitmaps
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The terminal map: the bundled GeoJSON drawn by [TerminalMapStyle]. The camera starts on the user's gate
 * when the map has it, else on the terminal buildings; tapping a POI in the list moves to it.
 */
@Composable
internal fun TerminalMapView(
    data: TerminalMapData,
    myGate: TerminalPlace?,
    myBelt: TerminalPlace?,
    selected: TerminalPlace?,
    dark: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val padding = with(LocalDensity.current) { 32.dp.roundToPx() }
    val gateLabelBg = MaterialTheme.colorScheme.surfaceContainerLowest.toArgb()
    val gateLabelText = MaterialTheme.colorScheme.onSurface.toArgb()
    val gateLabelRing = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val myLabelBg = MaterialTheme.colorScheme.tertiary.toArgb()
    val myLabelText = MaterialTheme.colorScheme.surfaceContainerLowest.toArgb()
    val latestSelected by rememberUpdatedState(selected)
    val holder = remember { TerminalMapHolder() }
    val mapView = remember { createTerminalMapView(context) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            holder.style = null
            holder.map = null
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView, dark, data.iata, myGate?.ref, myBelt?.id) {
        val gateLabels = MapLabelBitmaps(context, background = gateLabelBg, text = gateLabelText, outline = gateLabelRing, textSizeSp = 10f)
        val myLabels = MapLabelBitmaps(context, background = myLabelBg, text = myLabelText, textSizeSp = 12f)
        mapView.getMapAsync { map ->
            holder.map = map
            holder.style = null
            val json = TerminalMapStyle.styleJson(data.iata, TerminalPalette.of(dark), myGate?.ref, myBelt?.id)
            map.setStyle(Style.Builder().fromJson(json)) { style ->
                if (holder.map !== map) return@setStyle
                holder.style = style
                val refs = data.gates.mapNotNull { it.ref }.distinct()
                // The user's gate gets the accent pill; every other gate the plain one.
                gateLabels.addMissing(style, refs.filter { it != myGate?.ref }.associateBy { TerminalMapStyle.gateImageId(it) })
                myGate?.ref?.let { myLabels.addMissing(style, mapOf(TerminalMapStyle.gateImageId(it) to it)) }
                if (!holder.cameraPlaced) {
                    placeCamera(map, data, myGate, padding)
                    holder.cameraPlaced = true
                }
                renderSelected(holder, latestSelected, moveCamera = false)
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        update = { renderSelected(holder, selected, moveCamera = true) },
    )
}

private class TerminalMapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var cameraPlaced = false
    var shownSelection: GeoPoint? = null
}

private fun createTerminalMapView(context: Context): MapView {
    MapLibre.getInstance(context.applicationContext)
    val options = MapLibreMapOptions.createFromAttributes(context)
        .textureMode(true)
        // MapLibre's own attribution button would be empty (no tile source); the screen shows the
        // OpenStreetMap credit itself, always visible.
        .attributionEnabled(false)
        .logoEnabled(false)
        .compassEnabled(true)
        .tiltGesturesEnabled(false)
        .minZoomPreference(12.0)
        .maxZoomPreference(19.5)
    return MapView(context, options).also { it.onCreate(null) }
}

private fun placeCamera(map: MapLibreMap, data: TerminalMapData, myGate: TerminalPlace?, padding: Int) {
    if (myGate != null) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(myGate.point.lat, myGate.point.lon), GATE_ZOOM))
        return
    }
    val b = data.terminalBounds ?: data.bounds ?: return
    if (b.north - b.south < 1e-5 || b.east - b.west < 1e-5) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(b.center.lat, b.center.lon), GATE_ZOOM))
    } else {
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(b.north, b.east, b.south, b.west), padding))
    }
}

private fun renderSelected(holder: TerminalMapHolder, selected: TerminalPlace?, moveCamera: Boolean) {
    val style = holder.style ?: return
    if (!style.isFullyLoaded) return
    val point = selected?.point
    style.getSourceAs<GeoJsonSource>(TerminalMapStyle.SOURCE_SELECTED)?.setGeoJson(TerminalMapStyle.selectedGeoJson(point))
    if (moveCamera && point != null && point != holder.shownSelection) {
        holder.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), POI_ZOOM))
    }
    holder.shownSelection = point
}

private const val GATE_ZOOM = 17.0
private const val POI_ZOOM = 17.5
