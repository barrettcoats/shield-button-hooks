# Button Hooks

An Android TV app that takes over one remote button. It stops the button's normal action and sends an HTTP GET for a **single press**, **double press** or **hold**. It's made for Home Assistant webhooks, but any URL works.

It was built for the NVIDIA Shield TV's Netflix button on LineageOS 22.2. Nothing in it is Shield- or Google-specific.

## The Netflix button and LineageOS

The Shield remote's Netflix button is Linux `KEY_VIDEO` (scan code 393) on both systems, but the Android keycode differs: `KEYCODE_BUTTON_12` on NVIDIA's stock OS and `KEYCODE_BUTTON_4` (191) on LineageOS.

LineageOS for TV loads `org.lineageos.lineageparts.atv.KeyHandler`, which runs *before* accessibility services. For each keycode in `lineageos.platform:array/keyhandler_keycodes` (170, 176, 190, 191, 193, 194, 195 and 225), it swallows the **UP** event and launches an app such as Netflix or YouTube. Only DOWN events get through, so for those keys:

- single and double press still work, timed from one DOWN to the next
- **hold isn't possible**, because the app never sees a release

The app reads that array at runtime (`LineageKeyHandler`) and switches to DOWN-only detection automatically. Buttons that aren't on the list, such as the microphone button, get all three gestures.

A fix is drafted in `upstream/` and was tested on a Shield TV Pro 2019: it passes both events through when the key's app isn't installed. Once the app sees an UP from a listed key, it remembers that and turns hold back on for that key.

## How it works

- `ButtonHookService` is an accessibility service with `flagRequestFilterKeyEvents`. It sees every key event before apps do, and returns `true` for the hooked button so the system never acts on it.
- `GestureDetector` turns DOWN/UP into gestures:
  - **hold** fires while the button is still down, after *hold ms* (default 500)
  - **double** fires on the second press, if it comes within *double ms* (default 300) of the first release
  - **single** fires *double ms* after release, or immediately if no double press URL is set
- `HttpSender` runs the GET on a background thread with a 4 s timeout, and logs to logcat under the tag `ButtonHooks`.
- `SettingsActivity` is a D-pad-friendly screen for the three URLs, the timing, learning a new button, and test buttons.

`{gesture}` (`single`/`double`/`hold`) and `{key}` (the Android keycode) in a URL are substituted. That lets one webhook handle everything:

    http://homeassistant.local:8123/api/webhook/shield-netflix?gesture={gesture}

Measured on the Shield remote: a tap is 75–150 ms down, the gap in a double press is about 75 ms, and nothing auto-repeats while the button is held.

## Build and install

Requires JDK 17 and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Permissions: **Internet**, **notifications** (Android 13+ hides toasts from background apps whose notifications are off), and the **accessibility service**. No "display over other apps" is needed, now or for the planned overlay.

Android 13+ treats sideloaded apps as having "restricted settings", and refuses to turn on their accessibility service until you allow it. If you change it before then, the setting just reads back as `null`. With adb:

    adb shell appops set com.barrettcoats.buttonhooks ACCESS_RESTRICTED_SETTINGS allow
    adb install -r app/build/outputs/apk/debug/app-debug.apk   # reinstall so accessibility rescans it
    adb shell pm grant com.barrettcoats.buttonhooks android.permission.POST_NOTIFICATIONS
    adb shell settings put secure enabled_accessibility_services com.barrettcoats.buttonhooks/.ButtonHookService
    adb shell settings put secure accessibility_enabled 1

To log every key the service sees: `adb shell setprop log.tag.ButtonHooks DEBUG`, then `adb logcat -s ButtonHooks`.

## Roadmap

- [x] Hook one button, swallow its default action
- [x] Single / double / hold → GET, with editable endpoints
- [x] Learn a different button from the remote
- [ ] **Overlay menu**: a press opens an on-screen list (an accessibility overlay, `TYPE_ACCESSIBILITY_OVERLAY`, so no extra permission). You pick a device or scene with the D-pad, and it sends that item's request
- [ ] Menu items loaded from Home Assistant (entities or scripts via the REST API with a long-lived token), not just typed in by hand
- [ ] Several hooked buttons, each with its own gestures (for example the microphone button)
- [ ] POST with a JSON body, and custom headers
- [ ] Settings export/import, and an F-Droid release
