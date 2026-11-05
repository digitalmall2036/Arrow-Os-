package com.arrowos.app

import java.net.URLEncoder

/** Addresses for the open web: searches, websites and backup addresses for blocked sites. */
object WebLinks {

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    /** A normal web search (used outside the five apps). */
    fun searchUrl(query: String) = "https://www.google.com/search?q=${enc(query)}"

    /**
     * The address to open for a website. An address like "claude.ai" opens directly;
     * a name like "claude code" goes to the top search result (DuckDuckGo's
     * "I'm feeling ducky"), which is almost always the official site.
     */
    fun websiteUrl(target: String): String {
        val t = target.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            CommandParser.looksLikeAddress(t) -> "https://$t"
            else -> "https://duckduckgo.com/?q=${enc("!ducky $t")}"
        }
    }

    /**
     * Other addresses that serve the same site, tried in order when one can't be
     * reached. Some networks block m.facebook.com but not web. or mbasic.
     */
    fun alternativesFor(host: String): List<String> = when (host) {
        "m.facebook.com", "facebook.com", "www.facebook.com", "web.facebook.com", "mbasic.facebook.com", "touch.facebook.com" ->
            listOf("m.facebook.com", "web.facebook.com", "mbasic.facebook.com", "touch.facebook.com", "www.facebook.com")
        "www.instagram.com", "instagram.com", "m.instagram.com" -> listOf("www.instagram.com", "m.instagram.com")
        "m.youtube.com", "youtube.com", "www.youtube.com" -> listOf("m.youtube.com", "www.youtube.com")
        "www.tiktok.com", "tiktok.com", "m.tiktok.com" -> listOf("www.tiktok.com", "m.tiktok.com")
        // The WhatsApp Business advert site is refused on many networks; WhatsApp Web runs the account itself.
        "business.whatsapp.com", "www.whatsapp.com", "whatsapp.com" -> listOf("web.whatsapp.com")
        else -> emptyList()
    }
}
