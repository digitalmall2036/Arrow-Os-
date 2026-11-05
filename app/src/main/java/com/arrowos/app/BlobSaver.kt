package com.arrowos.app

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Saves "blob:" files. Many sites (file viewers, editors, Canva, CapCut) build the file inside
 * the page and hand over a blob: link that only that page can read. Arrow Os asks the page to
 * read it in pieces and pass them here, and writes them into Arrow Files.
 */
class BlobSaver {

    private class Job(val target: File, val out: OutputStream, val done: (ok: Boolean, mimeType: String?) -> Unit) {
        var mimeType: String? = null
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val main = Handler(Looper.getMainLooper())

    /** Starts reading [blobUrl] in [webView]'s page into [target]. */
    fun save(webView: WebView, blobUrl: String, id: String, target: File, done: (ok: Boolean, mimeType: String?) -> Unit) {
        target.parentFile?.mkdirs()
        jobs[id] = Job(target, target.outputStream().buffered(), done)
        val js = """
            (function(u,id){
              var B=window.$NAME; if(!B) return;
              fetch(u).then(function(r){return r.blob();}).then(function(b){
                B.begin(id, b.type||'');
                var CH=393216, off=0;
                function next(){
                  if(off>=b.size){B.end(id);return;}
                  var fr=new FileReader();
                  fr.onload=function(){var s=fr.result;B.chunk(id,s.substring(s.indexOf(',')+1));off+=CH;next();};
                  fr.onerror=function(){B.fail(id);};
                  fr.readAsDataURL(b.slice(off,off+CH));
                }
                next();
              }).catch(function(){B.fail(id);});
            })(${quote(blobUrl)},${quote(id)});
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        // A page that never answers (the blob was already freed) shouldn't leave the job open forever.
        main.postDelayed({ if (jobs.containsKey(id)) fail(id) }, 120_000)
    }

    @JavascriptInterface
    fun begin(id: String, mimeType: String) {
        jobs[id]?.mimeType = mimeType.ifBlank { null }
    }

    @JavascriptInterface
    fun chunk(id: String, base64: String) {
        val job = jobs[id] ?: return
        try {
            job.out.write(Base64.decode(base64, Base64.DEFAULT))
        } catch (e: Exception) {
            fail(id)
        }
    }

    @JavascriptInterface
    fun end(id: String) {
        val job = jobs.remove(id) ?: return
        val ok = try {
            job.out.close()
            job.target.length() > 0
        } catch (e: Exception) {
            false
        }
        if (!ok) job.target.delete()
        main.post { job.done(ok, job.mimeType) }
    }

    @JavascriptInterface
    fun fail(id: String) {
        val job = jobs.remove(id) ?: return
        try {
            job.out.close()
        } catch (e: Exception) {
            // nothing to close
        }
        job.target.delete()
        main.post { job.done(false, null) }
    }

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    companion object {
        const val NAME = "ArrowBlob"
    }
}
