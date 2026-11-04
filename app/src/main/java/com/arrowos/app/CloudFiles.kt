package com.arrowos.app

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The cloud copy of Arrow Files: Supabase Storage (free plan), private bucket "arrow-files".
 * Objects use the same path as on the phone: ArrowFiles/<uid>/websites/<site>/<file>.
 *
 * Requests carry the user's Firebase sign-in token; Supabase trusts Firebase (third-party
 * auth) and its storage rules (supabase-storage.sql) only let a user reach ArrowFiles/<their uid>/.
 * Every call blocks, so run it off the main thread.
 */
class CloudFiles(private val token: () -> String?) {

    /** Sends [local] to [path], replacing any older copy. */
    fun upload(local: File, path: String, mimeType: String): Boolean {
        val conn = open("object/$BUCKET/", path, "POST") ?: return false
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", mimeType.ifBlank { "application/octet-stream" })
            conn.setRequestProperty("x-upsert", "true")
            conn.setFixedLengthStreamingMode(local.length())
            conn.outputStream.use { out -> local.inputStream().use { it.copyTo(out) } }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /** Fetches [path] into [target]; on failure nothing is left behind. */
    fun download(path: String, target: File): Boolean {
        val conn = open("object/authenticated/$BUCKET/", path, "GET") ?: return false
        val part = File(target.path + ".part")
        return try {
            if (conn.responseCode !in 200..299) return false
            target.parentFile?.mkdirs()
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            part.renameTo(target)
        } catch (e: Exception) {
            false
        } finally {
            part.delete()
            conn.disconnect()
        }
    }

    fun delete(path: String): Boolean {
        val conn = open("object/$BUCKET/", path, "DELETE") ?: return false
        return try {
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun open(prefix: String, path: String, method: String): HttpURLConnection? {
        val bearer = token() ?: return null
        return try {
            (URL("$PROJECT_URL/storage/v1/$prefix${encodePath(path)}").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("apikey", ANON_KEY)
                setRequestProperty("Authorization", "Bearer $bearer")
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Mj's Supabase project. The anon key is meant to ship inside apps; the rules do the guarding. */
        const val PROJECT_URL = "https://omgbyixmqlvuibbydeus.supabase.co"
        const val ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im9tZ2J5aXhtcWx2dWliYnlkZXVzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTM4MjA3OTMsImV4cCI6MjEwOTM5Njc5M30._UnrBzXfdIwvxipQTtV5fVXgDpMNj7mSZCdXZf43fxk"
        const val BUCKET = "arrow-files"

        /** Each folder and file name URL-encoded on its own: "a b/(2).pdf" → "a%20b/%282%29.pdf". */
        fun encodePath(path: String): String =
            path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}
