package com.arrowos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun parse(s: String) = CommandParser.parse(s)

    @Test fun opensEachApp() {
        assertEquals(Command.Open(ArrowApp.YOUTUBE), parse("open YouTube"))
        assertEquals(Command.Open(ArrowApp.FACEBOOK), parse("Open Facebook"))
        assertEquals(Command.Open(ArrowApp.INSTAGRAM), parse("launch insta"))
        assertEquals(Command.Open(ArrowApp.TIKTOK), parse("open tik tok"))
        assertEquals(Command.Open(ArrowApp.NINE_APPS), parse("open nine apps"))
        assertEquals(Command.Open(ArrowApp.NINE_APPS), parse("open 9apps"))
        assertEquals(Command.Open(ArrowApp.NINE_APPS), parse("open 9 apps"))
    }

    @Test fun ignoresPolitenessAndPunctuation() {
        assertEquals(Command.Open(ArrowApp.YOUTUBE), parse("Hey Arrow, please open YouTube!"))
        assertEquals(Command.Open(ArrowApp.TIKTOK), parse("can you go to TikTok please"))
    }

    @Test fun justTheAppNameOpensIt() {
        assertEquals(Command.Open(ArrowApp.FACEBOOK), parse("Facebook"))
        assertEquals(Command.Open(ArrowApp.YOUTUBE), parse("watch youtube"))
    }

    @Test fun navigation() {
        assertEquals(Command.Back, parse("go back"))
        assertEquals(Command.Back, parse("Back"))
        assertEquals(Command.Home, parse("go home"))
        assertEquals(Command.Home, parse("open home screen"))
        assertEquals(Command.ScrollDown, parse("scroll down"))
        assertEquals(Command.ScrollUp, parse("scroll up"))
        assertEquals(Command.Reload, parse("refresh"))
        assertEquals(Command.StopListening, parse("stop listening"))
    }

    @Test fun searchOnApp() {
        assertEquals(Command.Search("cats", ArrowApp.TIKTOK), parse("search cats on TikTok"))
        assertEquals(Command.Search("funny cats", ArrowApp.YOUTUBE), parse("search for funny cats on youtube"))
        assertEquals(Command.Search("music", ArrowApp.YOUTUBE), parse("play music on YouTube"))
        assertEquals(Command.Search("dogs", ArrowApp.TIKTOK), parse("search tiktok for dogs"))
        assertEquals(Command.Search("dogs", ArrowApp.INSTAGRAM), parse("search on instagram for dogs"))
        assertEquals(Command.Search("cats in boxes", ArrowApp.YOUTUBE), parse("search cats in boxes on youtube"))
    }

    @Test fun searchWithoutAppLeavesAppEmpty() {
        assertEquals(Command.Search("cats", null), parse("search cats"))
        assertEquals(Command.Search("food in new york", null), parse("search food in new york"))
    }

    @Test fun unknownIsReported() {
        assertTrue(parse("what's the weather") is Command.Unknown)
        assertTrue(parse("") is Command.Unknown)
        assertTrue(parse("open the fridge") is Command.Unknown)
    }

    @Test fun searchUrlsEncodeQuery() {
        assertEquals("https://m.youtube.com/results?search_query=funny+cats", ArrowApp.YOUTUBE.searchUrl("funny cats"))
        assertEquals("https://www.tiktok.com/search?q=a%26b", ArrowApp.TIKTOK.searchUrl("a&b"))
        assertEquals("https://www.instagram.com/explore/tags/funnycats/", ArrowApp.INSTAGRAM.searchUrl("Funny Cats"))
    }
}
