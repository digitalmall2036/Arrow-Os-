// Arrow Os for Windows: the window itself. Home screen, built-in browser, and the
// "tap" / "search" commands from voice or the command box.
(function () {
  'use strict';

  const A = window.ArrowCommands;
  const $ = (id) => document.getElementById(id);
  const web = $('web');
  const status = $('status');
  const input = $('commandInput');
  const micBtn = $('micBtn');

  let helpers = '';
  let inApp = false;
  let currentApp = null;
  let micWanted = true;
  let micOn = false;
  let isFullscreen = false;
  let runId = 0; // a new instruction cancels an unfinished sequence
  let speakingUntil = 0;
  const failedHosts = new Set();

  window.arrow.pageHelpers().then((h) => { helpers = h; });

  // ---------- home screen ----------

  function buildTiles() {
    const tiles = $('tiles');
    const dots = $('appDots');
    A.APPS.forEach((app) => {
      const tile = document.createElement('button');
      tile.className = 'tile';
      tile.innerHTML = `<div class="circle" style="background:${app.color}">${app.label[0].toUpperCase()}</div><span>${app.label}</span>`;
      tile.addEventListener('click', () => openApp(app));
      tiles.appendChild(tile);

      const dot = document.createElement('button');
      dot.className = 'app-dot';
      dot.dataset.app = app.id;
      dot.title = app.label;
      dot.style.background = app.color;
      dot.textContent = app.label[0].toUpperCase();
      dot.addEventListener('click', () => openApp(app));
      dots.appendChild(dot);
    });
  }

  function tickClock() {
    const now = new Date();
    $('clock').textContent = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + '  ·  ' +
      now.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' });
  }

  function showHome() {
    inApp = false;
    currentApp = null;
    $('browser').classList.add('hidden');
    $('home').classList.remove('hidden');
    try { web.stop(); } catch (e) { /* not attached yet */ }
    try { web.executeJavaScript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})"); } catch (e) { /* ignore */ }
    markApp();
  }

  function showBrowser() {
    inApp = true;
    $('home').classList.add('hidden');
    $('browser').classList.remove('hidden');
  }

  function markApp() {
    document.querySelectorAll('.app-dot').forEach((d) => d.classList.toggle('active', !!currentApp && d.dataset.app === currentApp.id));
  }

  // ---------- browser ----------

  function openApp(app, url) {
    currentApp = app;
    markApp();
    openUrl(url || app.homeUrl);
  }

  function openUrl(url) {
    showBrowser();
    $('errorBox').classList.add('hidden');
    failedHosts.clear();
    load(url);
  }

  function load(url) {
    // loadURL rejects on redirects and aborted loads too; real failures arrive as did-fail-load.
    web.loadURL(url).catch(() => {});
  }

  web.addEventListener('did-navigate', (e) => {
    const app = A.appForUrl(e.url);
    if (app) currentApp = app;
    markApp();
    updateNav();
  });
  web.addEventListener('did-navigate-in-page', updateNav);
  web.addEventListener('page-title-updated', (e) => { $('pageTitle').textContent = e.title; });
  web.addEventListener('did-start-loading', () => $('loading').classList.remove('hidden'));
  web.addEventListener('did-stop-loading', () => { $('loading').classList.add('hidden'); updateNav(); });
  web.addEventListener('did-fail-load', (e) => {
    if (!e.isMainFrame || e.errorCode === -3) return; // -3: aborted, e.g. by a redirect
    let host = '';
    let url;
    try { url = new URL(e.validatedURL); host = url.hostname; } catch (x) { /* ignore */ }
    failedHosts.add(host);
    // Some networks block one address of a site but not another (Facebook has several).
    const next = A.WebLinks.alternativesFor(host).find((h) => !failedHosts.has(h));
    if (next && url) {
      url.hostname = next;
      status.textContent = `${host} didn't open, trying ${next}…`;
      load(url.toString());
      return;
    }
    $('errorText').textContent = `${e.errorDescription || 'The page failed to load'} (${host || e.validatedURL}). ` +
      'Check that the laptop is online and that this site opens in a normal browser.';
    $('errorBox').dataset.url = e.validatedURL;
    $('errorBox').classList.remove('hidden');
  });
  web.addEventListener('enter-html-full-screen', () => setFullscreenUi(true));
  web.addEventListener('leave-html-full-screen', () => setFullscreenUi(false));

  function updateNav() {
    try {
      $('backBtn').disabled = false; // back from the first page goes home
      $('pageTitle').textContent = web.getTitle() || web.getURL();
    } catch (e) { /* not ready */ }
  }

  function goBack() {
    if (web.canGoBack()) web.goBack();
    else showHome();
  }

  function setFullscreenUi(on) {
    isFullscreen = on;
    document.body.classList.toggle('fullscreen', on);
  }

  $('backBtn').addEventListener('click', goBack);
  $('homeBtn').addEventListener('click', showHome);
  $('reloadBtn').addEventListener('click', () => web.reload());
  $('retryBtn').addEventListener('click', () => openUrl($('errorBox').dataset.url || web.getURL()));
  $('helpLink').addEventListener('click', (e) => { e.preventDefault(); showHelp(); });

  // ---------- acting inside the page ----------

  /** Runs one of the page helpers (page-actions.js) inside the open website. */
  async function page(call) {
    if (!helpers) helpers = await window.arrow.pageHelpers();
    try {
      // userGesture=true lets the page start video and go full screen, like a real click.
      return await web.executeJavaScript(
        `${helpers}\n;(function(){try{return window.__arrow.${call}}catch(e){return null}})()`, true);
    } catch (e) {
      return null;
    }
  }
  const run = async (call) => !!(await page(call));
  const tapLabels = async (labels) => {
    const name = await page(`tap(${JSON.stringify(labels)})`);
    return name ? String(name) : null;
  };

  // ---------- commands ----------

  function handleInput(text, typed) {
    const commands = A.parseAll(text);
    if (commands.length === 1 && commands[0].type === 'Ignored') {
      // The always-on mic hears plenty of talk that isn't meant for Arrow Os, so only
      // a typed line gets a visible hint.
      status.textContent = `Heard “${text.trim()}”. Start with "tap" or "search" to give a command.`;
      if (typed) toast('Start with "tap" or "search", for example "tap open YouTube"');
      return;
    }
    status.textContent = `“${text.trim()}”`;
    runSequence(commands, ++runId);
  }

  const loadsPage = (c) => ['Open', 'Search', 'OpenWebsite', 'Home', 'Back', 'Tap', 'TapNth', 'Reload'].includes(c.type);
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  async function runSequence(commands, id) {
    for (let i = 0; i < commands.length; i++) {
      if (id !== runId) return;
      await perform(commands[i]);
      if (i + 1 < commands.length) {
        if (loadsPage(commands[i])) await waitForPage();
        else await sleep(800);
      }
    }
  }

  /** Waits until the page that a command opened has loaded, then a moment more for it to draw. */
  async function waitForPage() {
    await sleep(700);
    const start = Date.now();
    while (Date.now() - start < 8000) {
      let loading = false;
      try { loading = web.isLoading(); } catch (e) { /* not ready */ }
      if (!loading) break;
      await sleep(200);
    }
    await sleep(1200);
  }

  function needsPage() {
    if (inApp) return true;
    toast('Open an app or website first, for example "tap open TikTok"');
    return false;
  }

  async function perform(c) {
    switch (c.type) {
      case 'Open':
        confirm(`Opening ${c.app.label}`);
        openApp(c.app);
        break;
      case 'Search': {
        // Inside one of the five apps, search that app; anywhere else, search the web.
        const app = c.app || (inApp ? currentApp : null);
        if (app) {
          confirm(`Searching ${c.query} on ${app.label}`);
          openApp(app, app.search(c.query));
        } else {
          confirm(`Searching the web for ${c.query}`);
          currentApp = null;
          openUrl(A.WebLinks.searchUrl(c.query));
        }
        break;
      }
      case 'OpenWebsite':
        confirm(`Opening ${c.target}`);
        currentApp = null;
        openUrl(A.WebLinks.websiteUrl(c.target));
        break;
      case 'Back':
        confirm('Going back');
        if (inApp) goBack();
        break;
      case 'Forward':
        if (needsPage()) { if (web.canGoForward()) web.goForward(); else toast('Nothing to go forward to'); }
        break;
      case 'Home':
        confirm('Home');
        if (isFullscreen) await window.arrow.toggleFullscreen();
        showHome();
        break;
      case 'Reload':
        if (needsPage()) { confirm('Reloading'); web.reload(); }
        break;

      case 'ScrollDown': if (needsPage()) { toast('Scrolling down'); await run('scroll(0.8)'); } break;
      case 'ScrollUp': if (needsPage()) { toast('Scrolling up'); await run('scroll(-0.8)'); } break;
      case 'ScrollTop': if (needsPage()) { toast('Back to the top'); await run('edge(false)'); } break;
      case 'ScrollBottom': if (needsPage()) { toast('To the bottom'); await run('edge(true)'); } break;
      // The page's own Next/Previous button first; feeds without one (TikTok, Shorts) just move on.
      case 'NextItem':
      case 'PreviousItem': {
        if (!needsPage()) break;
        const forward = c.type === 'NextItem';
        const name = await tapLabels(forward ? A.TapVocabulary.next : A.TapVocabulary.previous);
        if (name) confirm(`Tapped ${name}`);
        else { toast(forward ? 'Next' : 'Previous'); await run(`item(${forward ? 1 : -1})`); }
        break;
      }
      case 'ZoomIn':
      case 'ZoomOut':
        if (needsPage()) {
          const level = web.getZoomLevel() + (c.type === 'ZoomIn' ? 0.5 : -0.5);
          web.setZoomLevel(level);
          toast(`Zoom ${Math.round(100 * Math.pow(1.2, level))}%`);
        }
        break;
      case 'Fullscreen':
        if (isFullscreen) {
          await web.executeJavaScript('document.fullscreenElement&&document.exitFullscreen()', true).catch(() => {});
          await window.arrow.toggleFullscreen();
        } else if (!inApp || !(await tapLabels(['full screen', 'fullscreen', 'enter full screen']))) {
          await window.arrow.toggleFullscreen();
        }
        break;

      case 'Play': if (needsPage()) toast((await run("media('play')")) ? 'Playing' : 'No video on this page'); break;
      case 'Pause': if (needsPage()) toast((await run("media('pause')")) ? 'Paused' : 'No video on this page'); break;
      case 'Mute': if (needsPage()) toast((await run("media('mute')")) ? 'Muted' : 'No video on this page'); break;
      case 'Unmute': if (needsPage()) toast((await run("media('unmute')")) ? 'Sound on' : 'No video on this page'); break;

      case 'Tap': {
        if (!needsPage()) break;
        status.textContent = `Looking for “${c.spoken}” on the page…`;
        const name = await tapLabels(c.labels);
        if (name) {
          confirm(`Tapped ${c.spoken}`);
          status.textContent = `Tapped “${name}”`;
        } else {
          toast(`Couldn't find "${c.spoken}" on this page`);
          status.textContent = `Couldn't find “${c.spoken}” on this page`;
        }
        break;
      }
      case 'TapNth':
        if (needsPage()) {
          if (await run(`nth(${c.n})`)) confirm(`Opening number ${c.n}`);
          else toast(`Couldn't find item ${c.n} on this page`);
        }
        break;
      case 'ScrollToNth':
        if (needsPage()) toast((await run(`scrollToNth(${c.n})`)) ? `Number ${c.n}` : `Couldn't find item ${c.n}`);
        break;
      case 'TypeText':
        if (needsPage()) {
          const ok = await run(`type(${JSON.stringify(c.text)}, ${c.submit})`);
          toast(ok ? `Typed "${c.text}"` : 'No text box on this page. Click one first, then type.');
        }
        break;
      case 'PressEnter':
        if (needsPage()) await run('enter()');
        break;

      case 'Help':
        showHelp();
        break;
      case 'StopListening':
        confirm('Voice off. Click the mic to turn it back on.');
        setMic(false);
        break;
      default:
        break;
    }
  }

  function showHelp() {
    const d = $('help');
    if (!d.open) d.showModal();
  }

  // ---------- feedback ----------

  let toastTimer = null;
  function toast(text, action) {
    const t = $('toast');
    t.textContent = text;
    if (action) {
      const b = document.createElement('button');
      b.textContent = action.label;
      b.addEventListener('click', () => { action.run(); t.classList.add('hidden'); });
      t.appendChild(b);
    }
    t.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add('hidden'), action ? 7000 : 2600);
  }

  /** Shows and says a short confirmation, like the Android version. */
  function confirm(text) {
    toast(text);
    try {
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(text);
      u.rate = 1.05;
      // Don't treat Arrow Os's own voice as a command.
      speakingUntil = Date.now() + 600 + text.length * 80;
      u.onend = () => { speakingUntil = Date.now() + 400; };
      window.speechSynthesis.speak(u);
    } catch (e) { /* no speech voice installed */ }
  }

  // ---------- voice ----------

  async function setMic(on) {
    micWanted = on;
    if (on) {
      status.textContent = 'Starting the mic…';
      await window.arrow.startVoice();
    } else {
      await window.arrow.stopVoice();
    }
  }

  function showMic(on) {
    micOn = on;
    micBtn.classList.toggle('on', on);
  }

  const voiceErrors = {
    'not-windows': 'Voice control works on Windows. Type commands in the box instead.',
    'no-recognizer': 'Windows speech recognition isn\'t installed. Add English in Settings > Time & language > Speech, or type commands.',
    'no-mic': 'No microphone found. Plug one in or allow mic access in Settings > Privacy > Microphone, then click the mic.',
    'no-powershell': 'Voice needs Windows PowerShell, which wasn\'t found. Type commands in the box instead.',
  };

  window.arrow.onVoice((msg) => {
    if (msg.kind === 'listening') {
      showMic(true);
      status.textContent = 'Listening… start with "tap" or "search"';
    } else if (msg.kind === 'off') {
      showMic(false);
      status.textContent = 'Mic is off. Click the mic (or Ctrl+M) to talk, or type below.';
    } else if (msg.kind === 'error') {
      showMic(false);
      micWanted = false;
      status.textContent = voiceErrors[msg.code] || `Voice stopped: ${msg.code}. Type commands, or click the mic to retry.`;
    } else if (msg.kind === 'phrase') {
      if (Date.now() < speakingUntil) return;
      micBtn.classList.remove('hearing');
      void micBtn.offsetWidth; // restart the pulse animation
      micBtn.classList.add('hearing');
      handleInput(msg.text, false);
    }
  });

  micBtn.addEventListener('click', () => setMic(!micOn));

  // ---------- typing, shortcuts, downloads ----------

  $('commandForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    // Typing "open youtube" without "tap" is clearly meant as a command, so help it along.
    const parsed = A.parseAll(text);
    const isIgnored = parsed.length === 1 && parsed[0].type === 'Ignored';
    handleInput(isIgnored && A.parseAll('tap ' + text)[0].type !== 'Tap' ? 'tap ' + text : text, true);
  });

  window.arrow.onShortcut((name) => {
    if (name === 'command') { input.focus(); input.select(); }
    else if (name === 'mic') setMic(!micOn);
    else if (name === 'fullscreen') perform({ type: 'Fullscreen' });
    else if (name === 'back' && inApp) goBack();
    else if (name === 'home') showHome();
    else if (name === 'reload' && inApp) web.reload();
    else if (name === 'escape' && isFullscreen) perform({ type: 'Fullscreen' });
  });

  window.arrow.onFullscreen(setFullscreenUi);

  window.arrow.onDownload((d) => {
    if (d.state === 'started') toast(`Downloading ${d.name}…`);
    else if (d.state === 'completed') {
      toast(`Downloaded ${d.name} to your Downloads folder`, { label: 'Open', run: () => window.arrow.openDownload(d.path) });
    } else toast(`Download of ${d.name} didn't finish`);
  });

  // ---------- start ----------

  buildTiles();
  tickClock();
  setInterval(tickClock, 30000);
  setMic(true); // like the phone version, the mic listens while Arrow Os is open
})();
