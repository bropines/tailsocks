package io.github.bropines.tailscaled.admin.safety

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Devices tab's classes: bulk tags and a new address are HIGH, so is taking an exit node away. */
class DeviceChangeClassTest {

    @Test
    fun bulkTagsAndANewAddressAreHigh() {
        assertEquals(ChangeClass.HIGH, ChangeClassifier.classify(ChangeKind.DEVICE_TAGS_BULK))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.classify(ChangeKind.DEVICE_IPV4))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.classify(ChangeKind.DEVICE_TAGS))
        assertEquals(ChangeClass.LOW, ChangeClassifier.classify(ChangeKind.DEVICE_RENAME))
    }

    @Test
    fun removingAnExitNodeIsHighApprovingIsMedium() {
        val exit = listOf("0.0.0.0/0", "::/0")
        val subnet = listOf("192.168.1.0/24")
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.routes(emptyList(), exit))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.routes(exit, emptyList()))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.routes(exit + subnet, subnet))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.routes(exit + subnet, exit))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.routes(subnet, emptyList()))
        assertEquals("only half of the pair left still serves as an exit node", ChangeClass.MEDIUM, ChangeClassifier.routes(exit, listOf("0.0.0.0/0")))
    }
}
