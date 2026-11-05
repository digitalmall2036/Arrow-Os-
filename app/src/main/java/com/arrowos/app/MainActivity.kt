package com.arrowos.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
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
import android.os.Handler
import android.os.Looper
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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.arrowos.app.databinding.ActivityMainBinding
import com.arrowos.app.databinding.ItemAppTileBinding
import com.arrowos.app.databinding.ItemArrowFileBinding
import com.arrowos.app.databinding.ItemDesignerToolBinding
import com.arrowos.app.databinding.ItemPreviousTabBinding
import java.io.File
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), VoiceController.Listener {

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

    /** The page view. A fresh one replaces it if the phone stops the page to free memory. */
    private lateinit var webView: WebView

    /** The browser names Arrow Os uses: Chrome on a phone, and Chrome on a computer for sites that need it. */
    private lateinit var mobileUserAgent: String
    private lateinit var desktopUserAgent: String

    /** The page's address, readable from the background threads that load page parts. */
    @Volatile private var pageUrlForBlocking: String? = null

    /** 2 GB phones and smaller: keep only one page in memory and let it go when leaving it. */
    private val lowRam by lazy {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        am.isLowRamDevice || info.totalMem < 3L * 1024 * 1024 * 1024
    }

    /** The address the user last opened (a home screen app, a search...), to recognise it when it is asleep. */
    private var lastRequestedUrl: String? = null

    /** A sign-in window a site opened ("Sign in with Google"), shown on top of the page. */
    private var popupView: WebView? = null

    /** Downloads straight into Arrow Os's private folder; nothing goes to the phone's Downloads. */
    private val downloader = ArrowDownloader()
    private val blobSaver = BlobSaver()
    private val downloadProgress = mutableMapOf<String, Pair<Long, Long>>()

    /** Opens Arrow Files inside Arrow Os. */
    private lateinit var viewer: FileViewer

    /** Reloads the page when the internet comes back. */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

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

    /** Downloads started by older versions of Arrow Os through the phone's download manager. */
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

    /** For housekeeping (saving Previous, closing windows) that a new voice command must not cancel. */
    private val housekeeping = Handler(Looper.getMainLooper())

    /** Arrow Cloner: the skills it was taught (per account), and what it is watching right now. */
    private var clonerSkills = ClonerSkills()
    private val clonerRecorder = ClonerRecorder()

    /** A task handed over before the user was signed in; it is offered once they are. */
    private var pendingClonerTask: ClonerTask? = null

    private var fullscreenView: View? = null
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
        webView = binding.webView
        mobileUserAgent = SitePolicy.chromeUserAgent(WebSettings.getDefaultUserAgent(this))
        desktopUserAgent = SitePolicy.desktopUserAgent(mobileUserAgent)
        web = WebActions(webView)
        viewer = FileViewer(this, binding.viewerBody)
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
        watchNetwork()
        moveFilesInsideArrowOs()

        pendingClonerTask = taskFrom(intent)
        val uid = cloud.uid
        if (uid != null) beginSession(uid) else showSignIn()
    }

    /** An Arrow Cloner link opened while Arrow Os is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val task = taskFrom(intent) ?: return
        if (accountUid != null) askToRunClonerTask(task) else pendingClonerTask = task
    }

    private fun taskFrom(intent: Intent?): ClonerTask? =
        intent?.dataString?.let { ClonerTask.fromLink(it) }

    override fun onResume() {
        super.onResume()
        if (binding.browserScreen.isVisible) webView.onResume()
        webView.resumeTimers()
        if (accountUid != null) updateGoogleCard()
        if (!userMuted && accountUid != null) ensureMicPermissionThenListen(userAsked = false)
    }

    override fun onPause() {
        // Always-listening only while Arrow Os is on screen.
        voice.stop()
        webView.onPause()
        // Sign-ins are written to Arrow Os's storage now, so they survive the phone closing the app.
        CookieManager.getInstance().flush()
        rememberOpenPage()
        flushPrevious()
        super.onPause()
    }

    override fun onStop() {
        // Nothing runs in the background while Arrow Os is out of sight (saves battery and memory).
        if (!isChangingConfigurations) webView.pauseTimers()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The phone is short of memory: let go of a page that isn't on screen.
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !binding.browserScreen.isVisible) {
            releaseHiddenPage()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        flushPrevious()
        unregisterReceiver(downloadDone)
        networkCallback?.let {
            try {
                getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // already gone
            }
        }
        downloader.shutdown()
        viewer.close()
        closePopup()
        cloudWorker.shutdown()
        voice.destroy()
        tts?.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    // ---------- Home screen ----------

    private fun setupHomeScreen() {
        binding.homePrevious.setOnClickListener { showPrevious() }
        binding.homeChangeBusiness.setOnClickListener { showDesigner() }
        binding.homeAccount.setOnClickListener { askToSignOut() }
        binding.homeGoogle.setOnClickListener { openGoogleAccount() }
        renderHomeApps()
    }

    /**
     * The home screen: Arrow Files and the essentials (Gmail, WhatsApp, YouTube...) for everyone,
     * then the business apps the user ticked in the Auto Designer.
     */
    private fun renderHomeApps() {
        val inflater = LayoutInflater.from(this)
        binding.essentialsGrid.removeAllViews()
        // Arrow Files is always the first app, whatever business the user picked.
        val filesTile = ItemAppTileBinding.inflate(inflater, binding.essentialsGrid, false)
        filesTile.appIcon.text = ""
        filesTile.appIcon.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_folder, 0, 0)
        filesTile.appIcon.setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 0)
        filesTile.appIcon.backgroundTintList = ColorStateList.valueOf(Color.parseColor(FILES_COLOR))
        filesTile.appLabel.text = getString(R.string.files_title)
        filesTile.root.contentDescription = getString(R.string.open_app, getString(R.string.files_title))
        filesTile.root.setOnClickListener { showFiles() }
        binding.essentialsGrid.addView(filesTile.root)
        AutoDesigner.essentials.forEach { addHomeTile(binding.essentialsGrid, it, inflater) }

        binding.appGrid.removeAllViews()
        val setup = homeSetup
        val tools = setup?.businessTools.orEmpty()
        tools.forEach { addHomeTile(binding.appGrid, it, inflater) }
        binding.homeBusinessTitle.isVisible = tools.isNotEmpty()
        binding.homeEmpty.isVisible = tools.isEmpty()
        binding.homeBusiness.isVisible = setup != null && setup.category.isNotBlank()
        binding.homeBusiness.text = getString(R.string.home_business, setup?.category.orEmpty())
    }

    private fun addHomeTile(grid: android.widget.GridLayout, tool: BusinessTool, inflater: LayoutInflater) {
        val tile = ItemAppTileBinding.inflate(inflater, grid, false)
        tile.appIcon.text = tool.initial
        tile.appIcon.backgroundTintList = ColorStateList.valueOf(Color.parseColor(tool.colorHex))
        tile.appLabel.text = tool.label
        tile.appLabel.maxLines = 2
        tile.appLabel.gravity = Gravity.CENTER
        tile.root.contentDescription = getString(R.string.open_app, tool.label)
        tile.root.setOnClickListener {
            clonerRecorder.record("tap open ${tool.label}")
            openTool(tool)
        }
        grid.addView(tile.root)
    }

    /** The home screen app the user named ("tap open Gmail"), essentials included. */
    private fun findHomeTool(spoken: String): BusinessTool? =
        homeSetup?.findTool(spoken) ?: AutoDesigner.findTool(AutoDesigner.essentials, spoken)

    /** Opens a home screen app straight away, inside Arrow Os. */
    private fun openTool(tool: BusinessTool) {
        val app = ArrowApp.fromUrl(tool.url)
        if (app != null) openApp(app, tool.url) else openWebsite(tool.url, tool.label)
    }

    /**
     * One account for all of Arrow Os: the user signs in to Google once inside Arrow Os (a new
     * account just for Arrow Os, if they like). Gmail, YouTube, Drive and every "Sign in with
     * Google" button then use it. It lives in Arrow Os's own storage, apart from the phone's accounts.
     */
    private fun updateGoogleCard() {
        val signedIn = SitePolicy.signedInToGoogle(CookieManager.getInstance().getCookie("https://accounts.google.com")) ||
            SitePolicy.signedInToGoogle(CookieManager.getInstance().getCookie("https://www.google.com"))
        binding.homeGoogle.text = getString(if (signedIn) R.string.google_signed_in else R.string.google_signed_out)
        binding.homeGoogle.tag = signedIn
    }

    private fun openGoogleAccount() {
        if (binding.homeGoogle.tag == true) {
            openWebsite("https://myaccount.google.com/", "Google Account")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.google_choice_title)
            .setMessage(R.string.google_choice_message)
            .setPositiveButton(R.string.google_sign_in) { _, _ ->
                openWebsite("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmail.google.com%2Fmail%2F", "Google")
            }
            .setNeutralButton(R.string.google_create) { _, _ ->
                openWebsite("https://accounts.google.com/signup?continue=https%3A%2F%2Fmail.google.com%2Fmail%2F", "Google")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHome() {
        hidePrevious()
        hideFiles()
        closeViewer()
        closePopup()
        if (homeSetup == null) showDesigner()
        binding.homeScreen.isVisible = true
        binding.browserScreen.isVisible = false
        pendingAlternatives.clear()
        updateGoogleCard()
        if (lowRam) {
            releaseHiddenPage()
        } else {
            // Bigger phones keep the page asleep, so going back to it is instant.
            web.pause { }
            webView.onPause()
        }
    }

    /** Frees the page's memory (2 GB phones, or when the phone runs short). */
    private fun releaseHiddenPage() {
        currentApp = null
        pageUrlForBlocking = null
        lastRequestedUrl = null
        errorPageUrl = null
        webView.stopLoading()
        resetHistoryOnNextPage = true
        webView.loadUrl("about:blank")
    }

    /** The page that is asleep behind the home screen, if it is [url] (or the same app's home). */
    private fun isAsleep(url: String, app: ArrowApp?): Boolean {
        val current = webView.url ?: return false
        if (current == "about:blank" || errorPageUrl != null) return false
        if (current == url || url == lastRequestedUrl) return true
        return app != null && currentApp == app && url == app.homeUrl && ArrowApp.fromUrl(current) == app
    }

    // ---------- In-app browser ----------

    private fun setupBrowser() {
        CookieManager.getInstance().setAcceptCookie(true)
        configureWebView(webView)
        binding.browserBack.setOnClickListener { goBack() }
        binding.browserHome.setOnClickListener { showHome() }
        binding.browserReload.setOnClickListener { reloadPage() }
        binding.browserPrevious.setOnClickListener { showPrevious() }
        binding.browserFiles.setOnClickListener { showFiles() }
        binding.popupClose.setOnClickListener { closePopup() }
        binding.viewerClose.setOnClickListener { closeViewer() }
    }

    /** Settings every page view shares: the main one, a fresh replacement, and sign-in windows. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(view: WebView) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = if (isOnline()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
            // Sign-in windows ("Sign in with Google") open on top of the page instead of breaking.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // Look like Chrome rather than an embedded WebView. With the WebView marks, Instagram
            // and Facebook hide features, Google refuses sign-in and some sites can't "verify the browser".
            userAgentString = mobileUserAgent
        }
        hideWebViewMarks(view)
        // The page on screen keeps its priority, so the phone frees other memory first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
    }

    /**
     * WebView tells every site "I am the app com.arrowos.app" (the X-Requested-With header) and
     * "I am Android WebView" (the browser hints). Google sign-in and Cloudflare's "verify your
     * browser" check refuse such browsers. Newer WebViews let Arrow Os leave both out.
     */
    private fun hideWebViewMarks(view: WebView) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                val major = SitePolicy.chromeMajorVersion(mobileUserAgent)
                val full = Regex("Chrome/([0-9.]+)").find(mobileUserAgent)?.groupValues?.get(1) ?: "$major.0.0.0"
                fun brand(name: String, version: String, fullVersion: String) =
                    UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(version).setFullVersion(fullVersion).build()
                val brands = listOf(
                    brand("Chromium", major, full),
                    brand("Google Chrome", major, full),
                    brand("Not?A_Brand", "99", "99.0.0.0"),
                )
                val metadata = UserAgentMetadata.Builder(WebSettingsCompat.getUserAgentMetadata(view.settings))
                    .setBrandVersionList(brands)
                    .setFullVersion(full)
                    .build()
                WebSettingsCompat.setUserAgentMetadata(view.settings, metadata)
            }
        } catch (e: Exception) {
            // An older WebView: sites still work, they just see a WebView.
        }
    }

    /** Sets up the main page view: settings, downloads, page events. */
    private fun configureWebView(view: WebView) {
        applySettings(view)
        view.addJavascriptInterface(blobSaver, BlobSaver.NAME)

        // Every download goes into Arrow Files, inside Arrow Os.
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(view, request.url.toString())

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Ads and trackers are skipped: pages load faster and use less data and memory.
                if (!request.isForMainFrame && SitePolicy.shouldBlock(request.url.toString(), pageUrlForBlocking)) {
                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != errorPageUrl) pageUrlForBlocking = url
                binding.pageProgress.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.pageProgress.isVisible = false
                if (url != errorPageUrl && previous.updateTitle(url, view.title)) savePrevious()
                CookieManager.getInstance().flush()
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
                if (url != errorPageUrl && url?.startsWith("http") == true) pageUrlForBlocking = url
                // Every page (including in-page moves like a new YouTube video) goes into Previous.
                // The title is filled in by onReceivedTitle; view.title can still be the old page's here.
                if (url != errorPageUrl && previous.record(url, null, System.currentTimeMillis())) savePrevious()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                handleLoadFailure(view, request.url, error.errorCode, error.description?.toString() ?: "")
            }

            // The phone stopped the page to free memory (common on 2 GB phones). Without this
            // Arrow Os itself would close; instead a fresh page takes its place and reloads.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view !== webView) return true
                replaceCrashedWebView()
                return true
            }
        }

        view.webChromeClient = object : WebChromeClient() {
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

            // A site opened a new window: a sign-in window stays a window; anything else opens here.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean =
                openPopup(resultMsg)

            // Lets sites ask for a photo or file (posting on Instagram, TikTok, Facebook...).
            // Arrow Files comes first, so what was made in Arrow Os can be posted.
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                showFilePicker(params)
                return true
            }
        }
    }

    /** The page view crashed or was stopped by the phone: put a fresh one in its place and reload. */
    private fun replaceCrashedWebView() {
        val lastUrl = pageUrlForBlocking ?: previous.all.firstOrNull()?.url
        val old = webView
        exitFullscreen()
        closePopup()
        binding.webHolder.removeView(old)
        try {
            old.destroy()
        } catch (e: Exception) {
            // already gone
        }
        val fresh = WebView(this)
        binding.webHolder.addView(fresh, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        webView = fresh
        web.webView = fresh
        configureWebView(fresh)
        if (binding.browserScreen.isVisible && lastUrl != null && lastUrl.startsWith("http")) {
            toast(getString(R.string.page_restarting))
            resetHistoryOnNextPage = true
            fresh.loadUrl(lastUrl)
        } else {
            currentApp = null
        }
    }

    // ---------- Sign-in windows ----------

    private fun openPopup(resultMsg: android.os.Message): Boolean {
        closePopup()
        val popup = WebView(this)
        applySettings(popup)
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (!url.startsWith("http")) return handleUrl(view, url)
                return takeOverPopup(url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && url.startsWith("http")) takeOverPopup(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                binding.popupTitle.text = view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }
                    ?: Uri.parse(url ?: "").host ?: getString(R.string.popup_title)
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) = closePopup()
        }
        popup.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
        popupView = popup
        binding.popupHolder.addView(popup, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        binding.popupTitle.text = getString(R.string.popup_title)
        binding.popupScreen.isVisible = true
        (resultMsg.obj as WebView.WebViewTransport).webView = popup
        resultMsg.sendToTarget()
        return true
    }

    /**
     * A new window that is not a sign-in (a link that opens "in a new tab") becomes a normal
     * page in Arrow Os. Returns true when the popup was closed.
     */
    private fun takeOverPopup(url: String): Boolean {
        if (SitePolicy.isSignInPopup(url) || popupView == null) return false
        closePopup()
        val app = ArrowApp.fromUrl(url)
        if (app != null) openApp(app, url) else openWebsite(url, Uri.parse(url).host ?: url)
        return true
    }

    private fun closePopup() {
        val popup = popupView ?: return
        popupView = null
        binding.popupScreen.isVisible = false
        binding.popupHolder.removeAllViews()
        housekeeping.post {
            try {
                popup.stopLoading()
                popup.destroy()
            } catch (e: Exception) {
                // already gone
            }
        }
        updateGoogleCard()
    }

    /** Keeps every link inside Arrow Os. Returns true when the WebView should NOT load [url] itself. */
    private fun handleUrl(view: WebView, url: String): Boolean {
        // "Run in Arrow Os" on the Arrow Cloner page (or any site): hand the task to Arrow Cloner.
        if (ClonerTask.isTaskLink(url)) {
            ClonerTask.fromLink(url)?.let { askToRunClonerTask(it) } ?: toast("That Arrow Cloner link has no steps")
            return true
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            // WhatsApp Web only opens for a computer browser; switch the browser name to match.
            val wantDesktop = SitePolicy.needsDesktopSite(url)
            val isDesktop = view.settings.userAgentString == desktopUserAgent
            if (wantDesktop != isDesktop) {
                view.settings.userAgentString = if (wantDesktop) desktopUserAgent else mobileUserAgent
                view.loadUrl(url)
                return true
            }
            return false
        }
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
        // fb://, instagram://, snssdk://, market://, mailto: ... would leave Arrow Os, so block them.
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
        val asleep = !binding.browserScreen.isVisible && isAsleep(url, app)
        currentApp = app
        binding.browserTitle.text = app.label
        if (asleep) wakePage() else load(url, freshHistory = switching)
    }

    /** Opens any website inside Arrow Os. */
    private fun openWebsite(url: String, title: String) {
        val switching = currentApp != null || !binding.browserScreen.isVisible
        val asleep = !binding.browserScreen.isVisible && isAsleep(url, null)
        currentApp = null
        binding.browserTitle.text = title
        if (asleep) wakePage() else load(url, freshHistory = switching)
    }

    /** Shows the page that was asleep behind the home screen, right where the user left it. */
    private fun wakePage() {
        hidePrevious()
        hideDesigner()
        binding.homeScreen.isVisible = false
        binding.browserScreen.isVisible = true
        webView.onResume()
        webView.resumeTimers()
    }

    private fun load(url: String, freshHistory: Boolean) {
        hidePrevious()
        hideDesigner()
        closePopup()
        errorPageUrl = null
        binding.homeScreen.isVisible = false
        binding.browserScreen.isVisible = true
        webView.onResume()
        webView.resumeTimers()
        pendingAlternatives.clear()
        triedAlternatives = false
        if (freshHistory) resetHistoryOnNextPage = true
        webView.settings.cacheMode = if (isOnline()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
        webView.settings.userAgentString = if (SitePolicy.needsDesktopSite(url)) desktopUserAgent else mobileUserAgent
        pageUrlForBlocking = url
        lastRequestedUrl = url
        webView.loadUrl(url)
    }

    private fun reloadPage() {
        val failed = errorPageUrl
        if (failed != null) load(failed, freshHistory = false) else webView.reload()
    }

    /**
     * A page couldn't load. If the site has backup addresses (Facebook has several),
     * try the next one; otherwise show a clear explanation with a Retry button.
     */
    private fun handleLoadFailure(view: WebView, failed: Uri, errorCode: Int, description: String) {
        val host = failed.host ?: ""
        val connectionDrop = !isOnline() || SitePolicy.isConnectionDrop(description)
        if (!connectionDrop) {
            if (!triedAlternatives) {
                triedAlternatives = true
                pendingAlternatives = WebLinks.alternativesFor(host).filter { it != host }.toMutableList()
            }
            val next = pendingAlternatives.removeFirstOrNull()
            if (next != null) {
                binding.voiceStatus.text = getString(R.string.trying_other_address, next)
                val nextUrl = if (next == "web.whatsapp.com") "https://web.whatsapp.com/" else failed.buildUpon().authority(next).build().toString()
                if (SitePolicy.needsDesktopSite(nextUrl)) view.settings.userAgentString = desktopUserAgent
                view.loadUrl(nextUrl)
                return
            }
        }
        showErrorPage(view, failed.toString(), host, errorCode, description, connectionDrop)
    }

    private fun showErrorPage(view: WebView, url: String, host: String, errorCode: Int, description: String, connectionDrop: Boolean) {
        val blocked = errorCode == WebViewClient.ERROR_CONNECT || errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
            description.contains("REFUSED") || description.contains("RESET") || description.contains("NAME_NOT_RESOLVED")
        val isFacebook = host.contains("facebook")
        val reason = when {
            connectionDrop -> getString(R.string.waiting_for_internet)
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

    private fun isOnline(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** When the internet comes back, a page that failed to load reloads by itself. */
    private fun watchNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runOnUiThread {
                    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    val failed = errorPageUrl
                    if (failed != null && binding.browserScreen.isVisible) {
                        toast(getString(R.string.internet_back))
                        load(failed, freshHistory = false)
                    }
                    uploadWaitingFiles()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            // Not available on this phone; the Try again button still works.
        }
    }

    /** Remembers the open page, so after the phone closes Arrow Os the user comes back to it. */
    private fun rememberOpenPage() {
        val url = webView.url
        val open = binding.browserScreen.isVisible && errorPageUrl == null && url != null && url.startsWith("http")
        prefs.edit()
            .putString(PREF_LAST_URL, if (open) url else null)
            .putString(PREF_LAST_APP, if (open) currentApp?.name else null)
            .putString(PREF_LAST_TITLE, if (open) binding.browserTitle.text.toString() else null)
            .putLong(PREF_LAST_AT, System.currentTimeMillis())
            .apply()
    }

    /** Opens the page the user was on when Arrow Os was last closed (within a day). Returns true if it did. */
    private fun restoreOpenPage(): Boolean {
        val url = prefs.getString(PREF_LAST_URL, null) ?: return false
        if (System.currentTimeMillis() - prefs.getLong(PREF_LAST_AT, 0) > DateUtils.DAY_IN_MILLIS) return false
        val app = prefs.getString(PREF_LAST_APP, null)?.let { name -> ArrowApp.entries.firstOrNull { it.name == name } }
        if (app != null) openApp(app, url) else openWebsite(url, prefs.getString(PREF_LAST_TITLE, null) ?: Uri.parse(url).host ?: url)
        return true
    }

    // ---------- Downloads: always into Arrow Files, never the phone's storage ----------

    /**
     * Every download goes into the user's Arrow Files folder inside Arrow Os's private storage:
     * ArrowFiles/<user>/websites/<site>/<name>. The site is the page the user was on.
     */
    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val pageUrl = webView.url?.takeIf { it.startsWith("http") } ?: url
        val source = ArrowFilesIndex.sourceOf(pageUrl)
        when {
            url.startsWith("data:") -> saveDataUrl(url, source, pageUrl)
            url.startsWith("blob:") -> saveBlob(url, source, pageUrl, contentDisposition, mimeType)
            url.startsWith("http") -> downloadToArrowFiles(url, userAgent, contentDisposition, mimeType, source, pageUrl)
            else -> toast(getString(R.string.files_cant_download))
        }
    }

    private fun downloadToArrowFiles(
        url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, source: String, pageUrl: String,
    ) {
        val wanted = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val (name, relPath) = newFilePlace(source, wanted) ?: return
        val mime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: mimeOf(name)
        val file = ArrowFile(
            id = UUID.randomUUID().toString(), name = name, source = source, kind = FileKind.of(name, mime),
            mimeType = mime, size = 0, savedAt = System.currentTimeMillis(), originUrl = url,
            relPath = relPath, status = FileStatus.Downloading,
        )
        files.add(file)
        saveFiles()
        syncFile(file.id)
        runDownload(file, url, userAgent, pageUrl)
        toast(getString(R.string.files_downloading, name))
    }

    private fun runDownload(f: ArrowFile, url: String, userAgent: String?, referer: String?) {
        val target = File(filesBase(), f.relPath)
        val headers = buildMap {
            CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
            put("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: mobileUserAgent)
            if (referer != null && referer.startsWith("http")) put("Referer", referer)
        }
        val owner = filesUser
        downloader.download(f.id, url, headers, target, object : ArrowDownloader.Listener {
            override fun onProgress(id: String, bytes: Long, total: Long) {
                if (filesUser != owner) return
                downloadProgress[id] = bytes to total
                if (binding.filesScreen.isVisible) renderFiles()
            }

            override fun onFinished(id: String, ok: Boolean, size: Long, mimeType: String?) {
                downloadProgress.remove(id)
                if (filesUser != owner) return
                val done = files.update(id) {
                    if (ok) {
                        val mime = mimeType?.takeIf { m -> m.isNotBlank() && m != "application/octet-stream" && it.mimeType == "application/octet-stream" }
                        it.copy(status = FileStatus.Ready, size = size, mimeType = mime ?: it.mimeType,
                            kind = if (mime != null) FileKind.of(it.name, mime) else it.kind)
                    } else {
                        it.copy(status = FileStatus.Failed)
                    }
                } ?: return
                saveFiles()
                syncFile(id)
                if (ok) uploadWaitingFiles()
                toast(getString(if (ok) R.string.files_saved else R.string.files_failed, done.name))
                if (binding.filesScreen.isVisible) renderFiles()
            }
        })
    }

    /** A "blob:" file made inside the page: the page reads it out to Arrow Os in pieces. */
    private fun saveBlob(url: String, source: String, pageUrl: String, contentDisposition: String?, mimeType: String?) {
        val wanted = URLUtil.guessFileName(url, contentDisposition, mimeType).let { guess ->
            // blob: links have no name of their own; use the page's title if the guess is just the id.
            if (guess.length > 30 && !guess.contains('.')) "download" else guess
        }
        val (name, relPath) = newFilePlace(source, wanted) ?: return
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: mimeOf(name)
        val file = ArrowFile(
            id = UUID.randomUUID().toString(), name = name, source = source, kind = FileKind.of(name, mime),
            mimeType = mime, size = 0, savedAt = System.currentTimeMillis(), originUrl = pageUrl,
            relPath = relPath, status = FileStatus.Downloading,
        )
        files.add(file)
        saveFiles()
        toast(getString(R.string.files_downloading, name))
        val owner = filesUser
        val target = File(filesBase(), relPath)
        downloadProgress[file.id] = 0L to -1L
        blobSaver.save(webView, url, file.id, target) { ok, blobMime ->
            downloadProgress.remove(file.id)
            if (filesUser != owner) return@save
            if (!ok) {
                // Streaming videos (YouTube, TikTok) are made of pieces and can't be saved whole.
                files.remove(file.id)
                saveFiles()
                toast(getString(R.string.files_cant_save_stream))
            } else {
                val realMime = blobMime ?: mime
                var finalName = name
                // Give a nameless file the right ending, for example "download.mp4".
                if (!name.contains('.')) {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(realMime)?.let { ext ->
                        val renamed = File(target.parentFile, "$name.$ext")
                        if (!renamed.exists() && target.renameTo(renamed)) finalName = "$name.$ext"
                    }
                }
                files.update(file.id) {
                    it.copy(
                        name = finalName, relPath = ArrowFilesIndex.pathFor(owner, source, finalName),
                        status = FileStatus.Ready, size = File(filesBase(), ArrowFilesIndex.pathFor(owner, source, finalName)).length(),
                        mimeType = realMime, kind = FileKind.of(finalName, realMime),
                    )
                }
                saveFiles()
                syncFile(file.id)
                uploadWaitingFiles()
                toast(getString(R.string.files_saved, finalName))
            }
            if (binding.filesScreen.isVisible) renderFiles()
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
            val target = File(filesBase(), relPath)
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

    /**
     * Arrow Files' home: the app's private storage. Only Arrow Os can read it. The phone's
     * file manager, gallery and other apps can't see it, and it is never backed up to the phone's accounts.
     */
    private fun filesBase(): File = filesDir

    /** Where older versions of Arrow Os kept files; they are moved inside on first launch. */
    private fun legacyBase(): File? = getExternalFilesDir(null)

    /** A free name and its path in the user's folder for [source], with the folder created. */
    private fun newFilePlace(source: String, wanted: String): Pair<String, String>? {
        val base = filesBase()
        val name = files.uniqueName(filesUser, source, wanted) {
            File(base, ArrowFilesIndex.pathFor(filesUser, source, it)).exists() ||
                File(base, ArrowFilesIndex.pathFor(filesUser, source, it) + ".part").exists()
        }
        val relPath = ArrowFilesIndex.pathFor(filesUser, source, name)
        File(base, relPath).parentFile?.mkdirs()
        return name to relPath
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: "application/octet-stream"

    /** The file on this phone: inside Arrow Os, or (until it is moved) where older versions kept it. */
    private fun fileOf(f: ArrowFile): File {
        val inside = File(filesBase(), f.relPath)
        if (inside.exists()) return inside
        val old = legacyBase()?.let { File(it, f.relPath) }
        return if (old != null && old.exists()) old else inside
    }

    /** Moves files that older versions saved in the phone's shared storage into Arrow Os's private storage. */
    private fun moveFilesInsideArrowOs() {
        val oldRoot = legacyBase()?.let { File(it, ArrowFilesIndex.ROOT) } ?: return
        if (!oldRoot.exists()) return
        val newRoot = File(filesBase(), ArrowFilesIndex.ROOT)
        toast(getString(R.string.files_moving))
        cloudWorker.execute {
            oldRoot.walkBottomUp().forEach { src ->
                val rel = src.relativeTo(oldRoot)
                val dest = File(newRoot, rel.path)
                try {
                    if (src.isFile) {
                        if (src.name.endsWith(".part")) {
                            src.delete()
                        } else if (!dest.exists()) {
                            dest.parentFile?.mkdirs()
                            if (!src.renameTo(dest)) src.copyTo(dest, overwrite = false)
                            src.delete()
                        } else {
                            src.delete()
                        }
                    } else {
                        src.delete() // empty folder after its files moved
                    }
                } catch (e: Exception) {
                    // Leave it; fileOf() still finds it in the old place.
                }
            }
            runOnUiThread { if (binding.filesScreen.isVisible) renderFiles() }
        }
    }

    /** Marks a download from an older version (phone download manager) as ready or failed. */
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
        val onDisk = fileOf(f).takeIf { it.exists() && it.length() > 0 }
        val ready = status == DownloadManager.STATUS_SUCCESSFUL || (status == -1 && onDisk != null)
        val failed = !ready && (status == DownloadManager.STATUS_FAILED || status == -1)
        if (!ready && !failed) return // still downloading
        files.update(f.id) {
            if (ready) it.copy(status = FileStatus.Ready, size = onDisk?.length() ?: it.size, downloadId = -1)
            else it.copy(status = FileStatus.Failed, downloadId = -1)
        }
        saveFiles()
        syncFile(f.id)
        if (ready) {
            uploadWaitingFiles()
            moveFilesInsideArrowOs()
        }
        if (announce) toast(getString(if (ready) R.string.files_saved else R.string.files_failed, f.name))
        if (binding.filesScreen.isVisible) renderFiles()
    }

    /** Downloads that finished, failed or were cut off while Arrow Os was closed. */
    private fun checkUnfinishedDownloads() {
        files.all.filter { it.status == FileStatus.Downloading }.forEach { f ->
            when {
                f.downloadId >= 0 -> finishDownload(f, announce = false) // an older version's download
                !downloader.isActive(f.id) && downloadProgress[f.id] == null -> {
                    // Arrow Os was closed mid-download: it can be started again from Arrow Files.
                    files.update(f.id) { it.copy(status = FileStatus.Failed) }
                    saveFiles()
                    syncFile(f.id)
                }
            }
        }
    }

    /** Files that sat in Trash for 30 days are removed for good. */
    private fun emptyOldTrash() {
        val expired = files.expiredTrash(System.currentTimeMillis())
        if (expired.isEmpty()) return
        expired.forEach { f ->
            fileOf(f).delete()
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
            fullscreenView != null -> exitFullscreen()
            binding.viewerScreen.isVisible -> closeViewer()
            binding.popupScreen.isVisible -> {
                val popup = popupView
                if (popup != null && popup.canGoBack()) popup.goBack() else closePopup()
            }
            binding.designerScreen.isVisible -> if (homeSetup != null) hideDesigner()
            binding.previousScreen.isVisible -> hidePrevious()
            binding.filesScreen.isVisible && filesShowTrash -> {
                filesShowTrash = false
                renderFiles()
            }
            binding.filesScreen.isVisible -> hideFiles()
            binding.browserScreen.isVisible && webView.canGoBack() -> webView.goBack()
            binding.browserScreen.isVisible -> showHome()
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val firstDesign = (binding.designerScreen.isVisible && homeSetup == null) || binding.signInScreen.isVisible
                val onHome = binding.homeScreen.isVisible && !binding.designerScreen.isVisible && !binding.previousScreen.isVisible &&
                    !binding.filesScreen.isVisible
                if (fullscreenView == null && (onHome || firstDesign)) {
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

    /**
     * Saves Previous a moment later, once. YouTube and TikTok change the address on every
     * video, and writing the whole list each time made scrolling stutter on small phones.
     */
    private fun savePrevious() {
        housekeeping.removeCallbacks(savePreviousNow)
        housekeeping.postDelayed(savePreviousNow, 2000)
    }

    private val savePreviousNow = Runnable { prefs.edit().putString(PREF_PREVIOUS, previous.encode()).apply() }

    private fun flushPrevious() {
        housekeeping.removeCallbacks(savePreviousNow)
        savePreviousNow.run()
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
        // Back where the user left off, still signed in to the site.
        if (homeSetup != null) restoreOpenPage()
        if (!userMuted) ensureMicPermissionThenListen(userAsked = false)
        clonerSkills = ClonerSkills.decode(prefs.getString(clonerPrefKey(), null))
        pendingClonerTask?.let { task ->
            pendingClonerTask = null
            askToRunClonerTask(task)
        }
    }

    /**
     * The first account to sign in on this phone takes over what was saved before sign-in
     * existed: the files in the guest folder and the home screen.
     */
    private fun adoptDataFromBeforeSignIn(uid: String) {
        val guestFiles = prefs.getString(PREF_FILES, null)
        if (!guestFiles.isNullOrBlank() && files.all.isEmpty()) {
            val guest = ArrowFilesIndex.decode(guestFiles)
            listOfNotNull(filesBase(), legacyBase()).forEach { base ->
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
        cloud.signOut()
        rememberOpenPage()
        prefs.edit().remove(PREF_LAST_URL).apply()
        // The next person on this phone must not land in this person's Gmail, TikTok or WhatsApp.
        closePopup()
        closeViewer()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
        accountUid = null
        filesUser = ArrowFilesIndex.LOCAL_USER
        files = ArrowFilesIndex()
        homeSetup = null
        clonerSkills = ClonerSkills()
        clonerRecorder.cancel()
        designerShown = emptyList()
        designerTicked.clear()
        currentApp = null
        binding.browserScreen.isVisible = false
        binding.homeScreen.isVisible = true
        webView.stopLoading()
        webView.loadUrl("about:blank")
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
                FileStatus.Downloading -> downloadProgress[f.id]?.takeIf { it.first > 0 }?.let { (bytes, total) ->
                    if (total > 0) getString(R.string.files_progress, Formatter.formatShortFileSize(this, bytes), Formatter.formatShortFileSize(this, total))
                    else Formatter.formatShortFileSize(this, bytes)
                } ?: getString(R.string.files_still_downloading)
                FileStatus.Failed -> getString(R.string.files_failed_short)
                FileStatus.Ready -> "${Formatter.formatShortFileSize(this, f.size)} · " +
                    DateUtils.getRelativeTimeSpanString(f.savedAt, now, DateUtils.MINUTE_IN_MILLIS) + " · " +
                    getString(
                        when {
                            f.inCloud && !fileOf(f).exists() -> R.string.files_cloud_only
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
        // Files stay in Arrow Os: there is no "Save to phone" or "Share to another app".
        // A file leaves only when the user posts it on a website (it is offered whenever a site asks for a file).
        val options = when {
            f.inTrash -> listOf(R.string.files_restore, R.string.files_delete_forever)
            f.status == FileStatus.Ready -> listOf(R.string.files_open, R.string.files_move_to_trash)
            f.status == FileStatus.Downloading -> listOf(R.string.files_cancel)
            else -> listOf(R.string.files_retry, R.string.files_delete_forever)
        }
        options.forEachIndexed { i, res -> menu.menu.add(0, res, i, res) }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.string.files_open -> openFile(f)
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
                R.string.files_cancel -> {
                    downloader.cancel(f.id)
                    downloadProgress.remove(f.id)
                    File(filesBase(), f.relPath + ".part").delete()
                    files.update(f.id) { it.copy(status = FileStatus.Failed) }
                    saveFiles()
                    syncFile(f.id)
                    toast(getString(R.string.files_stopped))
                }
                R.string.files_retry -> retryDownload(f)
                R.string.files_delete_forever -> {
                    if (f.status == FileStatus.Downloading && f.downloadId >= 0) {
                        try {
                            getSystemService(DownloadManager::class.java).remove(f.downloadId)
                        } catch (e: Exception) {
                            // already gone
                        }
                    }
                    downloader.cancel(f.id)
                    fileOf(f).delete()
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

    /** Starts a failed or stopped download again, into the same place in Arrow Files. */
    private fun retryDownload(f: ArrowFile) {
        if (!f.originUrl.startsWith("http")) {
            toast(getString(R.string.files_cant_download))
            return
        }
        files.update(f.id) { it.copy(status = FileStatus.Downloading, downloadId = -1, size = 0) }
        saveFiles()
        syncFile(f.id)
        runDownload(files.get(f.id) ?: return, f.originUrl, null, null)
        toast(getString(R.string.files_downloading, f.name))
    }

    /**
     * Runs [action] once the file is on this phone. A file that is only in the cloud
     * (saved on another phone) is downloaded first, into Arrow Os.
     */
    private fun withLocalCopy(f: ArrowFile, action: () -> Unit) {
        val local = fileOf(f)
        if (f.status != FileStatus.Ready || local.exists() || !f.inCloud) {
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
                if (!local.exists()) continue
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

    /** Opens the file inside Arrow Os. It is never handed to another app on the phone. */
    private fun openFileNow(f: ArrowFile) {
        if (f.status != FileStatus.Ready) {
            toast(getString(if (f.status == FileStatus.Downloading) R.string.files_still_downloading else R.string.files_failed_short))
            return
        }
        val file = fileOf(f)
        if (!file.exists()) {
            toast(getString(R.string.files_missing))
            return
        }
        binding.viewerTitle.text = f.name
        binding.viewerScreen.isVisible = true
        if (!viewer.show(file, f.kind, f.mimeType, f.name)) {
            closeViewer()
            toast(getString(R.string.files_no_app))
        }
    }

    private fun closeViewer() {
        if (!binding.viewerScreen.isVisible) return
        viewer.close()
        binding.viewerScreen.isVisible = false
    }

    /**
     * A website asked for a file (to post a photo or video). Arrow Files is offered first, so
     * work made in Arrow Os can be published; the phone's gallery is the last option.
     */
    private fun showFilePicker(params: WebChromeClient.FileChooserParams) {
        val accept = params.acceptTypes.orEmpty().map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        val choices = files.active.filter { f ->
            f.status == FileStatus.Ready && fileOf(f).exists() && matchesAccept(f, accept)
        }.take(60)
        val labels = choices.map { "${it.name}  ·  ${it.source}" } + getString(R.string.pick_from_phone)
        var answered = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (choices.isEmpty()) getString(R.string.pick_none) else getString(R.string.pick_title))
            .setItems(labels.toTypedArray()) { _, which ->
                answered = true
                if (which < choices.size) {
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", fileOf(choices[which]))
                    fileChooserCallback?.onReceiveValue(arrayOf(uri))
                    fileChooserCallback = null
                } else {
                    try {
                        fileChooser.launch(params.createIntent())
                    } catch (e: Exception) {
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                        toast("No app on this phone can pick that file")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            if (!answered) {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = null
            }
        }
        dialog.show()
    }

    /** True when [f] fits what the site's file box accepts (for example any image, "video/mp4" or ".pdf"). */
    private fun matchesAccept(f: ArrowFile, accept: List<String>): Boolean {
        val types = accept.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        if (types.isEmpty() || "*/*" in types) return true
        val mime = f.mimeType.lowercase(Locale.ROOT)
        val name = f.name.lowercase(Locale.ROOT)
        return types.any { t ->
            when {
                t.startsWith(".") -> name.endsWith(t)
                t.endsWith("/*") -> mime.startsWith(t.removeSuffix("*"))
                else -> mime == t
            }
        }
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
        if (binding.designerScreen.isVisible || binding.signInScreen.isVisible) return
        val commands = CommandParser.parseAll(text)
        if (commands.singleOrNull() is Command.Ignored) {
            // Not a command: do nothing. Only a typed line gets a hint, since the
            // always-on mic hears plenty of talk that isn't meant for Arrow Os.
            binding.voiceStatus.text = getString(R.string.say_ignored, text.trim())
            if (typed) toast(getString(R.string.say_ignored, text.trim()))
            return
        }
        binding.voiceStatus.text = "“$text”"
        handler.removeCallbacksAndMessages(null) // a new instruction replaces an unfinished sequence
        if (clonerRecorder.record(text)) {
            binding.voiceStatus.text = getString(R.string.cloner_learned, clonerRecorder.recorded.size, text.trim())
        }
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
        binding.voiceStatus.text = "“$text”"
    }

    override fun onPhrase(text: String) = handleInput(text, typed = false)

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
                val app = command.app ?: currentApp.takeIf { inApp }
                if (app != null) {
                    confirm(getString(R.string.say_searching, command.query, app.label))
                    openApp(app, app.searchUrl(command.query))
                } else {
                    confirm(getString(R.string.say_searching_web, command.query))
                    openWebsite(WebLinks.searchUrl(command.query), command.query)
                }
            }
            is Command.OpenWebsite -> {
                findHomeTool(command.target)?.let { tool ->
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
                if (webView.canGoForward()) webView.goForward() else toast("Nothing to go forward to")
            }
            Command.Home -> {
                confirm(getString(R.string.say_home))
                exitFullscreen()
                showHome()
            }
            Command.Reload -> inPage {
                confirm(getString(R.string.say_reload))
                webView.reload()
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
            Command.ZoomIn -> inPage { webView.zoomIn() }
            Command.ZoomOut -> inPage { webView.zoomOut() }

            Command.Play -> inPage { web.play { ok -> toast(if (ok) "Playing" else "No video on this page") } }
            Command.Pause -> inPage { web.pause { ok -> toast(if (ok) "Paused" else "No video on this page") } }
            Command.Mute -> inPage { web.mute { ok -> toast(if (ok) "Muted" else "No video on this page") } }
            Command.Unmute -> inPage { web.unmute { ok -> toast(if (ok) "Sound on" else "No video on this page") } }

            is Command.Tap -> {
                // "tap open Canva" (or just "tap Canva" on the home screen): any app the user put on their home screen.
                val spoken = CommandParser.normalize(command.spoken)
                val toolName = Regex("^(?:open|launch|start|go to|switch to|visit|take me to) (.+)$")
                    .find(spoken)?.groupValues?.get(1) ?: spoken.takeIf { !inApp && !binding.previousScreen.isVisible }
                val tool = toolName?.let { findHomeTool(it.removeSuffix(" app")) }
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
            Command.StartTeaching -> startTeaching()
            is Command.StopTeaching -> stopTeaching(command.name)
            Command.ShowSkills -> showClonerSkills()
            is Command.RunSkill -> {
                val skill = clonerSkills.find(command.name)
                if (skill != null) {
                    runClonerTask(skill)
                } else {
                    // Not a skill: it may be a button called "Run code" or "Do it now".
                    perform(Command.Tap(command.spoken, TapVocabulary.labelsFor(command.spoken)))
                }
            }
            is Command.Ignored -> Unit
        }
    }

    // ---------- Arrow Cloner ----------

    private fun clonerPrefKey() = "cloner_skills_${accountUid ?: ArrowFilesIndex.LOCAL_USER}"

    private fun saveClonerSkills() {
        prefs.edit().putString(clonerPrefKey(), clonerSkills.encode()).apply()
    }

    private fun startTeaching() {
        clonerRecorder.start()
        confirm(getString(R.string.say_cloner_teaching))
        binding.voiceStatus.text = getString(R.string.cloner_watching)
    }

    private fun stopTeaching(name: String?) {
        if (!clonerRecorder.isRecording) {
            toast(getString(R.string.cloner_not_teaching))
            return
        }
        val steps = clonerRecorder.stop()
        if (steps.isEmpty()) {
            toast(getString(R.string.cloner_nothing_learned))
            return
        }
        if (!name.isNullOrBlank()) {
            saveClonerSkill(ClonerTask(name.trim(), steps))
            return
        }
        // No name said: ask for one.
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.cloner_name_hint)
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.cloner_name_title)
            .setMessage(steps.joinToString("\n") { "• $it" })
            .setView(box)
            .setPositiveButton(R.string.cloner_save) { _, _ ->
                val typed = input.text.toString().trim().ifEmpty { "Skill ${clonerSkills.size + 1}" }
                saveClonerSkill(ClonerTask(typed, steps))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> toast(getString(R.string.cloner_forgot)) }
            .show()
    }

    private fun saveClonerSkill(task: ClonerTask) {
        clonerSkills.save(task)
        saveClonerSkills()
        confirm(getString(R.string.say_cloner_saved, task.name))
    }

    /** The skills Arrow Cloner knows: tap one to run it. */
    private fun showClonerSkills() {
        if (clonerSkills.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.cloner_title)
                .setMessage(R.string.cloner_empty)
                .setPositiveButton(R.string.cloner_teach_now) { _, _ -> startTeaching() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val skills = clonerSkills.all
        val rows = skills.map { "${it.name}  (${it.steps.size} steps)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.cloner_title)
            .setItems(rows) { _, i -> runClonerTask(skills[i]) }
            .setPositiveButton(R.string.cloner_teach_new) { _, _ -> startTeaching() }
            .setNeutralButton(R.string.cloner_delete) { _, _ -> askToDeleteClonerSkill() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askToDeleteClonerSkill() {
        val skills = clonerSkills.all
        AlertDialog.Builder(this)
            .setTitle(R.string.cloner_delete_title)
            .setItems(skills.map { it.name }.toTypedArray()) { _, i ->
                clonerSkills.remove(skills[i].name)
                saveClonerSkills()
                toast(getString(R.string.cloner_deleted, skills[i].name))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * A task handed over by a link. A link can come from any website, so Arrow Os always
     * shows the steps and runs them only when the user says so.
     */
    private fun askToRunClonerTask(task: ClonerTask) {
        if (binding.designerScreen.isVisible || binding.signInScreen.isVisible) {
            pendingClonerTask = task
            return
        }
        val plan = ClonerPlan.of(task)
        val message = buildString {
            task.steps.forEachIndexed { i, step -> append("${i + 1}. $step\n") }
            if (plan.problems.isNotEmpty()) {
                append("\n").append(getString(R.string.cloner_skipped)).append("\n")
                plan.problems.forEach { append("• ${it.line}: ${it.reason}\n") }
            }
        }.trim()
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.cloner_run_title, task.name))
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
        if (plan.canRun) {
            dialog.setPositiveButton(R.string.cloner_run) { _, _ -> runClonerTask(task) }
            dialog.setNeutralButton(R.string.cloner_keep) { _, _ -> saveClonerSkill(task) }
        }
        dialog.show()
    }

    /** Arrow Cloner at work: each step runs like a spoken command, one after another. */
    private fun runClonerTask(task: ClonerTask) {
        val plan = ClonerPlan.of(task)
        if (!plan.canRun) {
            toast(getString(R.string.cloner_no_steps, task.name))
            return
        }
        handler.removeCallbacksAndMessages(null)
        confirm(getString(R.string.say_cloner_running, task.name))
        runClonerAction(task.name, plan.actions, 0)
    }

    private fun runClonerAction(name: String, actions: List<ClonerAction>, index: Int) {
        val action = actions.getOrNull(index)
        if (action == null) {
            binding.voiceStatus.text = getString(R.string.cloner_done, name)
            toast(getString(R.string.cloner_done, name))
            return
        }
        binding.voiceStatus.text = getString(R.string.cloner_step, index + 1, actions.size, action.line)
        val delay = when (action) {
            is ClonerAction.Wait -> action.seconds * 1000L
            is ClonerAction.Do -> {
                perform(action.command)
                // Pages on small phones and slow data need a moment longer than a person's chain.
                if (action.command.loadsPage()) 4000L else 1200L
            }
        }
        handler.postDelayed({ runClonerAction(name, actions, index + 1) }, delay)
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

                ARROW CLONER learns your work and repeats it:
                tap teach arrow cloner (then do the work with tap and search)
                tap save skill as morning post
                tap do morning post · tap cloner skills

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

    private companion object {
        const val PREF_PREVIOUS = "previous_tabs"
        const val PREF_HOME_SETUP = "home_setup"
        const val PREF_FILES = "arrow_files"
        const val PREF_LAST_URL = "last_url"
        const val PREF_LAST_APP = "last_app"
        const val PREF_LAST_TITLE = "last_title"
        const val PREF_LAST_AT = "last_at"
        const val FILES_COLOR = "#0091B3"
        val previousScrollCommands = setOf(
            Command.ScrollDown, Command.ScrollUp, Command.ScrollTop, Command.ScrollBottom, Command.NextItem, Command.PreviousItem,
        )
    }

    private fun resumeMic() = runOnUiThread {
        // Stay paused while the user is typing a command.
        if (!binding.commandInput.hasFocus()) voice.paused = false
    }
}
