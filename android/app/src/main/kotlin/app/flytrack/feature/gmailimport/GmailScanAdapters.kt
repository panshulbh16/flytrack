package app.flytrack.feature.gmailimport

import android.content.Context
import android.net.Uri
import app.flytrack.data.repo.FlightRepository
import app.flytrack.di.IoDispatcher
import app.flytrack.feature.importticket.ExtractRequest
import app.flytrack.feature.importticket.TicketTextExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Writes the attachment to a cache file, reads it with the existing on-device PDF reader and
 * deletes the file. A file Uri is enough: the extractor opens it with ContentResolver.openFileDescriptor,
 * which accepts file Uris, and the file never leaves the app, so no FileProvider is needed.
 */
class AndroidPdfTextReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extractor: TicketTextExtractor,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PdfTextReader {
    override suspend fun read(bytes: ByteArray): String = withContext(io) {
        val file = File.createTempFile("gmail-", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            extractor.extract(
                ExtractRequest(uris = listOf(Uri.fromFile(file).toString()), mimeType = "application/pdf"),
            ).text
        } finally {
            file.delete()
        }
    }
}

/** Tracked, unarchived flights as (compact flight number, local departure date at the origin). */
class RepositoryTrackedKeysSource @Inject constructor(
    private val flights: FlightRepository,
) : TrackedKeysSource {
    override suspend fun current(): Set<TrackedKey> =
        flights.observeTrackedFlights().first().mapTo(mutableSetOf()) { details ->
            TrackedKey(GmailSuggestions.compact(details.flight.designator), details.flight.flightDate)
        }
}
