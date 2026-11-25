package app.flytrack.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlyTrackDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FlyTrackDatabase::class.java)

    @Test
    fun migrate1To2_keepsCachedRowsAndAddsTheGmailTables() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO airports (iata, name, city, tz, lat, lon) " +
                    "VALUES ('DEL', 'Indira Gandhi International', 'Delhi', 'Asia/Kolkata', 28.5562, 77.1)",
            )
        }

        // Runs AutoMigration(1, 2) and checks the result against schemas/.../2.json.
        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT name FROM airports WHERE iata = 'DEL'").use { cursor ->
                assertTrue("cached rows survive the migration", cursor.moveToFirst())
                assertEquals("Indira Gandhi International", cursor.getString(0))
            }
            db.execSQL(
                "INSERT INTO gmail_suggestions (id, message_id, flight, date, from_iata, to_iata, pnr, subject, status, found_at) " +
                    "VALUES ('6E2341_2026-12-03', 'm1', '6E 2341', '2026-12-03', 'DEL', NULL, NULL, 'Your e-ticket', 'pending', 1)",
            )
            db.execSQL("INSERT INTO gmail_seen_messages (message_id) VALUES ('m1')")
        }

        // Room opens the migrated file itself (identity hash check) and reads it through the DAO.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, FlyTrackDatabase::class.java, DB).build()
        helper.closeWhenFinished(room)
        runBlocking {
            assertEquals(listOf("6E2341_2026-12-03"), room.gmailDao().suggestions().map { it.id })
            assertEquals(listOf("m1"), room.gmailDao().seen(listOf("m1", "m2")))
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
