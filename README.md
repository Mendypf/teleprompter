# Teleprompter

Open source teleprompter for Android.

The app (it shows up on the phone as Prompter) puts a small band with your script on top of the Pixel Camera. When you double tap the band, it listens through the mic and moves the text along as you speak. If you repeat a word, pause, or say "um", the text waits for you. If you skip a line, it catches up.

The band starts at the top of the screen, near the front camera lens, so your eyes stay close to it. Words you have said turn dim and the next word is the brightest. Swipe the words to go back or ahead, drag the handle under them or either side of the band to move it, and pinch it side to side or up and down to change its width or height. The text size is set in the app and never changes the band's size, so bigger words show fewer lines and smaller words show more. It comes in three looks: Dark glow, Glass and Clear glass.

## Why it is an Accessibility app

Android only lets an accessibility app share the mic with a camera that is recording. A normal app loses the mic the moment the camera starts a video.

The app uses the Accessibility service to see which app is in front, so the band shows only over the camera. It does not read the screen (`canRetrieveWindowContent` is false), and it does not record or store which apps you open. It is declared as not an assistive tool.

## Privacy

- The only permission is `RECORD_AUDIO`. There is no internet permission, so the app cannot send anything over the network.
- Your script is a text file in the app's private storage. Backup is turned off.
- Speech is recognized on the device, by the phone's built-in speech recognizer. No audio is saved.
- The mic opens only when the prompter is switched on in the app, the camera app is in front, and you double tap the band. It closes when you double tap again, leave the camera, turn the screen off, reach the end of the script, or switch the prompter off.
- The band does not show on the lock screen.
- A small log file in the app's private storage records events and counts, for example "at word 12 of 80". It never holds your script or your words.
- A few test broadcasts for adb live in `AdbReceiver`. One of them can start listening from a computer. They require the `DUMP` permission, which only adb and the system hold, so other apps cannot send them.
- The debug build below is debuggable, so a computer with adb access to the phone can read the app's files. Build a release with your own signing key if that matters to you.

## Requirements

- Android 13 or newer.
- On-device speech recognition with English installed. Pixel phones have it.
- To build: JDK 17 or newer, and the Android SDK with platform 37.

## Build and install

```
git clone https://github.com/Mendypf/teleprompter
cd teleprompter
./gradlew :core:test          # tests for the matching engine
./gradlew :app:assembleDebug  # builds the app
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `gradlew.bat`. If Gradle cannot find the Android SDK, set `ANDROID_HOME` or put `sdk.dir=/path/to/Sdk` in a `local.properties` file.

## Setup

1. Open Prompter and tap **Allow the mic**.
2. Tap **Open Accessibility settings**, find Prompter in the list, and turn it on.
3. If the switch is grey, Android is blocking an app installed outside the Play Store. Open App info for Prompter, tap the three dots at the top right, tap **Allow restricted settings**, and try the switch again.

## Use

1. Paste your script in the app and turn the prompter on. It stays off until you do.
2. Open the Pixel Camera. The band appears at the top.
3. Double tap the band and read. Double tap again when you are done.
4. Swipe the words to go back or ahead, and reading picks up from the line at the top. Tap a word to jump there. Drag the handle under the words, or either side of the band, to move it. Pinch it side to side to make it wider or narrower, or up and down to make it taller or shorter.

## Project layout

- `core/` is the matching engine in plain Kotlin, with tests. It turns heard words into a position in the script.
- `app/` is the Android app: the Accessibility service, the band, the mic and speech recognizer, and the settings screen.
- `core/tools/` builds the matcher's test cases from a reference version of the rules in JavaScript (needs Node).
- `tools/sound_check.py` compares the sound of two videos, to check that the prompter does not change a recording (needs ffmpeg).

## License

MIT. See [LICENSE](LICENSE).
