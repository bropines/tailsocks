package io.github.bropines.tailscaled.admin.webhooks

import io.github.bropines.tailscaled.admin.api.TailnetSettingKey
import io.github.bropines.tailscaled.admin.api.WebhookEvents
import io.github.bropines.tailscaled.admin.settings.SettingDirection
import io.github.bropines.tailscaled.admin.settings.SettingsChanges
import io.github.bropines.tailscaled.admin.settings.SettingsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookInputTest {

    @Test
    fun anEndpointTailscaleCanPostTo() {
        assertNull(WebhookInput.urlError("https://hooks.example.com/tailscale"))
        assertNull(WebhookInput.urlError("https://hooks.example.com:443/x?token=1"))
        assertNull(WebhookInput.urlError("https://hooks.example.com:80/x"))
        assertEquals(WebhookUrlError.EMPTY, WebhookInput.urlError(" "))
        assertEquals(WebhookUrlError.NOT_HTTPS, WebhookInput.urlError("http://hooks.example.com/x"))
        assertEquals(WebhookUrlError.BAD_PORT, WebhookInput.urlError("https://hooks.example.com:8443/x"))
        assertEquals(WebhookUrlError.CREDENTIALS, WebhookInput.urlError("https://user:pw@hooks.example.com/x"))
        assertEquals(WebhookUrlError.NOT_URL, WebhookInput.urlError("hooks.example.com"))
        assertEquals(WebhookUrlError.NOT_URL, WebhookInput.urlError("https://localhost/x"))
        assertEquals(WebhookUrlError.NOT_URL, WebhookInput.urlError("https://hooks example.com"))
        assertEquals("hooks.example.com", WebhookInput.host("https://hooks.example.com/x"))
    }

    @Test
    fun theGroupsHoldEveryEventOnce() {
        val grouped = WebhookGroups.all.flatMap { it.events }
        assertEquals(18, grouped.size)
        assertEquals(WebhookEvents.all.toSet(), grouped.toSet())
        assertTrue(WebhookGroups.defaults.all { it in WebhookEvents.all })
    }

    @Test
    fun whichWayASettingMoves() {
        fun d(k: TailnetSettingKey, b: Any?, a: Any?) = SettingsText.direction(k, b, a)
        assertEquals(SettingDirection.OPENS, d(TailnetSettingKey.DEVICES_APPROVAL, true, false))
        assertEquals(SettingDirection.CLOSES, d(TailnetSettingKey.DEVICES_APPROVAL, false, true))
        assertEquals(SettingDirection.OPENS, d(TailnetSettingKey.DEVICES_KEY_DURATION, 30, 90))
        assertEquals(SettingDirection.CLOSES, d(TailnetSettingKey.DEVICES_KEY_DURATION, 90, 30))
        assertEquals(SettingDirection.OPENS, d(TailnetSettingKey.USERS_EXTERNAL_ROLE, "none", "admin"))
        assertEquals(SettingDirection.CLOSES, d(TailnetSettingKey.USERS_EXTERNAL_ROLE, "member", "none"))
        assertEquals(SettingDirection.OPENS, d(TailnetSettingKey.NETWORK_FLOW_LOGGING, false, true))
        assertEquals(SettingDirection.NEUTRAL, d(TailnetSettingKey.ROUTE_SELECTION, "regional-routing", "active-passive-failover"))
        assertTrue(SettingsChanges.linkOk(""))
        assertTrue(SettingsChanges.linkOk("https://github.com/example/policy"))
        assertFalse(SettingsChanges.linkOk("http://github.com/example/policy"))
        assertFalse(SettingsChanges.linkOk("github.com/example"))
        assertTrue(SettingsChanges.keyDurations.all { it in 1..180 })
    }
}
