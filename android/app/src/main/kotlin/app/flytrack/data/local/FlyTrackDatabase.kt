package app.flytrack.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import javax.inject.Inject

/**
 * The app's single source of truth: screens read these tables only, network results are written
 * into them. Everything here is a cache of server data, so a destructive fallback on a schema
 * change is safe (the next refresh refills it). The schema is exported to android/app/schemas.
 */
@Database(
    entities = [FlightEntity::class, TrackedFlightEntity::class, AirportEntity::class, ProfileEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FlyTrackDatabase : RoomDatabase() {
    abstract fun flightDao(): FlightDao
    abstract fun trackedFlightDao(): TrackedFlightDao
    abstract fun airportDao(): AirportDao
    abstract fun profileDao(): ProfileDao

    companion object {
        const val NAME = "flytrack.db"
    }
}

/** Runs several DAO calls atomically. An interface so repositories can be tested without Room. */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

class RoomTransactionRunner @Inject constructor(private val database: FlyTrackDatabase) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction(block)
}
