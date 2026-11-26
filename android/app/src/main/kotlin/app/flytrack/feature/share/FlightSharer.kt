package app.flytrack.feature.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** How the user asked to share. */
enum class ShareTarget {
    /** WhatsApp (or WhatsApp Business) directly; the share sheet when neither is installed. */
    WHATSAPP_FIRST,

    /** Always the system share sheet. */
    SHARE_SHEET,
}

/** WhatsApp, then WhatsApp Business (P1 contract, section 2). */
val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

/**
 * Sends [text] out. Starting an explicit package needs no `<queries>` entry (only package queries
 * are filtered on Android 11+), so "installed" is decided by trying: a missing app throws
 * [ActivityNotFoundException] and the next option is tried.
 *
 * @return false when nothing on the phone can share text
 */
fun shareFlightText(context: Context, text: String, subject: String, chooserTitle: String, target: ShareTarget): Boolean {
    fun sendIntent() = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, subject)

    if (target == ShareTarget.WHATSAPP_FIRST) {
        for (pkg in WHATSAPP_PACKAGES) {
            try {
                context.startActivity(sendIntent().setPackage(pkg))
                return true
            } catch (_: ActivityNotFoundException) {
                // not installed: try the next one
            }
        }
    }
    return try {
        context.startActivity(Intent.createChooser(sendIntent(), chooserTitle))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
