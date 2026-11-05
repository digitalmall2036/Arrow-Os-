package com.arrowos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePolicyTest {

    @Test
    fun whatsAppWebGetsTheComputerSite() {
        assertTrue(SitePolicy.needsDesktopSite("https://web.whatsapp.com/"))
        assertFalse(SitePolicy.needsDesktopSite("https://m.youtube.com/"))
        assertFalse(SitePolicy.needsDesktopSite(null))
    }

    @Test
    fun whatsAppBusinessOpensWhatsAppWeb() {
        assertEquals("https://web.whatsapp.com/", AutoDesigner.tool("whatsapp_business")?.url)
        assertEquals(listOf("web.whatsapp.com"), WebLinks.alternativesFor("business.whatsapp.com"))
    }

    @Test
    fun adsAreSkippedButNotOnGoogleSites() {
        assertTrue(SitePolicy.shouldBlock("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://www.bbc.com/news"))
        assertTrue(SitePolicy.shouldBlock("https://www.google-analytics.com/analytics.js", "https://www.tiktok.com/"))
        assertFalse(SitePolicy.shouldBlock("https://static.doubleclick.net/instream/ad_status.js", "https://m.youtube.com/watch?v=1"))
        assertFalse(SitePolicy.shouldBlock("https://i.ytimg.com/vi/1/hq.jpg", "https://www.bbc.com/"))
        assertFalse(SitePolicy.shouldBlock("https://www.tiktok.com/api/feed", "https://www.tiktok.com/"))
        assertFalse(SitePolicy.shouldBlock("https://notdoubleclick.net/x", "https://example.com/"))
    }

    @Test
    fun signInWindowsStayWindows() {
        assertTrue(SitePolicy.isSignInPopup("https://accounts.google.com/o/oauth2/v2/auth?client_id=1"))
        assertTrue(SitePolicy.isSignInPopup("about:blank"))
        assertTrue(SitePolicy.isSignInPopup("https://www.facebook.com/v18.0/dialog/oauth?app_id=1"))
        assertTrue(SitePolicy.isSignInPopup("https://example.com/oauth/authorize?x=1"))
        assertFalse(SitePolicy.isSignInPopup("https://www.bbc.com/news/world"))
        assertFalse(SitePolicy.isSignInPopup("https://www.canva.com/design/abc"))
    }

    @Test
    fun googleSignInIsReadFromCookies() {
        assertTrue(SitePolicy.signedInToGoogle("NID=1; SID=abc; HSID=x"))
        assertTrue(SitePolicy.signedInToGoogle("__Secure-1PSID=abc"))
        assertFalse(SitePolicy.signedInToGoogle("NID=1; AEC=2"))
        assertFalse(SitePolicy.signedInToGoogle(null))
    }

    @Test
    fun connectionDropsAreRecognised() {
        assertTrue(SitePolicy.isConnectionDrop("net::ERR_INTERNET_DISCONNECTED"))
        assertTrue(SitePolicy.isConnectionDrop("net::ERR_NETWORK_CHANGED"))
        assertFalse(SitePolicy.isConnectionDrop("net::ERR_CONNECTION_REFUSED"))
    }

    @Test
    fun browserNamesLookLikeChrome() {
        val wv = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.39 Mobile Safari/537.36"
        val mobile = SitePolicy.chromeUserAgent(wv)
        assertFalse("; wv" in mobile)
        assertFalse("Version/4.0" in mobile)
        assertTrue("Chrome/131.0.6778.39 Mobile" in mobile)
        assertEquals("131", SitePolicy.chromeMajorVersion(mobile))
        val desktop = SitePolicy.desktopUserAgent(mobile)
        assertTrue("X11; Linux x86_64" in desktop)
        assertTrue("Chrome/131.0.0.0" in desktop)
        assertFalse("Mobile" in desktop)
    }

    @Test
    fun essentialsAreOnEveryHomeScreen() {
        val ids = AutoDesigner.essentials.map { it.id }
        assertTrue("gmail" in ids)
        assertTrue("whatsapp" in ids)
        assertTrue("youtube" in ids)
        assertEquals(AutoDesigner.essentialToolIds.size, ids.size)
        val setup = HomeSetup("Shop", listOf("gmail", "canva"))
        assertEquals(listOf("canva"), setup.businessTools.map { it.id })
        assertEquals("gmail", setup.findTool("gmail")?.id)
        assertEquals("whatsapp", setup.findTool("whatsapp")?.id)
        assertEquals("gdrive", AutoDesigner.findTool(AutoDesigner.essentials, "google drive")?.id)
    }
}
