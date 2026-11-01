package com.arrowos.app

/** What the user asked Arrow Os to do. */
sealed class Command {
    data class Open(val app: ArrowApp) : Command()
    /** [app] is null when the user didn't name one; the current app (or YouTube) is used. */
    data class Search(val query: String, val app: ArrowApp?) : Command()
    data object Back : Command()
    data object Home : Command()
    data object ScrollDown : Command()
    data object ScrollUp : Command()
    data object Reload : Command()
    data object StopListening : Command()
    data class Unknown(val heard: String) : Command()
}

/**
 * Turns recognized speech into a [Command]. Pure Kotlin, no Android types,
 * so it is covered by plain JVM unit tests.
 */
object CommandParser {

    private val openVerbs = listOf("open", "launch", "start", "show", "go to", "take me to", "switch to")
    private val searchVerbs = listOf("search for", "search", "find", "look for", "look up", "play", "watch")

    fun parse(raw: String): Command {
        val text = normalize(raw)
        if (text.isEmpty()) return Command.Unknown(raw)

        when (text) {
            "back", "go back", "previous", "previous page", "return" -> return Command.Back
            "home", "go home", "home screen", "close", "close app", "exit app" -> return Command.Home
            "scroll down", "down", "next" -> return Command.ScrollDown
            "scroll up", "up" -> return Command.ScrollUp
            "reload", "refresh", "reload page", "refresh page" -> return Command.Reload
            "stop listening", "stop", "be quiet", "mute" -> return Command.StopListening
        }

        parseSearch(text)?.let { return it }

        for (verb in openVerbs) {
            if (text.startsWith("$verb ")) {
                val rest = text.removePrefix("$verb ").trim()
                if (rest == "home" || rest == "home screen") return Command.Home
                ArrowApp.fromSpoken(rest)?.let { return Command.Open(it) }
            }
        }

        // Just saying the app's name ("youtube") opens it.
        exactApp(text)?.let { return Command.Open(it) }

        return Command.Unknown(raw)
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
        val fillers = listOf("hey arrow", "ok arrow", "okay arrow", "arrow", "please", "can you", "could you", "i want to", "i wanna")
        var changed = true
        while (changed) {
            changed = false
            for (f in fillers) {
                if (t.startsWith("$f ")) { t = t.removePrefix("$f ").trim(); changed = true }
                if (t == f) { t = ""; changed = true }
            }
        }
        t = t.removeSuffix(" please").trim()
        return t
    }
}
