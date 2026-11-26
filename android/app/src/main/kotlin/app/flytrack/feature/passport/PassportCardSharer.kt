package app.flytrack.feature.passport

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import app.flytrack.feature.share.ShareTarget
import app.flytrack.feature.share.WHATSAPP_PACKAGES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Where the card PNG is written; must match `res/xml/passport_share_paths.xml`. */
private const val CARD_DIR = "shared_cards"

/** FileProvider authority declared in the manifest. */
fun cardFileAuthority(context: Context): String = "${context.packageName}.fileprovider"

/**
 * Writes [bitmap] as a PNG in the cache and returns a content Uri other apps can read once granted.
 * Null when the file could not be written. The previous card is overwritten (one file per year).
 */
suspend fun writeCardPng(context: Context, bitmap: Bitmap, year: Int): Uri? = withContext(Dispatchers.IO) {
    try {
        val dir = File(context.cacheDir, CARD_DIR).apply { mkdirs() }
        val file = File(dir, "flytrack-$year-in-review.png")
        // A hardware bitmap (from a GraphicsLayer) has no CPU pixels to compress.
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        file.outputStream().use { out ->
            if (!software.compress(Bitmap.CompressFormat.PNG, 100, out)) return@withContext null
        }
        FileProvider.getUriForFile(context, cardFileAuthority(context), file)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        // FileProvider paths not configured
        null
    }
}

/**
 * Sends the card the same way a flight link is shared ([app.flytrack.feature.share.shareFlightText]):
 * WhatsApp first when asked, the system share sheet otherwise. Returns false when no app can take it.
 */
fun shareCardImage(context: Context, uri: Uri, text: String, subject: String, chooserTitle: String, target: ShareTarget): Boolean {
    fun sendIntent() = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(subject, uri) }

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
        context.startActivity(Intent.createChooser(sendIntent(), chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
