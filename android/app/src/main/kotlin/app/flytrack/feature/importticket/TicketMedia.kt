package app.flytrack.feature.importticket

import app.flytrack.core.model.TrackSource

/** What a picked or shared file is, judged from its mime type and name. */
enum class TicketMedia { PDF, IMAGE, TEXT, UNKNOWN }

fun mediaKind(mime: String?, name: String?): TicketMedia {
    val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val file = name?.lowercase().orEmpty()
    return when {
        type == "application/pdf" || file.endsWith(".pdf") -> TicketMedia.PDF
        type.startsWith("image/") || file.endsWith(".png") || file.endsWith(".jpg") ||
            file.endsWith(".jpeg") || file.endsWith(".webp") -> TicketMedia.IMAGE
        type.startsWith("text/") || file.endsWith(".txt") -> TicketMedia.TEXT
        else -> TicketMedia.UNKNOWN
    }
}

/** `user_flights.source` for a batch of files. Mixed or plain text is a share. */
fun trackSourceFor(kinds: Collection<TicketMedia>): TrackSource = when {
    kinds.isNotEmpty() && kinds.all { it == TicketMedia.PDF } -> TrackSource.PDF
    kinds.isNotEmpty() && kinds.all { it == TicketMedia.IMAGE } -> TrackSource.SCREENSHOT
    else -> TrackSource.SHARE
}

/** Largest bitmap edge sent to ML Kit. Larger pages are scaled down first. */
const val MAX_BITMAP_EDGE = 1_600

const val MAX_PDF_PAGES = 6

const val MAX_TICKET_CHARS = 20_000

/** Width and height that keep the longer edge at most [maxEdge], never below 1. */
fun scaledSize(width: Int, height: Int, maxEdge: Int = MAX_BITMAP_EDGE): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return width to height
    val scale = maxEdge.toFloat() / longest
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}
