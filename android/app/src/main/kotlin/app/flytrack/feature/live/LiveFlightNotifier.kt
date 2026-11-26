package app.flytrack.feature.live

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.flytrack.R
import app.flytrack.core.push.PushContract
import app.flytrack.ui.nav.openFlightIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The live channel (contract section 6): low importance, ongoing, silent. */
object LiveChannel {
    const val ID = "flight_live"

    fun create(context: Context) {
        val channel = NotificationChannel(
            ID,
            context.getString(R.string.live_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.live_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

/**
 * Posts, updates and cancels the one live notification. On Android 16 (API 36) and newer it uses
 * the platform `Notification.ProgressStyle` with the four journey segments and milestone points,
 * and asks to be promoted to a Live Update (status-bar chip, top of the shade). Older versions get
 * a plain determinate progress bar.
 *
 * Identical content is not re-posted, so the system's update rate limit is never hit.
 */
@Singleton
class LiveFlightNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    private var channelCreated = false

    // Starts "unknown" so the first render of a process also cancels a notification a previous process left.
    private var rendered = false
    private var shown: LiveNotificationContent? = null

    @Synchronized
    fun render(content: LiveNotificationContent?) {
        if (rendered && content == shown) return
        rendered = true
        if (content == null) {
            manager.cancel(TAG, NOTIFICATION_ID)
            shown = null
            return
        }
        if (!canPost()) {
            rendered = false
            shown = null
            return
        }
        if (!channelCreated) {
            LiveChannel.create(context)
            channelCreated = true
        }
        manager.notify(TAG, NOTIFICATION_ID, build(content))
        shown = content
    }

    private fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    private fun build(content: LiveNotificationContent): Notification {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            openFlightIntent(context, content.flightId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissed = PendingIntent.getBroadcast(
            context,
            REQUEST_DISMISS,
            Intent(context, LiveDismissReceiver::class.java)
                .setAction(LiveDismissReceiver.ACTION_DISMISSED)
                .putExtra(PushContract.KEY_FLIGHT_ID, content.flightId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, LiveChannel.ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.forest))
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
        content.subText?.let(builder::setSubText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            applyLiveUpdate(builder, content)
        } else {
            builder.setProgress(LiveRules.PROGRESS_MAX, content.progress.position, false)
            builder.setStyle(Notification.BigTextStyle().bigText(content.text))
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun applyLiveUpdate(builder: Notification.Builder, content: LiveNotificationContent) {
        val progress = content.progress
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(
                progress.segments.map { segment ->
                    Notification.ProgressStyle.Segment(segment.weight).setId(segment.ordinal).setColor(segmentColor(segment))
                },
            )
            .setProgressPoints(
                progress.points.map { point ->
                    Notification.ProgressStyle.Point(point.position).setId(point.ordinal).setColor(POINT_COLOR)
                },
            )
            .setProgress(progress.position)
            .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_notification).setTint(TRACKER_COLOR))
        builder.setStyle(style)
        content.shortCriticalText?.let(builder::setShortCriticalText)
        // Notification.Builder.setRequestPromotedOngoing is API 36.1; the extra works on every 36 build.
        builder.addExtras(Bundle().apply { putBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING, true) })
    }

    private fun segmentColor(segment: LiveSegment): Int = when (segment) {
        LiveSegment.CHECK_IN -> SAGE
        LiveSegment.BOARDING -> TERRACOTTA
        LiveSegment.AIRBORNE -> GREEN
        LiveSegment.BELT -> SAGE
    }

    private companion object {
        const val TAG = "live"
        const val NOTIFICATION_ID = 0x4C495645 // "LIVE"
        const val REQUEST_OPEN = 7101
        const val REQUEST_DISMISS = 7102

        // docs/design-brief.md tones that read on both light and dark notification shades.
        const val SAGE = 0xFF768775.toInt()
        const val TERRACOTTA = 0xFFC86842.toInt()
        const val GREEN = 0xFF4C7B41.toInt()
        const val POINT_COLOR = 0xFFC86842.toInt()
        const val TRACKER_COLOR = 0xFFC86842.toInt()
    }
}
