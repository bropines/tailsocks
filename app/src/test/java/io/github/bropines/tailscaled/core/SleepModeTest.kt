package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The two stored switches behind the sleep mode, and which one wins. */
class SleepModeTest {
    @Test
    fun alwaysWinsSoOldBackupsAndRemoteToggleKeepWorking() {
        assertEquals(SleepMode.ALWAYS, SleepMode.from(always = true, periodic = false))
        assertEquals(SleepMode.ALWAYS, SleepMode.from(always = true, periodic = true))
    }

    @Test
    fun periodicOnlyWithoutTheLock() {
        assertEquals(SleepMode.PERIODIC, SleepMode.from(always = false, periodic = true))
        assertEquals(SleepMode.OFF, SleepMode.from(always = false, periodic = false))
    }
}
