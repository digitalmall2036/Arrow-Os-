package com.arrowos.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.media.projection.MediaProjectionManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.Html
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.arrowos.app.databinding.ActivityMainBinding
import com.arrowos.app.databinding.ItemAppTileBinding
import com.arrowos.app.databinding.ItemArrowFileBinding
import com.arrowos.app.databinding.ItemDesignerToolBinding
import com.arrowos.app.databinding.ItemPreviousTabBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), VoiceController.Listener, ClonerBridge.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var voice: VoiceController
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** The app currently open in the browser, or null on the home screen or a website. */
    private var currentApp: ArrowApp? = null

    /** Clear back-history once the next page commits, so Back never leads into the previous app. */
    private var resetHistoryOnNextPage = false

    /** Backup addresses still to try for the page that failed to load (see [WebLinks.alternativesFor]). */
    private var pendingAlternatives: MutableList<String> = mutableListOf()
    private var triedAlternatives = false

    /** Ask for the microphone at most once per launch; afterwards only when the mic button is tapped. */
    private var askedForMic = false

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileChooserCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileChooserCallback = null
    }

    /** The user turned the mic off with the button; don't auto-restart it on resume. */
    private var userMuted = false

    private lateinit var web: WebActions

    /** "Previous": every tab ever opened in Arrow Os, saved across launches. */
    private lateinit var previous: TabHistory
    private val prefs by lazy { getSharedPreferences("arrow_os", MODE_PRIVATE) }

    /** Arrow Files: every file downloaded in Arrow Os, saved across launches. */
    private lateinit var files: ArrowFilesIndex

    /** The signed-in Arrow Os account (Firebase email sign-in). */
    private val cloud by lazy { CloudAccount() }

    /** The cloud copy of Arrow Files (Supabase Storage), and the one background line that talks to it. */
    private val cloudFiles by lazy { CloudFiles { cloud.idTokenBlocking() } }
    private val cloudWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var uploading = false

    /** The signed-in user's id, or null on the sign-in screen. */
    private var accountUid: String? = null

    /** Whose folder downloads go to: the signed-in account's id. */
    private var filesUser = ArrowFilesIndex.LOCAL_USER

    /** Arrow Files screen state: the type and site filters, and whether Trash is showing. */
    private var filesKind: FileKind? = null
    private var filesSource: String? = null
    private var filesShowTrash = false

    /** "Save to phone" waiting for the storage permission (Android 9 and older only). */
    private var pendingSaveToPhone: ArrowFile? = null
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val file = pendingSaveToPhone
        pendingSaveToPhone = null
        if (granted && file != null) saveToPhone(file) else if (file != null) toast(getString(R.string.files_save_failed))
    }

    /** The phone tells us when a download finishes; the file is then ready in Arrow Files. */
    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            files.byDownloadId(id)?.let { finishDownload(it, announce = true) }
        }
    }

    /** The user's business and the apps they ticked in the Auto Designer; null until they finish it. */
    private var homeSetup: HomeSetup? = null

    /** Auto Designer state while it is open: what was searched, what is shown and what is ticked. */
    private var designerQuery = ""
    private var designerShown: List<BusinessTool> = emptyList()
    private val designerTicked = linkedSetOf<String>()

    /** The address the error page is standing in for; it is not a real visit. */
    private var errorPageUrl: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private var fullscreenView: View? = null

    /** The browser's own phone user agent (set in setupBrowser), used for every site but WhatsApp Web. */
    private var phoneUa: String? = null
    private fun phoneUserAgent(): String = phoneUa ?: binding.webView.settings.userAgentString
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else {
            binding.voiceStatus.text = getString(R.string.voice_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voice = VoiceController(this, this)
        web = WebActions(binding.webView)
        previous = TabHistory.decode(prefs.getString(PREF_PREVIOUS, null))
        files = ArrowFilesIndex() // filled in once the user is signed in
        ContextCompat.registerReceiver(
            this, downloadDone, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED,
        )
        setupSignIn()
        setupHomeScreen()
        setupFilesScreen()
        setupDesigner()
        setupPreviousScreen()
        setupBrowser()
        setupVoiceBar()
        setupBackHandling()
        setupTts()
        setupCloner()

        val uid = cloud.uid
        if (uid != null) beginSession(uid) else showSignIn()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        if (!userMuted && accountUid != null && !recordingWithMic) ensureMicPermissionThenListen(userAsked = false)
    }

    override fun onPause() {
        // Always-listening only while Arrow Os is on screen.
        voice.stop()
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(downloadDone)
        cloudWorker.shutdown()
        clonerWorker.shutdown()
        brainWriter.shutdown()
        jobHandler.removeCallbacksAndMessages(null)
        ScreenRecordService.onFinished = null
        binding.clonerWeb.destroy()
        voice.destroy()
        tts?.shutdown()
        binding.webView.destroy()
        super.onDestroy()
    }

    // ---------- Home screen ----------

    private fun setupHomeScreen() {
        binding.homePrevious.setOnClickListener { showPrevious() }
        binding.homeChangeBusiness.setOnClickListener { showDesigner() }
        binding.homeAccount.setOnClickListener { askToSignOut() }
        renderHomeApps()
    }

    /** The home screen shows exactly the apps the user ticked in the Auto Designer. */
    private fun renderHomeApps() {
        binding.appGrid.removeAllViews()
        val setup = homeSetup
        val tools = setup?.tools.orEmpty()
        val inflater = LayoutInflater.from(this)
        // Arrow Cloner, the digital worker, is always first; Arrow Files comes next.
        val clonerTile = ItemAppTileBinding.inflate(inflater, binding.appGrid, false)
        clonerTile.appIcon.text = ""
        // Mj's Arrow AI Cloner logo, in a round tile like the other apps.
        clonerTile.appIcon.backgroundTintList = null
        clonerTile.appIcon.background = ContextCompat.getDrawable(this, R.drawable.cloner_logo)
        clonerTile.appIcon.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clonerTile.appIcon.clipToOutline = true
        clonerTile.appLabel.text = getString(R.string.cloner_title)
        clonerTile.root.contentDescription = getString(R.string.open_app, getString(R.string.cloner_title))
        clonerTile.root.setOnClickListener { showCloner() }
        binding.appGrid.addView(clonerTile.root)
        // Arrow Files is always there too, whatever business the user picked.
        val filesTile = ItemAppTileBinding.inflate(inflater, binding.appGrid, false)
        filesTile.appIcon.text = ""
        filesTile.appIcon.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_folder, 0, 0)
        filesTile.appIcon.setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 0)
        filesTile.appIcon.backgroundTintList = ColorStateList.valueOf(Color.parseColor(FILES_COLOR))
        filesTile.appLabel.text = getString(R.string.files_title)
        filesTile.root.contentDescription = getString(R.string.open_app, getString(R.string.files_title))
        filesTile.root.setOnClickListener { showFiles() }
        binding.appGrid.addView(filesTile.root)
        tools.forEach { tool ->
            val tile = ItemAppTileBinding.inflate(inflater, binding.appGrid, false)
            tile.appIcon.text = tool.initial
            tile.appIcon.backgroundTintList = ColorStateList.valueOf(Color.parseColor(tool.colorHex))
            tile.appLabel.text = tool.label
            tile.appLabel.maxLines = 2
            tile.appLabel.gravity = Gravity.CENTER
            tile.root.contentDescription = getString(R.string.open_app, tool.label)
            tile.root.setOnClickListener { openTool(tool) }
            binding.appGrid.addView(tile.root)
        }
        binding.homeEmpty.isVisible = tools.isEmpty()
        binding.homeBusiness.isVisible = setup != null && setup.category.isNotBlank()
        binding.homeBusiness.text = getString(R.string.home_business, setup?.category.orEmpty())
    }

    /** Opens a home screen app straight away, inside Arrow Os. */
    private fun openTool(tool: BusinessTool) {
        if (tool.id == "whatsapp_business") {
            openWhatsAppBusiness()
            return
        }
        val app = ArrowApp.fromUrl(tool.url)
        if (app != null) openApp(app, tool.url) else openWebsite(tool.url, tool.label)
    }

    /**
     * WhatsApp Business: the real app when it is installed on the phone. If it isn't, offer to
     * install it from the Play Store, or to use WhatsApp Web inside Arrow Os (it works for
     * Business accounts too). business.whatsapp.com is only an advert page and some networks refuse it.
     */
    private fun openWhatsAppBusiness() {
        val launch = packageManager.getLaunchIntentForPackage(WHATSAPP_BUSINESS)
        if (launch != null) {
            confirm(getString(R.string.say_opening, "WhatsApp Business"))
            startActivity(launch)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.wab_title)
            .setMessage(R.string.wab_message)
            .setPositiveButton(R.string.wab_install) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$WHATSAPP_BUSINESS")))
                } catch (e: ActivityNotFoundException) {
                    openWebsite("https://play.google.com/store/apps/details?id=$WHATSAPP_BUSINESS", "Play Store")
                }
            }
            .setNeutralButton(R.string.wab_web) { _, _ -> openWebsite(WHATSAPP_WEB, "WhatsApp Web") }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHome() {
        hidePrevious()
        hideFiles()
        hideCloner()
        if (homeSetup == null) showDesigner()
        currentApp = null
        binding.homeScreen.isVisible = true
        binding.browserScreen.isVisible = false
        pendingAlternatives.clear()
        binding.webView.stopLoading()
        resetHistoryOnNextPage = true
        binding.webView.loadUrl("about:blank")
    }

    // ---------- In-app browser ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupBrowser() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            // Look like Chrome rather than an embedded WebView. With the WebView marker
            // ("; wv"), Instagram and Facebook hide features and push "Open the app".
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        }
        phoneUa = binding.webView.settings.userAgentString

        // Files (for example APKs from 9apps) download with Android's download manager.
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(view, request.url.toString())

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                binding.pageProgress.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.pageProgress.isVisible = false
                if (url != errorPageUrl && previous.updateTitle(url, view.title)) savePrevious()
                // On a website (not one of the five apps) show the page's name.
                if (currentApp == null && binding.browserScreen.isVisible) {
                    binding.browserTitle.text = view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }
                        ?: Uri.parse(url ?: "").host ?: getString(R.string.app_name)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (resetHistoryOnNextPage) {
                    resetHistoryOnNextPage = false
                    view.clearHistory()
                }
                // Every page (including in-page moves like a new YouTube video) goes into Previous.
                // The title is filled in by onReceivedTitle; view.title can still be the old page's here.
                if (url != errorPageUrl && previous.record(url, null, System.currentTimeMillis())) savePrevious()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                handleLoadFailure(view, request.url, error.errorCode, error.description?.toString() ?: "")
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                val url = view.url
                if (url != errorPageUrl && previous.updateTitle(url, title)) savePrevious()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.pageProgress.progress = newProgress
                binding.pageProgress.isVisible = newProgress < 100
            }

            // Full-screen video (YouTube, TikTok) stays inside Arrow Os too.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                exitFullscreen()
                fullscreenView = view
                fullscreenCallback = callback
                binding.fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER),
                )
                binding.fullscreenContainer.isVisible = true
            }

            override fun onHideCustomView() = exitFullscreen()

            // Lets sites ask for a photo or file (posting on Instagram/Facebook).
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null
                    toast("No app on this phone can pick that file")
                    false
                }
            }
        }

        binding.browserBack.setOnClickListener { goBack() }
        binding.browserHome.setOnClickListener { showHome() }
        binding.browserReload.setOnClickListener { binding.webView.reload() }
        binding.browserPrevious.setOnClickListener { showPrevious() }
        binding.browserFiles.setOnClickListener { showFiles() }
    }

    /** Keeps every link inside Arrow Os. Returns true when the WebView should NOT load [url] itself. */
    private fun handleUrl(view: WebView, url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://")) return false
        if (url.startsWith("intent://")) {
            // Sites try to bounce to the real app; use their web fallback instead.
            val fallback = try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            } catch (e: Exception) {
                null
            }
            if (fallback != null && fallback.startsWith("http")) {
                view.loadUrl(fallback)
                return true
            }
        }
        // fb://, instagram://, snssdk://, market:// ... would leave Arrow Os, so block them.
        return true
    }

    private fun exitFullscreen() {
        fullscreenView?.let { binding.fullscreenContainer.removeView(it) }
        binding.fullscreenContainer.isVisible = false
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView = null
        fullscreenCallback = null
    }

    private fun openApp(app: ArrowApp, url: String = app.homeUrl) {
        val switching = currentApp != app || !binding.browserScreen.isVisible
        currentApp = app
        binding.browserTitle.text = app.label
        load(url, freshHistory = switching)
    }

    /** Opens any website inside Arrow Os. */
    private fun openWebsite(url: String, title: String) {
        val switching = currentApp != null || !binding.browserScreen.isVisible
        currentApp = null
        binding.browserTitle.text = title
        load(url, freshHistory = switching)
    }

    private fun load(url: String, freshHistory: Boolean) {
        hidePrevious()
        hideDesigner()
        hideCloner()
        recordLessonEvent(JSONObject().put("kind", "open").put("url", url).put("title", binding.browserTitle.text.toString()))
        errorPageUrl = null
        binding.homeScreen.isVisible = false
        binding.browserScreen.isVisible = true
        pendingAlternatives.clear()
        triedAlternatives = false
        if (freshHistory) resetHistoryOnNextPage = true
        // WhatsApp Web only works with a computer browser's name; every other site gets the phone one.
        val wantUa = if (Uri.parse(url).host == "web.whatsapp.com") DESKTOP_UA else phoneUserAgent()
        if (binding.webView.settings.userAgentString != wantUa) binding.webView.settings.userAgentString = wantUa
        binding.webView.loadUrl(url)
    }

    /**
     * A page couldn't load. If the site has backup addresses (Facebook has several),
     * try the next one; otherwise show a clear explanation with a Retry button.
     */
    private fun handleLoadFailure(view: WebView, failed: Uri, errorCode: Int, description: String) {
        val host = failed.host ?: ""
        if (!triedAlternatives) {
            triedAlternatives = true
            pendingAlternatives = WebLinks.alternativesFor(host).filter { it != host }.toMutableList()
        }
        val next = pendingAlternatives.removeFirstOrNull()
        if (next != null) {
            binding.voiceStatus.text = getString(R.string.trying_other_address, next)
            view.loadUrl(failed.buildUpon().authority(next).build().toString())
            return
        }
        showErrorPage(view, failed.toString(), host, errorCode, description)
    }

    private fun showErrorPage(view: WebView, url: String, host: String, errorCode: Int, description: String) {
        val blocked = errorCode == WebViewClient.ERROR_CONNECT || errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
            description.contains("REFUSED") || description.contains("RESET") || description.contains("NAME_NOT_RESOLVED")
        val isFacebook = host.contains("facebook")
        val reason = when {
            isFacebook && blocked -> getString(R.string.error_facebook_blocked)
            blocked -> getString(R.string.error_blocked, host)
            else -> getString(R.string.error_generic, description)
        }
        val safeUrl = Html.escapeHtml(url)
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{background:#0B0F1A;color:#fff;font-family:sans-serif;padding:28px;line-height:1.5}
            h2{color:#00D1FF}button{background:#00D1FF;color:#0B0F1A;border:0;border-radius:24px;padding:12px 28px;font-size:16px;font-weight:bold}
            small{color:#9AA4BF;word-break:break-all}</style></head>
            <body><h2>${Html.escapeHtml(getString(R.string.error_title, host))}</h2>
            <p>${Html.escapeHtml(reason)}</p>
            <p><button onclick="location.href=decodeURIComponent('${Uri.encode(url)}')">${Html.escapeHtml(getString(R.string.retry))}</button></p>
            <small>$safeUrl<br>${Html.escapeHtml(description)}</small></body></html>
        """.trimIndent()
        errorPageUrl = url
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", url)
    }

    /**
     * Every download goes into the user's Arrow Files folder on the phone:
     * ArrowFiles/<user>/websites/<site>/<name>. The site is the page the user was on.
     */
    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val pageUrl = binding.webView.url?.takeIf { it.startsWith("http") } ?: url
        val source = ArrowFilesIndex.sourceOf(pageUrl)
        if (url.startsWith("data:")) {
            saveDataUrl(url, source, pageUrl)
            return
        }
        if (!url.startsWith("http")) {
            toast(getString(R.string.files_cant_download))
            return
        }
        try {
            val wanted = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val (name, relPath) = newFilePlace(source, wanted) ?: return
            val mime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: mimeOf(name)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(this@MainActivity, null, relPath)
            }
            val downloadId = getSystemService(DownloadManager::class.java).enqueue(request)
            var added = ""
            files.add(
                ArrowFile(
                    id = UUID.randomUUID().toString(), name = name, source = source, kind = FileKind.of(name, mime),
                    mimeType = mime, size = 0, savedAt = System.currentTimeMillis(), originUrl = pageUrl,
                    relPath = relPath, status = FileStatus.Downloading, downloadId = downloadId,
                ).also { added = it.id },
            )
            saveFiles()
            syncFile(added)
            toast(getString(R.string.files_downloading, name))
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
        }
    }

    /** Some sites hand the file over inside the link itself ("data:"); write it straight to Arrow Files. */
    private fun saveDataUrl(url: String, source: String, pageUrl: String) {
        try {
            val header = url.substringBefore(',')
            val body = url.substringAfter(',', "")
            val mime = header.removePrefix("data:").substringBefore(';').ifBlank { "application/octet-stream" }
            val bytes = if (header.contains(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            val (name, relPath) = newFilePlace(source, if (ext != null) "download.$ext" else "download") ?: return
            val target = File(getExternalFilesDir(null), relPath)
            target.writeBytes(bytes)
            var added = ""
            files.add(
                ArrowFile(
                    id = UUID.randomUUID().toString(), name = name, source = source, kind = FileKind.of(name, mime),
                    mimeType = mime, size = target.length(), savedAt = System.currentTimeMillis(), originUrl = pageUrl,
                    relPath = relPath, status = FileStatus.Ready,
                ).also { added = it.id },
            )
            saveFiles()
            syncFile(added)
            uploadWaitingFiles()
            toast(getString(R.string.files_saved, name))
        } catch (e: Exception) {
            toast(getString(R.string.files_failed, "file"))
        }
    }

    /** A free name and its path in the user's folder for [source], with the folder created. */
    private fun newFilePlace(source: String, wanted: String): Pair<String, String>? {
        val base = getExternalFilesDir(null)
        if (base == null) {
            toast(getString(R.string.files_save_failed))
            return null
        }
        val name = files.uniqueName(filesUser, source, wanted) { File(base, ArrowFilesIndex.pathFor(filesUser, source, it)).exists() }
        val relPath = ArrowFilesIndex.pathFor(filesUser, source, name)
        File(base, relPath).parentFile?.mkdirs()
        return name to relPath
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: "application/octet-stream"

    private fun fileOf(f: ArrowFile): File? = getExternalFilesDir(null)?.let { File(it, f.relPath) }

    /** Marks a download as ready or failed once the phone has finished with it. */
    private fun finishDownload(f: ArrowFile, announce: Boolean) {
        var status = -1
        try {
            getSystemService(DownloadManager::class.java)
                .query(DownloadManager.Query().setFilterById(f.downloadId))?.use { c ->
                    if (c.moveToFirst()) status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
        } catch (e: Exception) {
            // Fall back to checking the file on disk.
        }
        val onDisk = fileOf(f)?.takeIf { it.exists() && it.length() > 0 }
        val ready = status == DownloadManager.STATUS_SUCCESSFUL || (status == -1 && onDisk != null)
        val failed = !ready && (status == DownloadManager.STATUS_FAILED || status == -1)
        if (!ready && !failed) return // still downloading
        files.update(f.id) {
            if (ready) it.copy(status = FileStatus.Ready, size = onDisk?.length() ?: it.size, downloadId = -1)
            else it.copy(status = FileStatus.Failed, downloadId = -1)
        }
        saveFiles()
        syncFile(f.id)
        if (ready) uploadWaitingFiles()
        if (announce) toast(getString(if (ready) R.string.files_saved else R.string.files_failed, f.name))
        if (binding.filesScreen.isVisible) renderFiles()
    }

    /** Downloads that finished (or failed) while Arrow Os was closed. */
    private fun checkUnfinishedDownloads() {
        files.all.filter { it.status == FileStatus.Downloading }.forEach { finishDownload(it, announce = false) }
    }

    /** Files that sat in Trash for 30 days are removed for good. */
    private fun emptyOldTrash() {
        val expired = files.expiredTrash(System.currentTimeMillis())
        if (expired.isEmpty()) return
        expired.forEach { f ->
            fileOf(f)?.delete()
            if (f.inCloud) deleteCloudCopy(f)
            files.remove(f.id)
            syncFile(f.id)
        }
        saveFiles()
    }

    private fun saveFiles() {
        prefs.edit().putString(filesPrefKey(), files.encode()).apply()
    }

    /** Sends one file's record (or its removal) to the user's account in the cloud. */
    private fun syncFile(id: String) {
        val uid = accountUid ?: return
        val f = files.get(id)
        if (f != null) cloud.saveFile(uid, f) else cloud.deleteFile(uid, id)
    }

    private fun filesPrefKey() =
        if (filesUser == ArrowFilesIndex.LOCAL_USER) PREF_FILES else "${PREF_FILES}_$filesUser"

    private fun homeSetupPrefKey() =
        if (accountUid == null) PREF_HOME_SETUP else "${PREF_HOME_SETUP}_$accountUid"

    private fun goBack() {
        when {
            heardTitle != null -> stopHearing()
            fullscreenView != null -> exitFullscreen()
            binding.clonerScreen.isVisible -> binding.clonerWeb.evaluateJavascript("window.Cloner?Cloner.back():false") { handled ->
                if (handled != "true") showHome()
            }
            binding.designerScreen.isVisible -> if (homeSetup != null) hideDesigner()
            binding.previousScreen.isVisible -> hidePrevious()
            binding.filesScreen.isVisible && filesShowTrash -> {
                filesShowTrash = false
                renderFiles()
            }
            binding.filesScreen.isVisible -> hideFiles()
            binding.browserScreen.isVisible && binding.webView.canGoBack() -> binding.webView.goBack()
            binding.browserScreen.isVisible -> showHome()
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val firstDesign = (binding.designerScreen.isVisible && homeSetup == null) || binding.signInScreen.isVisible
                val onHome = binding.homeScreen.isVisible && !binding.designerScreen.isVisible && !binding.previousScreen.isVisible &&
                    !binding.filesScreen.isVisible && !binding.clonerScreen.isVisible
                if (fullscreenView == null && heardTitle == null && (onHome || firstDesign)) {
                    // On the Arrow Os home screen, back leaves the app as usual.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                } else {
                    goBack()
                }
            }
        })
    }

    // ---------- Auto Designer ----------

    private fun setupDesigner() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        AutoDesigner.popular.forEach { example ->
            val chip = TextView(this).apply {
                text = example
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 14f
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.chip_bg)
                setPadding(pad + pad / 3, pad * 2 / 3, pad + pad / 3, pad * 2 / 3)
                setOnClickListener {
                    binding.designerInput.setText(example)
                    runDesigner()
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = pad * 2 / 3
            binding.designerExamples.addView(chip, lp)
        }
        binding.designerFind.setOnClickListener { runDesigner() }
        binding.designerInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                runDesigner()
                true
            } else {
                false
            }
        }
        binding.designerCancel.setOnClickListener { hideDesigner() }
        binding.designerDone.setOnClickListener { saveDesigner() }
        binding.designerWebSearch.setOnClickListener {
            val query = getString(R.string.designer_web_search, designerQuery)
            openWebsite(WebLinks.searchUrl("best apps and websites for $designerQuery"), query)
        }
    }

    /**
     * Step 1 of the Auto Designer: the welcome bar asks for the user's business.
     * When the user already has a setup, it opens on their current choices so they can change them.
     */
    private fun showDesigner() {
        exitFullscreen()
        hidePrevious()
        binding.designerScreen.isVisible = true
        binding.commandBar.isVisible = false
        val setup = homeSetup
        binding.designerCancel.isVisible = setup != null
        if (designerShown.isNotEmpty()) return // came back from a web search: keep what was there
        if (setup != null) {
            binding.designerInput.setText(setup.category)
            designerTicked.clear()
            designerTicked.addAll(setup.toolIds)
            runDesigner(keepTicks = true)
        } else {
            binding.designerInput.text.clear()
            binding.designerResultTitle.text = getString(R.string.designer_start)
            binding.designerList.removeAllViews()
            binding.designerWebSearch.isVisible = false
            updateDesignerDone()
        }
    }

    private fun hideDesigner() {
        if (!binding.designerScreen.isVisible) return
        binding.designerScreen.isVisible = false
        binding.commandBar.isVisible = true
        hideKeyboard(binding.designerInput)
        if (homeSetup != null) {
            designerShown = emptyList()
            designerTicked.clear()
        }
    }

    /** Step 2: find the apps and websites for what the user typed, and list them with tick boxes. */
    private fun runDesigner(keepTicks: Boolean = false) {
        val query = binding.designerInput.text.toString().trim()
        if (query.isEmpty()) {
            toast(getString(R.string.designer_empty_input))
            return
        }
        hideKeyboard(binding.designerInput)
        binding.designerInput.clearFocus()
        designerQuery = query
        val found = AutoDesigner.suggest(query)
        designerShown = found.all
        // Ticks only make sense for apps on the list.
        val shownIds = designerShown.map { it.id }.toSet()
        if (!keepTicks) designerTicked.retainAll(shownIds)

        val categoryName = found.categories.joinToString(" & ") { it.name }
        binding.designerResultTitle.text = if (found.matched) {
            getString(R.string.designer_results, categoryName)
        } else {
            getString(R.string.designer_no_match, query)
        }

        val list = binding.designerList
        list.removeAllViews()
        if (found.forCategory.isNotEmpty()) {
            addDesignerHeader(getString(R.string.designer_section_category, categoryName))
            found.forCategory.forEach(::addDesignerRow)
        }
        addDesignerHeader(getString(R.string.designer_section_general))
        found.general.forEach(::addDesignerRow)

        binding.designerWebSearch.text = getString(R.string.designer_web_search, query.lowercase())
        binding.designerWebSearch.isVisible = true
        binding.designerScroll.scrollTo(0, 0)
        updateDesignerDone()
    }

    private fun addDesignerHeader(text: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val header = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            textSize = 13f
            setAllCaps(true)
            letterSpacing = 0.08f
            setPadding(pad, pad, pad, pad / 3)
        }
        binding.designerList.addView(header)
    }

    private fun addDesignerRow(tool: BusinessTool) {
        val row = ItemDesignerToolBinding.inflate(LayoutInflater.from(this), binding.designerList, false)
        row.toolIcon.text = tool.initial
        row.toolIcon.backgroundTintList = ColorStateList.valueOf(Color.parseColor(tool.colorHex))
        row.toolLabel.text = tool.label
        row.toolDescription.text = tool.description
        row.toolCheck.isChecked = tool.id in designerTicked
        row.root.contentDescription = "${tool.label}. ${tool.description}"
        row.root.setOnClickListener {
            if (!designerTicked.remove(tool.id)) designerTicked.add(tool.id)
            row.toolCheck.isChecked = tool.id in designerTicked
            updateDesignerDone()
        }
        binding.designerList.addView(row.root)
    }

    private fun updateDesignerDone() {
        val count = designerTicked.size
        binding.designerDone.isEnabled = count > 0
        binding.designerDone.alpha = if (count > 0) 1f else 0.5f
        binding.designerDone.text = if (count > 0) getString(R.string.designer_done, count) else getString(R.string.designer_done_none)
    }

    /** The ticked apps become the home screen, in the order they were listed. */
    private fun saveDesigner() {
        val ids = designerShown.map { it.id }.filter { it in designerTicked }
        if (ids.isEmpty()) return
        val category = designerQuery.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        val setup = HomeSetup(category, ids)
        homeSetup = setup
        prefs.edit().putString(homeSetupPrefKey(), setup.encode()).apply()
        accountUid?.let { cloud.saveHomeSetup(it, setup.encode()) }
        renderHomeApps()
        hideDesigner()
        if (!binding.homeScreen.isVisible) showHome()
        confirm(getString(R.string.designer_saved))
    }

    private fun hideKeyboard(view: View) {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ---------- Previous (tab history) ----------

    private fun setupPreviousScreen() {
        binding.previousClose.setOnClickListener { hidePrevious() }
        binding.previousClear.setOnClickListener { askToClearPrevious() }
    }

    /** Shows every tab ever opened in Arrow Os, newest first. */
    private fun showPrevious() {
        exitFullscreen()
        renderPrevious()
        binding.previousScreen.isVisible = true
        binding.previousScroll.scrollTo(0, 0)
    }

    private fun scrollPrevious(command: Command) {
        val scroll = binding.previousScroll
        val page = (scroll.height * 0.8).toInt()
        when (command) {
            Command.ScrollTop -> scroll.smoothScrollTo(0, 0)
            Command.ScrollBottom -> scroll.fullScroll(View.FOCUS_DOWN)
            Command.ScrollUp, Command.PreviousItem -> scroll.smoothScrollBy(0, -page)
            else -> scroll.smoothScrollBy(0, page)
        }
    }

    private fun hidePrevious() {
        binding.previousScreen.isVisible = false
    }

    private fun renderPrevious() {
        val list = binding.previousList
        list.removeAllViews()
        val tabs = previous.all
        binding.previousEmpty.isVisible = tabs.isEmpty()
        binding.previousHint.isVisible = tabs.isNotEmpty()
        binding.previousClear.isVisible = tabs.isNotEmpty()
        val inflater = LayoutInflater.from(this)
        val now = System.currentTimeMillis()
        tabs.forEachIndexed { i, tab ->
            val row = ItemPreviousTabBinding.inflate(inflater, list, false)
            val app = ArrowApp.fromUrl(tab.url)
            row.tabNumber.text = (i + 1).toString()
            row.tabNumber.backgroundTintList = ColorStateList.valueOf(
                if (app != null) Color.parseColor(app.colorHex) else ContextCompat.getColor(this, R.color.bg_panel),
            )
            row.tabTitle.text = tab.title.ifBlank { tab.host }
            val ago = DateUtils.getRelativeTimeSpanString(tab.visitedAt, now, DateUtils.MINUTE_IN_MILLIS)
            row.tabDetails.text = "${app?.label ?: tab.host} · $ago"
            row.root.contentDescription = "${i + 1}. ${row.tabTitle.text}"
            row.root.setOnClickListener { openPreviousTab(tab) }
            list.addView(row.root)
        }
    }

    /** Takes the user straight back to [tab], inside its app when it belongs to one. */
    private fun openPreviousTab(tab: PreviousTab) {
        val name = tab.title.ifBlank { tab.host }
        confirm(getString(R.string.say_previous_opening, name))
        val app = ArrowApp.fromUrl(tab.url)
        if (app != null) openApp(app, tab.url) else openWebsite(tab.url, name)
    }

    private fun askToClearPrevious() {
        if (previous.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.previous_clear_title)
            .setMessage(R.string.previous_clear_message)
            .setPositiveButton(R.string.previous_clear) { _, _ ->
                previous.clear()
                savePrevious()
                renderPrevious()
                toast(getString(R.string.say_previous_cleared))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun savePrevious() {
        prefs.edit().putString(PREF_PREVIOUS, previous.encode()).apply()
    }

    // ---------- Sign-in ----------

    private fun setupSignIn() {
        binding.signInButton.setOnClickListener { submitSignIn(create = false) }
        binding.signInCreate.setOnClickListener { submitSignIn(create = true) }
        binding.signInPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitSignIn(create = false)
                true
            } else {
                false
            }
        }
        binding.signInForgot.setOnClickListener {
            val email = binding.signInEmail.text.toString()
            AccountRules.emailProblem(email)?.let {
                showSignInError(it)
                return@setOnClickListener
            }
            setSignInBusy(true)
            cloud.sendPasswordReset(email) { error ->
                setSignInBusy(false)
                if (error != null) showSignInError(error)
                else toast(getString(R.string.signin_reset_sent, AccountRules.cleanEmail(email)))
            }
        }
    }

    private fun showSignIn() {
        hidePrevious()
        hideFiles()
        binding.designerScreen.isVisible = false
        binding.signInError.isVisible = false
        binding.signInScreen.isVisible = true
        binding.commandBar.isVisible = false
        voice.stop()
    }

    private fun submitSignIn(create: Boolean) {
        val email = binding.signInEmail.text.toString()
        val password = binding.signInPassword.text.toString()
        val problem = AccountRules.emailProblem(email) ?: AccountRules.passwordProblem(password)
        if (problem != null) {
            showSignInError(problem)
            return
        }
        setSignInBusy(true)
        val done: (String?) -> Unit = { error ->
            setSignInBusy(false)
            val uid = cloud.uid
            if (error != null || uid == null) {
                showSignInError(error ?: "Something went wrong. Try again.")
            } else {
                hideKeyboard(binding.signInPassword)
                binding.signInPassword.text.clear()
                toast(getString(R.string.signin_welcome, cloud.email.orEmpty()))
                beginSession(uid)
            }
        }
        if (create) cloud.createAccount(email, password, done) else cloud.signIn(email, password, done)
    }

    private fun setSignInBusy(busy: Boolean) {
        binding.signInProgress.isVisible = busy
        binding.signInButton.isEnabled = !busy
        binding.signInCreate.isEnabled = !busy
        binding.signInForgot.isEnabled = !busy
        if (busy) binding.signInError.isVisible = false
    }

    private fun showSignInError(message: String) {
        binding.signInError.text = message
        binding.signInError.isVisible = true
    }

    /** Loads the signed-in user's own space: their Arrow Files and their home screen. */
    private fun beginSession(uid: String) {
        accountUid = uid
        filesUser = uid
        files = ArrowFilesIndex.decode(prefs.getString(filesPrefKey(), null))
        homeSetup = HomeSetup.decode(prefs.getString(homeSetupPrefKey(), null))
        adoptDataFromBeforeSignIn(uid)
        emptyOldTrash()
        checkUnfinishedDownloads()

        binding.signInScreen.isVisible = false
        binding.commandBar.isVisible = true
        binding.homeAccount.text = getString(R.string.home_account, cloud.email.orEmpty())
        renderHomeApps()
        if (homeSetup == null) {
            // Built their home screen on another phone? Bring it here.
            cloud.fetchHomeSetup(uid) { raw ->
                val setup = HomeSetup.decode(raw)
                if (accountUid == uid && homeSetup == null && setup != null) {
                    homeSetup = setup
                    prefs.edit().putString(homeSetupPrefKey(), setup.encode()).apply()
                    renderHomeApps()
                    hideDesigner()
                }
            }
        }
        cloud.fetchFiles(uid) { fromCloud ->
            if (accountUid != uid) return@fetchFiles
            if (files.mergeFromCloud(fromCloud) > 0) {
                saveFiles()
                if (binding.filesScreen.isVisible) renderFiles()
            }
        }
        uploadWaitingFiles()
        showHome()
        // After signing in, Arrow Cloner (the digital worker) is the first thing the user sees.
        clonerLoaded = false
        showCloner()
    }

    /**
     * The first account to sign in on this phone takes over what was saved before sign-in
     * existed: the files in the guest folder and the home screen.
     */
    private fun adoptDataFromBeforeSignIn(uid: String) {
        val guestFiles = prefs.getString(PREF_FILES, null)
        if (!guestFiles.isNullOrBlank() && files.all.isEmpty()) {
            val guest = ArrowFilesIndex.decode(guestFiles)
            getExternalFilesDir(null)?.let { base ->
                val from = File(base, ArrowFilesIndex.userFolder(ArrowFilesIndex.LOCAL_USER))
                val to = File(base, ArrowFilesIndex.userFolder(uid))
                if (from.exists() && !to.exists()) from.renameTo(to)
            }
            guest.moveToUser(ArrowFilesIndex.LOCAL_USER, uid)
            files = guest
            saveFiles()
            prefs.edit().remove(PREF_FILES).apply()
            files.all.forEach { cloud.saveFile(uid, it) }
        }
        val oldSetup = HomeSetup.decode(prefs.getString(PREF_HOME_SETUP, null))
        if (homeSetup == null && oldSetup != null) {
            homeSetup = oldSetup
            prefs.edit().putString(homeSetupPrefKey(), oldSetup.encode()).remove(PREF_HOME_SETUP).apply()
            cloud.saveHomeSetup(uid, oldSetup.encode())
        }
    }

    private fun askToSignOut() {
        AlertDialog.Builder(this)
            .setTitle(R.string.signout_title)
            .setMessage(R.string.signout_message)
            .setPositiveButton(R.string.signout) { _, _ -> signOut() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun signOut() {
        stopJob(finished = false)
        heardTitle = null
        stopMedia()
        if (ScreenRecordService.running) ScreenRecordService.stop(this)
        hideCloner()
        clonerLoaded = false
        cloud.signOut()
        accountUid = null
        filesUser = ArrowFilesIndex.LOCAL_USER
        files = ArrowFilesIndex()
        homeSetup = null
        designerShown = emptyList()
        designerTicked.clear()
        currentApp = null
        binding.browserScreen.isVisible = false
        binding.homeScreen.isVisible = true
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        renderHomeApps()
        showSignIn()
    }

    // ---------- Arrow Files ----------

    private fun setupFilesScreen() {
        binding.filesClose.setOnClickListener { goBack() }
        binding.filesTrashToggle.setOnClickListener {
            filesShowTrash = !filesShowTrash
            renderFiles()
        }
    }

    /** Shows Arrow Files on top of home or the browser. */
    private fun showFiles() {
        exitFullscreen()
        hidePrevious()
        filesShowTrash = false
        checkUnfinishedDownloads()
        uploadWaitingFiles()
        renderFiles()
        binding.filesScreen.isVisible = true
        binding.filesScroll.scrollTo(0, 0)
    }

    private fun hideFiles() {
        binding.filesScreen.isVisible = false
        filesShowTrash = false
    }

    private fun renderFiles() {
        val trash = filesShowTrash
        binding.filesTitle.text = getString(if (trash) R.string.files_trash_title else R.string.files_title)
        binding.filesTrashToggle.text = getString(if (trash) R.string.files_back_to_files else R.string.files_trash)
        binding.filesHint.text = getString(if (trash) R.string.files_trash_hint else R.string.files_hint)

        val sources = files.sources
        if (filesSource != null && filesSource !in sources) filesSource = null
        binding.filesKindsScroll.isVisible = !trash && files.active.isNotEmpty()
        binding.filesSourcesScroll.isVisible = !trash && sources.size > 1

        binding.filesKinds.removeAllViews()
        addFilesChip(binding.filesKinds, getString(R.string.files_all), filesKind == null) { filesKind = null }
        FileKind.entries.forEach { kind ->
            addFilesChip(binding.filesKinds, kind.label, filesKind == kind) { filesKind = kind }
        }
        binding.filesSources.removeAllViews()
        addFilesChip(binding.filesSources, getString(R.string.files_all_sources), filesSource == null) { filesSource = null }
        sources.forEach { source ->
            addFilesChip(binding.filesSources, source, filesSource == source) { filesSource = source }
        }

        val shown = if (trash) files.trash else files.filter(filesKind, filesSource)
        binding.filesEmpty.isVisible = shown.isEmpty()
        binding.filesEmpty.text = when {
            trash -> getString(R.string.files_trash_empty)
            files.active.isEmpty() -> getString(R.string.files_empty)
            else -> getString(R.string.files_no_match)
        }
        val list = binding.filesList
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val now = System.currentTimeMillis()
        shown.forEach { f ->
            val row = ItemArrowFileBinding.inflate(inflater, list, false)
            row.fileIcon.text = f.kind.letter
            row.fileIcon.backgroundTintList = ColorStateList.valueOf(Color.parseColor(f.kind.colorHex))
            row.fileName.text = f.name
            val state = when (f.status) {
                FileStatus.Downloading -> getString(R.string.files_still_downloading)
                FileStatus.Failed -> getString(R.string.files_failed_short)
                FileStatus.Ready -> "${Formatter.formatShortFileSize(this, f.size)} · " +
                    DateUtils.getRelativeTimeSpanString(f.savedAt, now, DateUtils.MINUTE_IN_MILLIS) + " · " +
                    getString(
                        when {
                            f.inCloud && fileOf(f)?.exists() != true -> R.string.files_cloud_only
                            f.inCloud -> R.string.files_in_cloud
                            files.tooBigForCloud(f) -> R.string.files_phone_only
                            else -> R.string.files_waiting_upload
                        },
                    )
            }
            row.fileDetails.text = "${f.source} · $state"
            row.root.contentDescription = "${f.name}, ${row.fileDetails.text}"
            row.root.setOnClickListener { if (trash) showFileMenu(f, row.fileMore) else openFile(f) }
            row.root.setOnLongClickListener {
                showFileMenu(f, row.fileMore)
                true
            }
            row.fileMore.setOnClickListener { showFileMenu(f, it) }
            list.addView(row.root)
        }
    }

    private fun addFilesChip(parent: LinearLayout, label: String, selected: Boolean, pick: () -> Unit) {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val chip = TextView(this).apply {
            text = label
            textSize = 14f
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.chip_bg)
            if (selected) backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.accent)
            setTextColor(ContextCompat.getColor(this@MainActivity, if (selected) R.color.bg_dark else R.color.text_primary))
            setPadding(pad + pad / 3, pad / 2, pad + pad / 3, pad / 2)
            setOnClickListener {
                pick()
                renderFiles()
            }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = pad * 2 / 3
        parent.addView(chip, lp)
    }

    private fun showFileMenu(f: ArrowFile, anchor: View) {
        val menu = PopupMenu(this, anchor)
        val options = when {
            f.inTrash -> listOf(R.string.files_restore, R.string.files_delete_forever)
            f.status == FileStatus.Ready ->
                listOf(R.string.files_open, R.string.files_share, R.string.files_save_to_phone, R.string.files_move_to_trash)
            else -> listOf(R.string.files_delete_forever)
        }
        options.forEachIndexed { i, res -> menu.menu.add(0, res, i, res) }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.string.files_open -> openFile(f)
                R.string.files_share -> shareFile(f)
                R.string.files_save_to_phone -> saveToPhone(f)
                R.string.files_move_to_trash -> {
                    files.moveToTrash(f.id, System.currentTimeMillis())
                    saveFiles()
                    syncFile(f.id)
                    toast(getString(R.string.files_trashed))
                }
                R.string.files_restore -> {
                    files.restore(f.id)
                    saveFiles()
                    syncFile(f.id)
                    toast(getString(R.string.files_restored))
                }
                R.string.files_delete_forever -> {
                    if (f.status == FileStatus.Downloading) {
                        try {
                            getSystemService(DownloadManager::class.java).remove(f.downloadId)
                        } catch (e: Exception) {
                            // already gone
                        }
                    }
                    fileOf(f)?.delete()
                    if (f.inCloud) deleteCloudCopy(f)
                    files.remove(f.id)
                    saveFiles()
                    syncFile(f.id)
                    toast(getString(R.string.files_deleted))
                }
            }
            renderFiles()
            true
        }
        menu.show()
    }

    /** A content link other apps may read, or null (with a message) when the file is gone. */
    private fun shareableUri(f: ArrowFile): Uri? {
        if (f.status != FileStatus.Ready) {
            toast(getString(if (f.status == FileStatus.Downloading) R.string.files_still_downloading else R.string.files_failed_short))
            return null
        }
        val file = fileOf(f)
        if (file == null || !file.exists()) {
            toast(getString(R.string.files_missing))
            return null
        }
        return FileProvider.getUriForFile(this, "$packageName.files", file)
    }

    /**
     * Runs [action] once the file is on this phone. A file that is only in the cloud
     * (saved on another phone) is downloaded first.
     */
    private fun withLocalCopy(f: ArrowFile, action: () -> Unit) {
        val local = fileOf(f)
        if (f.status != FileStatus.Ready || local == null || local.exists() || !f.inCloud) {
            action()
            return
        }
        toast(getString(R.string.files_fetching, f.name))
        cloudWorker.execute {
            val ok = cloudFiles.download(f.relPath, local)
            runOnUiThread {
                if (ok) {
                    if (binding.filesScreen.isVisible) renderFiles()
                    action()
                } else {
                    toast(getString(R.string.files_fetch_failed))
                }
            }
        }
    }

    /** Copies every waiting file to the user's cloud folder, one at a time, on Wi-Fi or mobile data. */
    private fun uploadWaitingFiles() {
        val uid = accountUid ?: return
        if (uploading) return
        val waiting = files.waitingForUpload()
        if (waiting.isEmpty()) return
        uploading = true
        cloudWorker.execute {
            for (f in waiting) {
                if (accountUid != uid) break
                val local = fileOf(f)
                if (local == null || !local.exists()) continue
                if (!cloudFiles.upload(local, f.relPath, f.mimeType)) break // offline or refused: try again later
                runOnUiThread {
                    if (accountUid == uid) {
                        files.update(f.id) { it.copy(inCloud = true) }
                        saveFiles()
                        syncFile(f.id)
                        if (binding.filesScreen.isVisible) renderFiles()
                    }
                }
            }
            runOnUiThread { uploading = false }
        }
    }

    private fun deleteCloudCopy(f: ArrowFile) {
        cloudWorker.execute { cloudFiles.delete(f.relPath) }
    }

    private fun openFile(f: ArrowFile) = withLocalCopy(f) { openFileNow(f) }

    private fun openFileNow(f: ArrowFile) {
        val uri = shareableUri(f) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, f.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.files_no_app))
        }
    }

    private fun shareFile(f: ArrowFile) = withLocalCopy(f) { shareFileNow(f) }

    private fun shareFileNow(f: ArrowFile) {
        val uri = shareableUri(f) ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(f.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.files_share)))
    }

    /** Copies the file to the phone's own Downloads/ArrowOs folder, where every app can see it. */
    private fun saveToPhone(f: ArrowFile) = withLocalCopy(f) { saveToPhoneNow(f) }

    private fun saveToPhoneNow(f: ArrowFile) {
        val src = fileOf(f)
        if (src == null || !src.exists()) {
            toast(getString(R.string.files_missing))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSaveToPhone = f
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        Thread {
            val ok = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                        put(MediaStore.Downloads.MIME_TYPE, f.mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ArrowOs")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("no place to save")
                    contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ArrowOs")
                    dir.mkdirs()
                    var target = File(dir, f.name)
                    var n = 2
                    while (target.exists()) {
                        target = File(dir, "${f.name.substringBeforeLast('.')} ($n)" +
                            (if ('.' in f.name) ".${f.name.substringAfterLast('.')}" else ""))
                        n++
                    }
                    src.copyTo(target)
                }
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread { toast(getString(if (ok) R.string.files_saved_to_phone else R.string.files_save_failed)) }
        }.start()
    }

    // ---------- Voice ----------

    private fun setupVoiceBar() {
        binding.micButton.setOnClickListener {
            if (voice.continuous) {
                userMuted = true
                voice.stop()
                binding.voiceStatus.text = getString(R.string.voice_off)
            } else {
                userMuted = false
                ensureMicPermissionThenListen(userAsked = true)
            }
        }
        binding.voiceStatus.text = getString(R.string.voice_hint)

        // Typing is the second way to give commands; it goes through the same parser as speech.
        binding.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitTypedCommand()
                true
            } else {
                false
            }
        }
        binding.sendButton.setOnClickListener { submitTypedCommand() }
        // Don't let the mic pick up stray words while the user is typing.
        binding.commandInput.setOnFocusChangeListener { _, hasFocus -> voice.paused = hasFocus }
    }

    private fun submitTypedCommand() {
        val text = binding.commandInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.commandInput.text.clear()
        binding.commandInput.clearFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.commandInput.windowToken, 0)
        handleInput(text, typed = true)
    }

    /**
     * Runs what the user said or typed. Only input that starts with "tap" or "search"
     * does anything. One input can hold several steps
     * ("tap scroll to the second video and play it"); they run one after another.
     */
    private fun handleInput(text: String, typed: Boolean) {
        // The Auto Designer has its own text box; the always-on mic waits until it is closed.
        if (binding.designerScreen.isVisible || binding.signInScreen.isVisible || binding.clonerScreen.isVisible) return
        val commands = CommandParser.parseAll(text)
        if (commands.singleOrNull() is Command.Ignored) {
            // Not a command: do nothing. Only a typed line gets a hint, since the
            // always-on mic hears plenty of talk that isn't meant for Arrow Os.
            binding.voiceStatus.text = getString(R.string.say_ignored, text.trim())
            if (typed) toast(getString(R.string.say_ignored, text.trim()))
            return
        }
        binding.voiceStatus.text = "“$text”"
        recordLessonEvent(JSONObject().put("kind", "cmd").put("text", text.trim()))
        handler.removeCallbacksAndMessages(null) // a new instruction replaces an unfinished sequence
        runSequence(commands, 0)
    }

    private fun runSequence(commands: List<Command>, index: Int) {
        val command = commands.getOrNull(index) ?: return
        perform(command)
        if (index + 1 < commands.size) {
            // Give a new page time to load before acting on it.
            val delay = if (command.loadsPage()) 3000L else 800L
            handler.postDelayed({ runSequence(commands, index + 1) }, delay)
        }
    }

    private fun Command.loadsPage() = this is Command.Open || this is Command.Search || this is Command.OpenWebsite || this is Command.Home ||
        this is Command.Back || this is Command.Tap || this is Command.TapNth || this is Command.Reload

    private fun ensureMicPermissionThenListen(userAsked: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (userAsked || !askedForMic) {
            // Closing the permission dialog resumes the activity; without this guard a
            // "Deny" would bring the dialog straight back.
            askedForMic = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (!voice.continuous) voice.start()
    }

    override fun onListeningChanged(listening: Boolean) {
        binding.micButton.isActivated = listening || voice.continuous
        binding.micButton.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (voice.continuous) R.color.mic_on else R.color.mic_off),
        )
        if (listening) binding.voiceStatus.text = getString(R.string.voice_listening)
    }

    override fun onPartialText(text: String) {
        if (heardTitle != null) return
        binding.voiceStatus.text = "“$text”"
    }

    override fun onPhrase(text: String) {
        // While Arrow Cloner is listening to a video, what the mic hears is a note, not a command.
        if (hearPhrase(text)) return
        handleInput(text, typed = false)
    }

    override fun onVoiceUnavailable() {
        binding.voiceStatus.text = getString(R.string.voice_unavailable)
    }

    private val inApp: Boolean
        get() = binding.browserScreen.isVisible && !binding.previousScreen.isVisible && !binding.filesScreen.isVisible

    private fun perform(command: Command) {
        // On the Previous screen, scrolling moves the list instead of a web page.
        if (binding.previousScreen.isVisible && command in previousScrollCommands) {
            scrollPrevious(command)
            return
        }
        when (command) {
            is Command.Open -> {
                confirm(getString(R.string.say_opening, command.app.label))
                openApp(command.app)
            }
            is Command.Search -> {
                // Inside one of the five apps, search that app; anywhere else, search the web.
                val app = command.app ?: currentApp
                if (app != null) {
                    confirm(getString(R.string.say_searching, command.query, app.label))
                    openApp(app, app.searchUrl(command.query))
                } else {
                    confirm(getString(R.string.say_searching_web, command.query))
                    openWebsite(WebLinks.searchUrl(command.query), command.query)
                }
            }
            is Command.OpenWebsite -> {
                if (isClonerName(command.target)) {
                    confirm(getString(R.string.say_opening, getString(R.string.cloner_title)))
                    showCloner()
                    return
                }
                homeSetup?.findTool(command.target)?.let { tool ->
                    confirm(getString(R.string.say_opening, tool.label))
                    openTool(tool)
                    return
                }
                confirm(getString(R.string.say_opening, command.target))
                openWebsite(WebLinks.websiteUrl(command.target), command.target)
            }
            Command.Back -> {
                confirm(getString(R.string.say_back))
                goBack()
            }
            Command.Forward -> inPage {
                if (binding.webView.canGoForward()) binding.webView.goForward() else toast("Nothing to go forward to")
            }
            Command.Home -> {
                confirm(getString(R.string.say_home))
                exitFullscreen()
                showHome()
            }
            Command.Reload -> inPage {
                confirm(getString(R.string.say_reload))
                binding.webView.reload()
            }

            Command.ScrollDown -> inPage { toast("Scrolling down"); web.scroll(0.8) }
            Command.ScrollUp -> inPage { toast("Scrolling up"); web.scroll(-0.8) }
            Command.ScrollTop -> inPage { toast("Back to the top"); web.scrollToTop() }
            Command.ScrollBottom -> inPage { toast("To the bottom"); web.scrollToBottom() }
            // The eyes look for the page's own Next/Previous button first; feeds without
            // one (TikTok, Shorts, Reels) just move on to the next video.
            Command.NextItem -> inPage {
                web.tap(TapVocabulary.next) { name ->
                    if (name != null) confirm("Tapped $name") else { toast("Next"); web.nextItem(forward = true) }
                }
            }
            Command.PreviousItem -> inPage {
                web.tap(TapVocabulary.previous) { name ->
                    if (name != null) confirm("Tapped $name") else { toast("Previous"); web.nextItem(forward = false) }
                }
            }
            Command.ZoomIn -> inPage { binding.webView.zoomIn() }
            Command.ZoomOut -> inPage { binding.webView.zoomOut() }

            Command.Play -> inPage { web.play { ok -> toast(if (ok) "Playing" else "No video on this page") } }
            Command.Pause -> inPage { web.pause { ok -> toast(if (ok) "Paused" else "No video on this page") } }
            Command.Mute -> inPage { web.mute { ok -> toast(if (ok) "Muted" else "No video on this page") } }
            Command.Unmute -> inPage { web.unmute { ok -> toast(if (ok) "Sound on" else "No video on this page") } }

            is Command.Tap -> {
                // "tap open Canva" (or just "tap Canva" on the home screen): any app the user put on their home screen.
                val spoken = CommandParser.normalize(command.spoken)
                val toolName = Regex("^(?:open|launch|start|go to|switch to|visit|take me to) (.+)$")
                    .find(spoken)?.groupValues?.get(1) ?: spoken.takeIf { !inApp && !binding.previousScreen.isVisible }
                if (toolName != null && isClonerName(toolName)) {
                    confirm(getString(R.string.say_opening, getString(R.string.cloner_title)))
                    showCloner()
                    return
                }
                val tool = toolName?.let { homeSetup?.findTool(it.removeSuffix(" app")) }
                if (tool != null) {
                    confirm(getString(R.string.say_opening, tool.label))
                    openTool(tool)
                    return
                }
                if (binding.previousScreen.isVisible) {
                    // "tap Tom and Jerry" on the Previous screen opens the tab with that name.
                    val tab = previous.find(command.spoken)
                    if (tab != null) openPreviousTab(tab) else toast("No tab called \"${command.spoken}\" in Previous")
                    return
                }
                if (!inApp) {
                    toast("Open an app or website first, for example \"tap open TikTok\"")
                    return
                }
                binding.voiceStatus.text = getString(R.string.scanning, command.spoken)
                web.tap(command.labels) { name ->
                    if (name != null) {
                        confirm("Tapped ${command.spoken}")
                        binding.voiceStatus.text = getString(R.string.tapped, name)
                    } else {
                        toast("Couldn't find \"${command.spoken}\" on this page")
                        binding.voiceStatus.text = getString(R.string.not_found, command.spoken)
                    }
                }
            }
            // On the Previous screen, "tap 2" / "tap the second one" opens that tab.
            is Command.TapNth -> if (binding.previousScreen.isVisible) {
                val tab = previous.nth(command.n)
                if (tab != null) openPreviousTab(tab) else toast(getString(R.string.previous_not_found, command.n))
            } else inPage {
                web.tapNth(command.n) { ok ->
                    if (ok) confirm("Opening number ${command.n}") else toast("Couldn't find item ${command.n} on this page")
                }
            }
            is Command.TypeText -> {
                if (!inApp) {
                    toast("Open an app or website first, then type into it")
                    return
                }
                web.type(command.text, command.submit) { ok ->
                    toast(if (ok) "Typed \"${command.text}\"" else "No text box on this page. Tap one first, then type.")
                }
            }
            Command.PressEnter -> inPage { web.pressEnter() }

            Command.ShowPrevious -> {
                confirm(getString(R.string.say_previous))
                showPrevious()
            }
            Command.ClearPrevious -> {
                showPrevious()
                askToClearPrevious()
            }
            Command.ShowFiles -> {
                confirm(getString(R.string.say_files))
                showFiles()
            }
            Command.Help -> showHelp()
            Command.StopListening -> {
                confirm(getString(R.string.say_stop))
                userMuted = true
                voice.stop()
                binding.voiceStatus.text = getString(R.string.voice_off)
            }
            is Command.ScrollToNth -> inPage {
                web.scrollToNth(command.n) { ok -> toast(if (ok) "Number ${command.n}" else "Couldn't find item ${command.n}") }
            }
            is Command.Ignored -> Unit
        }
    }

    /** Runs [action] only when an app is open; on the home screen, explains what to do. */
    private inline fun inPage(action: () -> Unit) {
        if (inApp) action() else toast("Open an app or website first, for example \"tap open TikTok\"")
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Start every command with \"tap\" or \"search\"")
            .setMessage(
                """
                SEARCH finds things:
                search Tom and Jerry · search Jeff Bezos · search cats on TikTok
                Inside an app it searches that app; elsewhere it searches the web.

                Websites:
                search Claude code website · search claude.ai · tap open bbc.com

                TAP does anything on screen:
                tap open YouTube · tap home · tap back
                tap open (any app on your home screen, for example tap open Canva)
                tap previous (every tab you opened; then tap 2 to go back to one)
                tap like this video · tap comment · tap share · tap follow · tap subscribe
                tap play the second video on this page
                tap scroll to the second video and play it
                tap next video · tap previous video · tap scroll down · tap scroll to the top
                tap pause · tap play · tap mute · tap unmute · tap skip ad
                tap Shorts · tap Following (any button or link by its name)
                tap type happy birthday and send
                tap stop listening

                Chain them: tap open TikTok then search cats

                Anything that doesn't start with tap or search is ignored.
                """.trimIndent(),
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /** Shows what Arrow Os understood, and says it out loud. */
    private fun confirm(message: String) {
        toast(message)
        val engine = tts
        if (ttsReady && engine != null) {
            voice.paused = true // don't let the mic hear Arrow Os talking
            val result = engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "arrow-confirm")
            if (result == TextToSpeech.ERROR) voice.paused = false
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = resumeMic()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = resumeMic()
            override fun onError(utteranceId: String?, errorCode: Int) = resumeMic()
        })
    }

    // ---------- Arrow Cloner (the digital worker) ----------

    /** Background line for the worker's web reading and file imports. */
    private val clonerWorker = Executors.newFixedThreadPool(3)
    /** Saves the worker's brain one write at a time, in order. */
    private val brainWriter = Executors.newSingleThreadExecutor()

    /** The cloner page was loaded for the account that is signed in now. */
    private var clonerLoaded = false

    private var pendingPickCallback: String? = null
    private var pendingListenCallback: String? = null
    private val pickDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importForCloner(uris.map { PickedFile(it, null, null, -1L) }, folder = false)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) importForCloner(emptyList(), folder = true) else {
            clonerWorker.execute {
                val found = filesInTree(tree)
                runOnUiThread { importForCloner(found, folder = true) }
            }
        }
    }
    private val listenOnce = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        pendingListenCallback?.let { clonerCallback(it, JSONObject().put("text", heard).toString()) }
        pendingListenCallback = null
    }

    // A lesson: the teacher works while Arrow Cloner records the screen and every step.
    private var lessonTitle: String? = null
    private var lessonPath: String? = null
    private var lessonRelPath: String? = null
    private var lessonEvents = JSONArray()
    private var lessonWithMic = false
    private var recordingWithMic = false
    private val screenCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = lessonPath
        if (result.resultCode != RESULT_OK || result.data == null || path == null) {
            lessonTitle = null
            clonerEvent("lesson", JSONObject().put("ok", false).put("error", getString(R.string.cloner_record_refused)))
            return@registerForActivityResult
        }
        val intent = Intent(this, ScreenRecordService::class.java)
            .putExtra(ScreenRecordService.EXTRA_CODE, result.resultCode)
            .putExtra(ScreenRecordService.EXTRA_DATA, result.data)
            .putExtra(ScreenRecordService.EXTRA_PATH, path)
            .putExtra(ScreenRecordService.EXTRA_AUDIO, lessonWithMic)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            lessonTitle = null
            clonerEvent("lesson", JSONObject().put("ok", false).put("error", e.message ?: "could not start"))
            return@registerForActivityResult
        }
        lessonEvents = JSONArray()
        if (lessonWithMic) {
            // The phone's microphone can only do one thing: the lesson video gets it.
            recordingWithMic = true
            voice.stop()
        }
        showWorkerBar(getString(R.string.cloner_recording, lessonTitle.orEmpty()), getString(R.string.cloner_stop_recording), onStop = {
            ScreenRecordService.stop(this)
        })
        showHome()
    }
    private val recordMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        lessonWithMic = granted
        askForScreenCapture()
    }

    // A job: the steps of a task the worker runs inside Arrow Os.
    private val jobHandler = Handler(Looper.getMainLooper())
    private var jobSteps: List<JSONObject> = emptyList()
    private var jobIndex = -1
    private var jobRunning = false

    private class PickedFile(val uri: Uri, val name: String?, val mime: String?, val size: Long, val folder: String = "")

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupCloner() {
        binding.clonerWeb.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        binding.clonerWeb.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_dark))
        binding.clonerWeb.addJavascriptInterface(ClonerBridge(this), "ArrowOs")
        binding.clonerWeb.webChromeClient = WebChromeClient() // lets the page ask "Forget this?"
        binding.clonerWeb.webViewClient = object : WebViewClient() {
            // Links on the cloner page open in Arrow Os's browser, never inside the cloner page.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) openWebsite(url, request.url.host ?: url)
                return true
            }
        }
        binding.workerBarStop.setOnClickListener { workerBarStop?.invoke() }
        binding.workerBarContinue.setOnClickListener { workerBarContinue?.invoke() }
        ScreenRecordService.onFinished = { ok, path -> onLessonRecorded(ok, path) }
    }

    private fun isClonerName(text: String): Boolean = CommandParser.normalize(text).removeSuffix(" app").trim() in clonerNames

    private fun showCloner() {
        if (accountUid == null) return
        exitFullscreen()
        hidePrevious()
        hideFiles()
        binding.designerScreen.isVisible = false
        binding.clonerScreen.isVisible = true
        binding.commandBar.isVisible = false
        voice.paused = true
        if (!clonerLoaded) {
            clonerLoaded = true
            binding.clonerWeb.loadUrl(CLONER_PAGE)
        } else {
            clonerEvent("shown", JSONObject())
        }
    }

    private fun hideCloner() {
        if (!binding.clonerScreen.isVisible) return
        binding.clonerScreen.isVisible = false
        binding.commandBar.isVisible = true
        hideKeyboard(binding.clonerWeb)
        voice.paused = false
        if (!userMuted && !recordingWithMic && accountUid != null) ensureMicPermissionThenListen(userAsked = false)
    }

    /** Sends an event to the cloner page: Cloner.onEvent(name, data). */
    private fun clonerEvent(name: String, data: JSONObject) {
        binding.clonerWeb.evaluateJavascript(
            "window.Cloner&&Cloner.onEvent(${JSONObject.quote(name)},${JSONObject.quote(data.toString())})", null,
        )
    }

    override fun clonerCallback(id: String, json: String) = runOnUiThread {
        binding.clonerWeb.evaluateJavascript("window.Cloner&&Cloner.onCallback(${JSONObject.quote(id)},${JSONObject.quote(json)})", null)
    }

    private fun brainFile(): File? = accountUid?.let { File(filesDir, "cloner/${ArrowFilesIndex.safeFileName(it)}.json") }

    override fun clonerLoadBrain(): String = try {
        brainFile()?.takeIf { it.exists() }?.readText() ?: ""
    } catch (e: Exception) {
        ""
    }

    override fun clonerSaveBrain(json: String) {
        val file = brainFile() ?: return
        brainWriter.execute {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    file.writeText(json)
                    tmp.delete()
                }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.cloner_save_failed)) }
            }
        }
    }

    override fun clonerAccount(): String =
        JSONObject().put("uid", accountUid ?: "").put("email", cloud.email ?: "").toString()

    override fun clonerRunInBackground(work: () -> Unit) {
        try {
            clonerWorker.execute(work)
        } catch (e: Exception) {
            // Shutting down.
        }
    }

    override fun clonerToast(text: String) = runOnUiThread { toast(text) }

    override fun clonerSpeak(text: String) = runOnUiThread { confirm(text) }

    override fun clonerGoHome() = runOnUiThread { showHome() }

    override fun clonerShowFiles() = runOnUiThread {
        hideCloner()
        showFiles()
    }

    override fun clonerOpen(url: String, title: String) = runOnUiThread {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@runOnUiThread
        val app = ArrowApp.fromUrl(url)
        if (app != null) openApp(app, url) else openWebsite(url, title.ifBlank { Uri.parse(url).host ?: url })
        // Show the way back while the user signs in, pays or looks around.
        if (!jobRunning && !ScreenRecordService.running) {
            showWorkerBar(getString(R.string.cloner_back_hint), getString(R.string.cloner_back), onStop = {
                hideWorkerBar()
                showCloner()
            })
        }
    }

    override fun clonerListen(callbackId: String) = runOnUiThread {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.cloner_listen_prompt))
        pendingListenCallback = callbackId
        try {
            listenOnce.launch(intent)
        } catch (e: ActivityNotFoundException) {
            pendingListenCallback = null
            clonerCallback(callbackId, JSONObject().put("text", "").put("error", getString(R.string.voice_unavailable)).toString())
        }
    }

    // ----- Tools -----

    override fun clonerHomeTools(): String = ClonerBridge.toolsJson(homeSetup?.tools.orEmpty())

    override fun clonerAddTools(idsJson: String, profession: String) = runOnUiThread {
        val ids = try {
            JSONArray(idsJson).let { a -> (0 until a.length()).map { a.getString(it) } }
        } catch (e: Exception) {
            emptyList()
        }
        if (ids.isEmpty()) return@runOnUiThread
        val base = homeSetup ?: HomeSetup(profession.ifBlank { getString(R.string.cloner_title) }, emptyList())
        val setup = base.plus(ids)
        if (setup.toolIds.isEmpty()) return@runOnUiThread
        saveHomeSetup(setup)
    }

    override fun clonerRemoveTool(id: String) = runOnUiThread {
        val setup = homeSetup ?: return@runOnUiThread
        saveHomeSetup(setup.minus(id))
    }

    private fun saveHomeSetup(setup: HomeSetup) {
        homeSetup = setup
        prefs.edit().putString(homeSetupPrefKey(), setup.encode()).apply()
        accountUid?.let { cloud.saveHomeSetup(it, setup.encode()) }
        renderHomeApps()
    }

    // ----- Teaching with files and folders -----

    override fun clonerPickFiles(callbackId: String, folder: Boolean) = runOnUiThread {
        pendingPickCallback = callbackId
        try {
            if (folder) pickFolder.launch(null) else pickDocuments.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            pendingPickCallback = null
            clonerCallback(callbackId, JSONObject().put("files", JSONArray()).put("error", getString(R.string.cloner_no_picker)).toString())
        }
    }

    /** Every file inside a folder the user picked (and its sub-folders), up to [MAX_IMPORT]. */
    private fun filesInTree(tree: Uri): List<PickedFile> {
        val out = mutableListOf<PickedFile>()
        val rootId = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { return out }
        val pending = ArrayDeque(listOf(rootId to ""))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        while (pending.isNotEmpty() && out.size < MAX_IMPORT) {
            val (docId, path) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            try {
                contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext() && out.size < MAX_IMPORT) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(id to (if (path.isEmpty()) name else "$path/$name"))
                        } else {
                            val size = if (c.isNull(3)) -1L else c.getLong(3)
                            out.add(PickedFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime, size, path))
                        }
                    }
                }
            } catch (e: Exception) {
                // An unreadable folder: skip it.
            }
        }
        return out
    }

    /**
     * Copies what the user picked into their Arrow Files ("arrow cloner" folder, with a cloud copy)
     * and reads the words in each file, so the worker can learn from it.
     */
    private fun importForCloner(picked: List<PickedFile>, folder: Boolean) {
        val callback = pendingPickCallback ?: return
        pendingPickCallback = null
        if (picked.isEmpty()) {
            clonerCallback(callback, JSONObject().put("files", JSONArray()).put("folder", folder).toString())
            return
        }
        toast(getString(R.string.cloner_importing, picked.size))
        val base = getExternalFilesDir(null)
        clonerWorker.execute {
            val results = JSONArray()
            var textBudget = 3_000_000
            for (p in picked.take(MAX_IMPORT)) {
                var name = p.name
                var size = p.size
                if (name == null || size < 0) {
                    try {
                        contentResolver.query(p.uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                if (name == null) name = c.getString(0)
                                if (size < 0 && !c.isNull(1)) size = c.getLong(1)
                            }
                        }
                    } catch (e: Exception) {
                        // Use what we have.
                    }
                }
                val fileName = ArrowFilesIndex.safeFileName(name ?: "file")
                val mime = p.mime ?: contentResolver.getType(p.uri) ?: mimeOf(fileName)
                val entry = JSONObject().put("name", fileName).put("folder", p.folder).put("mime", mime)
                if (size > MAX_IMPORT_BYTES || base == null) {
                    results.put(entry.put("skipped", if (base == null) "no storage" else "too big"))
                    continue
                }
                val place = onUi { newFilePlace(CLONER_SOURCE, fileName) }
                if (place == null) {
                    results.put(entry.put("skipped", "no storage"))
                    continue
                }
                val target = File(base, place.second)
                try {
                    contentResolver.openInputStream(p.uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: throw IllegalStateException("can't open")
                } catch (e: Exception) {
                    target.delete()
                    results.put(entry.put("skipped", "can't read"))
                    continue
                }
                var text = ""
                if (ClonerText.canRead(place.first, mime) && target.length() <= MAX_TEXT_FILE_BYTES && textBudget > 0) {
                    text = ClonerText.read(place.first, mime, target.readBytes()).take(textBudget)
                    textBudget -= text.length
                }
                val file = ArrowFile(
                    id = UUID.randomUUID().toString(), name = place.first, source = CLONER_SOURCE, kind = FileKind.of(place.first, mime),
                    mimeType = mime, size = target.length(), savedAt = System.currentTimeMillis(), originUrl = "",
                    relPath = place.second, status = FileStatus.Ready,
                )
                onUi {
                    files.add(file)
                    syncFile(file.id)
                }
                results.put(
                    entry.put("name", file.name).put("size", file.size).put("fileId", file.id).put("kind", file.kind.label)
                        .put("readable", text.isNotEmpty()).put("text", text),
                )
            }
            runOnUiThread {
                saveFiles()
                uploadWaitingFiles()
                if (binding.filesScreen.isVisible) renderFiles()
            }
            clonerCallback(callback, JSONObject().put("files", results).put("folder", folder).toString())
        }
    }

    /** Runs [block] on the main thread and waits for its answer (only from a background thread). */
    private fun <T> onUi(block: () -> T): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        runOnUiThread {
            result = runCatching(block)
            latch.countDown()
        }
        latch.await()
        return result!!.getOrThrow()
    }

    // ----- Teaching by recording the screen -----

    override fun clonerStartRecording(title: String, withAudio: Boolean) = runOnUiThread {
        if (ScreenRecordService.running) {
            toast(getString(R.string.cloner_already_recording))
            return@runOnUiThread
        }
        val name = title.trim().ifBlank { getString(R.string.cloner_lesson_default) }
        val place = newFilePlace(CLONER_SOURCE, "Lesson - ${ArrowFilesIndex.safeFileName(name)}.mp4")
        val base = getExternalFilesDir(null)
        if (place == null || base == null) return@runOnUiThread
        lessonTitle = name
        lessonRelPath = place.second
        lessonPath = File(base, place.second).absolutePath
        if (withAudio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            lessonWithMic = withAudio
            askForScreenCapture()
        }
    }

    private fun askForScreenCapture() {
        try {
            screenCapture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } catch (e: Exception) {
            lessonTitle = null
            clonerEvent("lesson", JSONObject().put("ok", false).put("error", getString(R.string.cloner_record_refused)))
        }
    }

    /** Every command and page during a lesson becomes a step the worker can repeat. */
    private fun recordLessonEvent(event: JSONObject) {
        if (lessonTitle == null || !ScreenRecordService.running || jobRunning) return
        if (lessonEvents.length() >= 500) return
        lessonEvents.put(event.put("at", System.currentTimeMillis()))
    }

    private fun onLessonRecorded(ok: Boolean, path: String?) {
        val title = lessonTitle ?: return
        val relPath = lessonRelPath
        lessonTitle = null
        hideWorkerBar()
        if (recordingWithMic) {
            recordingWithMic = false
            if (!userMuted) ensureMicPermissionThenListen(userAsked = false)
        }
        val video = path?.let { File(it) }?.takeIf { ok && it.exists() && it.length() > 0 }
        val data = JSONObject().put("ok", true).put("title", title).put("events", lessonEvents)
        if (video != null && relPath != null) {
            val file = ArrowFile(
                id = UUID.randomUUID().toString(), name = video.name, source = CLONER_SOURCE, kind = FileKind.of(video.name, "video/mp4"),
                mimeType = "video/mp4", size = video.length(), savedAt = System.currentTimeMillis(), originUrl = "",
                relPath = relPath, status = FileStatus.Ready,
            )
            files.add(file)
            saveFiles()
            syncFile(file.id)
            uploadWaitingFiles()
            data.put("video", JSONObject().put("fileId", file.id).put("name", file.name).put("size", file.size))
        } else {
            path?.let { File(it).delete() }
            if (lessonEvents.length() == 0) data.put("ok", false).put("error", ScreenRecordService.lastError ?: getString(R.string.cloner_record_empty))
        }
        lessonEvents = JSONArray()
        showCloner()
        clonerEvent("lesson", data)
    }

    // ----- Doing a job -----

    override fun clonerRunJob(json: String) = runOnUiThread {
        val steps = try {
            val a = JSONObject(json).getJSONArray("steps")
            (0 until a.length()).map { a.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
        if (steps.isEmpty()) return@runOnUiThread
        jobHandler.removeCallbacksAndMessages(null)
        jobSteps = steps
        jobIndex = -1
        jobRunning = true
        hideCloner()
        nextJobStep(0)
    }

    override fun clonerJobContinue() = runOnUiThread { if (jobRunning) nextJobStep(0) }

    override fun clonerStopJob() = runOnUiThread { stopJob(finished = false) }

    private fun nextJobStep(delayMs: Long) {
        jobHandler.removeCallbacksAndMessages(null)
        jobHandler.postDelayed({
            if (!jobRunning) return@postDelayed
            jobIndex++
            runJobStep()
        }, delayMs)
    }

    private fun runJobStep() {
        val step = jobSteps.getOrNull(jobIndex)
        if (step == null) {
            stopJob(finished = true)
            return
        }
        val total = jobSteps.size
        val label = step.optString("label").ifBlank { step.optString("text").ifBlank { step.optString("title") } }
        clonerEvent("job", JSONObject().put("event", "step").put("index", jobIndex))
        val stop: () -> Unit = { stopJob(finished = false) }
        showWorkerBar(getString(R.string.cloner_job_step, jobIndex + 1, total, label), getString(R.string.cloner_stop), stop)
        when (step.optString("type")) {
            "open" -> {
                val url = step.optString("url")
                val app = ArrowApp.fromUrl(url)
                if (app != null) openApp(app, url) else if (url.startsWith("http")) openWebsite(url, step.optString("title").ifBlank { url })
                nextJobStep(4000)
            }
            "cmd" -> {
                val commands = CommandParser.parseAll(step.optString("text")).filter { it !is Command.Ignored && it !in jobRefused }
                var delay = 0L
                commands.forEach { c ->
                    jobHandler.postDelayed({ if (jobRunning) perform(c) }, delay)
                    delay += if (c.loadsPage()) 3000L else 900L
                }
                // Queue the next step after the commands (the delays above are on the same handler).
                jobHandler.postDelayed({
                    if (!jobRunning) return@postDelayed
                    jobIndex++
                    runJobStep()
                }, delay + 600)
            }
            "type" -> {
                web.type(step.optString("text"), step.optBoolean("submit")) { ok ->
                    if (!jobRunning) return@type
                    if (ok) {
                        nextJobStep(1500)
                    } else {
                        // Let the person put the cursor in the box, then try the same step again.
                        confirm(getString(R.string.cloner_your_turn))
                        showWorkerBar(getString(R.string.cloner_type_failed), getString(R.string.cloner_stop), stop, getString(R.string.cloner_try_again)) {
                            jobIndex--
                            nextJobStep(0)
                        }
                    }
                }
            }
            "you" -> {
                confirm(getString(R.string.cloner_your_turn))
                showWorkerBar(getString(R.string.cloner_your_turn_text, step.optString("text")), getString(R.string.cloner_stop), stop, getString(R.string.cloner_continue)) {
                    nextJobStep(0)
                }
            }
            "wait" -> nextJobStep(step.optLong("seconds", 2).coerceIn(0, 60) * 1000)
            "say" -> {
                confirm(step.optString("text"))
                nextJobStep(1500)
            }
            // Steps the cloner page does itself (for example reading the web to learn); it calls jobContinue().
            else -> clonerEvent("job", JSONObject().put("event", "pageStep").put("index", jobIndex).put("step", step))
        }
    }

    private fun stopJob(finished: Boolean) {
        if (!jobRunning) return
        jobRunning = false
        jobHandler.removeCallbacksAndMessages(null)
        hideWorkerBar()
        if (!finished) confirm(getString(R.string.cloner_job_stopped))
        showCloner()
        clonerEvent("job", JSONObject().put("event", "end").put("finished", finished).put("index", jobIndex))
    }

    // ----- Watch and listen: a video or audio plays on the phone while the mic writes down what it hears -----

    private var heardTitle: String? = null
    private val heardText = StringBuilder()
    private var heardMicWasOff = false
    private var mediaBox: FrameLayout? = null
    private var mediaVideo: android.widget.VideoView? = null

    private val pickMediaToListen = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val title = pendingListenTitle
        pendingListenTitle = null
        if (uri == null || title == null) {
            showCloner()
            return@registerForActivityResult
        }
        startHearing(title) { playMedia(uri) }
    }
    private var pendingListenTitle: String? = null
    private var pendingHearing: (() -> Unit)? = null
    private val hearingMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val go = pendingHearing
        pendingHearing = null
        if (granted && go != null) go() else {
            heardTitle = null
            toast(getString(R.string.voice_permission_denied))
            showCloner()
        }
    }

    override fun clonerListenToPage(url: String, title: String) = runOnUiThread {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@runOnUiThread
        startHearing(title) {
            val app = ArrowApp.fromUrl(url)
            if (app != null) openApp(app, url) else openWebsite(url, title)
        }
    }

    override fun clonerPickAndListen(title: String) = runOnUiThread {
        pendingListenTitle = title.ifBlank { getString(R.string.cloner_heard_default) }
        try {
            pickMediaToListen.launch(arrayOf("video/*", "audio/*"))
        } catch (e: ActivityNotFoundException) {
            pendingListenTitle = null
            toast(getString(R.string.cloner_no_picker))
        }
    }

    override fun clonerListenToFile(fileId: String, title: String) = runOnUiThread {
        val f = files.get(fileId) ?: return@runOnUiThread
        withLocalCopy(f) {
            val local = fileOf(f)
            if (local == null || !local.exists()) {
                toast(getString(R.string.files_missing))
                return@withLocalCopy
            }
            startHearing(title.ifBlank { f.name }) { playMedia(Uri.fromFile(local)) }
        }
    }

    override fun clonerStopListening() = runOnUiThread { stopHearing() }

    /** Turns the mic into a note-taker for [title], then runs [play] to start the video or page. */
    private fun startHearing(title: String, play: () -> Unit) {
        if (heardTitle != null) stopHearing()
        if (ScreenRecordService.running && recordingWithMic) {
            toast(getString(R.string.cloner_mic_busy))
            return
        }
        val begin = {
            heardTitle = title
            heardText.setLength(0)
            heardMicWasOff = userMuted || !voice.continuous
            hideCloner()
            play()
            if (!voice.continuous) voice.start()
            voice.paused = false
            showWorkerBar(getString(R.string.cloner_hearing, title), getString(R.string.cloner_hearing_stop), onStop = { stopHearing() })
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            pendingHearing = begin
            hearingMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** What the mic heard while listening; returns true when it was taken as a note. */
    private fun hearPhrase(text: String): Boolean {
        val title = heardTitle ?: return false
        if (text.isBlank()) return true
        if (heardText.isNotEmpty()) heardText.append(' ')
        heardText.append(text.trim())
        val words = heardText.split(' ').size
        showWorkerBar(
            getString(R.string.cloner_hearing_progress, title, words, text.trim().takeLast(80)),
            getString(R.string.cloner_hearing_stop), onStop = { stopHearing() },
        )
        return true
    }

    /** Plays a video or audio file full screen inside Arrow Os, through the phone's speaker. */
    private fun playMedia(uri: Uri) {
        stopMedia()
        val box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = android.widget.VideoView(this)
        // Don't fight the mic for the speaker: keep playing while speech recognition listens.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) video.setAudioFocusRequest(android.media.AudioManager.AUDIOFOCUS_NONE)
        val controller = android.widget.MediaController(this)
        controller.setAnchorView(video)
        video.setMediaController(controller)
        video.setVideoURI(uri)
        video.setOnPreparedListener { it.start() }
        video.setOnCompletionListener { handler.postDelayed({ if (heardTitle != null) stopHearing() }, 4000) }
        video.setOnErrorListener { _, _, _ ->
            toast(getString(R.string.cloner_cant_play))
            stopHearing()
            true
        }
        box.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val stop = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.cloner_hearing_stop)
            isAllCaps = false
            setOnClickListener { stopHearing() }
        }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        val m = (16 * resources.displayMetrics.density).toInt()
        lp.setMargins(m, m * 2, m, m)
        box.addView(stop, lp)
        binding.fullscreenContainer.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        binding.fullscreenContainer.isVisible = true
        mediaBox = box
        mediaVideo = video
    }

    private fun stopMedia() {
        mediaVideo?.stopPlayback()
        mediaBox?.let { binding.fullscreenContainer.removeView(it) }
        if (fullscreenView == null) binding.fullscreenContainer.isVisible = false
        mediaBox = null
        mediaVideo = null
    }

    /** Stops listening and keeps what was heard in the brain and in Arrow Files ("Arrow Cloner - Learned"). */
    private fun stopHearing() {
        val title = heardTitle ?: return
        heardTitle = null
        stopMedia()
        hideWorkerBar()
        if (binding.browserScreen.isVisible) binding.webView.evaluateJavascript(
            "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null,
        )
        if (heardMicWasOff) {
            voice.stop()
        }
        val text = heardText.toString().trim()
        heardText.setLength(0)
        val data = JSONObject().put("title", title).put("text", text)
        if (text.isNotEmpty()) saveLearned("Heard - $title", text)?.let { data.put("fileId", it) }
        showCloner()
        clonerEvent("heard", data)
    }

    // ----- What the worker learned, kept as text files in its own folder -----

    override fun clonerSaveLearned(title: String, text: String) = runOnUiThread { saveLearned(title, text) }

    /** Writes [text] to Arrow Files, "Arrow Cloner - Learned" folder (with a cloud copy). Returns the file id. */
    private fun saveLearned(title: String, text: String): String? {
        if (text.isBlank() || accountUid == null) return null
        val wanted = ArrowFilesIndex.safeFileName(title.take(80)) + ".txt"
        // Learning the same thing again replaces the old note.
        files.active.filter { it.source == CLONER_LEARNED && it.name == wanted }.forEach { old ->
            fileOf(old)?.delete()
            if (old.inCloud) deleteCloudCopy(old)
            files.remove(old.id)
            syncFile(old.id)
        }
        val (name, relPath) = newFilePlace(CLONER_LEARNED, wanted) ?: return null
        val target = File(getExternalFilesDir(null), relPath)
        return try {
            target.writeText(text)
            val file = ArrowFile(
                id = UUID.randomUUID().toString(), name = name, source = CLONER_LEARNED, kind = FileKind.of(name, "text/plain"),
                mimeType = "text/plain", size = target.length(), savedAt = System.currentTimeMillis(), originUrl = "",
                relPath = relPath, status = FileStatus.Ready,
            )
            files.add(file)
            saveFiles()
            syncFile(file.id)
            uploadWaitingFiles()
            file.id
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Files already on the phone (in Arrow Files) that fit [query]: the worker reads these first,
     * before going to the web. Text is read for documents; videos and audio can be listened to.
     */
    override fun clonerPhoneFiles(query: String, callbackId: String) = runOnUiThread {
        val found = files.related(query, 8).filter { it.status == FileStatus.Ready && it.source != CLONER_LEARNED }
        clonerWorker.execute {
            val out = JSONArray()
            for (f in found) {
                val local = fileOf(f)
                var text = ""
                if (local != null && local.exists() && ClonerText.canRead(f.name, f.mimeType) && local.length() <= MAX_TEXT_FILE_BYTES) {
                    text = try { ClonerText.read(f.name, f.mimeType, local.readBytes()) } catch (e: Exception) { "" }
                }
                out.put(
                    JSONObject().put("fileId", f.id).put("name", f.name).put("source", f.source).put("kind", f.kind.label)
                        .put("media", f.kind == FileKind.Video || f.kind == FileKind.Audio).put("text", text),
                )
            }
            clonerCallback(callbackId, JSONObject().put("files", out).toString())
        }
    }

    // ----- The worker bar (recording, jobs, way back to the cloner) -----

    private var workerBarStop: (() -> Unit)? = null
    private var workerBarContinue: (() -> Unit)? = null

    private fun showWorkerBar(text: String, stopLabel: String, onStop: () -> Unit, continueLabel: String? = null, onContinue: (() -> Unit)? = null) {
        binding.workerBarText.text = text
        binding.workerBarStop.text = stopLabel
        workerBarStop = onStop
        binding.workerBarContinue.isVisible = continueLabel != null
        binding.workerBarContinue.text = continueLabel.orEmpty()
        workerBarContinue = onContinue
        binding.workerBar.isVisible = true
    }

    private fun hideWorkerBar() {
        binding.workerBar.isVisible = false
        workerBarStop = null
        workerBarContinue = null
    }

    private companion object {
        const val PREF_PREVIOUS = "previous_tabs"
        const val PREF_HOME_SETUP = "home_setup"
        const val PREF_FILES = "arrow_files"
        const val FILES_COLOR = "#0091B3"
        const val CLONER_COLOR = "#7C4DFF"
        const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        const val WHATSAPP_WEB = "https://web.whatsapp.com/"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        /** Arrow Files folder for everything taught to Arrow Cloner (uploads and lesson videos). */
        const val CLONER_SOURCE = "Arrow Cloner - From phone"
        /** Arrow Files folder for what the worker learned: what it heard, read and was told, as text files. */
        const val CLONER_LEARNED = "Arrow Cloner - Learned"
        const val CLONER_PAGE = "file:///android_asset/cloner/index.html"
        const val MAX_IMPORT = 300
        const val MAX_IMPORT_BYTES = 25L * 1024 * 1024
        const val MAX_TEXT_FILE_BYTES = 5L * 1024 * 1024
        /** A job (or a lesson) may never stop the mic, clear history or open help. */
        val jobRefused = setOf(Command.StopListening, Command.ClearPrevious, Command.Help)
        val clonerNames = setOf("arrow cloner", "cloner", "the cloner", "arrow clone", "digital worker", "my digital worker", "my worker", "clone worker", "my clone")
        val previousScrollCommands = setOf(
            Command.ScrollDown, Command.ScrollUp, Command.ScrollTop, Command.ScrollBottom, Command.NextItem, Command.PreviousItem,
        )
    }

    private fun resumeMic() = runOnUiThread {
        // Stay paused while the user is typing a command.
        if (!binding.commandInput.hasFocus() && !binding.clonerScreen.isVisible) voice.paused = false
    }
}
