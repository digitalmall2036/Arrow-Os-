package com.arrowos.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.Html
import android.webkit.CookieManager
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.arrowos.app.databinding.ActivityMainBinding
import com.arrowos.app.databinding.ItemAppTileBinding
import com.arrowos.app.databinding.ItemDesignerToolBinding
import com.arrowos.app.databinding.ItemPreviousTabBinding
import java.util.Locale

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

    /** "Previous": every tab ever opened in Arrow Os, saved across launches. */
    private lateinit var previous: TabHistory
    private val prefs by lazy { getSharedPreferences("arrow_os", MODE_PRIVATE) }

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
        homeSetup = HomeSetup.decode(prefs.getString(PREF_HOME_SETUP, null))
        setupHomeScreen()
        setupDesigner()
        setupPreviousScreen()
        setupBrowser()
        setupVoiceBar()
        setupBackHandling()
        setupTts()

        showHome()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        if (!userMuted) ensureMicPermissionThenListen(userAsked = false)
    }

    override fun onPause() {
        // Always-listening only while Arrow Os is on screen.
        voice.stop()
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        voice.destroy()
        tts?.shutdown()
        binding.webView.destroy()
        super.onDestroy()
    }

    // ---------- Home screen ----------

    private fun setupHomeScreen() {
        binding.homePrevious.setOnClickListener { showPrevious() }
        binding.homeChangeBusiness.setOnClickListener { showDesigner() }
        renderHomeApps()
    }

    /** The home screen shows exactly the apps the user ticked in the Auto Designer. */
    private fun renderHomeApps() {
        binding.appGrid.removeAllViews()
        val setup = homeSetup
        val tools = setup?.tools.orEmpty()
        val inflater = LayoutInflater.from(this)
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
        val app = ArrowApp.fromUrl(tool.url)
        if (app != null) openApp(app, tool.url) else openWebsite(tool.url, tool.label)
    }

    private fun showHome() {
        hidePrevious()
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
        errorPageUrl = null
        binding.homeScreen.isVisible = false
        binding.browserScreen.isVisible = true
        pendingAlternatives.clear()
        triedAlternatives = false
        if (freshHistory) resetHistoryOnNextPage = true
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

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http")) {
            toast("This file can't be downloaded here")
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            getSystemService(DownloadManager::class.java).enqueue(request)
            toast("Downloading $fileName. Tap the notification when it's done.")
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
        }
    }

    private fun goBack() {
        when {
            fullscreenView != null -> exitFullscreen()
            binding.designerScreen.isVisible -> if (homeSetup != null) hideDesigner()
            binding.previousScreen.isVisible -> hidePrevious()
            binding.browserScreen.isVisible && binding.webView.canGoBack() -> binding.webView.goBack()
            binding.browserScreen.isVisible -> showHome()
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val firstDesign = binding.designerScreen.isVisible && homeSetup == null
                val onHome = binding.homeScreen.isVisible && !binding.designerScreen.isVisible && !binding.previousScreen.isVisible
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
        prefs.edit().putString(PREF_HOME_SETUP, setup.encode()).apply()
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
        if (binding.designerScreen.isVisible) return
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

    private val inApp: Boolean get() = binding.browserScreen.isVisible && !binding.previousScreen.isVisible

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

    private companion object {
        const val PREF_PREVIOUS = "previous_tabs"
        const val PREF_HOME_SETUP = "home_setup"
        val previousScrollCommands = setOf(
            Command.ScrollDown, Command.ScrollUp, Command.ScrollTop, Command.ScrollBottom, Command.NextItem, Command.PreviousItem,
        )
    }

    private fun resumeMic() = runOnUiThread {
        // Stay paused while the user is typing a command.
        if (!binding.commandInput.hasFocus()) voice.paused = false
    }
}
