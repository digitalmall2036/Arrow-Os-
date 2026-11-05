package com.arrowos.app

import java.net.URI
import java.util.Locale

/**
 * Rules for how websites run inside Arrow Os: which sites need the desktop version,
 * which ad and tracker addresses are skipped to make pages load faster, which popups
 * are sign-in windows, and how to tell the user is signed in to Google.
 *
 * Plain Kotlin (no Android types), so plain JVM tests cover it.
 */
object SitePolicy {

    /**
     * Sites that only work with a computer browser. WhatsApp Web sends phones to a
     * "download the app" page unless the browser looks like a computer.
     */
    private val desktopHosts = setOf("web.whatsapp.com")

    fun needsDesktopSite(url: String?): Boolean = hostOf(url)?.let { it in desktopHosts } == true

    /**
     * Ad and tracker addresses Arrow Os doesn't load. They slow pages down and use data
     * without showing the user anything they asked for. Google's own sites (YouTube, Gmail)
     * are left alone so nothing on them breaks.
     */
    private val blockedDomains = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "google-analytics.com",
        "scorecardresearch.com",
        "adnxs.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "criteo.net",
        "amazon-adsystem.com",
        "moatads.com",
        "hotjar.com",
        "quantserve.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "adsrvr.org",
        "zedo.com",
        "popads.net",
        "propellerads.com",
    )

    private val googleDomains = listOf(
        "google.com", "youtube.com", "gmail.com", "googlevideo.com", "ytimg.com", "gstatic.com", "googleapis.com",
        "googleusercontent.com", "youtube-nocookie.com",
    )

    /** True when [requestUrl], asked for by the page on [pageUrl], is an ad or tracker to skip. */
    fun shouldBlock(requestUrl: String?, pageUrl: String?): Boolean {
        val host = hostOf(requestUrl) ?: return false
        if (!blockedDomains.any { host == it || host.endsWith(".$it") }) return false
        val page = hostOf(pageUrl) ?: return true
        return !isGoogleHost(page)
    }

    fun isGoogleHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        return googleDomains.any { h == it || h.endsWith(".$it") } || Regex("(^|\\.)google\\.[a-z.]+$").containsMatchIn(h)
    }

    /**
     * Addresses that sign-in popups open ("Sign in with Google", Facebook, Apple, Microsoft).
     * A popup to one of these stays a popup, so the site that opened it gets the answer back.
     * Any other popup becomes a normal page in Arrow Os.
     */
    fun isSignInPopup(url: String?): Boolean {
        val u = url?.lowercase(Locale.ROOT) ?: return false
        if (u.isBlank() || u == "about:blank") return true // filled in a moment later
        val host = hostOf(u) ?: return false
        return host == "accounts.google.com" ||
            host == "appleid.apple.com" ||
            host == "login.microsoftonline.com" ||
            host == "login.live.com" ||
            host.endsWith("auth0.com") ||
            host.endsWith("okta.com") ||
            (host.endsWith("facebook.com") && ("/dialog/oauth" in u || "/login" in u)) ||
            (host.endsWith("twitter.com") || host.endsWith("x.com")) && "oauth" in u ||
            Regex("[/?&._-](oauth2?|sso|signin|sign-in|login|authorize)([/?&._=-]|$)").containsMatchIn(u)
    }

    /** True when the Google cookies in [cookieHeader] show someone is signed in to Google. */
    fun signedInToGoogle(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        return cookieHeader.split(';').map { it.trim().substringBefore('=') }
            .any { it == "SID" || it == "__Secure-1PSID" || it == "__Secure-3PSID" }
    }

    /**
     * Errors that come from the phone's own connection (no signal, Wi-Fi switched, timed out)
     * rather than from the site. Arrow Os waits for the internet and reloads by itself.
     */
    fun isConnectionDrop(description: String): Boolean {
        val d = description.uppercase(Locale.ROOT)
        return listOf("INTERNET_DISCONNECTED", "NETWORK_CHANGED", "TIMED_OUT", "CONNECTION_TIMED_OUT", "NETWORK_IO_SUSPENDED",
            "ADDRESS_UNREACHABLE", "CONNECTION_ABORTED")
            .any { it in d }
    }

    /** The phone's mobile browser name, turned into a desktop one with the same Chrome version. */
    fun desktopUserAgent(mobileUserAgent: String): String {
        val chrome = Regex("Chrome/([0-9.]+)").find(mobileUserAgent)?.groupValues?.get(1)?.substringBefore('.') ?: "130"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.0.0 Safari/537.36"
    }

    /**
     * The WebView's own browser name, made to look like Chrome on the phone. With the WebView
     * marks ("; wv" and "Version/4.0") many sites hide features, push "Open the app", or refuse
     * Google sign-in.
     */
    fun chromeUserAgent(webViewUserAgent: String): String =
        webViewUserAgent
            .replace("; wv", "")
            .replace(Regex("Version/\\d+(\\.\\d+)* "), "")

    fun chromeMajorVersion(userAgent: String): String =
        Regex("Chrome/(\\d+)").find(userAgent)?.groupValues?.get(1) ?: "130"

    fun hostOf(url: String?): String? = try {
        URI(url.orEmpty().trim()).host?.lowercase(Locale.ROOT)
    } catch (e: Exception) {
        null
    }
}
