package com.arrowos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun parse(s: String) = CommandParser.parse(s)
    private fun all(s: String) = CommandParser.parseAll(s)

    @Test fun onlyTapAndSearchDoAnything() {
        assertTrue(parse("Jeff Bezos") is Command.Ignored)
        assertTrue(parse("open YouTube") is Command.Ignored)
        assertTrue(parse("scroll down") is Command.Ignored)
        assertTrue(parse("next video") is Command.Ignored)
        assertTrue(parse("like this video") is Command.Ignored)
        assertTrue(parse("") is Command.Ignored)
        assertTrue(parse("tap") is Command.Ignored)
        assertTrue(parse("search") is Command.Ignored)
    }

    @Test fun search() {
        assertEquals(Command.Search("tom and jerry", null), parse("search Tom and Jerry"))
        assertEquals(Command.Search("jeff bezos", null), parse("Search Jeff Bezos"))
        assertEquals(Command.Search("cats", ArrowApp.TIKTOK), parse("search cats on TikTok"))
        assertEquals(Command.Search("dogs", ArrowApp.TIKTOK), parse("search tiktok for dogs"))
        assertEquals(Command.Search("funny cats", null), parse("search for funny cats"))
        assertEquals(Command.Search("food in new york", null), parse("search food in new york"))
        assertEquals(Command.Search("the next episode", null), parse("search the next episode"))
    }

    @Test fun tapPlaysTheNthVideo() {
        assertEquals(Command.TapNth(2), parse("tap play the second video on this page"))
        assertEquals(Command.TapNth(1), parse("tap open the first video"))
        assertEquals(Command.TapNth(3), parse("tap the third result"))
        assertEquals(Command.TapNth(2), parse("tap second video"))
    }

    @Test fun tapScrollToVideoAndPlayIt() {
        assertEquals(listOf(Command.ScrollToNth(2), Command.Play), all("tap scroll to second video and play it"))
        assertEquals(listOf(Command.ScrollToNth(3), Command.Play), all("tap scroll to the third video then play it"))
        assertEquals(listOf(Command.ScrollToNth(2), Command.Play), all("tap scroll to the second video and tap play it"))
    }

    @Test fun tapPageButtons() {
        val like = listOf("like", "love", "heart")
        assertEquals(like, (parse("tap like this video") as Command.Tap).labels)
        assertEquals(like, (parse("tap the like icon") as Command.Tap).labels)
        assertEquals(like, (parse("tap like") as Command.Tap).labels)
        assertEquals("comment", (parse("tap comment") as Command.Tap).spoken)
        assertEquals("share", (parse("tap share") as Command.Tap).spoken)
        assertEquals("follow", (parse("tap follow") as Command.Tap).spoken)
        assertEquals("subscribe", (parse("tap subscribe") as Command.Tap).spoken)
        assertEquals("skip ad", (parse("tap skip ad") as Command.Tap).spoken)
    }

    @Test fun tapAnythingByName() {
        assertEquals(Command.Tap("shorts", listOf("shorts")), parse("tap Shorts"))
        // The user's word comes first, then other names sites use for the same button.
        val settingsLabels = (parse("tap settings") as Command.Tap).labels
        assertEquals("settings", settingsLabels.first())
        assertTrue("preferences" in settingsLabels && "gear" in settingsLabels)
        assertEquals(Command.Tap("following", listOf("following")), parse("tap on Following"))
        // "and" inside a name stays part of the name.
        assertEquals(Command.Tap("tom and jerry", listOf("tom and jerry")), parse("tap Tom and Jerry"))
    }

    @Test fun tapNavigation() {
        assertEquals(Command.Open(ArrowApp.YOUTUBE), parse("tap open YouTube"))
        assertEquals(Command.Open(ArrowApp.TIKTOK), parse("tap TikTok"))
        assertEquals(Command.Open(ArrowApp.NINE_APPS), parse("tap open nine apps"))
        assertEquals(Command.Home, parse("tap home"))
        assertEquals(Command.Back, parse("tap back"))
        assertEquals(Command.Back, parse("tap go back"))
        assertEquals(Command.Reload, parse("tap refresh"))
    }

    @Test fun tapFeedAndScroll() {
        assertEquals(Command.NextItem, parse("tap next video"))
        assertEquals(Command.NextItem, parse("tap scroll to the next video"))
        assertEquals(Command.PreviousItem, parse("tap previous video"))
        assertEquals(Command.ScrollDown, parse("tap scroll down"))
        assertEquals(Command.ScrollUp, parse("tap scroll up"))
        assertEquals(Command.ScrollTop, parse("tap scroll to the top"))
    }

    @Test fun tapVideoControls() {
        assertEquals(Command.Play, parse("tap play"))
        assertEquals(Command.Pause, parse("tap pause"))
        assertEquals(Command.Mute, parse("tap mute"))
        assertEquals(Command.Unmute, parse("tap unmute"))
    }

    @Test fun tapTyping() {
        assertEquals(Command.TypeText("happy birthday", true), parse("tap type happy birthday and send"))
        assertEquals(Command.TypeText("hello", false), parse("tap type hello"))
        assertEquals(Command.PressEnter, parse("tap enter"))
    }

    @Test fun chainedTapAndSearch() {
        assertEquals(listOf(Command.Open(ArrowApp.TIKTOK), Command.Search("cats", null)), all("tap open TikTok then search cats"))
        assertEquals(listOf(Command.Search("cats", null), Command.TapNth(1)), all("search cats and tap the first video"))
    }

    @Test fun fillerAndMishearings() {
        assertEquals(Command.Open(ArrowApp.YOUTUBE), parse("Hey Arrow, please tap open YouTube!"))
        assertEquals(Command.Pause, parse("tab pause"))
        assertEquals(Command.Help, parse("tap help"))
        assertEquals(Command.StopListening, parse("tap stop listening"))
    }

    @Test fun websites() {
        assertEquals(Command.OpenWebsite("claude code"), parse("search Claude code website"))
        assertEquals(Command.OpenWebsite("bbc"), parse("search the BBC website"))
        assertEquals(Command.OpenWebsite("claude.ai"), parse("search claude.ai"))
        assertEquals(Command.OpenWebsite("claude.ai"), parse("search claude dot ai"))
        assertEquals(Command.OpenWebsite("bbc.co.uk/news"), parse("tap open bbc.co.uk/news"))
        assertEquals(Command.OpenWebsite("jumia.ug"), parse("tap go to jumia.ug"))
        assertEquals(Command.OpenWebsite("new vision"), parse("tap open New Vision website"))
        assertEquals(Command.Open(ArrowApp.YOUTUBE), parse("search YouTube website"))
        assertTrue((parse("search claude.ai") as Command.OpenWebsite).isAddress)
        assertTrue(!(parse("search Claude code website") as Command.OpenWebsite).isAddress)
        // Ordinary searches are unchanged.
        assertEquals(Command.Search("how to cook rice", null), parse("search how to cook rice"))
    }

    @Test fun tapOpenSomethingOnThePage() {
        val tap = parse("tap open settings") as Command.Tap
        assertEquals("open settings", tap.labels.first())
        assertTrue("settings" in tap.labels)
        assertEquals(Command.Tap("sign up", listOf("sign up", "create account", "register")), parse("tap sign up"))
    }

    @Test fun webLinks() {
        assertEquals("https://claude.ai", WebLinks.websiteUrl("claude.ai"))
        assertEquals("https://duckduckgo.com/?q=%21ducky+claude+code", WebLinks.websiteUrl("claude code"))
        assertEquals("https://www.google.com/search?q=jeff+bezos", WebLinks.searchUrl("jeff bezos"))
        assertTrue("web.facebook.com" in WebLinks.alternativesFor("m.facebook.com"))
        assertTrue("mbasic.facebook.com" in WebLinks.alternativesFor("m.facebook.com"))
    }

    @Test fun searchUrlsEncodeQuery() {
        assertEquals("https://m.youtube.com/results?search_query=funny+cats", ArrowApp.YOUTUBE.searchUrl("funny cats"))
        assertEquals("https://www.tiktok.com/search?q=a%26b", ArrowApp.TIKTOK.searchUrl("a&b"))
        assertEquals("https://www.instagram.com/explore/tags/funnycats/", ArrowApp.INSTAGRAM.searchUrl("Funny Cats"))
    }

    @Test fun tapPreviousOpensTabHistory() {
        assertEquals(Command.ShowPrevious, parse("tap previous"))
        assertEquals(Command.ShowPrevious, parse("Tap previous."))
        assertEquals(Command.ShowPrevious, parse("tab previous")) // common mishearing
        assertEquals(Command.ShowPrevious, parse("hey arrow tap previous please"))
        assertEquals(Command.ShowPrevious, parse("tap previous tabs"))
        assertEquals(Command.ShowPrevious, parse("tap show previous"))
        assertEquals(Command.ShowPrevious, parse("tap open previous"))
        assertEquals(Command.ShowPrevious, parse("tap history"))
        assertEquals(Command.ShowPrevious, parse("tap on previous"))
        assertEquals(Command.ClearPrevious, parse("tap clear previous"))
        assertEquals(Command.ClearPrevious, parse("tap clear history"))
        assertTrue(parse("previous") is Command.Ignored) // still needs "tap"
        // Feeds and pages keep their own previous.
        assertEquals(Command.PreviousItem, parse("tap previous video"))
        assertEquals(Command.PreviousItem, parse("tap go to previous"))
        assertEquals(Command.Back, parse("tap previous page"))
        assertEquals(listOf(Command.Open(ArrowApp.YOUTUBE), Command.ShowPrevious), all("tap open YouTube then tap previous"))
    }
}
