package app.flytrack.push

/** Whether a notification channel exists and whether the user has blocked it. */
enum class ChannelHealth {
    /** Channel exists and can show notifications. */
    OK,

    /** The user set the channel to "None", or notifications for the app are off. */
    BLOCKED,

    /** The channel has not been created yet. */
    MISSING,
}

/** Runtime notification permission, which only exists from Android 13 (API 33). */
enum class PermissionHealth {
    /** Android 13+ and the user allowed notifications. */
    GRANTED,

    /** Android 13+ and the user has not allowed notifications. */
    DENIED,

    /** Before Android 13 there is no runtime prompt; notifications are allowed. */
    NOT_REQUIRED,

    /** Before Android 13, but the user disabled notifications for the app in system settings. */
    NOT_REQUIRED_BUT_BLOCKED,
}

/**
 * Everything the settings screen needs to explain why an alert might not arrive.
 * Assembled from platform facts by [notificationHealth]; the platform reads stay outside.
 */
data class NotificationHealth(
    val permission: PermissionHealth,
    val criticalChannel: ChannelHealth,
    val updatesChannel: ChannelHealth,
    val batteryUnrestricted: Boolean,
    val maker: DeviceMaker,
    /** False when this build has no Firebase config. The app still runs; push is simply off. */
    val pushConfigured: Boolean,
) {
    val notificationsBlocked: Boolean
        get() = permission == PermissionHealth.DENIED || permission == PermissionHealth.NOT_REQUIRED_BUT_BLOCKED

    val anyChannelBlocked: Boolean
        get() = criticalChannel == ChannelHealth.BLOCKED || updatesChannel == ChannelHealth.BLOCKED
}

fun permissionHealth(sdkInt: Int, runtimeGranted: Boolean, appNotificationsEnabled: Boolean): PermissionHealth =
    if (sdkInt >= 33) {
        if (runtimeGranted && appNotificationsEnabled) PermissionHealth.GRANTED else PermissionHealth.DENIED
    } else if (appNotificationsEnabled) {
        PermissionHealth.NOT_REQUIRED
    } else {
        PermissionHealth.NOT_REQUIRED_BUT_BLOCKED
    }

/** Android 13+ rationale is shown once, and only while the permission is still missing. */
fun shouldShowNotificationRationale(sdkInt: Int, runtimeGranted: Boolean, alreadyAsked: Boolean): Boolean =
    sdkInt >= 33 && !runtimeGranted && !alreadyAsked

fun channelHealth(exists: Boolean, blocked: Boolean): ChannelHealth = when {
    !exists -> ChannelHealth.MISSING
    blocked -> ChannelHealth.BLOCKED
    else -> ChannelHealth.OK
}

fun notificationHealth(
    sdkInt: Int,
    runtimeGranted: Boolean,
    appNotificationsEnabled: Boolean,
    criticalExists: Boolean,
    criticalBlocked: Boolean,
    updatesExists: Boolean,
    updatesBlocked: Boolean,
    batteryUnrestricted: Boolean,
    maker: DeviceMaker,
    pushConfigured: Boolean,
): NotificationHealth = NotificationHealth(
    permission = permissionHealth(sdkInt, runtimeGranted, appNotificationsEnabled),
    criticalChannel = channelHealth(criticalExists, criticalBlocked),
    updatesChannel = channelHealth(updatesExists, updatesBlocked),
    batteryUnrestricted = batteryUnrestricted,
    maker = maker,
    pushConfigured = pushConfigured,
)
