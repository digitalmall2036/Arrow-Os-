package com.arrowos.app

/** What the user asked Arrow Os to do. */
sealed class Command {
    // Moving between apps
    data class Open(val app: ArrowApp) : Command()
    /** [app] is null when the user didn't name one; the current app (or YouTube) is used. */
    data class Search(val query: String, val app: ArrowApp?) : Command()
    data object Back : Command()
    data object Forward : Command()
    data object Home : Command()
    data object Reload : Command()

    // Moving around inside a page
    data object ScrollDown : Command()
    data object ScrollUp : Command()
    data object ScrollTop : Command()
    data object ScrollBottom : Command()
    /** Next video / post / reel: one full screen down (TikTok, Shorts, Reels feeds). */
    data object NextItem : Command()
    data object PreviousItem : Command()
    /** Bring the [n]th video/result/post (1-based) into view, without opening it. */
    data class ScrollToNth(val n: Int) : Command()
    data object ZoomIn : Command()
    data object ZoomOut : Command()

    // Video
    /** Plays the video on screen, or opens the item last scrolled to. */
    data object Play : Command()
    data object Pause : Command()
    data object Mute : Command()
    data object Unmute : Command()

    // Interacting with the page
    /** Tap the button or link whose label best matches one of [labels]. */
    data class Tap(val spoken: String, val labels: List<String>) : Command()
    /** Open the [n]th video/result/post on the page (1-based). */
    data class TapNth(val n: Int) : Command()
    data class TypeText(val text: String, val submit: Boolean) : Command()
    data object PressEnter : Command()

    // Arrow Os itself
    data object Help : Command()
    data object StopListening : Command()

    /** Didn't start with "tap" or "search", so Arrow Os does nothing. */
    data class Ignored(val heard: String) : Command()
}

/**
 * Arrow Os understands two command words:
 *
 *  - **search** + words: searches for those words ("search Jeff Bezos",
 *    "search cats on TikTok").
 *  - **tap** + what to do: does it on screen ("tap like this video",
 *    "tap play the second video on this page", "tap scroll to the second video and play it",
 *    "tap open YouTube", "tap home").
 *
 * Anything else ("Jeff Bezos" on its own) is [Command.Ignored] and does nothing.
 *
 * Pure Kotlin, no Android types, so it is covered by plain JVM unit tests.
 */
object CommandParser {

    private const val TAP = "tap"
    private const val SEARCH = "search"

    private val itemWords = "video|videos|post|posts|reel|reels|clip|short|shorts|one|item|story|result|results|link|song|movie|episode"

    private val ordinals = mapOf(
        "first" to 1, "1st" to 1, "1" to 1, "one" to 1,
        "second" to 2, "2nd" to 2, "2" to 2, "two" to 2,
        "third" to 3, "3rd" to 3, "3" to 3, "three" to 3,
        "fourth" to 4, "4th" to 4, "4" to 4, "four" to 4,
        "fifth" to 5, "5th" to 5, "5" to 5, "five" to 5,
        "sixth" to 6, "6th" to 6, "6" to 6, "six" to 6,
        "seventh" to 7, "7th" to 7, "7" to 7, "seven" to 7,
        "eighth" to 8, "8th" to 8, "8" to 8, "eight" to 8,
        "ninth" to 9, "9th" to 9, "9" to 9, "nine" to 9,
        "tenth" to 10, "10th" to 10, "10" to 10, "ten" to 10,
    )
    private val ordinalPattern = ordinals.keys.sortedByDescending { it.length }.joinToString("|")

    /** Page buttons people ask for by name, with the labels sites use for them. */
    private val pageButtons: List<Pair<Regex, List<String>>> = listOf(
        Regex("^(like|love|heart|double tap)( (it|this|that|the \\w+|this \\w+))?( (button|icon))?$") to listOf("like", "love", "heart"),
        Regex("^((open|show|read|see) )?(the )?comments?( (it|this|that|section|button|icon))?$") to listOf("comment", "comments"),
        Regex("^share( (it|this|that|the \\w+|this \\w+|button|icon))?$") to listOf("share"),
        Regex("^follow( (him|her|them|this \\w+|the \\w+|creator|account|user|page|button))?$") to listOf("follow"),
        Regex("^subscribe( (to )?(him|her|them|this \\w+|the \\w+|channel|button))?$") to listOf("subscribe"),
        Regex("^(save|bookmark|favou?rite)( (it|this|that|the \\w+|this \\w+|button|icon))?$") to listOf("save", "bookmark", "favorite", "favourite"),
        Regex("^dislike( (it|this|that|the \\w+|this \\w+|button|icon))?$") to listOf("dislike"),
        Regex("^(log ?in|sign ?in)( button)?$") to listOf("log in", "sign in", "login"),
        Regex("^(sign ?up|create account|register)( button)?$") to listOf("sign up", "create account", "register"),
        Regex("^(open )?(the )?menu( button| icon)?$") to listOf("menu", "more", "open menu"),
        Regex("^(open )?(my )?(profile|account)$") to listOf("profile", "account", "you"),
        Regex("^(open )?(my )?(notifications|inbox|messages)$") to listOf("notifications", "inbox", "messages"),
        Regex("^skip( the)?( ad| ads)?( button)?$") to listOf("skip ad", "skip ads", "skip"),
    )

    /**
     * Parses one input into the commands to run, in order. Several commands can be
     * joined: "tap open TikTok then search cats", or inside one tap,
     * "tap scroll to the second video and play it".
     */
    fun parseAll(raw: String): List<Command> {
        val text = normalize(raw)
        if (!startsWithCommandWord(text)) return listOf(Command.Ignored(raw))

        // Split before each new "tap"/"search": "tap open tiktok and then search cats".
        val segments = text.split(Regex(" (?:and then|then|and|after that) (?=(?:$TAP|$SEARCH) )"))
        return segments.flatMap { segment ->
            when {
                segment.startsWith("$SEARCH ") -> listOfNotNull(parseSearch(segment.removePrefix("$SEARCH ")))
                segment.startsWith("$TAP ") -> parseTapBody(segment.removePrefix("$TAP "))
                else -> emptyList()
            }
        }.ifEmpty { listOf(Command.Ignored(raw)) }
    }

    /** The first command of [raw]; handy where only one is expected. */
    fun parse(raw: String): Command = parseAll(raw).first()

    private fun startsWithCommandWord(text: String) = text.startsWith("$TAP ") || text.startsWith("$SEARCH ")

    // ---------- search ----------

    private fun parseSearch(body: String): Command? {
        var query = body.trim().removePrefix("for ").trim()
        if (query.isEmpty()) return null

        // "search on tiktok for cats" / "search tiktok for cats"
        Regex("^(?:on |in )?(.+?) for (.+)$").find(query)?.let { m ->
            val app = exactApp(m.groupValues[1])
            if (app != null) return Command.Search(m.groupValues[2].trim(), app)
        }

        // "search cats on tiktok"
        var app: ArrowApp? = null
        Regex("^(.+) (?:on|in|using|with) (.+)$").find(query)?.let { m ->
            val candidate = exactApp(m.groupValues[2].removeSuffix(" app").trim())
            if (candidate != null) {
                app = candidate
                query = m.groupValues[1].trim()
            }
        }
        return Command.Search(query, app)
    }

    // ---------- tap ----------

    /**
     * Everything after "tap". Several actions can be joined with "and" / "then"
     * ("scroll to the second video and play it"), but only when every part is an
     * action Arrow Os knows, so "tap Tom and Jerry" still taps the thing called
     * "Tom and Jerry".
     */
    private fun parseTapBody(rawBody: String): List<Command> {
        val body = cleanTapBody(rawBody)
        if (body.isEmpty()) return emptyList()
        // "type happy birthday and send" is one step, whatever the typed words contain.
        parseTyping(body)?.let { return listOf(it) }

        val parts = body.split(Regex(" (?:and then|then|and|after that) ")).map { cleanTapBody(it) }.filter { it.isNotEmpty() }
        if (parts.size > 1) {
            val actions = parts.map { knownAction(it) }
            if (actions.all { it != null }) return actions.filterNotNull()
        }
        return listOf(knownAction(body) ?: tapByName(body))
    }

    private fun cleanTapBody(raw: String): String {
        var t = raw.trim().removePrefix("$TAP ").trim().removePrefix("on ").trim()
        for (suffix in listOf(" on this page", " on the page", " on this screen", " on the screen", " here", " for me")) {
            t = t.removeSuffix(suffix).trim()
        }
        return t
    }

    /** "tap Shorts", "tap the like icon", "tap Tom and Jerry": tap whatever has that name. */
    private fun tapByName(body: String): Command {
        val target = body.removePrefix("the ").trim()
            .removeSuffix(" button").removeSuffix(" icon").removeSuffix(" link").removeSuffix(" tab").trim()
        return Command.Tap(target, listOf(target))
    }

    /** A specific action Arrow Os knows how to do, or null if [text] is just a name to tap. */
    private fun knownAction(raw: String): Command? {
        val text = raw.removePrefix("the ").trim()
        exactAction(text)?.let { return it }
        parseTyping(text)?.let { return it }
        parseScrollToNth(text)?.let { return it }
        parseNth(text)?.let { return it }
        parseFeedAndScroll(text)?.let { return it }
        pageButtons.firstOrNull { (regex, _) -> regex.matches(text) }?.let { (_, labels) ->
            return Command.Tap(labels.first(), labels)
        }
        parseOpenApp(text)?.let { return it }
        // "tap click subscribe", "tap press next": the extra verb is fine.
        Regex("^(?:click|press|select|hit|choose|pick)(?: on)? (.+)$").find(text)?.let { m ->
            return knownAction(m.groupValues[1]) ?: tapByName(m.groupValues[1])
        }
        return null
    }

    private fun exactAction(text: String): Command? = when (text) {
        "back", "go back", "back button", "previous page", "return", "go to previous page" -> Command.Back
        "forward", "go forward" -> Command.Forward
        "home", "go home", "home screen", "home button", "go to home", "go to home screen", "back to home",
        "go back home", "close", "close app", "close it", "close this", "exit", "exit app" -> Command.Home
        "reload", "refresh", "reload page", "refresh page", "reload it", "refresh it" -> Command.Reload
        "stop listening", "mic off", "turn off mic", "turn off the mic", "voice off" -> Command.StopListening
        "help", "commands", "show commands" -> Command.Help
        "play", "play it", "play video", "play the video", "play this", "play that", "play this video",
        "resume", "continue", "unpause", "start video", "start it" -> Command.Play
        "pause", "pause it", "pause video", "pause the video", "pause this", "stop", "stop it", "stop video",
        "stop the video", "stop playing" -> Command.Pause
        "mute", "mute it", "mute video", "mute the video", "sound off", "volume off", "turn off sound",
        "turn off the sound" -> Command.Mute
        "unmute", "unmute it", "unmute video", "sound on", "volume on", "turn on sound", "turn on the sound" -> Command.Unmute
        "zoom in" -> Command.ZoomIn
        "zoom out" -> Command.ZoomOut
        "enter", "press enter", "submit", "send", "search button" -> Command.PressEnter
        else -> null
    }

    /** "type happy birthday and send" / "write hello". */
    private fun parseTyping(text: String): Command? {
        val m = Regex("^(?:type|write|input|enter)(?: in)? (.+?)(?: (?:and|then) (search|enter|submit|send|go|post|press enter))?$")
            .find(text) ?: return null
        val typed = m.groupValues[1].trim()
        if (typed.isEmpty()) return null
        return Command.TypeText(typed, submit = m.groupValues[2].isNotEmpty())
    }

    /** "scroll to the second video", "go to the third result". */
    private fun parseScrollToNth(text: String): Command? {
        val m = Regex("^(?:scroll|go|move|jump|swipe)(?: down| up)? to (?:the )?(?:number )?($ordinalPattern)(?: ($itemWords))?$")
            .find(text) ?: return null
        return Command.ScrollToNth(ordinals.getValue(m.groupValues[1]))
    }

    /** "play the second video", "open the first result", "third one". */
    private fun parseNth(text: String): Command? {
        val m = Regex(
            "^(?:(?:open|play|watch|view|show|select|choose|pick)(?: on)? )?(?:the )?(?:number )?" +
                "($ordinalPattern)(?: ($itemWords))?$",
        ).find(text) ?: return null
        // A bare number ("tap 2") is fine; "tap one" alone is too vague.
        if (m.groupValues[1] == "one" && m.groupValues[2].isEmpty()) return null
        return Command.TapNth(ordinals.getValue(m.groupValues[1]))
    }

    private fun parseFeedAndScroll(text: String): Command? {
        val words = text.split(' ').toSet()
        val mentionsItem = words.any { it in itemWords.split('|') }

        if ("previous" in words || ("last" in words && mentionsItem) || text == "swipe down" ||
            ("before" in words && mentionsItem)
        ) {
            return Command.PreviousItem
        }
        if ("next" in words || ("skip" in words && mentionsItem) || ("another" in words && mentionsItem) ||
            text == "swipe up" || text == "swipe"
        ) {
            return Command.NextItem
        }

        val isScroll = words.any { it in setOf("scroll", "swipe") } || text in setOf("down", "up", "page down", "page up")
        if (!isScroll) return null
        return when {
            "top" in words || "beginning" in words || "start" in words -> Command.ScrollTop
            "bottom" in words || "end" in words -> Command.ScrollBottom
            "up" in words -> Command.ScrollUp
            else -> Command.ScrollDown
        }
    }

    /** "open YouTube", "go to TikTok", or just "YouTube". */
    private fun parseOpenApp(text: String): Command? {
        exactApp(text)?.let { return Command.Open(it) }
        val m = Regex("^(?:open|launch|start|go to|switch to|show) (.+)$").find(text) ?: return null
        return exactApp(m.groupValues[1].removeSuffix(" app").trim())?.let { Command.Open(it) }
    }

    private fun exactApp(text: String): ArrowApp? {
        val t = text.trim().removePrefix("the ").trim()
        return ArrowApp.entries.firstOrNull { app -> app.aliases.any { it == t } }
    }

    /**
     * Lowercase, strip punctuation and polite filler so "Hey Arrow, please tap like!"
     * becomes "tap like". "Tab" is accepted as a common mishearing of "tap".
     */
    fun normalize(raw: String): String {
        var t = raw.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val fillers = listOf(
            "hey arrow", "ok arrow", "okay arrow", "arrow", "please", "can you", "could you", "would you",
            "will you", "i want to", "i wanna", "i would like to", "now", "just", "kindly", "hey", "ok", "okay",
        )
        var changed = true
        while (changed) {
            changed = false
            for (f in fillers) {
                if (t.startsWith("$f ")) { t = t.removePrefix("$f ").trim(); changed = true }
            }
        }
        for (suffix in listOf(" please", " now", " right now")) {
            t = t.removeSuffix(suffix).trim()
        }
        // Speech recognizers often hear "tap" as "tab", "tapped" or "top".
        t = t.replace(Regex("^(tab|tapped|taps|tip) "), "tap ")
        t = t.replace(Regex(" (?:and|then) (tab|tapped|taps) "), " and tap ")
        t = t.replace(Regex("^(searching|searched|search for) "), "search ")
        return t
    }
}
