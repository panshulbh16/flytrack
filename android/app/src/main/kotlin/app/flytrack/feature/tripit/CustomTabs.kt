package app.flytrack.feature.tripit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Opens [url] in a Custom Tab when the browser supports them, else in the browser. The androidx.browser
 * library is not in the dependency set, so the intent carries the Custom Tabs extras itself (the
 * documented protocol: a session extra, which may be null, turns a VIEW intent into a Custom Tab).
 *
 * @return false when nothing can open the URL
 */
fun openInCustomTab(context: Context, url: String, toolbarColor: Int): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
        putExtra(EXTRA_TOOLBAR_COLOR, toolbarColor)
        putExtra(EXTRA_SHARE_STATE, SHARE_STATE_OFF)
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"
private const val EXTRA_TOOLBAR_COLOR = "android.support.customtabs.extra.TOOLBAR_COLOR"
private const val EXTRA_SHARE_STATE = "androidx.browser.customtabs.extra.SHARE_STATE"
private const val SHARE_STATE_OFF = 2
