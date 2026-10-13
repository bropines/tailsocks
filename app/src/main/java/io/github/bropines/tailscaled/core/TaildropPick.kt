package io.github.bropines.tailscaled.core

/** What answers when Taildrop asks for files to send or a folder to save into. */
enum class PickerKind {
    /** The system's document picker, as on every phone. */
    SYSTEM,

    /** The app's own browser (ui/FileBrowser.kt). */
    IN_APP,
}

/**
 * Decides which, and starts the system's picker when it is the one. The app's browser
 * takes over in two cases. A device that is not offered the system picker
 * ([Feature.SYSTEM_FILE_PICKER]): a television answers the picker intents with a
 * placeholder that shows a toast and finishes, so there [launchSystem] is not even tried.
 * And a device that is offered one and turns out to have none, where the launch fails
 * ([tryLaunch]). Wherever the system picker works, nothing changes.
 */
inline fun choosePicker(systemOffered: Boolean, launchSystem: () -> Boolean): PickerKind =
    if (systemOffered && launchSystem()) PickerKind.SYSTEM else PickerKind.IN_APP
