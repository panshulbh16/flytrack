package app.flytrack.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import javax.inject.Inject

/**
 * The app's single source of truth: screens read these tables only, network results are written
 * into them. Most tables are a cache of server data, but `gmail_suggestions` and
 * `gmail_seen_messages` are local-only state (dismissed suggestions, Gmail messages already read)
 * that no refresh can rebuild. So there is no destructive fallback: every schema change needs a
 * migration, and a missing one fails loudly. The schema is exported to android/app/schemas
 * (commit every version; the instrumented migration test reads it).
 */
@Database(
    entities = [
        FlightEntity::class, TrackedFlightEntity::class, AirportEntity::class, ProfileEntity::class,
        GmailSuggestionEntity::class, GmailSeenMessageEntity::class,
    ],
    version = 3,
    exportSchema = true,
    // ponytail: 1 -> 2 only adds tables, so Room derives the SQL from schemas/1.json and 2.json; hand-write a Migration when a change needs data rewriting.
    // 2 -> 3 adds the nullable profiles.trial_flight_id column (P1 billing).
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class FlyTrackDatabase : RoomDatabase() {
    abstract fun flightDao(): FlightDao
    abstract fun trackedFlightDao(): TrackedFlightDao
    abstract fun airportDao(): AirportDao
    abstract fun profileDao(): ProfileDao
    abstract fun gmailDao(): GmailDao

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
