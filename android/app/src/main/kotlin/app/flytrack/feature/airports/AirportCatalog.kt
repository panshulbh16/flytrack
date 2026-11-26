package app.flytrack.feature.airports

import android.content.Context
import app.flytrack.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One airport of the bundled catalog (`assets/airports.json`): the 4,000 airports with scheduled service
 * from OurAirports (public domain), with HeyTurbulence's curated display cities and time zones. It is the
 * same list the HeyTurbulence calendar parser resolves names against, so the calendar import (A3) and the
 * route pickers (B5) work offline and agree with each other.
 */
data class CatalogAirport(
    val iata: String,
    val name: String,
    val city: String,
    val country: String,
    val lat: Double,
    val lon: Double,
    val tz: String,
)

/** The bundled airports, sorted by IATA code, with O(1) lookup by code. */
class AirportList(val airports: List<CatalogAirport>) {
    private val byCode: Map<String, CatalogAirport> = airports.associateBy { it.iata }

    operator fun get(iata: String): CatalogAirport? = byCode[iata]

    val size: Int get() = airports.size

    companion object {
        /**
         * Reads the asset's compact rows `[iata, name, city, country, lat, lon, tz]`. Rows that do not
         * have that shape are skipped. Throws [kotlinx.serialization.SerializationException] or
         * [IllegalArgumentException] when the text is not a JSON array.
         */
        fun parse(json: String): AirportList {
            val rows = Json.parseToJsonElement(json).jsonArray
            val airports = rows.mapNotNull { row ->
                val cells = row as? JsonArray ?: return@mapNotNull null
                if (cells.size < 7) return@mapNotNull null
                val text = { i: Int -> cells[i].jsonPrimitive.content }
                val iata = text(0).takeIf { it.length == 3 } ?: return@mapNotNull null
                CatalogAirport(
                    iata = iata,
                    name = text(1),
                    city = text(2),
                    country = text(3),
                    lat = cells[4].jsonPrimitive.doubleOrNull ?: return@mapNotNull null,
                    lon = cells[5].jsonPrimitive.doubleOrNull ?: return@mapNotNull null,
                    tz = text(6),
                )
            }.sortedBy { it.iata }
            return AirportList(airports)
        }
    }
}

/** Loads the bundled catalog once, off the main thread. */
interface AirportCatalog {
    /** The whole list; empty only when the asset is missing or broken (never throws). */
    suspend fun load(): AirportList

    companion object {
        /** No airports (previews and tests that do not need the catalog). */
        val Empty: AirportCatalog = object : AirportCatalog {
            override suspend fun load(): AirportList = AirportList(emptyList())
        }
    }
}

@Singleton
class AssetAirportCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AirportCatalog {
    private val mutex = Mutex()
    private var cached: AirportList? = null

    override suspend fun load(): AirportList = mutex.withLock {
        cached ?: withContext(io) {
            try {
                AirportList.parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
            } catch (e: java.io.IOException) {
                AirportList(emptyList())
            } catch (e: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException
                AirportList(emptyList())
            }
        }.also { cached = it }
    }

    private companion object {
        const val ASSET = "airports.json"
    }
}

/** Lower-case, accents removed: "São Paulo" → "sao paulo". */
internal fun foldForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKD).replace(COMBINING_MARKS, "").lowercase()

private val COMBINING_MARKS = Regex("[\\u0300-\\u036f]")

/**
 * Airports for the picker's query: an exact IATA code first, then airports whose city starts with the
 * query, then whose city or name contains it (Indian airports first within each group, as the app is
 * India-first). A blank query lists the Indian airports. At most [limit] results.
 */
fun AirportList.search(query: String, limit: Int = 30): List<CatalogAirport> {
    val q = foldForSearch(query.trim())
    if (q.isEmpty()) return airports.filter { it.country == HOME_COUNTRY }.sortedBy { it.city }.take(limit)
    val exact = if (q.length == 3) this[q.uppercase()] else null
    val ranked = airports.asSequence()
        .filter { it !== exact }
        .mapNotNull { a ->
            val city = foldForSearch(a.city)
            val name = foldForSearch(a.name)
            val rank = when {
                city.startsWith(q) -> 0
                name.startsWith(q) -> 1
                city.contains(q) || name.contains(q) -> 2
                q.length <= 3 && a.iata.startsWith(q.uppercase()) -> 3
                else -> return@mapNotNull null
            }
            Triple(rank, if (a.country == HOME_COUNTRY) 0 else 1, a)
        }
        .sortedWith(compareBy({ it.first }, { it.second }, { it.third.city }, { it.third.iata }))
        .map { it.third }
        .take(limit)
        .toList()
    return listOfNotNull(exact) + ranked.take(limit - (if (exact != null) 1 else 0))
}

private const val HOME_COUNTRY = "India"
