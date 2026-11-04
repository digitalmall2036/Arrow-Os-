package com.arrowos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabHistoryTest {

    @Test fun newestTabComesFirst() {
        val h = TabHistory()
        h.record("https://m.youtube.com/", "YouTube", 1)
        h.record("https://www.bbc.com/news", "BBC News", 2)
        assertEquals(listOf("https://www.bbc.com/news", "https://m.youtube.com/"), h.all.map { it.url })
        assertEquals("BBC News", h.nth(1)?.title)
        assertEquals("YouTube", h.nth(2)?.title)
        assertNull(h.nth(3))
    }

    @Test fun openingAPageAgainMovesItToTheTopWithoutDuplicates() {
        val h = TabHistory()
        h.record("https://m.youtube.com/", "YouTube", 1)
        h.record("https://www.bbc.com/", "BBC", 2)
        h.record("https://m.youtube.com", null, 3) // same page, no slash, no title yet
        assertEquals(2, h.size)
        assertEquals("https://m.youtube.com", h.nth(1)?.url)
        assertEquals("YouTube", h.nth(1)?.title) // keeps the name it had
        assertEquals(3L, h.nth(1)?.visitedAt)
    }

    @Test fun skipsBlankAndErrorPages() {
        val h = TabHistory()
        assertFalse(h.record("about:blank", "", 1))
        assertFalse(h.record("data:text/html,oops", "", 1))
        assertFalse(h.record(null, null, 1))
        assertFalse(h.record("https://", null, 1))
        assertTrue(h.isEmpty())
    }

    @Test fun titleArrivesLater() {
        val h = TabHistory()
        h.record("https://m.youtube.com/watch?v=abc", null, 1)
        assertEquals("", h.nth(1)?.title)
        assertTrue(h.updateTitle("https://m.youtube.com/watch?v=abc", "Tom and Jerry - YouTube"))
        assertEquals("Tom and Jerry - YouTube", h.nth(1)?.title)
        assertFalse(h.updateTitle("https://m.youtube.com/watch?v=abc", "https://m.youtube.com/watch?v=abc"))
        assertFalse(h.updateTitle("https://never.opened.com/", "Nope"))
    }

    @Test fun neverGrowsPastItsLimit() {
        val h = TabHistory(maxSize = 3)
        (1..5).forEach { h.record("https://site$it.com/", "Site $it", it.toLong()) }
        assertEquals(listOf("Site 5", "Site 4", "Site 3"), h.all.map { it.title })
    }

    @Test fun survivesSavingAndLoading() {
        val h = TabHistory()
        h.record("https://example.com/a?b=1\tc", "Tabs\tand\nnew lines \\ too", 10)
        h.record("https://m.youtube.com/", "YouTube", 20)
        val back = TabHistory.decode(h.encode())
        assertEquals(h.all, back.all)
        assertTrue(TabHistory.decode(null).isEmpty())
        assertTrue(TabHistory.decode("garbage\nmore garbage").isEmpty())
    }

    @Test fun findsATabByWhatTheUserSaid() {
        val h = TabHistory()
        h.record("https://www.bbc.co.uk/news", "BBC News - Home", 1)
        h.record("https://m.youtube.com/watch?v=1", "Tom and Jerry | Classic Cartoon", 2)
        h.record("https://claude.ai/", "Claude", 3)
        assertEquals("https://m.youtube.com/watch?v=1", h.find("tom and jerry")?.url)
        assertEquals("https://www.bbc.co.uk/news", h.find("bbc")?.url)
        assertEquals("https://claude.ai/", h.find("Claude")?.url)
        assertNull(h.find("netflix"))
        assertNull(h.find(""))
    }

    @Test fun hostIsShownWithoutWww() {
        assertEquals("bbc.com", PreviousTab("https://www.bbc.com/news", "", 0).host)
    }

    @Test fun pagesGoBackToTheirOwnApp() {
        assertEquals(ArrowApp.YOUTUBE, ArrowApp.fromUrl("https://m.youtube.com/watch?v=1"))
        assertEquals(ArrowApp.YOUTUBE, ArrowApp.fromUrl("https://www.youtube.com/"))
        assertEquals(ArrowApp.FACEBOOK, ArrowApp.fromUrl("https://web.facebook.com/home"))
        assertEquals(ArrowApp.TIKTOK, ArrowApp.fromUrl("https://www.tiktok.com/@someone"))
        assertEquals(ArrowApp.INSTAGRAM, ArrowApp.fromUrl("https://instagram.com/p/x"))
        assertEquals(ArrowApp.NINE_APPS, ArrowApp.fromUrl("https://www.9apps.com/"))
        assertNull(ArrowApp.fromUrl("https://notyoutube.com/"))
        assertNull(ArrowApp.fromUrl("https://www.bbc.com/"))
        assertNull(ArrowApp.fromUrl("not a url"))
    }
}
