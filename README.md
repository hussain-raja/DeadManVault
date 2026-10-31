# DeadVault

A photo and video vault for Android. Items you add are **encrypted, removed from the normal
gallery**, and kept inside the app. You must enter your passcode at least once every *X* days —
if you don't, **everything in the vault is erased permanently**.

Version 0.1.0 — first working build.

## What it does

| Feature | How |
|---|---|
| Hidden from gallery | Files live only in the app's private storage, never in MediaStore. After import the app asks Android to delete the originals. |
| Encryption | Each file is AES-256-GCM (streaming, via `androidx.security:security-crypto`) with a key held in the Android Keystore. |
| Check-in deadline | `deadline = last successful unlock + interval`. Intervals: 1, 3, 7, 14, 30 days (plus 2 minutes in debug builds, for testing). Every unlock restarts the countdown. |
| Wipe | Deletes the Keystore key first (data becomes unreadable instantly), then deletes the files, cached playback copies and all state. An interrupted wipe is finished on next launch. |
| Wrong-passcode limit | Wipes after N wrong passcodes in a row (default 10, configurable 5 / 10 / 20 / off). |
| Warning | A notification (hidden on the lock screen) shortly before the deadline: 24 h before, or 25 % of the interval for a 1-day interval. |
| Privacy | `FLAG_SECURE` (no screenshots / screen recording, blank recents card), auto-lock the moment you leave the app, no cloud backup or device transfer. |
| Restore | Select items → *Restore to gallery* puts them in Pictures / Movies › DeadVault Restored and removes them from the vault. |

## How the deadline is enforced

The check runs from four directions so a missed deadline is acted on as soon as Android lets the app run:

1. Every process start and every unlock attempt (a vault past its deadline never opens, even with the right passcode).
2. An `AlarmManager` alarm at the deadline (`setAndAllowWhileIdle`, no special permission; may fire a few minutes late in Doze).
3. A 3-hourly WorkManager job as a backup.
4. A boot / app-update receiver — a phone that was **off** at the deadline is wiped when it starts.

Clock tampering: the check never trusts a wall clock earlier than the latest one it has seen, and within a
single boot it also uses the monotonic clock.

## Build the APK

### Option A — Android Studio (easiest)
1. Install Android Studio (Koala or newer) and open the `DeadVault` folder.
2. Let Gradle sync (it will offer to create the Gradle wrapper and download SDK 35).
3. **Build ▸ Build APK(s)** — the file is `app/build/outputs/apk/debug/app-debug.apk`.
4. Copy it to your phone and install (allow "install unknown apps"), or press **Run** with the phone connected over USB.

### Option B — GitHub Actions (no local setup)
Push this folder to a GitHub repository. `.github/workflows/build-apk.yml` builds a debug APK on every push;
download it from the run's **Artifacts** (`DeadVault-debug-apk`).

### Option C — command line
Needs JDK 17, the Android SDK (platform 35) and Gradle 8.9:

```
gradle :app:assembleDebug
```

## Testing the dead-man's-switch quickly
Debug builds offer a **2 min (debug)** interval on the setup screen. Create a vault with it, add a photo, lock the app,
and wait: the vault is erased at the deadline (within a few minutes if the phone is idle). Try it with throw-away photos first.

## Known limitations in 0.1.0
- **Not compiled or run yet.** This build was written without an Android SDK available; expect to fix a small number
  of compile errors on first build.
- Unlocking gets slower as the vault grows (every item's metadata is decrypted on unlock); fine for hundreds of items.
- If you leave the app while the photo picker is open, the vault re-locks on return after 5 minutes.
- The encryption key is protected by the Keystore but is **not derived from the passcode**; the passcode is a gate in
  front of the key. (Planned: derive/wrap the key from the passcode.)
- Video playback decrypts to a temporary file in the app's private cache while playing; it is deleted afterwards,
  on lock and on every app start.
- Some phone makers (Xiaomi, Oppo, Vivo, Huawei, Samsung "sleeping apps") kill background work. Set DeadVault to
  *Unrestricted* battery usage. Even if alarms are suppressed, the wipe happens the next time the app starts
  or the phone reboots.
- Deleting originals only works for items picked from Android's Images / Videos browser, and only after you
  approve the system prompt. **Cloud backups (Google Photos, etc.) are not touched.**
- A large forward jump of the phone clock (for example a bad network time sync) is indistinguishable from time passing
  and can trigger a wipe.
- Images have no pinch-to-zoom yet; only photos and videos are supported (no documents).
- Root access can edit the app's preference file and extend the deadline.

## Project layout
```
app/src/main/java/com/deadvault/app/
  DeadVaultApp.kt          process-start enforcement
  MainActivity.kt          FLAG_SECURE, auto-lock
  data/                    VaultStore (encrypted storage), VaultWiper, VaultPrefs, PasscodeHasher
  work/                    DeadlineEnforcer, alarm receiver, WorkManager worker, notifications
  ui/                      VaultViewModel, Compose screens (setup, lock, gallery, viewer, settings)
```
