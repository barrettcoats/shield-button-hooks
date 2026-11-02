# LineageOS ATV KeyHandler: pass through keys it can't handle

Draft upstream change to `LineageOS/android_packages_apps_LineageParts`, file `src/org/lineageos/lineageparts/atv/KeyHandler.java`. The diff is `atv-keyhandler-pass-unhandled-keys.patch`, and `a/` and `b/` hold the before and after files. It's against `lineage-24.0` as of 2026-11-01.

## What LineageOS does today

1. `PhoneWindowManager.interceptKeyBeforeQueueing()` (frameworks/base, around line 5926 on lineage-22.2) calls `dispatchKeyToKeyHandlers(event)`. If any `DeviceKeyHandler` returns `null`, it returns `0`, so the event is never passed to the user. That's before the accessibility input filter, so accessibility services never see it.
2. On TV builds, `config_deviceKeyHandlerClasses` includes `org.lineageos.lineageparts.atv.KeyHandler`.
3. That handler maps `lineageos.platform:array/keyhandler_keycodes` to `keyhandler_packages`: 170 TV, 176 Settings, 190 YouTube, **191 Netflix**, 193 Disney+, 194 HBO, 195 Play Store, 225 Connect input.
4. `handleKeyEvent()` only looks at **UP** events. For any mapped keycode it calls `launchTarget()` and returns `null`, **whether or not anything was launched**. DOWN is always passed through.

The results:

- **The UP is swallowed even when there's nothing to launch.** On a Shield without Netflix, the Netflix button does nothing and logs `Cannot launch com.netflix.ninja`, yet no app or accessibility service can use it either.
- **Every mapped key arrives as an unpaired DOWN.** Apps get a DOWN with no UP, even when the handler did launch something. A key-remapper can't detect a hold or long press, and anything tracking key state sees the key stuck down.

Unchanged on lineage-23.2 and lineage-24.0. The only recent change, `9b791aa8f1` "Support leanback-only key handler targets" by Bruno Martins on 2026-09-26, touched launching, not consuming.

## The change

- Resolve the target first (`launchTarget` becomes `resolveTarget`, which returns the Intent or null).
- No target means **return the event untouched**, for both DOWN and UP.
- Target found means **consume both DOWN and UP**, launching on UP as before.
- The "Cannot launch" warning becomes a debug log, because a missing target is now a normal case and not an error.

The behaviour when the app *is* installed is unchanged, apart from also consuming the DOWN.

## Draft commit message

    LineageParts: atv: Don't swallow keys with no target

    The ATV KeyHandler consumed the UP event of every mapped key, even
    when the target package or intent wasn't installed, while always
    passing the DOWN through. Apps and accessibility services therefore
    saw an unpaired DOWN for keys that did nothing, e.g. the Netflix
    button on a device without Netflix, and could never react to the
    key's release.

    Only handle a mapped key when its target resolves, and consume
    both DOWN and UP in that case so events stay paired.

The title is 46 chars; lines are 80 or fewer, as their guide asks.

## Tested on device (2026-11-02)

The spare Shield TV Pro 2019 (`mdarcy`) ran an unofficial `lineage-22.2-20261102-UNOFFICIAL-foster` build with the 22.2 version of this patch (`lineage-22.2/KeyHandler.java`). Netflix wasn't installed.

- Netflix button (KEYCODE_BUTTON_4 / 191, scan 393): DOWN and UP **both** reach an accessibility service. Single, double and hold (500 ms) were all detected by Button Hooks.
- Settings key (176, target installed), sent with `input keyevent 176`: still launches TV Settings from `KeyHandler.handleKeyEvent`, and no event leaks through.
- The "Calling a method in the system process without a qualified user" warning from `startActivity` was already there; the original code makes the same call.

Suggested line for the Gerrit description: "Tested on foster (mdarcy), lineage-22.2: the Netflix key without Netflix installed now delivers DOWN/UP to apps; the Settings key still launches Settings."

## Testing caveat

LineageParts is a platform-signed priv-app, so a modified build **can't be installed on an official LineageOS build without root**. Real options:

- Build LineageOS for `foster` from source with the patch (`breakfast foster`, then `m`). That needs about 300 GB of disk and a few hours. It's possible on this machine (1.7 TB free).
- Submit it marked as untested on-device but with a clear explanation, and ask Aaron Kling (the foster maintainer) to verify it. Reviewers generally prefer a change that's been tested.

## Submitting (wiki.lineageos.org/how-to/submitting-patches)

1. Make a Gerrit account at review.lineageos.org. You **log in with GitHub or Google**, sign the Contributor Agreement, and set a username under Settings → HTTP Credentials.
2. Add an SSH key to Gerrit, and check it with `ssh <user>@review.lineageos.org -p 29418`.
3. The name and email in your git commits **are public on Gerrit**. Pick the identity on purpose.
4. Make a single commit on `lineage-24.0` with a `Change-Id` (Gerrit's commit-msg hook adds it). Push it with `repo upload .` from a full checkout, or `git push ssh://<user>@review.lineageos.org:29418/LineageOS/android_packages_apps_LineageParts HEAD:refs/for/lineage-24.0` from a single-repo clone.
5. Their guide wants titles of 50 chars or less and body lines of 80 or less.
