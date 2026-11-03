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

## TV power over the remote's IR (Shield Remote 2019)

On NVIDIA's firmware, the Shield turns the TV on and off by IR: when the Shield sleeps or wakes, it tells the remote over Bluetooth to fire the TV power code stored on the remote. LineageOS has nothing that does that. With **TV power: follows the Shield** turned on, Button Hooks does it instead. When the Shield sleeps it fires the remote's stored **power off** code, and when it wakes it fires **power on**, three times each like NVIDIA's firmware. Repeating a discrete code is harmless, and it covers a missed IR flash. For remotes that only have a **toggle** stored, a second setting fires that once instead.

Requirements and limits:

- **The 2019 Shield Remote** (Bluetooth name `NVIDIA SHIELD Remote`). The 2017 remote uses a different link and isn't supported.
- **The power code has to be on the remote already.** Set it up on NVIDIA's firmware **before** flashing LineageOS: Settings → Display & Sound → Power control (and Volume control → IR). The codes are stored on the remote, so they survive the flash and re-pairing. Writing new codes from LineageOS isn't implemented yet (the protocol is worked out in `shield-remote-ir/NOTES.md`).
- With the toggle setting, if the TV and Shield get out of step, fix it once with the TV's own button. The on/off codes can't get out of step.
- **Several remotes**: only one may fire, because two would toggle the TV twice. By default it uses the first connected remote. With two paired, use **Fires from** to pick the one kept with this TV, identified by the end of its Bluetooth address (Settings → Remotes & accessories shows it). Android doesn't tell apps which Bluetooth remote sent a key (the hidden `InputDevice.getBluetoothAddress()` is blocked for apps), so it can't follow whichever remote you last pressed.
- Volume isn't involved. If the remote already sends IR volume, lock the Shield's HDMI output at full volume so Bluetooth volume doesn't stack on top: `adb shell settings put system AudioService_DeviceVolumeBehavior_hdmi 1`, then reboot.

Setup on a new Shield, after the steps under *Build and install*:

    adb shell pm grant com.barrettcoats.buttonhooks android.permission.BLUETOOTH_CONNECT

Then open Button Hooks, set **TV power** to *follows the Shield (on/off codes)*, and try **Test: TV off, then on**. If the TV only turns off and never comes back on, the remote has only a toggle stored: switch to *(toggle code)*.

How it talks to the remote: a vendor GATT service `00001000-b89b-11e5-9912-ba0be0483c18`. 18-byte frames `[command, transactionId, slot, offset, data…]` are written to `…2001`, and the replies arrive as notifications on `…2000`. Command 44 is *emit*, with data[0] = slot (0 = power off, 1 = power toggle, 5 = power on, 2/3 = volume up/down, 11 = mute). See `RemoteIr.kt`.

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

Permissions: **Internet**, **notifications** (Android 13+ hides toasts from background apps whose notifications are off), **Bluetooth connect** (only for TV power), and the **accessibility service**. No "display over other apps" is needed, now or for the planned overlay.

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
