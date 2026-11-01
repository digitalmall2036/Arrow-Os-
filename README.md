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

## Using voice commands

When Arrow Os is open it is **always listening**. The bar at the bottom shows what it
hears. The **mic button** turns listening on or off. The first time, allow the
microphone permission.

Arrow Os shows (and says out loud) what it understood.

| Say | What happens |
| --- | --- |
| "open YouTube" / "launch TikTok" / just "Instagram" | Opens that app |
| "search cats on TikTok" | Searches TikTok for "cats" |
| "play music on YouTube" | Searches YouTube for "music" |
| "search Facebook for news" | Searches Facebook for "news" |
| "search cats" | Searches the app you're in (YouTube from the home screen) |
| "go back" | Goes back one page (or home, from an app's first page) |
| "go home" | Returns to the Arrow Os home screen |
| "scroll down" / "scroll up" | Scrolls the page |
| "refresh" | Reloads the page |
| "stop listening" | Turns voice off (tap the mic to turn it back on) |

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
