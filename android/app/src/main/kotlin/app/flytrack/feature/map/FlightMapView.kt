package app.flytrack.feature.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.flytrack.R
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The MapLibre map for one flight. This is the only place that touches MapLibre (a native
 * library), so nothing below the UI layer and no JVM unit test loads it.
 *
 * The style comes from [FlightMapStyle] (built in code, land from the bundled asset, no tile
 * server). The camera fits the route once, when the style first loads; later updates only move
 * the plane and the flown/remaining split, so the user's own panning is kept.
 */
@Composable
internal fun FlightMapView(
    model: FlightMapUiModel,
    dark: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val padding = with(LocalDensity.current) { 56.dp.roundToPx() }
    val latestModel by rememberUpdatedState(model)
    val holder = remember { MapHolder() }
    val mapView = remember { createMapView(context) }

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
            // Walk the map down its lifecycle before it goes, whatever state the screen is in.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            holder.style = null
            holder.map = null
            mapView.onDestroy()
        }
    }

    // (Re)load the style whenever the theme changes; the first load also fits the camera.
    LaunchedEffect(mapView, dark) {
        mapView.getMapAsync { map ->
            holder.map = map
            holder.style = null
            val builder = Style.Builder().fromJson(FlightMapStyle.styleJson(MapPalette.of(dark)))
            ContextCompat.getDrawable(context, R.drawable.ic_map_plane)?.let { builder.withImage(FlightMapStyle.PLANE_IMAGE, it) }
            map.setStyle(builder) { style ->
                if (holder.map !== map) return@setStyle
                holder.style = style
                render(style, latestModel)
                if (!holder.cameraFitted) {
                    fitCamera(map, latestModel, padding)
                    holder.cameraFitted = true
                }
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        update = { holder.style?.let { render(it, model) } },
    )
}

/** MapLibre objects that only exist once the map and style are ready. */
private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var cameraFitted: Boolean = false
}

private fun createMapView(context: Context): MapView {
    // Needed once per process before a MapView exists. No API key and no tile server: the style is local.
    MapLibre.getInstance(context.applicationContext)
    val options = MapLibreMapOptions.createFromAttributes(context)
        .textureMode(true) // a TextureView composes correctly inside Compose and dialogs
        .attributionEnabled(false) // nothing to attribute: Natural Earth is public domain, no tiles
        .logoEnabled(false)
        .compassEnabled(false)
        .rotateGesturesEnabled(false)
        .tiltGesturesEnabled(false)
    return MapView(context, options).also { it.onCreate(null) }
}

private fun render(style: Style, model: FlightMapUiModel) {
    if (!style.isFullyLoaded) return
    style.getSourceAs<GeoJsonSource>(FlightMapStyle.SOURCE_ROUTE_FLOWN)?.setGeoJson(FlightMapStyle.lineGeoJson(model.flownPath))
    style.getSourceAs<GeoJsonSource>(FlightMapStyle.SOURCE_ROUTE_REMAINING)?.setGeoJson(FlightMapStyle.lineGeoJson(model.remainingPath))
    val airports = (model.flownPath + model.remainingPath).let { path ->
        // Use the path's own (unwrapped) ends so the dots sit exactly on the line.
        if (path.size >= 2) listOf(path.first(), path.last()) else listOf(model.dep, model.arr)
    }
    style.getSourceAs<GeoJsonSource>(FlightMapStyle.SOURCE_AIRPORTS)?.setGeoJson(FlightMapStyle.airportsGeoJson(airports))
    style.getSourceAs<GeoJsonSource>(FlightMapStyle.SOURCE_PLANE)
        ?.setGeoJson(FlightMapStyle.planeGeoJson(model.plane?.point, model.plane?.bearingDeg ?: 0.0))
}

private fun fitCamera(map: MapLibreMap, model: FlightMapUiModel, padding: Int) {
    val bounds = mapBounds(model.cameraPoints) ?: return
    // A zero-size box (same airport twice) cannot be fitted; widen it a little.
    val pad = if (bounds.north - bounds.south < 0.5 && bounds.east - bounds.west < 0.5) 1.0 else 0.0
    val latLngBounds = LatLngBounds.from(
        (bounds.north + pad).coerceAtMost(85.0),
        bounds.east + pad,
        (bounds.south - pad).coerceAtLeast(-85.0),
        bounds.west - pad,
    )
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, padding))
}
