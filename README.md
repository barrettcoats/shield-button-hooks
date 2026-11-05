# Button Hooks

An Android TV app that takes over remote buttons you choose. It stops a button's normal action, and gives each **single press**, **double press** and **hold** its own action:

- **Webhook**: an HTTP request (made for Home Assistant, but any URL works)
- **Navigation**: Back, Home, Recent apps, Notifications, Quick settings, D-pad (D-pad needs Android 13+)
- **Media key**: play/pause, play, pause, stop, next, previous, fast-forward, rewind
- **Open app**: any installed app
- **TV remote (IR)**: fire a code already stored on the Shield Remote (2019): TV input, power toggle/on/off, mute. Codes the remote doesn't have stored fail with "remote error 2048"
- **Nothing**: just disable the button

A new install starts with no buttons; use **Add button** and press the one you want.

Android doesn't let apps inject arbitrary key presses, so the button can't be remapped to letters, F-keys or a key for one specific app. The list above is what an accessibility service can do.

It was built for the NVIDIA Shield TV's Netflix button on LineageOS 22.2. Nothing in it is Shield- or Google-specific.

## The Netflix button and LineageOS

The Shield remote's Netflix button is Linux `KEY_VIDEO` (scan code 393) on both systems, but the Android keycode differs: `KEYCODE_BUTTON_12` on NVIDIA's stock OS and `KEYCODE_BUTTON_4` (191) on LineageOS.

LineageOS for TV loads `org.lineageos.lineageparts.atv.KeyHandler`, which runs *before* accessibility services. For each keycode in `lineageos.platform:array/keyhandler_keycodes` (170, 176, 190, 191, 193, 194, 195 and 225), it swallows the **UP** event and launches an app such as Netflix or YouTube. Only DOWN events get through, so for those keys:

- single and double press still work, timed from one DOWN to the next
- **hold isn't possible**, because the app never sees a release

The app reads that array at runtime (`LineageKeyHandler`) and switches to DOWN-only detection automatically. Buttons that aren't on the list, such as the microphone button, get all three gestures.

A fix is in review at LineageOS as [change 511786](https://review.lineageos.org/c/LineageOS/android_packages_apps_LineageParts/+/511786), tested on a Shield TV Pro 2019: it passes both events through when the key's app isn't installed. On builds with that fix, Button Hooks notices the first UP from a listed key, remembers it, and turns hold back on for that key.

## TV power over the remote's IR (Shield Remote 2019)

On NVIDIA's firmware, the Shield turns the TV on and off by IR: when the Shield sleeps or wakes, it tells the remote over Bluetooth to fire the TV power code stored on the remote. LineageOS has nothing that does that. With **TV power: follows the Shield** turned on, Button Hooks does it instead. When you put the Shield to sleep with the power button, it fires the remote's stored **power off** code, and when the Shield wakes it fires **power on**, three times each like NVIDIA's firmware. Repeating a discrete code is harmless, and it covers a missed IR flash. For remotes that only have a **toggle** stored, a second setting fires that once instead.

Requirements and limits:

- **The 2019 Shield Remote**. It's found by its name (anything containing "SHIELD Remote"). Failing that, up to four other paired Bluetooth LE devices are checked for NVIDIA's IR service, and whichever has it is remembered. The 2017 remote uses a different link and isn't supported.
- **The power code has to be on the remote already.** Set it up on NVIDIA's firmware **before** flashing LineageOS: Settings → Display & Sound → Power control (and Volume control → IR). The codes are stored on the remote, so they survive the flash and re-pairing. Writing new codes from LineageOS isn't implemented yet (the protocol is worked out in `shield-remote-ir/NOTES.md`).
- With the toggle setting, if the TV and Shield get out of step, fix it once with the TV's own button. The on/off codes can't get out of step.
- **Several remotes**: only one may fire, because two would toggle the TV twice. By default it uses the first connected remote. With two paired, use **Fires from** to pick the one kept with this TV, identified by the end of its Bluetooth address (Settings → Remotes & accessories shows it). Android doesn't tell apps which Bluetooth remote sent a key (the hidden `InputDevice.getBluetoothAddress()` is blocked for apps), so it can't follow whichever remote you last pressed.
- **Only the power button turns the TV off.** If the Shield falls asleep on its sleep timer, or HDMI-CEC puts it to sleep because a console or other device took over the TV, the TV is left alone, so it can't go dark in the middle of a game or film on another input. Button Hooks tells these apart by Android's recorded sleep reason (`mLastSleepReason` in `dumpsys power`), which needs the DUMP permission. Without that grant it never turns the TV off, and only the wake side works; the settings screen greys out **TV off: only with the power button** and shows the command. If the Shield is the TV's only input, you can pick **TV off: whenever the Shield sleeps** instead, which needs no grant, but on a TV shared with a console it can switch the TV off mid-game. Waking always fires **power on**. That's harmless if the TV is already on, and it's useful where CEC stops waking a TV that has been off for a while (for example, an LG in deep standby).
- Volume isn't involved. If the remote already sends IR volume, lock the Shield's HDMI output at full volume so Bluetooth volume doesn't stack on top. See *Volume on LineageOS* below.

Setup on a new Shield, after the steps under *Build and install*:

    adb shell pm grant com.barrettcoats.buttonhooks android.permission.BLUETOOTH_CONNECT
    adb shell pm grant com.barrettcoats.buttonhooks android.permission.DUMP

The DUMP grant goes away if the app is uninstalled, but survives updates.

Then open Button Hooks, set **TV power** to *follows the Shield (on/off codes)*, and try **Test: TV off, then on**. If the TV only turns off and never comes back on, the remote has only a toggle stored: switch to *(toggle code)*.

How it talks to the remote: a vendor GATT service `00001000-b89b-11e5-9912-ba0be0483c18`. 18-byte frames `[command, transactionId, slot, offset, data…]` are written to `…2001`, and the replies arrive as notifications on `…2000`. Command 44 is *emit*, with data[0] = slot (0 = power off, 1 = power toggle, 5 = power on, 2/3 = volume up/down, 11 = mute). See `RemoteIr.kt`.

## Volume on LineageOS

If the remote's volume buttons were set to IR on NVIDIA's firmware, each press is sent **twice** on LineageOS: as IR straight to the TV, and as a Bluetooth `KEY_VOLUMEUP`/`KEY_VOLUMEDOWN` to the Shield. Android then changes its own volume on top of the TV's, and shows its volume slider. On a TV without working HDMI-CEC, the two volumes stack.

The fix is to lock the Shield's HDMI output at full volume, so only the TV's volume changes:

    adb shell settings put system AudioService_DeviceVolumeBehavior_hdmi 1
    adb reboot

- **It only takes effect after a reboot.** The setting is read when the audio service starts, so until you reboot, `settings get` shows `1` but nothing changes.
- To check it's active, look for `0x400` (HDMI) in `adb shell dumpsys audio | grep mFullVolumeDevices`. Before the reboot that list might show only `0x40000,0x40001` (HDMI ARC/eARC).
- To undo: `adb shell settings delete system AudioService_DeviceVolumeBehavior_hdmi`, then reboot.
- Android's volume slider still pops up on each press, but it no longer changes how loud anything is.

I haven't found a switch for this in LineageOS's TV Settings, and Button Hooks can't set it for you: as far as I know, changing a device's volume behavior (`AudioManager.setDeviceVolumeBehavior`) needs a permission only system apps get, so for now it's done over adb.

**Volume keys can't be hooked.** Android handles them before accessibility services see them: on LineageOS 22.2 (Shield TV 2017, 2019 remote) the service logged every D-pad and OK press but never a volume key, so **Add button** doesn't react to them either. The remote's IR still controls the TV. The remote has no mute button.

## How it works

- `ButtonHookService` is an accessibility service with `flagRequestFilterKeyEvents`. It sees every key event before apps do, and returns `true` for the hooked button so the system never acts on it.
- `GestureDetector` turns DOWN/UP into gestures:
  - **hold** fires while the button is still down, after *hold ms* (default 500)
  - **double** fires on the second press, if it comes within *double ms* (default 300) of the first release
  - **single** fires *double ms* after release, or immediately if no double press URL is set
- `LocalActions` runs the non-webhook actions: `performGlobalAction` for navigation, `AudioManager.dispatchMediaKeyEvent` for media keys (they go to the app that last played media), the app's TV launch intent for Open app, and `RemoteIr` (the same Bluetooth emit as TV power) for TV remote codes.
- `HttpSender` sends the request on a background thread and logs the method, URL and result (never headers) to logcat under the tag `ButtonHooks`.
- `SettingsActivity` is a D-pad-friendly screen for the three URLs, the timing, learning a new button, and test buttons.

`{gesture}` (`single`/`double`/`hold`) and `{key}` (the Android keycode) in a URL or body are substituted. That lets one webhook handle everything:

    http://homeassistant.local:8123/api/webhook/shield-netflix?gesture={gesture}

Each gesture also has a **method** (GET, POST, PUT, DELETE) and an optional **body**. A body starting with `{` or `[` is sent as JSON unless you set a `Content-Type` header. Each button has a **headers** box, one `Name: value` per line, sent with all of its gestures. For example, to run a Home Assistant script through the REST API with a long-lived access token:

    URL:     http://homeassistant.local:8123/api/services/script/turn_on
    Method:  POST
    Body:    {"entity_id": "script.movie_time"}
    Headers: Authorization: Bearer <long-lived token>

Headers are never written to the log. The request timeout (default 4 s) is under *Timing*.

Measured on the Shield remote: a tap is 75–150 ms down, the gap in a double press is about 75 ms, and nothing auto-repeats while the button is held.

## Install

Download the APK from [Releases](https://github.com/barrettcoats/shield-button-hooks/releases) and sideload it, for example with `adb install button-hooks-0.3.3.apk`. Then follow the permission steps below.

Releases are signed with this certificate (SHA-256). Check it with `apksigner verify --print-certs`:

    A5:D5:D0:C0:67:07:60:69:15:2F:BE:BF:30:88:EC:33:03:80:B1:2D:2F:9E:8A:10:EE:E8:AC:69:BC:CA:A3:40

A build you make yourself is signed with your own key, so Android won't install it over a release (or the other way round) without uninstalling first, which loses the app's settings.

## Build and install

Requires JDK 17 and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/button-hooks-0.3.3-debug.apk

`./gradlew assembleRelease` signs the APK if `~/.config/button-hooks/keystore.properties` exists (or the file named by `BUTTON_HOOKS_SIGNING`), with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Otherwise it writes an unsigned APK.

Permissions: **Internet**, **notifications** (Android 13+ hides toasts from background apps whose notifications are off), **Bluetooth connect** (only for TV power), and the **accessibility service**. No "display over other apps" is needed, now or for the planned overlay.

Android 13+ treats sideloaded apps as having "restricted settings", and refuses to turn on their accessibility service until you allow it. If you change it before then, the setting just reads back as `null`. With adb:

    adb shell appops set com.barrettcoats.buttonhooks ACCESS_RESTRICTED_SETTINGS allow
    adb install -r app/build/outputs/apk/debug/button-hooks-0.3.3-debug.apk   # reinstall so accessibility rescans it
    adb shell pm grant com.barrettcoats.buttonhooks android.permission.POST_NOTIFICATIONS
    adb shell settings put secure enabled_accessibility_services com.barrettcoats.buttonhooks/.ButtonHookService
    adb shell settings put secure accessibility_enabled 1

To log every key the service sees: `adb shell setprop log.tag.ButtonHooks DEBUG`, then `adb logcat -s ButtonHooks`.

## Roadmap

- [x] Hook one button, swallow its default action
- [x] Single / double / hold → HTTP request, with editable endpoints
- [x] Learn a different button from the remote
- [x] Remap: navigation, media keys, open app, or disable
- [ ] **Overlay menu**: a press opens an on-screen list (an accessibility overlay, `TYPE_ACCESSIBILITY_OVERLAY`, so no extra permission). You pick a device or scene with the D-pad, and it sends that item's request
- [ ] Menu items loaded from Home Assistant (entities or scripts via the REST API with a long-lived token), not just typed in by hand
- [x] Several hooked buttons, each with its own gestures (for example the microphone button)
- [x] TV power over the Shield remote's IR on LineageOS
- [ ] Write IR codes to the remote (for remotes never set up on NVIDIA's firmware)
- [x] POST/PUT/DELETE with a body, and custom headers
- [ ] Settings export/import, and an F-Droid release

## Status

I built this for my own Shield and TV, and I'm sharing it as-is. Issues and pull requests are welcome, but there's no promise of support or fixes.

This was built with help from an AI assistant (Claude), which traced the LineageOS key handling, worked out the remote's IR protocol, and drafted most of the code. I reviewed it and tested everything on my own hardware.

NVIDIA and SHIELD are trademarks of NVIDIA Corporation. This project isn't affiliated with or endorsed by NVIDIA, and it contains no NVIDIA code. The remote's protocol was worked out for interoperability, and the TV codes used are LG's public NEC codes.

## License

MIT, see `LICENSE`.
