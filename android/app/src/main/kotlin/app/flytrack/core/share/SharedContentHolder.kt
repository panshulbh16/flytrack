package app.flytrack.core.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What another app shared with FlyTrack (ACTION_SEND / ACTION_SEND_MULTIPLE).
 * [uris] are content Uri strings (`Uri.parse(it)` to open them); kept as strings so this stays JVM-testable.
 */
data class SharedContent(
    val uris: List<String> = emptyList(),
    /** The intent's MIME type: "application/pdf", "image/png", "text/plain", or a wildcard for mixed shares. */
    val mimeType: String? = null,
    /** EXTRA_TEXT, e.g. the body of a shared e-mail or SMS. */
    val text: String? = null,
) {
    val isEmpty: Boolean get() = uris.isEmpty() && text.isNullOrBlank()
}

/**
 * Hands shared content from MainActivity to the ImportTicket screen. Navigation arguments are the
 * wrong place for it: Uri read permissions belong to the receiving activity and text can be large.
 *
 * The content is in memory only. After process death the share is gone, and so is the Uri permission.
 */
@Singleton
class SharedContentHolder @Inject constructor() {
    private val _pending = MutableStateFlow<SharedContent?>(null)

    /** The share waiting to be imported, or null. */
    val pending: StateFlow<SharedContent?> = _pending.asStateFlow()

    /** Replaces any earlier share that was never consumed. */
    fun offer(content: SharedContent) {
        _pending.value = content.takeUnless { it.isEmpty }
    }

    /** Returns the pending share and clears it, so it is imported exactly once. */
    fun consume(): SharedContent? = _pending.getAndUpdate { null }
}
