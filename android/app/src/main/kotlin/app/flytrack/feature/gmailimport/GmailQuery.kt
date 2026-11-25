package app.flytrack.feature.gmailimport

/** The `q` for Gmail `users.messages.list`. Gmail matches whole words, so every variant is listed. */
object GmailQuery {
    const val TERMS: String =
        """subject:(ticket OR e-ticket OR itinerary OR booking OR PNR OR confirmation OR "boarding pass") -subject:(cancel OR cancelled OR canceled OR cancellation OR refund OR refunded)"""

    private const val OVERLAP_SECONDS = 3600L

    /** The last 180 days until a scan has completed; after that, since the last check less an hour of overlap. */
    fun build(lastCheckEpochSeconds: Long?): String =
        if (lastCheckEpochSeconds == null) {
            "$TERMS newer_than:180d"
        } else {
            "$TERMS after:${lastCheckEpochSeconds - OVERLAP_SECONDS}"
        }
}
