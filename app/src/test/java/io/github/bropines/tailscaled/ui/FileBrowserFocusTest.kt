package io.github.bropines.tailscaled.ui

import androidx.compose.ui.focus.FocusDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a remote goes from the list of a wide file browser. With a file checked, Right from the
 * last row landed on Cancel, because that is what is nearest, and Right then OK cancelled the whole
 * pick: the side panel is entered on the button that answers.
 */
class FileBrowserFocusTest {

    private val last = "/storage/emulated/0/Download/zz-file-5.txt"
    private val other = "/storage/emulated/0/Download/zz-file-4.txt"

    @Test
    fun rightFromAnyRowGoesToThePanel() {
        assertTrue(goesToPanel(FocusDirection.Right, other, last))
        assertTrue(goesToPanel(FocusDirection.Right, last, last))
    }

    @Test
    fun downGoesToThePanelFromTheLastRowOnly() {
        assertTrue(goesToPanel(FocusDirection.Down, last, last))
        // From any other row Down is the next row.
        assertFalse(goesToPanel(FocusDirection.Down, other, last))
    }

    @Test
    fun theOtherKeysAreTheSystems() {
        for (direction in listOf(FocusDirection.Up, FocusDirection.Left, FocusDirection.Next, FocusDirection.Previous)) {
            assertFalse(goesToPanel(direction, last, last))
            assertFalse(goesToPanel(direction, other, last))
        }
    }

    @Test
    fun aListWithNoRowsHasNoLastOne() {
        assertFalse(goesToPanel(FocusDirection.Down, other, null))
        assertTrue(goesToPanel(FocusDirection.Right, other, null))
    }

    @Test
    fun thePanelIsEnteredOnTheConfirmButtonWhenItCanBePressed() {
        assertTrue(panelEntersOnConfirm(Confirm("Send 1 file")))
        assertTrue(panelEntersOnConfirm(Confirm("Send 3 files", enabled = true)))
    }

    @Test
    fun thePanelIsEnteredOnCancelWhenThereIsNothingToConfirm() {
        // No file is checked yet: Cancel is the panel's one button.
        assertFalse(panelEntersOnConfirm(null))
        // A folder the app cannot write to: the button is there and cannot be pressed.
        assertFalse(panelEntersOnConfirm(Confirm("Choose this folder", enabled = false, note = "Read-only")))
    }
}
