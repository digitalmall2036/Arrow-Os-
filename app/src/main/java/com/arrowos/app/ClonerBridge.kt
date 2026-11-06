package com.arrowos.app

import android.webkit.JavascriptInterface
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * What the Arrow Cloner page (assets/cloner) can ask Arrow Os to do. The page is
 * Arrow Os's own file, never a website, and only it gets this bridge.
 *
 * Calls arrive on a background thread; [Host] moves anything that touches the screen
 * to the main thread. Answers that take time come back through `Cloner.onCallback(id, json)`.
 */
class ClonerBridge(private val host: Host) {

    interface Host {
        fun clonerLoadBrain(): String
        fun clonerSaveBrain(json: String)
        fun clonerAccount(): String
        fun clonerCallback(id: String, json: String)
        fun clonerPickFiles(callbackId: String, folder: Boolean)
        fun clonerStartRecording(title: String, withAudio: Boolean)
        fun clonerOpen(url: String, title: String)
        fun clonerRunJob(json: String)
        fun clonerJobContinue()
        fun clonerStopJob()
        fun clonerHomeTools(): String
        fun clonerAddTools(idsJson: String, profession: String)
        fun clonerRemoveTool(id: String)
        fun clonerSpeak(text: String)
        fun clonerReadAloud(text: String, id: String)
        fun clonerStopReading()
        fun clonerListen(callbackId: String)
        fun clonerGoHome()
        fun clonerToast(text: String)
        fun clonerShowFiles()
        fun clonerRunInBackground(work: () -> Unit)
        fun clonerListenToPage(url: String, title: String)
        fun clonerPickAndListen(title: String)
        fun clonerListenToFile(fileId: String, title: String)
        fun clonerStopListening()
        fun clonerSaveLearned(title: String, text: String)
        fun clonerPhoneFiles(query: String, callbackId: String)
    }

    @JavascriptInterface fun loadBrain(): String = host.clonerLoadBrain()
    @JavascriptInterface fun saveBrain(json: String) = host.clonerSaveBrain(json)
    @JavascriptInterface fun account(): String = host.clonerAccount()

    /** Reads any web page for the worker (no browser limits): {ok, status, url, body}. */
    @JavascriptInterface fun fetch(url: String, callbackId: String) {
        host.clonerRunInBackground { host.clonerCallback(callbackId, download(url).toString()) }
    }

    @JavascriptInterface fun pickFiles(callbackId: String) = host.clonerPickFiles(callbackId, folder = false)
    @JavascriptInterface fun pickFolder(callbackId: String) = host.clonerPickFiles(callbackId, folder = true)
    @JavascriptInterface fun startRecording(title: String, withAudio: Boolean) = host.clonerStartRecording(title, withAudio)
    @JavascriptInterface fun open(url: String, title: String) = host.clonerOpen(url, title)
    @JavascriptInterface fun runJob(json: String) = host.clonerRunJob(json)
    @JavascriptInterface fun jobContinue() = host.clonerJobContinue()
    @JavascriptInterface fun stopJob() = host.clonerStopJob()

    /** Tools for the picker, as JSON: [{id, label, url, color, description}]. */
    @JavascriptInterface fun searchTools(query: String): String = toolsJson(AutoDesigner.searchTools(query))
    @JavascriptInterface fun suggestTools(profession: String): String = toolsJson(AutoDesigner.suggest(profession).all)

    /** A website typed into the picker that isn't in the catalog, or "" when it isn't an address. */
    @JavascriptInterface fun customTool(label: String, address: String): String {
        val id = CustomSites.idFor(label, address) ?: return ""
        return CustomSites.toolFor(id)?.let { toolsJson(listOf(it)) } ?: ""
    }

    @JavascriptInterface fun homeTools(): String = host.clonerHomeTools()
    @JavascriptInterface fun addTools(idsJson: String, profession: String) = host.clonerAddTools(idsJson, profession)
    @JavascriptInterface fun removeTool(id: String) = host.clonerRemoveTool(id)
    @JavascriptInterface fun speak(text: String) = host.clonerSpeak(text)
    /** Reads a long text out loud; sends a "reading" event when it starts and ends. */
    @JavascriptInterface fun readAloud(text: String, id: String) = host.clonerReadAloud(text, id)
    @JavascriptInterface fun stopReading() = host.clonerStopReading()
    @JavascriptInterface fun listen(callbackId: String) = host.clonerListen(callbackId)
    @JavascriptInterface fun goHome() = host.clonerGoHome()
    @JavascriptInterface fun toast(text: String) = host.clonerToast(text)
    @JavascriptInterface fun showFiles() = host.clonerShowFiles()

    /** Watch and listen: the video plays on the phone and the mic writes down what it hears. */
    @JavascriptInterface fun listenToPage(url: String, title: String) = host.clonerListenToPage(url, title)
    @JavascriptInterface fun pickAndListen(title: String) = host.clonerPickAndListen(title)
    @JavascriptInterface fun listenToFile(fileId: String, title: String) = host.clonerListenToFile(fileId, title)
    @JavascriptInterface fun stopListening() = host.clonerStopListening()

    /** Keeps something learned as a text file in Arrow Files ("Arrow Cloner - Learned"). */
    @JavascriptInterface fun saveLearned(title: String, text: String) = host.clonerSaveLearned(title, text)

    /** Files already on the phone that fit a topic: {files: [{fileId, name, kind, media, text}]}. */
    @JavascriptInterface fun phoneFiles(query: String, callbackId: String) = host.clonerPhoneFiles(query, callbackId)

    companion object {
        private const val MAX_BYTES = 1_500_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        fun toolsJson(tools: List<BusinessTool>): String = org.json.JSONArray().apply {
            tools.forEach { t ->
                put(
                    JSONObject().put("id", t.id).put("label", t.label).put("url", t.url)
                        .put("color", t.colorHex).put("description", t.description).put("initial", t.initial),
                )
            }
        }.toString()

        /** Downloads [address] (http or https only, at most 1.5 MB), following redirects. */
        fun download(address: String): JSONObject {
            val result = JSONObject().put("ok", false).put("status", 0).put("url", address).put("body", "")
            if (!address.startsWith("https://") && !address.startsWith("http://")) return result.put("error", "not a web address")
            var conn: HttpURLConnection? = null
            try {
                var url = URL(address)
                var hops = 0
                while (true) {
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 20_000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", USER_AGENT)
                        setRequestProperty("Accept-Language", "en")
                        setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                    }
                    val code = conn.responseCode
                    val next = conn.getHeaderField("Location")
                    if (code in 300..399 && next != null && hops < 5) {
                        url = URL(url, next)
                        conn.disconnect()
                        hops++
                        continue
                    }
                    result.put("status", code).put("url", url.toString())
                    val stream = if (code >= 400) conn.errorStream else conn.inputStream
                    val out = ByteArrayOutputStream()
                    stream?.use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (out.size() < MAX_BYTES) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                        .find(conn.contentType.orEmpty())?.groupValues?.get(1) ?: "UTF-8"
                    val body = try { String(out.toByteArray(), charset(charset)) } catch (e: Exception) { String(out.toByteArray()) }
                    return result.put("ok", code in 200..299).put("body", body)
                }
            } catch (e: Exception) {
                return result.put("error", e.message ?: "could not connect")
            } finally {
                conn?.disconnect()
            }
            return result
        }
    }
}
