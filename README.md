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

Every app opens **inside Arrow Os** in its built-in browser, so you never get thrown
out to another app. Links that try to jump to the real YouTube/Facebook/etc. app are
kept inside Arrow Os.

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

## Giving commands: speak or type

When Arrow Os is open it is **always listening**. The bar at the bottom shows what it
hears. The **mic button** turns listening on or off. The first time, allow the
microphone permission.

Prefer typing? Tap the **Type a command…** box at the bottom, type the same thing
you would say (for example `search cats on TikTok`), and press **Go** on the keyboard or
the send arrow. Typed commands work exactly like spoken ones. The mic pauses while
you type.

Arrow Os shows (and says out loud) what it understood.

Commands work both outside and **inside** the apps:

| Say or type | What happens |
| --- | --- |
| "open YouTube" / "launch TikTok" / just "Instagram" | Opens that app |
| "search cats on TikTok" / "play music on YouTube" | Searches that app |
| "search cats" | Searches the app you're in (YouTube from the home screen) |
| "next video" / "scroll to the next video" / "swipe up" / "skip" | Next video or post in the feed |
| "previous video" / "swipe down" | Previous video or post |
| "scroll down" / "scroll up" / "go to the top" | Scrolls the page |
| "play" / "pause" / "mute" / "unmute" | Controls the video on screen |
| "like" / "comment" / "share" / "follow" / "subscribe" / "save" / "skip ad" | Taps that button on the page |
| "tap Shorts" / "click Following" / "press next" | Taps any button or link by its name |
| "open the first video" / "play the second one" | Opens that item on the page |
| "type happy birthday and send" | Types into the text box on the page (and sends it) |
| "open TikTok then next video" | Runs several commands in a row |
| "go back" / "go forward" / "go home" / "refresh" | Navigation |
| "help" | Shows the list of commands |
| "stop listening" | Turns voice off (tap the mic to turn it back on) |

If you **type** something Arrow Os doesn't recognize, it searches for it in the app
you're in, so typed text always does something. Unrecognized **speech** is ignored
(with a "didn't understand" note), because the always-on mic also hears
background talk.

Words like "hey Arrow", "please", and "can you" are ignored, so
"Hey Arrow, please open YouTube" works too. "Nine apps", "9 apps" and "9apps" all
mean 9apps; "tik tok" and "tiktok" both mean TikTok.

**Notes**

- Voice uses Android's built-in speech recognition (from the Google app). If the
  bar says voice isn't available, install or update the Google app.
- Listening stops when you leave Arrow Os and restarts when you come back.
- Instagram search needs a login, so "search X on Instagram" opens the `#X` hashtag page.

## For developers

Standard Android project: Kotlin, Gradle (wrapper included), minSdk 24, targetSdk 34.

```
app/src/main/java/com/arrowos/app/
  MainActivity.kt     home screen, in-app browser, runs voice commands
  VoiceController.kt  always-listening loop around Android's SpeechRecognizer
  CommandParser.kt    turns spoken text into a Command (pure Kotlin, unit tested)
  ArrowApp.kt         the five apps: names, URLs, search URLs, spoken aliases
```

Build locally with `./gradlew assembleDebug`; run tests with `./gradlew testDebugUnitTest`.
CI (`.github/workflows/build-apk.yml`) runs the tests and builds the APK on every push
and pull request.

To add an app, add an entry to `ArrowApp` with its URL, search URL, and spoken names.
It shows up on the home screen and in voice commands automatically.
