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
    data object ZoomIn : Command()
    data object ZoomOut : Command()

    // Video
    data object Play : Command()
    data object Pause : Command()
    data object Mute : Command()
    data object Unmute : Command()

    // Interacting with the page
    /**
     * Tap the button or link whose label best matches one of [labels].
     * [fallbackSearch] means: if nothing on the page matches, search for [spoken] instead.
     */
    data class Tap(val spoken: String, val labels: List<String>, val fallbackSearch: Boolean) : Command()
    /** Open the [n]th video/result/post on the page (1-based). */
    data class TapNth(val n: Int) : Command()
    data class TypeText(val text: String, val submit: Boolean) : Command()
    data object PressEnter : Command()

    // Arrow Os itself
    data object Help : Command()
    data object StopListening : Command()
    data class Unknown(val heard: String) : Command()
}

/**
 * Turns recognized speech or typed text into [Command]s. Pure Kotlin, no Android
 * types, so it is covered by plain JVM unit tests.
 */
object CommandParser {

    private val openVerbs = listOf("open", "launch", "start", "go to", "take me to", "switch to", "show me", "show")
    private val searchVerbs = listOf("search for", "search", "find", "look for", "look up", "play", "watch")
    private val tapVerbs = listOf("click on", "click", "tap on", "tap", "press on", "press", "select", "hit", "choose", "pick")
    private val itemWords = "video|videos|post|posts|reel|reels|clip|short|shorts|one|item|story|tiktok|page|result"

    private val ordinals = mapOf(
        "first" to 1, "1st" to 1, "top" to 1, "1" to 1,
        "second" to 2, "2nd" to 2, "2" to 2,
        "third" to 3, "3rd" to 3, "3" to 3,
        "fourth" to 4, "4th" to 4, "4" to 4,
        "fifth" to 5, "5th" to 5, "5" to 5,
        "sixth" to 6, "6th" to 6, "6" to 6,
    )

    /** Page buttons people ask for by name, with the labels sites use for them. */
    private val pageButtons: List<Pair<Regex, List<String>>> = listOf(
        Regex("^(like|love|heart|double tap)( (it|this|that|the \\w+|this \\w+))?$") to listOf("like", "love", "heart"),
        Regex("^((open|show|read|see) )?(the )?comments?( (it|this|that|section))?$") to listOf("comment", "comments"),
        Regex("^share( (it|this|that|the \\w+|this \\w+))?$") to listOf("share"),
        Regex("^follow( (him|her|them|this \\w+|the \\w+|creator|account|user|page))?$") to listOf("follow"),
        Regex("^subscribe( (to )?(him|her|them|this \\w+|the \\w+|channel))?$") to listOf("subscribe"),
        Regex("^(save|bookmark|favou?rite)( (it|this|that|the \\w+|this \\w+))?$") to listOf("save", "bookmark", "favorite", "favourite"),
        Regex("^(dislike)( (it|this|that|the \\w+|this \\w+))?$") to listOf("dislike"),
        Regex("^(log ?in|sign ?in)$") to listOf("log in", "sign in", "login"),
        Regex("^(sign ?up|create account|register)$") to listOf("sign up", "create account", "register"),
        Regex("^(open )?(the )?menu$") to listOf("menu", "more", "open menu"),
        Regex("^(open )?(my )?(profile|account)$") to listOf("profile", "account", "you"),
        Regex("^(open )?(my )?(notifications|inbox|messages)$") to listOf("notifications", "inbox", "messages"),
        Regex("^(skip|skip ad|skip ads|skip the ad)$") to listOf("skip ad", "skip ads", "skip"),
    )

    /**
     * Parses one instruction, or several joined with "then" / "and then" / "and"
     * ("open TikTok then next video"). Joined parts are only split up when every
     * part is understood on its own, so "search cats and dogs" stays one search.
     */
    fun parseAll(raw: String): List<Command> {
        val text = normalize(raw)
        val parts = text.split(Regex(" (?:and then|then|after that|and) ")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1) {
            val commands = parts.map { parse(it) }
            if (commands.none { it is Command.Unknown }) return commands
        }
        return listOf(parse(raw))
    }

    fun parse(raw: String): Command {
        val text = normalize(raw)
        if (text.isEmpty()) return Command.Unknown(raw)

        exactCommand(text)?.let { return it }
        parseTyping(text)?.let { return it }
        parseTap(text)?.let { return it }
        parseNth(text)?.let { return it }
        parseScrollAndFeed(text)?.let { return it }
        parsePageButton(text)?.let { return it }
        parseSearch(text)?.let { return it }
        parseOpen(text)?.let { return it }

        // Just saying the app's name ("youtube") opens it.
        exactApp(text)?.let { return Command.Open(it) }

        return Command.Unknown(raw)
    }

    private fun exactCommand(text: String): Command? = when (text) {
        "back", "go back", "previous page", "return", "back page", "go to previous page", "last page" -> Command.Back
        "forward", "go forward" -> Command.Forward
        "home", "go home", "home screen", "go to home", "go to home screen", "back to home", "go back home",
        "close", "close app", "close it", "close this", "exit", "exit app", "quit", "quit app" -> Command.Home
        "reload", "refresh", "reload page", "refresh page", "reload it", "refresh it" -> Command.Reload
        "stop listening", "be quiet", "mute mic", "mic off", "turn off mic", "stop voice", "voice off" -> Command.StopListening
        "help", "what can i say", "what can you do", "commands", "show commands", "list commands" -> Command.Help
        "play", "play it", "play video", "play the video", "play this", "resume", "continue", "unpause",
        "start video", "start it", "continue playing", "keep playing" -> Command.Play
        "pause", "pause it", "pause video", "pause the video", "stop", "stop it", "stop video",
        "stop the video", "stop playing", "hold on", "wait" -> Command.Pause
        "mute", "mute it", "mute video", "mute the video", "sound off", "volume off", "turn off sound",
        "turn off the sound", "silence" -> Command.Mute
        "unmute", "unmute it", "unmute video", "sound on", "volume on", "turn on sound", "turn on the sound" -> Command.Unmute
        "zoom in", "bigger", "make it bigger" -> Command.ZoomIn
        "zoom out", "smaller", "make it smaller" -> Command.ZoomOut
        "enter", "press enter", "submit", "send", "go", "ok", "okay", "done" -> Command.PressEnter
        else -> null
    }

    /** "type hello", "write happy birthday and send", "enter cats and search". */
    private fun parseTyping(text: String): Command? {
        val m = Regex("^(?:type|write|input|enter)(?: in)? (.+?)(?: (?:and|then) (search|enter|submit|send|go|post|press enter))?$")
            .find(text) ?: return null
        val typed = m.groupValues[1].trim()
        if (typed.isEmpty()) return null
        return Command.TypeText(typed, submit = m.groupValues[2].isNotEmpty())
    }

    /** "click subscribe", "tap on the like button", "press next". */
    private fun parseTap(text: String): Command? {
        val verb = tapVerbs.firstOrNull { text.startsWith("$it ") } ?: return null
        var target = text.removePrefix("$verb ").trim().removePrefix("the ").trim()
        if (target == "enter") return Command.PressEnter
        parseNth(target)?.let { return it }
        target = target.removeSuffix(" button").removeSuffix(" icon").removeSuffix(" link").removeSuffix(" tab").trim()
        if (target.isEmpty()) return null
        pageButtonFor(target)?.let { return it }
        return Command.Tap(target, listOf(target), fallbackSearch = false)
    }

    /** "open the first video", "play the second one", "third result". */
    private fun parseNth(text: String): Command? {
        val m = Regex(
            "^(?:(?:open|play|watch|click|tap|select|choose|pick|show|go to)(?: on)? )?(?:the )?(?:number )?" +
                "(${ordinals.keys.joinToString("|")})(?: ($itemWords))?$",
        ).find(text) ?: return null
        // A bare number on its own ("2") is too likely to be noise.
        if (m.groupValues[2].isEmpty() && m.groupValues[1].all { it.isDigit() } && !text.contains(' ')) return null
        return Command.TapNth(ordinals.getValue(m.groupValues[1]))
    }

    private fun parseScrollAndFeed(text: String): Command? {
        // "search for the next episode" is a search, not a swipe.
        if (listOf("search ", "find ", "look for ", "look up ").any { text.startsWith(it) }) return null
        val words = text.split(' ').toSet()
        val mentionsItem = words.any { it in itemWords.split('|') }

        // Next / previous video, post or reel.
        if ("previous" in words || ("last" in words && mentionsItem) ||
            text == "swipe down" || text.startsWith("go back to the previous") || "before" in words && mentionsItem
        ) {
            return Command.PreviousItem
        }
        if ("next" in words || "skip" in words && mentionsItem || "another" in words && mentionsItem ||
            text == "swipe up" || text == "swipe" || text.startsWith("swipe to")
        ) {
            return Command.NextItem
        }

        val isScroll = words.any { it in setOf("scroll", "swipe", "move", "page") } || text in setOf("down", "up", "more", "show more", "see more")
        if (!isScroll) return null
        return when {
            "top" in words || "beginning" in words || "start" in words -> Command.ScrollTop
            "bottom" in words || "end" in words -> Command.ScrollBottom
            "up" in words -> Command.ScrollUp
            else -> Command.ScrollDown
        }
    }

    private fun parsePageButton(text: String): Command? = pageButtonFor(text)

    private fun pageButtonFor(text: String): Command? {
        val (_, labels) = pageButtons.firstOrNull { (regex, _) -> regex.matches(text) } ?: return null
        return Command.Tap(labels.first(), labels, fallbackSearch = false)
    }

    private fun parseOpen(text: String): Command? {
        val verb = openVerbs.firstOrNull { text.startsWith("$it ") } ?: return null
        val rest = text.removePrefix("$verb ").trim()
        if (rest == "home" || rest == "home screen") return Command.Home
        ArrowApp.fromSpoken(rest)?.let { return Command.Open(it) }
        val target = rest.removePrefix("the ").removePrefix("my ").trim()
        if (target.isEmpty()) return null
        // "open settings", "show me cats": tap it if it's on the page, otherwise search for it.
        return Command.Tap(target, listOf(target), fallbackSearch = true)
    }

    private fun parseSearch(text: String): Command? {
        val verb = searchVerbs.firstOrNull { text.startsWith("$it ") } ?: return null
        var body = text.removePrefix("$verb ").trim()

        // "watch youtube" means open it, not search for the word "youtube".
        exactApp(body)?.let { return Command.Open(it) }

        // "search on tiktok for cats" / "search tiktok for cats"
        Regex("^(?:on |in )?(.+?) for (.+)$").find(body)?.let { m ->
            val app = exactApp(m.groupValues[1])
            if (app != null) return Command.Search(m.groupValues[2].trim(), app)
        }

        // "search cats on tiktok" / "play music on youtube"
        var app: ArrowApp? = null
        Regex("^(.+) (?:on|in|using|with) (.+)$").find(body)?.let { m ->
            val candidate = ArrowApp.fromSpoken(m.groupValues[2])
            if (candidate != null) {
                app = candidate
                body = m.groupValues[1].trim()
            }
        }

        if (body.isEmpty()) return null
        return Command.Search(body, app)
    }

    private fun exactApp(text: String): ArrowApp? {
        val t = text.trim()
        return ArrowApp.entries.firstOrNull { app -> app.aliases.any { it == t } }
    }

    /** Lowercase, strip punctuation and polite filler so "Please open YouTube!" == "open youtube". */
    fun normalize(raw: String): String {
        var t = raw.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val fillers = listOf(
            "hey arrow", "ok arrow", "okay arrow", "arrow", "please", "can you", "could you", "would you",
            "will you", "i want to", "i wanna", "i would like to", "i d like to", "let s", "lets", "now", "just",
            "kindly", "hey", "ok", "okay",
        )
        var changed = true
        while (changed) {
            changed = false
            for (f in fillers) {
                if (t.startsWith("$f ")) { t = t.removePrefix("$f ").trim(); changed = true }
            }
        }
        for (suffix in listOf(" please", " for me", " now", " right now")) {
            t = t.removeSuffix(suffix).trim()
        }
        return t
    }
}
