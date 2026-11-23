# Jester for Android

A floating jester that lives over your other apps: a vector mascot with its own synthesised sounds and voice, a chat
sheet, reminders and Done / Snooze notifications. It works fully on its own. A hub is optional: connect one to sync
tasks, reminders and AI chat from your own server (API in `docs/HUB_API.md`).

Pixel-art bot packs (the format is shared with the Mac app) can replace the vector jester: the bundled `pixel` pack
is in `app/src/main/assets/bots/pixel/`.

## What it does

| Feature | Where |
|---|---|
| Floating jester over all apps, drag + snap to edge; reacts to state (idle, thinking, working, done, error, remind, sleep) | `overlay/`, `jester/` |
| Speech bubble with ringing reminders; Jester hops until you press Done or Snooze | `overlay/Overlay.kt` |
| Tap: chat sheet with the text field focused (any dictation keyboard works). Long-press: menu, hide, voice | `ui/SheetActivity.kt` |
| Synth sound effects, babble and text-to-speech in the device language | `jester/JesterAudio.kt`, `voice/` |
| Outfits (themes), optional auto outfit by time of day, quiet hours for voice | `logic/Looks.kt` |
| Share to Jester: text becomes an item, images go through on-device text recognition (ML Kit, bundled model) | `ui/ShareActivity.kt` |
| Optional hub: tasks, ringing reminders, chat, bot packs over HTTPS | `Hub.kt` |
| Optional push: ntfy JSON stream (`<server>/<topic>/json`), reconnect with backoff | `push/` |
| Own notifications with Done / Snooze 15m, high importance so a watch mirroring app forwards them | `notify/` |
| Offline backup: exact alarms for the next 24 h of cached reminders, never double-shown | `reminders/` |
| Offline write queue: writes are stored first and sent later; hub rejections are kept, never deleted | `queue/` |
| Updates: checks GitHub once a day, "Update" downloads, verifies and installs (see below) | `update/`, `logic/Updates.kt`, `ui/UpdateActivity.kt` |
| Device token and ntfy topic in EncryptedSharedPreferences (Android Keystore); never logged, no backups | `Prefs.kt` |

With no hub configured the app still runs: the floating jester, outfits, sound, voice and the setup flow all work.
Task sync, chat answers and push need a hub.

## Build

Needs Java 17 and the Android SDK (platform 35, build-tools 35). No Android Studio needed.

```sh
echo "sdk.dir=/path/to/your/android-sdk" > local.properties   # or set ANDROID_HOME
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # -> app/build/outputs/apk/release/ (unsigned unless you set the keystore below)
```

### Release signing (optional)

Release builds are signed only when these are set, as environment variables or in `~/.gradle/gradle.properties`.
Otherwise the release APK is unsigned (the debug key is never used for release):

```
JESTER_KEYSTORE=/path/to/your.keystore
JESTER_KEYSTORE_PASSWORD=...
JESTER_KEY_ALIAS=...
JESTER_KEY_PASSWORD=...
```

## Install (sideload)

1. Copy the APK to your phone (or `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
2. Open it and allow "install unknown apps" for the app you opened it from (one-time warning for a sideload).
3. Open Jester. The setup checklist opens on first run.

## Setup checklist (in the app)

1. **Notifications**: allow (Android 13+ asks).
2. **Display over other apps**: needed for the floating jester.
3. **Alarms & reminders**: exact alarms for the offline reminder backup.
4. **Battery**: "don't optimise" Jester. On Samsung / Xiaomi / OnePlus also set Jester (and ntfy, if installed) to
   "Never sleeping" / "No restrictions", or the OEM kills push and the overlay.
5. **Hub (optional)**: in Settings enter the hub URL (https), the device token and, for push, the ntfy topic. A
   `jester://pair?hub=...&topic=...&token=...` link does the same in one tap. Token and topic are stored encrypted and
   never shown again (type to replace). Leave the URL empty to run without a hub.
6. **Watch (optional)**: if a watch app mirrors phone notifications, allow Jester there. If the ntfy app is subscribed to
   the same topic, mute it so the watch does not buzz twice.
7. Press **Start Jester**.

## Updates

| | |
|---|---|
| What it checks | The public GitHub releases list of `tupesanket1999/jester` for the newest `android-vX.Y.Z` release with a `jester-android-*.apk` asset (prereleases count, drafts do not). Nothing about you is sent. |
| When | At most once a day, when Jester starts or you open the app. **Me -> Updates -> Check for updates** always checks now. |
| Turn it off | **Me -> Updates -> Check for updates automatically**. Off means only the manual button checks. Automatic checks fail silently when offline. |
| If there is one | A quiet notification "Jester x.y.z is available" opens a sheet with what's new, the size and **Update / Later / Skip this version** (a newer release shows again). |
| Installing | Jester asks you to allow "install unknown apps" for itself (once), downloads the APK, checks its size and SHA-256 (when GitHub provides one; a mismatch is deleted, never installed), then Android shows its own install confirmation. |
| Signature | Android only installs an update signed with the same key as the installed Jester. A build signed with a different key (for example a debug build over a release one) is refused. |

## Hooks and the hub

Claude Code hooks drive the Mac mascot, not this app. The phone app only talks to a hub you run. See
`docs/HUB_API.md` for the endpoints it calls: tasks, ringing, done, snooze, status, devices/me, bots, chat and inbox.
The app has no call that can start work on your computer.

## Notes

- The overlay cannot draw over the lock screen or some system screens; notifications still work there.
- "Hide" keeps push, alarms and the queue running; show Jester again from the notification or Settings.
- Auto-hide in full-screen apps is best effort (a 1-px invisible overlay watches the status bar).
- The hub base URL must be https (cleartext is blocked).
- Not built yet: local re-nag while offline (the hub escalates when online).
