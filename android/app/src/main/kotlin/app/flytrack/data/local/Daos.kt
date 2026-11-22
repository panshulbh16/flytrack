package app.flytrack.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

@Dao
interface FlightDao {
    @Upsert
    suspend fun upsert(flights: List<FlightEntity>)

    @Query("SELECT * FROM flights WHERE id = :id")
    suspend fun get(id: String): FlightEntity?

    @Transaction
    @Query("SELECT * FROM flights WHERE id = :id")
    fun observe(id: String): Flow<FlightWithAirports?>

    /**
     * Drops cached flights nobody needs any more: not tracked, not the inbound leg of a cached
     * flight, and not touched by the server since [olderThan] (keeps fresh search results alive).
     */
    @Query(
        """
        DELETE FROM flights
         WHERE id NOT IN (SELECT flight_id FROM user_flights)
           AND id NOT IN (SELECT inbound_flight_id FROM flights WHERE inbound_flight_id IS NOT NULL)
           AND (updated_at IS NULL OR updated_at < :olderThan)
        """,
    )
    suspend fun pruneUnreferenced(olderThan: Instant)

    @Query("DELETE FROM flights")
    suspend fun clear()
}

@Dao
interface TrackedFlightDao {
    /** Non-archived tracked flights, soonest departure first; flights without any time go last. */
    @Transaction
    @Query(
        """
        SELECT uf.* FROM user_flights uf
          JOIN flights f ON f.id = uf.flight_id
         WHERE uf.archived_at IS NULL
         ORDER BY COALESCE(f.act_dep, f.est_dep, f.sched_dep) IS NULL,
                  COALESCE(f.act_dep, f.est_dep, f.sched_dep),
                  f.carrier_iata, f.flight_number
        """,
    )
    fun observeActive(): Flow<List<TrackedFlightWithFlight>>

    /** The user's tracking of one flight (archived included), null when not tracked or not cached. */
    @Transaction
    @Query(
        """
        SELECT uf.* FROM user_flights uf
          JOIN flights f ON f.id = uf.flight_id
         WHERE uf.flight_id = :flightId
         LIMIT 1
        """,
    )
    fun observeByFlightId(flightId: String): Flow<TrackedFlightWithFlight?>

    @Query("SELECT * FROM user_flights WHERE flight_id = :flightId LIMIT 1")
    suspend fun getByFlightId(flightId: String): TrackedFlightEntity?

    @Upsert
    suspend fun upsert(rows: List<TrackedFlightEntity>)

    @Query("DELETE FROM user_flights WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM user_flights")
    suspend fun clear()
}

@Dao
interface AirportDao {
    @Upsert
    suspend fun upsert(airports: List<AirportEntity>)

    @Query("SELECT * FROM airports WHERE iata = :iata")
    suspend fun get(iata: String): AirportEntity?

    @Query("SELECT iata FROM airports WHERE iata IN (:iatas)")
    suspend fun knownIatas(iatas: List<String>): List<String>
}

@Dao
interface ProfileDao {
    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observe(id: String): Flow<ProfileEntity?>

    @Query("DELETE FROM profiles")
    suspend fun clear()
}
