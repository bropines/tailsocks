package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri

/**
 * What the interface is drawn for. A phone, a tablet, a foldable and a desktop
 * window are all [HANDHELD] — they differ in size, which `ui/Adaptive.kt`
 * answers — and a television is the one that differs in kind: read from a
 * sofa, driven by a remote's arrows, with no touch, no camera, and often no
 * browser and no file picker.
 */
enum class DeviceClass { HANDHELD, TV }

/** Settings → Appearance → Interface: what this device is taken for. Stored by [id]. */
enum class DeviceOverride(val id: String) {
    /** Whatever the device says it is. */
    AUTO("auto"),
    HANDHELD("handheld"),
    TV("tv");

    companion object {
        fun fromId(id: String?): DeviceOverride = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * The parts of the app a device may not be offered. A screen asks about one of
 * these, never about the device: what a television gets is one table,
 * [DeviceProfile.availability], and a new entry here does not compile until it
 * has a line there.
 */
enum class Feature {
    /** The admin console and every way into it. */
    ADMIN_CONSOLE,
    /** Reading a QR code, with the camera or from a picture. */
    QR_SCANNER,
    /** Tailcat: connections, and this device as a server. */
    TAILCAT,
    /** Creating and changing Serve, Funnel and service rules. */
    SERVE_EDIT,
    /** Signing a node into Tailnet Lock, which asks for the screen lock. */
    TAILNET_LOCK_SIGN,
    /** The command console. */
    CONSOLE,
    /** The log screen. */
    LOGS,
    /** The DNS screen. */
    DNS_SCREEN,
    /** Root Mode and its settings. */
    ROOT_MODE,
    /** Exporting and restoring settings and profiles. */
    BACKUP_RESTORE,
    /** Theme, palette and launcher icon. */
    THEME_AND_ICON,
    /** The settings of external automation: the switch and the token other apps command this one with. */
    AUTOMATION,
    /** The diagnostics and developer settings: the state now, detailed logs, extra daemon arguments. */
    DIAGNOSTICS,
    /** Search fields and what reveals them. */
    SEARCH,
    /** Copy and paste. */
    CLIPBOARD_ACTIONS,
    /** The system's own file and folder picker. */
    SYSTEM_FILE_PICKER,
    /** Opening a web page in a browser. */
    BROWSER,
    /** Signing in on the device itself, in a browser tab; the other way is a code for another device. */
    BROWSER_SIGN_IN,
    /**
     * The live state card on the home screen. Where it is not offered there it is still in
     * Settings → Diagnostics, which shows the same card — keep that section reachable on a
     * device that takes this off its home screen, or the card is nowhere.
     */
    LIVE_STATE_ON_HOME,
    /** Hints that name a touch gesture: pull, swipe, long press. */
    TOUCH_GESTURES_HINTS,
    /** Asking to be left out of battery optimisation: the banner, the permission row, the onboarding card. */
    BATTERY_OPTIMISATION_PROMPT,
    /**
     * Remote management kept to its row in Settings → Advanced → Experimental, where a handheld has
     * always had it. A device that is not offered that has the page on its home screen as well, as a
     * tile: a television, where it is the way to stop using the remote and nobody finds a page three
     * levels deep. The one entry that is a place a device gets more of, not less — read it as
     * `!offered()`; a handheld is offered it like everything else, and so gets no tile.
     */
    REMOTE_MANAGEMENT_ONLY_IN_SETTINGS,
}

/** Where a [Feature] is to be found on a device. */
enum class Availability {
    /** Where it always was. */
    OFFERED,
    /** Only in the advanced settings: the device can do it, it is not what the device is for. */
    ADVANCED,
    /** Nowhere: the device cannot do it. */
    HIDDEN;

    /** Somewhere, at least: what a deep link or a shortcut may still open. */
    val reachable: Boolean get() = this != HIDDEN
}

/**
 * What the system around the app can do, asked of the system instead of
 * inferred from the kind of device: a television with a file manager
 * installed has a picker, a bare one has not.
 */
data class Capabilities(
    /** Some app opens web pages. */
    val browser: Boolean,
    /** The system picks files and folders: open, create and tree are all answered by a real picker. */
    val documents: Boolean,
) {
    companion object {
        val ALL = Capabilities(browser = true, documents = true)
        val NONE = Capabilities(browser = false, documents = false)

        /**
         * Asks the package manager, through [canLaunch]: a television's placeholder
         * picker and browser count as none. Four binder calls, so keep the answer
         * instead of asking per row.
         */
        fun of(context: Context): Capabilities = Capabilities(
            browser = context.canLaunch(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
            ),
            documents = context.canLaunch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")) &&
                context.canLaunch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")) &&
                context.canLaunch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)),
        )
    }
}

/**
 * The device the app runs on and what it is offered there.
 *
 * One APK serves phones and televisions, so all of this is decided at run
 * time. A handheld is offered everything, by construction: nothing here can
 * change a phone or a tablet.
 */
object DeviceProfile {
    /** Fire OS marks its televisions with a feature of its own; it counts as leanback here. */
    private const val FEATURE_FIRE_TV = "amazon.hardware.fire_tv"

    /**
     * A television is what says so in its UI mode, or a leanback box without a
     * touchscreen, for a build that reports no TV mode. Not leanback alone,
     * which a touch device may declare; and the touchscreen feature proves
     * little — the Android TV emulator lists it with no touch device at all —
     * so the UI mode is asked first. [override] is the user's own answer and wins.
     */
    fun deviceClass(
        uiMode: Int,
        hasLeanback: Boolean,
        hasTouchscreen: Boolean,
        override: DeviceOverride = DeviceOverride.AUTO,
    ): DeviceClass = when (override) {
        DeviceOverride.TV -> DeviceClass.TV
        DeviceOverride.HANDHELD -> DeviceClass.HANDHELD
        DeviceOverride.AUTO -> {
            val television = uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
            if (television || (hasLeanback && !hasTouchscreen)) DeviceClass.TV else DeviceClass.HANDHELD
        }
    }

    /** The class of this device, with the Interface setting applied. */
    fun deviceClass(
        context: Context,
        uiMode: Int = context.resources.configuration.uiMode,
        override: DeviceOverride = GlobalSettings.getDeviceOverride(context),
    ): DeviceClass {
        val pm = context.packageManager
        return deviceClass(
            uiMode = uiMode,
            hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature(FEATURE_FIRE_TV),
            hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
            override = override,
        )
    }

    /**
     * The one table of what a device is offered.
     *
     * On a television: what it cannot do is hidden — no camera, no screen lock,
     * no clipboard a user can reach, no touch, no battery to optimise — and so
     * is the admin console, which is not offered there; what it can do but is
     * not for moves to the advanced settings; the picker and the browser are
     * there when the system has them. Signing in is never done in a browser
     * there, whatever is installed: a login form is no job for a remote.
     */
    fun availability(feature: Feature, device: DeviceClass, caps: Capabilities): Availability = when (device) {
        DeviceClass.HANDHELD -> Availability.OFFERED
        DeviceClass.TV -> when (feature) {
            Feature.ADMIN_CONSOLE,
            Feature.QR_SCANNER,
            Feature.TAILCAT,
            Feature.TAILNET_LOCK_SIGN,
            Feature.SEARCH,
            Feature.CLIPBOARD_ACTIONS,
            Feature.TOUCH_GESTURES_HINTS,
            Feature.BROWSER_SIGN_IN,
            Feature.BATTERY_OPTIMISATION_PROMPT,
            // The page is in Settings too: what is hidden is the settings-only arrangement, for a tile.
            Feature.REMOTE_MANAGEMENT_ONLY_IN_SETTINGS -> Availability.HIDDEN

            Feature.SERVE_EDIT,
            Feature.CONSOLE,
            Feature.LOGS,
            Feature.DNS_SCREEN,
            Feature.ROOT_MODE,
            Feature.BACKUP_RESTORE,
            Feature.THEME_AND_ICON,
            Feature.LIVE_STATE_ON_HOME,
            Feature.AUTOMATION,
            Feature.DIAGNOSTICS -> Availability.ADVANCED

            Feature.SYSTEM_FILE_PICKER -> if (caps.documents) Availability.OFFERED else Availability.HIDDEN
            Feature.BROWSER -> if (caps.browser) Availability.OFFERED else Availability.HIDDEN
        }
    }

    /** [availability] outside a composition: deep links, shortcuts, services. */
    fun availability(context: Context, feature: Feature): Availability {
        val device = deviceClass(context)
        // A handheld's answer does not depend on what the system has: do not ask it.
        if (device == DeviceClass.HANDHELD) return Availability.OFFERED
        return availability(feature, device, Capabilities.of(context))
    }
}
