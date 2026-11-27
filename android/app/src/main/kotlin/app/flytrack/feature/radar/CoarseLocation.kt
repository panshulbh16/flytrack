package app.flytrack.feature.radar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import app.flytrack.core.estimate.GeoPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

/** The user's approximate position for "Planes near me". */
sealed interface LocationResult {
    data class Found(val point: GeoPoint) : LocationResult
    data object NoPermission : LocationResult

    /** Location is switched off in the phone's settings. */
    data object Disabled : LocationResult

    /** No fix within the time limit. */
    data object Unavailable : LocationResult
}

/**
 * Coarse location only (`ACCESS_COARSE_LOCATION`, asked for on tap). The network provider answers with
 * city-block accuracy and never needs GPS; the app then rounds to the radar's 0.05° grid before sending.
 */
interface CoarseLocationProvider {
    fun hasPermission(): Boolean
    suspend fun current(): LocationResult
}

class AndroidCoarseLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : CoarseLocationProvider {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission() first
    override suspend fun current(): LocationResult {
        if (!hasPermission()) return LocationResult.NoPermission
        val manager = context.getSystemService(LocationManager::class.java) ?: return LocationResult.Unavailable
        if (!LocationManagerCompat.isLocationEnabled(manager)) return LocationResult.Disabled
        val providers = PROVIDERS.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        // A recent fix is good enough for a 75 km radius.
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { ageMs(it) <= MAX_AGE_MS }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.let { return LocationResult.Found(GeoPoint(it.latitude, it.longitude)) }
        val provider = providers.firstOrNull() ?: return LocationResult.Unavailable
        val fix = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                } catch (e: IllegalArgumentException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        return fix?.let { LocationResult.Found(GeoPoint(it.latitude, it.longitude)) } ?: LocationResult.Unavailable
    }

    private fun ageMs(location: Location): Long = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    private companion object {
        val PROVIDERS = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        const val MAX_AGE_MS = 10 * 60 * 1000L
        const val TIMEOUT_MS = 15_000L
    }
}
