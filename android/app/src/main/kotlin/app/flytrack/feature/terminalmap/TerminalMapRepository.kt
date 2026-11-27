package app.flytrack.feature.terminalmap

import android.content.Context
import android.util.Log
import app.flytrack.di.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the bundled terminal-map files. An interface so the logic is tested without Android assets. */
interface TerminalMapSource {
    /** IATA codes that have a bundled file. */
    fun availableAirports(): Set<String>

    /** The file's text, or null when there is none. */
    fun read(iata: String): String?
}

class AssetTerminalMapSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : TerminalMapSource {
    @Volatile private var cached: Set<String>? = null

    override fun availableAirports(): Set<String> = cached ?: run {
        val names = try {
            context.assets.list(TerminalMapContract.ASSET_DIR).orEmpty().toList()
        } catch (e: IOException) {
            emptyList()
        }
        names.filter { it.endsWith(".geojson") }.map { it.removeSuffix(".geojson").uppercase() }.toSet().also { cached = it }
    }

    override fun read(iata: String): String? {
        if (iata.uppercase() !in availableAirports()) return null
        return try {
            context.assets.open(TerminalMapContract.assetPath(iata)).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            null
        }
    }
}

sealed interface TerminalMapLoad {
    data class Ready(val data: TerminalMapData) : TerminalMapLoad

    /** No bundled file for this airport: "Terminal map not available yet". */
    data object NotAvailable : TerminalMapLoad

    /** A file exists but could not be read (a broken build): shown like [NotAvailable], logged. */
    data object Broken : TerminalMapLoad
}

/** Parses each airport's file once per process. */
@Singleton
class TerminalMapRepository @Inject constructor(
    private val source: TerminalMapSource,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val parsed = mutableMapOf<String, TerminalMapData>()

    fun isAvailable(iata: String): Boolean = iata.uppercase() in source.availableAirports()

    /** Worth offering a button: a file exists, or the airport is one the data build covers. */
    fun isOffered(iata: String): Boolean = isAvailable(iata) || iata.uppercase() in TerminalMapContract.PLANNED_AIRPORTS

    suspend fun load(iata: String): TerminalMapLoad = withContext(io) {
        val code = iata.uppercase()
        mutex.withLock {
            parsed[code]?.let { return@withContext TerminalMapLoad.Ready(it) }
            val text = source.read(code) ?: return@withContext TerminalMapLoad.NotAvailable
            try {
                val data = TerminalMapParser.parse(text, code)
                parsed[code] = data
                TerminalMapLoad.Ready(data)
            } catch (e: SerializationException) {
                logBroken(code, e)
                TerminalMapLoad.Broken
            } catch (e: IllegalArgumentException) {
                logBroken(code, e)
                TerminalMapLoad.Broken
            }
        }
    }

    private fun logBroken(code: String, e: Exception) {
        runCatching { Log.w("TerminalMap", "unreadable terminal map $code: ${e.message}") }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TerminalMapModule {
    @Binds
    abstract fun bindTerminalMapSource(impl: AssetTerminalMapSource): TerminalMapSource
}
