package app.flytrack.push

/**
 * Which phone maker's battery killer we are talking to. Detection uses the manufacturer and brand
 * strings only, so it can be tested without a device.
 */
enum class DeviceMaker {
    XIAOMI,
    VIVO,
    OPPO,
    REALME,
    ONEPLUS,
    SAMSUNG,
    OTHER,
}

fun deviceMaker(manufacturer: String, brand: String = manufacturer): DeviceMaker {
    val text = "${manufacturer.lowercase()} ${brand.lowercase()}"
    return when {
        "xiaomi" in text || "redmi" in text || "poco" in text -> DeviceMaker.XIAOMI
        "vivo" in text || "iqoo" in text -> DeviceMaker.VIVO
        // Realme reports itself separately from Oppo; check it first.
        "realme" in text -> DeviceMaker.REALME
        "oppo" in text -> DeviceMaker.OPPO
        "oneplus" in text -> DeviceMaker.ONEPLUS
        "samsung" in text -> DeviceMaker.SAMSUNG
        else -> DeviceMaker.OTHER
    }
}

/**
 * Somewhere the user can exempt the app from battery restrictions. Component targets are tried
 * first; the settings actions are the fallback that exists on every phone.
 */
sealed interface SettingsTarget {
    /** A known OEM activity. [className] is fully qualified. */
    data class Component(val packageName: String, val className: String) : SettingsTarget

    /**
     * A platform Settings action.
     * @param withAppDetails when true, the intent also carries the app's package as its data Uri
     */
    data class Action(val action: String, val withAppDetails: Boolean = false) : SettingsTarget
}

/** Platform settings actions. Kept as constants so the pure list does not import android.content. */
object SettingsActions {
    const val IGNORE_BATTERY_OPTIMIZATIONS = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
    const val APPLICATION_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val APP_NOTIFICATIONS = "android.settings.APP_NOTIFICATION_SETTINGS"
    const val CHANNEL_NOTIFICATIONS = "android.settings.CHANNEL_NOTIFICATION_SETTINGS"
}

/**
 * Deep links for the battery savers that swallow data messages on Indian phones, then the generic
 * system screens. The caller tries them in order and uses the first one that resolves.
 */
fun batterySettingsTargets(maker: DeviceMaker): List<SettingsTarget> {
    val oem = when (maker) {
        DeviceMaker.XIAOMI -> listOf(
            SettingsTarget.Component(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            SettingsTarget.Component(
                "com.miui.securitycenter",
                "com.miui.powercenter.PowerSettings",
            ),
        )
        DeviceMaker.VIVO -> listOf(
            SettingsTarget.Component(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            SettingsTarget.Component(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            ),
            SettingsTarget.Component(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
        )
        DeviceMaker.OPPO, DeviceMaker.REALME -> listOf(
            SettingsTarget.Component(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            SettingsTarget.Component(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            SettingsTarget.Component(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        )
        DeviceMaker.ONEPLUS -> listOf(
            SettingsTarget.Component(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            ),
            SettingsTarget.Component(
                "com.oplus.securitypermission",
                "com.oplus.securitypermission.autostart.AutoStartManagementActivity",
            ),
        )
        DeviceMaker.SAMSUNG -> listOf(
            SettingsTarget.Component(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity",
            ),
            SettingsTarget.Component(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
        )
        DeviceMaker.OTHER -> emptyList()
    }
    return oem + listOf(
        SettingsTarget.Action(SettingsActions.IGNORE_BATTERY_OPTIMIZATIONS),
        SettingsTarget.Action(SettingsActions.APPLICATION_DETAILS, withAppDetails = true),
    )
}
