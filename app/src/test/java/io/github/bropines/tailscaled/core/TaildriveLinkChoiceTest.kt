package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link switch against the proxy it needs. The author: "if I switched the Taildrive proxy off and
 * the button is on, it won't work" — the page showed the proxy-off state and opened nothing, though
 * the document road needs no proxy.
 */
class TaildriveLinkChoiceTest {

    private val proxies = ProxyCondition.entries
    private val apis = listOf(24, 25, 26, 34, 36)

    @Test
    fun withTheSwitchOffNothingChangesAndNothingIsSaid() {
        for (proxy in proxies) for (api in apis) for (media in listOf(true, false)) {
            assertEquals(LinkDecision(useLink = false, notice = null), TaildriveLinkChoice.decide(false, proxy, api, media))
        }
    }

    @Test
    fun withTheSwitchOnAndTheProxyThereTheLinkGoesFirst() {
        for (api in apis) for (media in listOf(true, false)) {
            assertEquals(LinkDecision(useLink = true, notice = null), TaildriveLinkChoice.decide(true, ProxyCondition.READY, api, media))
        }
    }

    @Test
    fun withTheSwitchOnAndNoProxyTheFileOpensDirectlyAndThePageSaysWhy() {
        assertEquals(LinkDecision(false, LinkNotice.PROXY_OFF), TaildriveLinkChoice.decide(true, ProxyCondition.OFF, 34, media = true))
        assertEquals(LinkDecision(false, LinkNotice.PROXY_OFF), TaildriveLinkChoice.decide(true, ProxyCondition.OFF, 24, media = false))
        assertEquals(LinkDecision(false, LinkNotice.PROXY_SILENT), TaildriveLinkChoice.decide(true, ProxyCondition.SILENT, 26, media = true))
        // Before Android 8 only the link seeks: a film opened directly plays from the start, and the page says so.
        assertEquals(LinkDecision(false, LinkNotice.PROXY_OFF_NO_SEEK), TaildriveLinkChoice.decide(true, ProxyCondition.OFF, 24, media = true))
        assertEquals(LinkDecision(false, LinkNotice.PROXY_OFF_NO_SEEK), TaildriveLinkChoice.decide(true, ProxyCondition.OFF, 25, media = true))
        assertEquals(LinkDecision(false, LinkNotice.PROXY_SILENT_NO_SEEK), TaildriveLinkChoice.decide(true, ProxyCondition.SILENT, 25, media = true))
    }

    @Test
    fun onlyAProxyThatIsOffIsOfferedToBeTurnedOn() {
        assertTrue(LinkNotice.PROXY_OFF.offersProxy)
        assertTrue(LinkNotice.PROXY_OFF_NO_SEEK.offersProxy)
        assertFalse(LinkNotice.PROXY_SILENT.offersProxy)
        assertFalse(LinkNotice.PROXY_SILENT_NO_SEEK.offersProxy)
    }

    @Test
    fun turningTheSwitchOnAsksOnlyWhenTheProxyIsOff() {
        assertTrue(TaildriveLinkChoice.asksToEnable(turningOn = true, ProxyCondition.OFF))
        assertFalse(TaildriveLinkChoice.asksToEnable(turningOn = true, ProxyCondition.SILENT))
        assertFalse(TaildriveLinkChoice.asksToEnable(turningOn = true, ProxyCondition.READY))
        for (proxy in proxies) assertFalse(TaildriveLinkChoice.asksToEnable(turningOn = false, proxy))
    }

    @Test
    fun withSeekingSwitchedOffTheNoticeSaysSeekingNeedsTheLink() {
        assertEquals(
            LinkDecision(false, LinkNotice.PROXY_OFF_NO_SEEK),
            TaildriveLinkChoice.decide(true, ProxyCondition.OFF, 34, media = true, contentSeeks = false)
        )
        assertEquals(
            LinkDecision(false, LinkNotice.PROXY_OFF),
            TaildriveLinkChoice.decide(true, ProxyCondition.OFF, 34, media = false, contentSeeks = false)
        )
    }
}
