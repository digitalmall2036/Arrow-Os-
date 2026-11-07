// Arrow Os for Windows: the main (Electron) process.
// Opens the Arrow Os window, keeps every website inside it, runs the voice listener
// and saves downloads.
const { app, BrowserWindow, ipcMain, session, shell, Menu } = require('electron');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const PARTITION = 'persist:arrow'; // logins and cookies are kept between runs
let win = null;

// Sites like Google and Facebook refuse sign-in from browsers that announce
// themselves as "Electron", so look like plain Chrome.
app.userAgentFallback = app.userAgentFallback
  .replace(/ Electron\/\S+/, '')
  .replace(/ arrow-os\/\S+/i, '');

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!win) return;
    if (win.isMinimized()) win.restore();
    win.focus();
  });
  app.whenReady().then(start);
}

app.on('window-all-closed', () => {
  voice.stop();
  app.quit();
});

function start() {
  Menu.setApplicationMenu(null);
  setUpWebSession();

  win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 720,
    minHeight: 520,
    backgroundColor: '#0B0F1A',
    title: 'Arrow Os',
    icon: path.join(__dirname, '..', 'assets', process.platform === 'win32' ? 'icon.ico' : 'logo.png'),
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      webviewTag: true,
    },
  });
  win.once('ready-to-show', () => {
    win.maximize();
    win.show();
  });
  win.loadFile(path.join(__dirname, 'index.html'));
  win.on('enter-full-screen', () => send('fullscreen', true));
  win.on('leave-full-screen', () => send('fullscreen', false));
}

function send(channel, ...args) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, ...args);
}

/** The session the apps (YouTube, TikTok, ...) run in. */
function setUpWebSession() {
  const web = session.fromPartition(PARTITION);
  web.setUserAgent(app.userAgentFallback);

  // Let sites play video full screen, use the mic/camera for calls and copy text.
  const allowed = new Set(['fullscreen', 'media', 'clipboard-sanitized-write', 'notifications', 'pointerLock']);
  web.setPermissionRequestHandler((_wc, permission, callback) => callback(allowed.has(permission)));

  // Downloads (for example from 9apps) go straight to the Downloads folder.
  web.on('will-download', (_event, item) => {
    const target = uniquePath(path.join(app.getPath('downloads'), item.getFilename()));
    item.setSavePath(target);
    send('download', { state: 'started', name: path.basename(target) });
    item.once('done', (_e, state) => send('download', { state, name: path.basename(target), path: target }));
  });
}

function uniquePath(p) {
  if (!fs.existsSync(p)) return p;
  const { dir, name, ext } = path.parse(p);
  for (let i = 1; ; i++) {
    const candidate = path.join(dir, `${name} (${i})${ext}`);
    if (!fs.existsSync(candidate)) return candidate;
  }
}

app.on('web-contents-created', (_event, contents) => {
  // Lock down the embedded browser: websites never get Node.js access.
  contents.on('will-attach-webview', (_e, webPreferences) => {
    delete webPreferences.preload;
    webPreferences.nodeIntegration = false;
    webPreferences.contextIsolation = true;
  });

  // Arrow Os shortcuts work wherever the keyboard focus is, including inside a website.
  contents.on('before-input-event', (e, input) => {
    if (input.type !== 'keyDown') return;
    const ctrl = input.control || input.meta;
    let name = null;
    if (ctrl && input.key.toLowerCase() === 'k') name = 'command';
    else if (ctrl && input.key.toLowerCase() === 'm') name = 'mic';
    else if (input.key === 'F11') name = 'fullscreen';
    else if (input.key === 'F5') name = 'reload';
    else if (input.alt && input.key === 'ArrowLeft') name = 'back';
    else if (input.alt && input.key === 'Home') name = 'home';
    else if (input.key === 'Escape' && win && win.isFullScreen()) name = 'escape';
    if (name) {
      e.preventDefault();
      send('shortcut', name);
    }
  });

  if (contents.getType() !== 'webview') return;
  // Keep everything inside Arrow Os. Links that open "in a new tab" open in the same
  // view; real popups (like "Sign in with Google") get a small window of their own.
  contents.setWindowOpenHandler(({ url, disposition }) => {
    if (disposition === 'new-window') {
      return {
        action: 'allow',
        overrideBrowserWindowOptions: {
          width: 520, height: 680, parent: win, autoHideMenuBar: true, backgroundColor: '#ffffff',
          webPreferences: { partition: PARTITION },
        },
      };
    }
    if (/^https?:/i.test(url)) contents.loadURL(url);
    return { action: 'deny' };
  });
  // Links to apps that aren't websites (mailto:, intent:, ...) go to Windows.
  contents.on('will-navigate', (e, url) => {
    if (!/^(https?|about|data|blob):/i.test(url)) {
      e.preventDefault();
      if (/^(mailto|tel):/i.test(url)) shell.openExternal(url);
    }
  });
});

// ---------- talking to the window ----------

const helpers = fs.readFileSync(path.join(__dirname, 'page-actions.js'), 'utf8');
ipcMain.handle('page-helpers', () => helpers);
ipcMain.handle('show-download', (_e, p) => {
  if (typeof p === 'string' && p.startsWith(app.getPath('downloads'))) shell.showItemInFolder(p);
});
ipcMain.handle('open-download', (_e, p) => {
  if (typeof p === 'string' && p.startsWith(app.getPath('downloads'))) return shell.openPath(p);
  return '';
});
ipcMain.handle('toggle-fullscreen', () => {
  if (win) win.setFullScreen(!win.isFullScreen());
});
ipcMain.handle('voice-start', () => voice.start());
ipcMain.handle('voice-stop', () => voice.stop());

// ---------- voice ----------

/**
 * Runs voice.ps1 (Windows' built-in speech recognizer) and forwards what it hears.
 * The script is passed with -EncodedCommand, so it works from inside the packaged app.
 */
const voice = {
  proc: null,
  wanted: false,
  restarts: 0,

  start() {
    this.wanted = true;
    if (this.proc) return 'running';
    if (process.platform !== 'win32') {
      send('voice', { kind: 'error', code: 'not-windows' });
      return 'unavailable';
    }
    const script = fs.readFileSync(path.join(__dirname, 'voice.ps1'), 'utf8');
    const encoded = Buffer.from(script, 'utf16le').toString('base64');
    const proc = spawn('powershell.exe',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', encoded],
      { windowsHide: true });
    this.proc = proc;
    let buffer = '';
    let failed = false;
    proc.stdout.setEncoding('utf8');
    proc.stdout.on('data', (chunk) => {
      buffer += chunk;
      let nl;
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl).trim();
        buffer = buffer.slice(nl + 1);
        if (line.startsWith('PHRASE:')) send('voice', { kind: 'phrase', text: line.slice(7) });
        else if (line.startsWith('READY:')) { this.restarts = 0; send('voice', { kind: 'listening', language: line.slice(6) }); }
        else if (line.startsWith('ERROR:')) { failed = true; send('voice', { kind: 'error', code: line.slice(6) }); }
      }
    });
    proc.on('error', () => {
      failed = true;
      send('voice', { kind: 'error', code: 'no-powershell' });
    });
    proc.on('exit', () => {
      if (this.proc === proc) this.proc = null;
      // Restart after an unexpected exit, but give up if it keeps failing.
      if (this.wanted && !failed && this.restarts++ < 3) setTimeout(() => this.wanted && this.start(), 1500);
      else if (!this.wanted) send('voice', { kind: 'off' });
      else this.wanted = false;
    });
    return 'starting';
  },

  stop() {
    this.wanted = false;
    if (this.proc) {
      try { this.proc.kill(); } catch (e) { /* already gone */ }
      this.proc = null;
    }
    send('voice', { kind: 'off' });
    return 'off';
  },
};

app.on('before-quit', () => voice.stop());
