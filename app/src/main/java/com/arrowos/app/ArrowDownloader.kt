package com.arrowos.app

import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads files straight into Arrow Os's own private folder. Nothing goes through the
 * phone's download manager, so files never appear in the phone's Downloads, gallery or
 * notifications: they live only in Arrow Files.
 *
 * Two downloads run at a time (kind to 2 GB phones); the rest wait their turn.
 * Callbacks arrive on the main thread.
 */
class ArrowDownloader {

    interface Listener {
        fun onProgress(id: String, bytes: Long, total: Long)
        fun onFinished(id: String, ok: Boolean, size: Long, mimeType: String?)
    }

    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()

    fun isActive(id: String) = cancelled.containsKey(id)

    fun download(id: String, url: String, headers: Map<String, String>, target: File, listener: Listener) {
        val stop = AtomicBoolean(false)
        cancelled[id] = stop
        pool.execute {
            val part = File(target.path + ".part")
            var ok = false
            var mime: String? = null
            try {
                target.parentFile?.mkdirs()
                var conn = open(url, headers)
                // Follow redirects by hand too, since HttpURLConnection won't cross from http to https.
                var hops = 0
                while (conn.responseCode in 300..399 && hops < 8) {
                    val next = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    conn = open(URL(URL(conn.url.toString()), next).toString(), headers)
                    hops++
                }
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                mime = conn.contentType?.substringBefore(';')?.trim()
                val total = conn.contentLengthLong
                conn.inputStream.use { input -> copy(input, part, total, id, stop, listener) }
                conn.disconnect()
                if (!stop.get()) {
                    if (target.exists()) target.delete()
                    ok = part.renameTo(target)
                }
            } catch (e: Exception) {
                ok = false
            } finally {
                if (!ok) part.delete()
            }
            cancelled.remove(id)
            val size = if (ok) target.length() else 0L
            if (!stop.get()) main.post { listener.onFinished(id, ok, size, mime) }
        }
    }

    fun cancel(id: String) {
        cancelled.remove(id)?.set(true)
    }

    fun shutdown() {
        cancelled.values.forEach { it.set(true) }
        pool.shutdownNow()
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun copy(input: InputStream, part: File, total: Long, id: String, stop: AtomicBoolean, listener: Listener) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var lastReport = 0L
        part.outputStream().use { out ->
            while (!stop.get()) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (now - lastReport > 700) {
                    lastReport = now
                    val bytes = done
                    main.post { listener.onProgress(id, bytes, total) }
                }
            }
        }
    }
}
