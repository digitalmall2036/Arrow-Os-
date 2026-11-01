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
        assertEquals(Command.Forward, parse("go forward"))
        assertEquals(Command.ScrollTop, parse("scroll to the top"))
        assertEquals(Command.ScrollBottom, parse("scroll to the bottom"))
        assertEquals(Command.Help, parse("what can I say"))
    }

    @Test fun feedNavigation() {
        // The phrase from Mj's screenshot.
        assertEquals(Command.NextItem, parse("scroll to the next video"))
        assertEquals(Command.NextItem, parse("next video"))
        assertEquals(Command.NextItem, parse("next"))
        assertEquals(Command.NextItem, parse("swipe up"))
        assertEquals(Command.NextItem, parse("skip this video"))
        assertEquals(Command.NextItem, parse("show me the next one"))
        assertEquals(Command.NextItem, parse("play next video"))
        assertEquals(Command.PreviousItem, parse("previous video"))
        assertEquals(Command.PreviousItem, parse("go back to the previous video"))
        assertEquals(Command.PreviousItem, parse("swipe down"))
        assertEquals(Command.ScrollDown, parse("scroll"))
        assertEquals(Command.ScrollDown, parse("move down a bit"))
        assertEquals(Command.ScrollUp, parse("Scroll up please"))
    }

    @Test fun videoControls() {
        assertEquals(Command.Play, parse("play"))
        assertEquals(Command.Play, parse("resume"))
        assertEquals(Command.Pause, parse("pause the video"))
        assertEquals(Command.Pause, parse("stop"))
        assertEquals(Command.Mute, parse("mute"))
        assertEquals(Command.Unmute, parse("sound on"))
    }

    @Test fun pageButtons() {
        assertEquals(listOf("like", "love", "heart"), (parse("like this video") as Command.Tap).labels)
        assertEquals(listOf("like", "love", "heart"), (parse("tap the like button") as Command.Tap).labels)
        assertEquals("comment", (parse("open comments") as Command.Tap).spoken)
        assertEquals("share", (parse("share it") as Command.Tap).spoken)
        assertEquals("follow", (parse("follow this creator") as Command.Tap).spoken)
        assertEquals("subscribe", (parse("subscribe to this channel") as Command.Tap).spoken)
        assertEquals("skip ad", (parse("skip ad") as Command.Tap).spoken)
    }

    @Test fun tapAnything() {
        assertEquals(Command.Tap("shorts", listOf("shorts"), false), parse("tap Shorts"))
        assertEquals(Command.Tap("following", listOf("following"), false), parse("click on following"))
        assertEquals(Command.Tap("next", listOf("next"), false), parse("press the next button"))
        assertEquals(Command.Tap("fridge", listOf("fridge"), true), parse("open the fridge"))
        assertEquals(Command.TapNth(1), parse("open the first video"))
        assertEquals(Command.TapNth(2), parse("play the second one"))
        assertEquals(Command.TapNth(3), parse("third result"))
        assertEquals(Command.TapNth(1), parse("tap the first video"))
        assertEquals(Command.PressEnter, parse("press enter"))
    }

    @Test fun typing() {
        assertEquals(Command.TypeText("happy birthday", true), parse("type happy birthday and send"))
        assertEquals(Command.TypeText("hello world", false), parse("Type hello world"))
        assertEquals(Command.TypeText("cats", true), parse("enter cats and search"))
    }

    @Test fun chainedCommands() {
        assertEquals(listOf(Command.Open(ArrowApp.TIKTOK), Command.NextItem), CommandParser.parseAll("open TikTok then next video"))
        assertEquals(
            listOf(Command.Open(ArrowApp.YOUTUBE), Command.Search("cats", null)),
            CommandParser.parseAll("open YouTube and search cats"),
        )
        // "and" inside a search stays part of the search.
        assertEquals(listOf(Command.Search("cats and dogs", ArrowApp.YOUTUBE)), CommandParser.parseAll("search cats and dogs on youtube"))
        assertEquals(listOf(Command.Open(ArrowApp.FACEBOOK)), CommandParser.parseAll("open facebook"))
    }

    @Test fun searchIsNotMistakenForFeedNavigation() {
        assertEquals(Command.Search("the next episode", ArrowApp.YOUTUBE), parse("search for the next episode on youtube"))
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
    }

    @Test fun searchUrlsEncodeQuery() {
        assertEquals("https://m.youtube.com/results?search_query=funny+cats", ArrowApp.YOUTUBE.searchUrl("funny cats"))
        assertEquals("https://www.tiktok.com/search?q=a%26b", ArrowApp.TIKTOK.searchUrl("a&b"))
        assertEquals("https://www.instagram.com/explore/tags/funnycats/", ArrowApp.INSTAGRAM.searchUrl("Funny Cats"))
    }
}
