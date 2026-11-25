package app.flytrack.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.flytrack.BuildConfig
import app.flytrack.R
import app.flytrack.core.push.PushContract
import app.flytrack.feature.gmailimport.AndroidGmailNotifier
import app.flytrack.ui.nav.openFlightIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the two channels the backend addresses by id, plus the local Gmail suggestions channel when
 * that feature is built in. Safe to call on every process start.
 */
internal object NotificationChannels {
    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val critical = NotificationChannel(
            PushContract.CHANNEL_CRITICAL,
            context.getString(R.string.push_channel_critical_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.push_channel_critical_description)
            enableVibration(true)
            setSound(sound, Notification.AUDIO_ATTRIBUTES_DEFAULT)
        }
        val updates = NotificationChannel(
            PushContract.CHANNEL_UPDATES,
            context.getString(R.string.push_channel_updates_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.push_channel_updates_description)
        }
        manager.createNotificationChannel(critical)
        manager.createNotificationChannel(updates)
        // Not created while the release switch is off, so system settings do not list an unreleased feature.
        if (BuildConfig.GMAIL_IMPORT_ENABLED) {
            val suggestions = NotificationChannel(
                AndroidGmailNotifier.CHANNEL_ID,
                context.getString(R.string.gmail_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.gmail_channel_description)
            }
            manager.createNotificationChannel(suggestions)
        }
    }
}

/** Posts a foreground alert with the same channel and tag the server uses in the background. */
@Singleton
class SystemFlightNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun show(alert: IncomingPush.Alert) {
        val pending = PendingIntent.getActivity(
            context,
            alert.notificationId,
            openFlightIntent(context, alert.flightId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, alert.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.plum))
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(Notification.BigTextStyle().bigText(alert.body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(alert.tag, alert.notificationId, notification)
    }
}

/** WorkManager input for one sync. The compact JSON is dropped when it would not fit in Work Data. */
const val MAX_SYNC_PAYLOAD_CHARS = 8_000

fun syncPayload(flightJson: String?): String? = flightJson?.takeIf { it.length <= MAX_SYNC_PAYLOAD_CHARS }

@Singleton
class DeviceFactsProvider @Inject constructor() {
    fun current(): DeviceFacts = DeviceFacts(
        appVersion = BuildConfig.VERSION_NAME,
        osVersion = Build.VERSION.RELEASE,
        locale = Locale.getDefault().toLanguageTag(),
        timezone = TimeZone.getDefault().id,
    )
}

/** Reads notification and battery state, and opens the system screens that change it. */
interface NotificationHealthSource {
    fun read(): NotificationHealth
    fun openNotificationSettings()
    fun openChannelSettings(channelId: String)
    fun openBatterySettings()
}

@Singleton
class AndroidNotificationHealthSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokens: FirebaseTokenSource,
) : NotificationHealthSource {

    override fun read(): NotificationHealth {
        val manager = context.getSystemService(NotificationManager::class.java)
        val appEnabled = manager.areNotificationsEnabled()
        val runtimeGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val power = context.getSystemService(PowerManager::class.java)
        return notificationHealth(
            sdkInt = Build.VERSION.SDK_INT,
            runtimeGranted = runtimeGranted || Build.VERSION.SDK_INT < 33,
            appNotificationsEnabled = appEnabled,
            criticalExists = channel(manager, PushContract.CHANNEL_CRITICAL) != null,
            criticalBlocked = channelBlocked(manager, PushContract.CHANNEL_CRITICAL, appEnabled),
            updatesExists = channel(manager, PushContract.CHANNEL_UPDATES) != null,
            updatesBlocked = channelBlocked(manager, PushContract.CHANNEL_UPDATES, appEnabled),
            batteryUnrestricted = power.isIgnoringBatteryOptimizations(context.packageName),
            maker = deviceMaker(Build.MANUFACTURER, Build.BRAND),
            pushConfigured = tokens.isConfigured(),
        )
    }

    override fun openNotificationSettings() {
        start(notificationSettingsIntent(context.packageName, channelId = null))
    }

    override fun openChannelSettings(channelId: String) {
        start(notificationSettingsIntent(context.packageName, channelId))
    }

    override fun openBatterySettings() {
        val maker = deviceMaker(Build.MANUFACTURER, Build.BRAND)
        for (target in batterySettingsTargets(maker)) {
            val intent = target.toIntent(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next target, ending at the generic system screens.
            }
        }
    }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private fun channel(manager: NotificationManager, id: String): NotificationChannel? = manager.getNotificationChannel(id)

private fun channelBlocked(manager: NotificationManager, id: String, appEnabled: Boolean): Boolean {
    if (!appEnabled) return true
    val importance = manager.getNotificationChannel(id)?.importance ?: return false
    return importance == NotificationManager.IMPORTANCE_NONE
}

internal fun notificationSettingsIntent(packageName: String, channelId: String?): Intent =
    if (channelId == null) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    } else {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    }

internal fun SettingsTarget.toIntent(packageName: String): Intent = when (this) {
    is SettingsTarget.Component -> Intent().setClassName(this.packageName, className)
    is SettingsTarget.Action -> Intent(action).apply {
        if (withAppDetails) data = Uri.parse("package:$packageName")
    }
}
