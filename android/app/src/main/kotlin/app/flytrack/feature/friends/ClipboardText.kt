package app.flytrack.feature.friends

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Copies [text] and confirms with [confirmation]. Android 13+ shows its own "Copied" preview, so the toast
 * is only shown on older versions.
 */
fun copyText(context: Context, label: String, text: String, @StringRes confirmation: Int) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
    }
}
