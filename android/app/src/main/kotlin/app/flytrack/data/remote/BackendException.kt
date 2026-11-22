package app.flytrack.data.remote

/** Everything [BackendApi] and [AuthGateway] can throw. Repositories turn these into typed results. */
sealed class BackendException(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    /** The build has no Supabase URL/key. */
    class NotConfigured : BackendException("Supabase URL or anon key is missing from this build")

    /** No connection, DNS failure, timeout: the request may never have reached the server. */
    class Network(cause: Throwable) : BackendException(cause.message, cause)

    /**
     * The server answered with an error.
     * @param status HTTP status
     * @param code PostgREST: the Postgres error code ("P0001", "23505"); Edge Functions: the `error`
     *   field ("not_found", "rate_limited"); Auth: the error code ("identity_already_exists")
     * @param hint PostgREST only: the `hint` of the raised exception
     */
    class Http(val status: Int, val code: String?, message: String?, val hint: String? = null) :
        BackendException(message ?: code ?: "HTTP $status")

    /** The server answered 2xx with a body this app cannot read. */
    class Malformed(cause: Throwable) : BackendException(cause.message, cause)
}

/** Postgres error codes and messages the app reacts to. */
object PgErrors {
    /** `raise exception` in plpgsql (the tracking limit trigger). */
    const val RAISE_EXCEPTION = "P0001"
    const val UNIQUE_VIOLATION = "23505"
    const val FOREIGN_KEY_VIOLATION = "23503"
    const val CHECK_VIOLATION = "23514"
    const val TRACKING_LIMIT_MESSAGE = "tracking_limit_reached"
}

val BackendException.Http.isTrackingLimit: Boolean
    get() = message?.contains(PgErrors.TRACKING_LIMIT_MESSAGE) == true

val BackendException.Http.isUniqueViolation: Boolean get() = code == PgErrors.UNIQUE_VIOLATION

val BackendException.Http.isForeignKeyViolation: Boolean get() = code == PgErrors.FOREIGN_KEY_VIOLATION
