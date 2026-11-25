package app.flytrack.feature.gmailimport

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.flytrack.MainActivity
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.di.ApplicationScope
import app.flytrack.ui.nav.EXTRA_GMAIL_SUGGESTION_ID
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One notification per new Gmail find, grouped under a summary, on channel [CHANNEL_ID]. Track (and a tap
 * on the body) opens MainActivity with [EXTRA_GMAIL_SUGGESTION_ID]. Not now goes to
 * [GmailSuggestionActionReceiver]. Nothing is posted while the app is in the foreground (the Found in
 * Gmail card shows the finds) or when notifications are not allowed.
 */
@Singleton
class AndroidGmailNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : GmailNotifier {

    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    override fun notifyNew(suggestions: List<GmailSuggestion>) {
        if (suggestions.isEmpty() || !canPost() || appInForeground()) return
        suggestions.forEach { manager.notify(it.id, NOTIFICATION_ID, build(it)) }
        // notify() is asynchronous, so the new ids are added to what is already showing.
        postSummary(activeTags() + suggestions.map { it.id })
    }

    override fun cancel(ids: List<String>) {
        ids.forEach { manager.cancel(it, NOTIFICATION_ID) }
        if ((activeTags() - ids.toSet()).isEmpty()) manager.cancel(SUMMARY_ID)
    }

    override fun cancelAll() {
        manager.activeNotifications
            .filter { it.notification.channelId == CHANNEL_ID }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun canPost(): Boolean {
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && manager.areNotificationsEnabled()
    }

    private fun appInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun activeTags(): Set<String> = manager.activeNotifications
        .filter { it.id == NOTIFICATION_ID && it.notification.channelId == CHANNEL_ID }
        .mapNotNull { it.tag }
        .toSet()

    private fun build(suggestion: GmailSuggestion): Notification {
        val day = FlightTimeFormat.dayMonth(suggestion.date)
        val route = suggestion.notificationRoute()
        val title = if (route == null) {
            context.getString(R.string.gmail_notification_title, suggestion.flight, day)
        } else {
            context.getString(R.string.gmail_notification_title_route, suggestion.flight, day, route)
        }
        // ponytail: request code = id hash; a collision only opens the other suggestion in Add flight, where the user confirms.
        val requestCode = suggestion.id.hashCode()
        val track = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_TRACK)
                .putExtra(EXTRA_GMAIL_SUGGESTION_ID, suggestion.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notNow = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, GmailSuggestionActionReceiver::class.java)
                .putExtra(EXTRA_GMAIL_SUGGESTION_ID, suggestion.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.plum))
            .setContentTitle(title)
            .setContentText(context.getString(R.string.gmail_notification_body))
            .setContentIntent(track)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.gmail_notification_track), track).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.gmail_notification_not_now), notNow).build())
            .build()
    }

    private fun postSummary(tags: Set<String>) {
        val count = tags.size
        val summary = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.plum))
            .setContentTitle(context.resources.getQuantityString(R.plurals.gmail_notification_summary, count, count))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        manager.notify(SUMMARY_ID, summary)
    }

    companion object {
        const val CHANNEL_ID = "ticket_suggestions"
        private const val GROUP = "app.flytrack.GMAIL_SUGGESTIONS"
        private const val ACTION_TRACK = "app.flytrack.TRACK_GMAIL_SUGGESTION"

        // Each suggestion is tag = suggestion id + this id; the summary has no tag.
        private const val NOTIFICATION_ID = 4_100
        private const val SUMMARY_ID = 4_101
    }
}

/** Not now on a Gmail suggestion notification: marks it dismissed without opening the app. Not exported. */
class GmailSuggestionActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun store(): GmailSuggestionStore
        fun notifier(): GmailNotifier

        @ApplicationScope
        fun scope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_GMAIL_SUGGESTION_ID) ?: return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        deps.notifier().cancel(listOf(id))
        val pending = goAsync()
        deps.scope().launch {
            try {
                deps.store().setStatus(listOf(id), SuggestionStatus.DISMISSED)
            } finally {
                pending.finish()
            }
        }
    }
}
