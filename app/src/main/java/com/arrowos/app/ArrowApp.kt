package com.arrowos.app

import java.net.URLEncoder

/**
 * The apps that live inside Arrow Os. Each one opens as a mobile website
 * inside the built-in browser, so the user never leaves Arrow Os.
 */
enum class ArrowApp(
    val label: String,
    val homeUrl: String,
    val colorHex: String,
    /** Words the user might say for this app. Lowercase, normalized. */
    val aliases: List<String>,
) {
    YOUTUBE(
        label = "YouTube",
        homeUrl = "https://m.youtube.com/",
        colorHex = "#FF0033",
        aliases = listOf("youtube", "you tube", "yt"),
    ),
    FACEBOOK(
        label = "Facebook",
        homeUrl = "https://m.facebook.com/",
        colorHex = "#1877F2",
        aliases = listOf("facebook", "face book", "fb"),
    ),
    INSTAGRAM(
        label = "Instagram",
        homeUrl = "https://www.instagram.com/",
        colorHex = "#C13584",
        aliases = listOf("instagram", "insta", "ig"),
    ),
    TIKTOK(
        label = "TikTok",
        homeUrl = "https://www.tiktok.com/",
        colorHex = "#111111",
        aliases = listOf("tiktok", "tik tok", "tick tock", "tic tac", "tiktoks"),
    ),
    NINE_APPS(
        label = "9apps",
        homeUrl = "https://www.9apps.com/",
        colorHex = "#2FB344",
        aliases = listOf("9apps", "9 apps", "nine apps", "nineapps", "9 app", "nine app"),
    );

    /** The letter shown on the home screen tile. */
    val initial: String get() = label.take(1).uppercase()

    fun searchUrl(query: String): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return when (this) {
            YOUTUBE -> "https://m.youtube.com/results?search_query=$q"
            FACEBOOK -> "https://m.facebook.com/search/top/?q=$q"
            // Instagram search needs a login; hashtag pages are the closest open equivalent.
            INSTAGRAM -> "https://www.instagram.com/explore/tags/" +
                URLEncoder.encode(query.filter { it.isLetterOrDigit() }.lowercase(), "UTF-8") + "/"
            TIKTOK -> "https://www.tiktok.com/search?q=$q"
            NINE_APPS -> "https://www.google.com/search?q=site%3A9apps.com+$q"
        }
    }

    companion object {
        /** Finds the app the user named, or null. Longest alias wins ("tik tok" before "tik"). */
        fun fromSpoken(text: String): ArrowApp? {
            val normalized = CommandParser.normalize(text)
            if (normalized.isEmpty()) return null
            return entries
                .flatMap { app -> app.aliases.map { alias -> alias to app } }
                .sortedByDescending { it.first.length }
                .firstOrNull { (alias, _) -> normalized == alias || containsWord(normalized, alias) }
                ?.second
        }

        private fun containsWord(text: String, phrase: String): Boolean =
            Regex("(^|\\s)${Regex.escape(phrase)}($|\\s)").containsMatchIn(text)
    }
}
