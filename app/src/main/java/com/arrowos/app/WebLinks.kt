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

    const val WHATSAPP_WEB = "https://web.whatsapp.com/"

    /**
     * WhatsApp inside Arrow Os is always WhatsApp Web. Any other WhatsApp address (the
     * business.whatsapp.com advert page, wa.me chat links, group invites, whatsapp:// links)
     * becomes the matching WhatsApp Web address. Returns null for anything else, and for
     * WhatsApp's help and blog pages, which work as normal websites.
     */
    fun whatsAppWebFor(url: String): String? {
        val u = url.trim()
        val scheme = u.substringBefore("://", "").lowercase()
        val rest = u.substringAfter("://", "")
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').lowercase().removePrefix("www.")
        val path = rest.removePrefix(rest.substringBefore('/').substringBefore('?')).substringBefore('#')
        val query = u.substringAfter('?', "").substringBefore('#')
        fun send(q: String) = if (q.isBlank()) WHATSAPP_WEB else "${WHATSAPP_WEB}send?$q"
        return when {
            scheme == "whatsapp" -> send(query)
            scheme != "http" && scheme != "https" -> null
            host == "web.whatsapp.com" -> null // already WhatsApp Web
            host == "wa.me" -> {
                val phone = path.trim('/').substringBefore('?').filter { it.isDigit() }
                val q = listOfNotNull(phone.takeIf { it.isNotEmpty() }?.let { "phone=$it" }, query.takeIf { it.isNotBlank() })
                send(q.joinToString("&"))
            }
            host == "api.whatsapp.com" -> send(query)
            host == "chat.whatsapp.com" -> path.trim('/').substringBefore('?').takeIf { it.isNotEmpty() }
                ?.let { "${WHATSAPP_WEB}accept?code=$it" } ?: WHATSAPP_WEB
            host == "whatsapp.com" || host == "business.whatsapp.com" || host == "m.whatsapp.com" -> WHATSAPP_WEB
            else -> null
        }
    }

    fun isWhatsAppWeb(url: String?): Boolean =
        url != null && url.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase() == "web.whatsapp.com"

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
        else -> emptyList()
    }
}
