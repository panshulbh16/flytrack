package app.flytrack.data.remote

import kotlinx.serialization.json.Json

/**
 * The one Json configuration for everything exchanged with the backend.
 * - unknown keys are ignored, so a newer backend never breaks an older app;
 * - nulls are omitted when encoding, so Postgres column and RPC parameter defaults apply.
 */
val FlyTrackJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}
