package com.arrowos.app

import java.net.URI

/** One page the user opened inside Arrow Os. */
data class PreviousTab(
    val url: String,
    val title: String,
    /** When the page was last opened, in milliseconds since 1970. */
    val visitedAt: Long,
) {
    /** "m.youtube.com" style name for the second line of the list. */
    val host: String
        get() = try {
            URI(url).host?.removePrefix("www.") ?: url
        } catch (e: Exception) {
            url
        }
}

/**
 * The "Previous" list: every page ever opened in Arrow Os, newest first.
 *
 * Opening the same page again moves it back to the top instead of adding a
 * duplicate. The list is capped at [maxSize] so it never grows without limit.
 *
 * Pure Kotlin, no Android types, so it is covered by plain JVM unit tests.
 * MainActivity saves [encode]d text in SharedPreferences and [decode]s it on launch.
 */
class TabHistory(private val maxSize: Int = 300) {

    private val tabs = mutableListOf<PreviousTab>()

    /** Newest first. */
    val all: List<PreviousTab> get() = tabs.toList()

    val size: Int get() = tabs.size

    fun isEmpty() = tabs.isEmpty()

    /** The [n]th tab in the list (1-based, newest is 1), or null. */
    fun nth(n: Int): PreviousTab? = tabs.getOrNull(n - 1)

    /**
     * Remembers that [url] was opened. Returns true when the list changed.
     * Blank pages, error pages and other non-web addresses are skipped.
     */
    fun record(url: String?, title: String?, now: Long): Boolean {
        val clean = url?.trim().orEmpty()
        if (!isWorthKeeping(clean)) return false
        val key = keyOf(clean)
        val existing = tabs.indexOfFirst { keyOf(it.url) == key }
        val keptTitle = title?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("http") }
            ?: tabs.getOrNull(existing)?.title
            ?: ""
        if (existing >= 0) tabs.removeAt(existing)
        tabs.add(0, PreviousTab(clean, keptTitle, now))
        while (tabs.size > maxSize) tabs.removeAt(tabs.size - 1)
        return true
    }

    /** The page's name arrived after it was recorded (sites often set it late). */
    fun updateTitle(url: String?, title: String?): Boolean {
        val clean = url?.trim().orEmpty()
        val t = title?.trim().orEmpty()
        if (t.isEmpty() || t.startsWith("http") || !isWorthKeeping(clean)) return false
        val key = keyOf(clean)
        val i = tabs.indexOfFirst { keyOf(it.url) == key }
        if (i < 0 || tabs[i].title == t) return false
        tabs[i] = tabs[i].copy(title = t)
        return true
    }

    /**
     * The newest tab whose name or site matches what the user said ("tom and jerry",
     * "bbc"), or null. Whole-name matches win over partial ones.
     */
    fun find(spoken: String): PreviousTab? {
        val words = simplify(spoken)
        if (words.isEmpty()) return null
        return tabs.firstOrNull { simplify(it.title) == words }
            ?: tabs.firstOrNull { simplify(it.title).contains(words) }
            ?: tabs.firstOrNull { simplify(it.host).replace(" ", "").contains(words.replace(" ", "")) }
    }

    fun clear() = tabs.clear()

    /** One tab per line: time, url and title separated by tabs, with \t \n \\ escaped. */
    fun encode(): String = tabs.joinToString("\n") { tab ->
        listOf(tab.visitedAt.toString(), escape(tab.url), escape(tab.title)).joinToString("\t")
    }

    companion object {
        fun decode(text: String?, maxSize: Int = 300): TabHistory {
            val history = TabHistory(maxSize)
            text.orEmpty().lineSequence().forEach { line ->
                val parts = line.split('\t')
                if (parts.size < 2) return@forEach
                val time = parts[0].toLongOrNull() ?: return@forEach
                val url = unescape(parts[1])
                val title = parts.getOrNull(2)?.let { unescape(it) }.orEmpty()
                if (history.tabs.size < maxSize && isWorthKeeping(url)) {
                    history.tabs.add(PreviousTab(url, title, time))
                }
            }
            return history
        }

        fun isWorthKeeping(url: String): Boolean =
            (url.startsWith("http://") || url.startsWith("https://")) && url.length > "https://".length

        /** "https://m.youtube.com/watch?v=1#t" and ".../watch?v=1" are the same page. */
        private fun keyOf(url: String): String = url.substringBefore('#').removeSuffix("/")

        private fun simplify(s: String) =
            s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unescape(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        else -> out.append(s[i + 1])
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
