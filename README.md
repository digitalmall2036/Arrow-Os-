# Arrow Os

Arrow Os is an Android app that works like an **OS inside your phone**. It has its
own home screen with its own apps, and you control it with your **voice**.

Apps inside Arrow Os (v1):

| App | Opens |
| --- | --- |
| YouTube | m.youtube.com |
| Facebook | m.facebook.com |
| Instagram | instagram.com |
| TikTok | tiktok.com |
| 9apps | 9apps.com |

These are the apps' **websites** (their mobile sites), not the installed apps. Every app opens **inside Arrow Os** in its built-in browser, so you never get thrown
out to another app. Links that try to jump to the real YouTube/Facebook/etc. app are
kept inside Arrow Os.

## Auto Designer: Arrow Business Os

The first time Arrow Os opens, a welcome bar says **"Welcome to Arrow Business Os.
What is your professional / business category?"**

1. Type your business or profession (for example *Photographer*, *Restaurant*,
   *Lawyer*, *poultry farming*), or tap one of the examples, then tap **Find apps**.
   The Auto Designer lists the apps and websites people in that line of work use,
   plus a few that help any business.
2. Tick the ones you want and tap **Add to my home screen**. Those become your home
   screen. Tapping one opens it straight away, inside Arrow Os.

Tap **Change my business or apps** on the home screen to run it again. You can also
say "tap open" and the name of any app on your home screen, for example
"tap open Canva". YouTube, Facebook, Instagram, TikTok and 9apps keep working by
voice even when they are not on the home screen.

The catalog covers 24 business categories and about 120 apps and websites. It lives
in `AutoDesigner.kt`; if nothing matches what was typed, Arrow Os offers general
business tools and a web search for more.

## Arrow Files

**Arrow Files** is the file manager of Arrow Os. It is always the first app on the home
screen (and the folder button at the top of every app), whatever business you picked.

- Every file you download from a website inside Arrow Os lands in Arrow Files, in your
  own folder: `ArrowFiles/<user>/websites/<site>/<file>` inside Arrow Os's storage.
  Until email sign-in arrives, the user is `guest`.
- Filter by type (Videos, Images, Documents, Audio, Other) or by the site it came from.
- Tap a file to open it. The ⋮ button (or a long press) gives **Open**, **Share**,
  **Save to phone** (copies it to the phone's `Downloads/ArrowOs` folder) and
  **Move to Trash**. Trash keeps files for 30 days.
- Say "tap open Arrow Files", "tap my files" or "tap downloads".

Coming next: email sign-in and a cloud copy of every file (Firebase), uploaded on
Wi-Fi or mobile data, so files follow you to a new phone.

## Install the APK (from your phone)

**Easiest: the "latest" release**

1. Open this repo on GitHub in your phone's browser.
2. Tap **Releases** → **Arrow Os (latest build)**.
3. Tap **ArrowOs.apk** to download it.
4. Open the downloaded file. If Android asks, allow **Install unknown apps** for
   your browser, then tap **Install**.

The release is updated every time code lands on `main`.

**From GitHub Actions (any branch or pull request)**

1. Open the repo's **Actions** tab and tap the latest green **Build APK** run.
2. Scroll to **Artifacts** and tap **ArrowOs-apk** (you must be signed in to GitHub).
3. That downloads a `.zip`. Open it with your Files app, extract `ArrowOs.apk`,
   and tap it to install.

When updating, install the new APK over the old one. Your app logins are kept.

## Giving commands: "tap" and "search"

Arrow Os understands two command words. Every command starts with one of them;
anything else (like just saying "Jeff Bezos") is ignored and does nothing.

- **search** finds things: `search Tom and Jerry`, `search Jeff Bezos`,
  `search cats on TikTok`. Inside one of the five apps it searches that app; from the
  home screen or a website it searches the web (Google).
- **Websites:** `search Claude code website` opens that site directly (the top
  result), `search claude.ai` or `tap open bbc.co.uk` opens the address. Saying
  "dot" works too: `search claude dot ai`.
- **tap** does anything on screen:

| Command | What happens |
| --- | --- |
| tap open YouTube / tap TikTok | Opens that app |
| tap home / tap back / tap refresh | Navigation |
| tap previous | Opens **Previous**: every tab ever opened in Arrow Os |
| tap like this video / tap the like icon | Taps the like button |
| tap comment / tap share / tap follow / tap subscribe / tap save / tap skip ad | Taps that button |
| tap play the second video on this page | Opens and plays the 2nd video |
| tap scroll to the second video and play it | Scrolls to the 2nd video, then plays it |
| tap next video / tap previous video | Next or previous video in a feed |
| tap scroll down / tap scroll up / tap scroll to the top | Scrolls the page |
| tap play / tap pause / tap mute / tap unmute | Controls the video on screen |
| tap Shorts / tap Following | Taps any button or link by its name |
| tap type happy birthday and send | Types into the page's text box and sends it |
| tap help | Shows all of this in the app |
| tap stop listening | Turns the mic off (tap the mic button to turn it back on) |

**How "tap" finds things (its eyes):** before tapping, Arrow Os scans the whole page:
every button, link and icon, with its text, accessibility label, tooltip, icon name
and internal name. It picks the best match for your words, also trying other names
sites use (for example "next" also matches "›", "Continue" or "Go to next video"),
and allows one wrong letter. It outlines the chosen spot in blue for a moment, then
taps it. `tap next` and `tap previous video` press the page's own Next/Previous
button; on feeds without one (TikTok, Shorts) they move to the next or previous video.

**Previous (tab history):** Arrow Os remembers every page you open, in every app and
website, even after you close it. Open the list with `tap previous` (or `tap history`),
the **Previous** button on the home screen, or the clock icon at the top of any page.
The newest tab is at the top. Tap any tab, or say `tap 2` / `tap the second one` /
`tap Tom and Jerry`, and Arrow Os goes straight back to that page, inside its app.
`tap back` closes the list, and `tap clear previous` (or the Clear button) empties it
after asking first.

Join commands with "then": `tap open TikTok then search cats`.

You can **say** these or **type** them in the box at the bottom (press Go or the
send arrow). The mic is always listening while Arrow Os is open, and the mic button
turns it on or off. "Hey Arrow" and "please" are ignored, and "tab" is understood as
"tap" because speech recognition often mishears it.

**Notes**

- Voice uses Android's built-in speech recognition (from the Google app). If the
  bar says voice isn't available, install or update the Google app.
- Listening stops when you leave Arrow Os and restarts when you come back.
- If a site can't be reached, Arrow Os tries its other addresses (Facebook has
  several: m., web., mbasic., touch., www.) and then explains what went wrong, with a
  Try again button. If every Facebook address is refused, the network itself is
  blocking Facebook; check whether facebook.com opens in Chrome.
- Sites can ask for photos or files (for posting), and downloads (for example from
  9apps) go to Android's download manager; tap the notification to open them.
- Instagram search needs a login, so "search X on Instagram" opens the `#X` hashtag page.

## For developers

Standard Android project: Kotlin, Gradle (wrapper included), minSdk 24, targetSdk 34.

```
app/src/main/java/com/arrowos/app/
  MainActivity.kt     home screen, in-app browser, runs voice commands
  VoiceController.kt  always-listening loop around Android's SpeechRecognizer
  CommandParser.kt    the "tap" / "search" command language (pure Kotlin, unit tested)
  WebActions.kt       JavaScript helpers that act inside the page (tap, scroll, play, type)
  ArrowApp.kt         the five apps: names, URLs, search URLs, spoken aliases
  AutoDesigner.kt     business categories, the apps for each, and the matcher (unit tested)
```

Build locally with `./gradlew assembleDebug`; run tests with `./gradlew testDebugUnitTest`.
CI (`.github/workflows/build-apk.yml`) runs the tests and builds the APK on every push
and pull request.

To add an app, add an entry to `ArrowApp` with its URL, search URL, and spoken names.
It works in voice commands automatically. To offer it in the Auto Designer, add it to
`AutoDesigner.tools` and to the categories it fits.

To add a business category or tool to the Auto Designer, edit `AutoDesigner.kt`: tools
have a fixed `id` (saved on the phone, so never rename one), and categories list the
words people use for that work plus the tool ids to suggest.
