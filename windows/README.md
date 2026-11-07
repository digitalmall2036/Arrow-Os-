# Arrow Os for Windows

Arrow Os for Windows is the laptop version of Arrow Os: an **OS inside your laptop**
with its own home screen and apps, controlled by your **voice** or by typing.

Apps inside Arrow Os: **YouTube, Facebook, Instagram, TikTok and 9apps**. They open as
their websites inside Arrow Os's own window, so you never get thrown out to another
browser. Your logins are remembered between runs.

## Get the .exe

**From the Releases page (after this is merged into `main`)**

1. Open this repo on GitHub → **Releases** → **Arrow Os for Windows (latest build)**.
2. Download one of:
   - **ArrowOs-Setup.exe**: installs Arrow Os with a desktop and Start menu shortcut.
   - **ArrowOs-Portable.exe**: runs straight away, nothing installed.

**From GitHub Actions (any branch or pull request)**

1. Open the repo's **Actions** tab → the latest green **Build Windows app** run.
2. Under **Artifacts**, download **ArrowOs-windows** (sign in to GitHub first).
3. Unzip it to get both .exe files.

**First run:** the app isn't code-signed yet, so Windows SmartScreen may say
"Windows protected your PC". Click **More info → Run anyway**. This only happens once.

## Commands: "tap" and "search"

Exactly the same command language as the phone version. Every command starts with
**tap** or **search**; anything else is ignored.

- `search Tom and Jerry` · `search cats on TikTok` (inside an app it searches that app)
- `search Claude code website` · `search claude.ai` · `tap open bbc.com`
- `tap open YouTube` · `tap home` · `tap back` · `tap refresh`
- `tap like this video` · `tap comment` · `tap share` · `tap follow` · `tap subscribe` · `tap skip ad`
- `tap play the second video` · `tap scroll to the second video and play it`
- `tap next video` · `tap previous video` · `tap scroll down` · `tap scroll to the top`
- `tap play` · `tap pause` · `tap mute` · `tap unmute` · `tap full screen` · `tap zoom in`
- `tap Shorts` · `tap Following` (any button or link by its name)
- `tap type happy birthday and send`
- Chain with "then": `tap open TikTok then search cats`
- `tap help` shows all of them; `tap stop listening` turns the mic off.

Type commands in the box at the bottom and press Enter. When typing you can leave
out "tap" for app and page actions (`open youtube`, `scroll down`).

**Keyboard:** Ctrl+K command box · Ctrl+M mic on/off · F11 full screen ·
Alt+Left back · Alt+Home home · F5 reload.

## Voice

Arrow Os uses **Windows' built-in speech recognition**, which works offline and needs
no account. The mic starts listening when Arrow Os opens; click the mic button to turn
it on or off.

If voice doesn't work, the status line under the command box says why. The usual fixes:

- **Microphone access:** Settings → Privacy & security → Microphone → turn on
  "Let desktop apps access your microphone".
- **Speech language:** Settings → Time & language → Speech (or Language & region) →
  make sure an English speech pack is installed.

## For developers

Electron app, in this `windows/` folder.

```
src/main.js         window, keeps websites inside the app, downloads, voice process
src/voice.ps1       voice listener (Windows System.Speech), run by main.js
src/renderer.js     home screen, browser, runs commands
src/commands.js     "tap" / "search" command language (port of the Android CommandParser.kt)
src/page-actions.js helpers injected into websites: tap by name, scroll, video, typing
test/               unit tests for the command language (same cases as the Android tests)
assets/             the arrow logo and the Windows icon
```

```
cd windows
npm install
npm start        # run the app
npm test         # run the tests
npm run dist     # build dist/ArrowOs-Setup.exe and dist/ArrowOs-Portable.exe (on Windows)
```

CI (`.github/workflows/build-windows.yml`) runs the tests and builds both .exe files on
Windows for every push and pull request that touches `windows/`.
