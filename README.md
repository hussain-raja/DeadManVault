# DeadVault

A photo and video vault for Android. Items you add are **encrypted, removed from the normal
gallery**, and kept inside the app. You must unlock the vault at least once every *X* (configurable) days —
if you don't, **everything in the vault is erased permanently**.

Version 0.2.0.

## What it does

| Feature | How |
|---|---|
| Hidden from gallery | Files live only in the app's private storage, never in MediaStore. |
| Auto-delete originals | After an item is encrypted and read back successfully, the original is deleted from your phone with no confirmation (needs "All files access", see below). |
| Encryption | Each file is AES-256-GCM (streaming, via `androidx.security:security-crypto`) with a key held in the Android Keystore. |
| Check-in deadline | `deadline = last successful unlock + interval`. Intervals: 1, 3, 7, 14, 30 days (plus 2 minutes in debug builds, for testing). Every unlock (passcode or biometric) restarts the countdown. |
| Biometric unlock | Fingerprint / face (Class 3 "strong" only), backed by a Keystore key that Android destroys when a new fingerprint or face is enrolled. |
| Folders | Create, rename and delete folders; move items between them. Folder names are encrypted too. Deleting a folder moves its items back to the main library. |
| Image zoom | Pinch (up to 6x), drag to pan, double-tap to zoom in/out. Swiping between photos works when not zoomed. |
| Wipe | Deletes the Keystore keys first (data becomes unreadable instantly), then the files, cached playback copies and all state. An interrupted wipe is finished on next launch. |
| Wrong-passcode limit | Wipes after N wrong passcodes in a row (default 10, configurable 5 / 10 / 20 / off). |
| Warning | A notification (hidden on the lock screen) shortly before the deadline. |
| Privacy | `FLAG_SECURE` (no screenshots / screen recording, blank recents card), auto-lock the moment you leave the app, no cloud backup or device transfer. |
| Restore | Select items → *Restore* puts them in Pictures / Movies › DeadVault Restored and removes them from the vault. |

Small extras in 0.2.0: *Select all*, item / folder counts, the next check-in date in Settings, photo position ("3 / 12") in the viewer.

## Auto-delete originals: the "All files access" permission

Android does not let an app delete other apps' photos silently. The only way is the special
**All files access** permission. In Settings ▸ *Delete originals automatically*, tap **Allow all files access**
and switch DeadVault on in the system screen that opens. Until you do, DeadVault falls back to Android's
confirmation dialog after each import.

Notes:
- Pick photos with the **Images / Videos** browser in the picker (the default view of "Add"); that is what lets DeadVault find and delete the originals.
- The original is only deleted after the encrypted copy has been read back and its size checked.
- Google Photos / cloud backups of the same photo are **not** touched.
- This permission is not allowed for apps on the Play Store; fine for a personal sideloaded build.

## How the deadline is enforced

The check runs from four directions so a missed deadline is acted on as soon as Android lets the app run:

1. Every process start and every unlock attempt (a vault past its deadline never opens, even with the right passcode or fingerprint).
2. An `AlarmManager` alarm at the deadline (`setAndAllowWhileIdle`, no special permission; may fire a few minutes late in Doze).
3. A 3-hourly WorkManager job as a backup.
4. A boot / app-update receiver — a phone that was **off** at the deadline is wiped when it starts.

Clock tampering: within a boot the deadline uses the monotonic clock only; across a reboot it never trusts a wall clock earlier than the latest effective time it has stored.

## Known limitations
- The encryption key is protected by the Keystore but is **not derived from the passcode**; the passcode is a gate in
  front of the key. Anyone with root access to the phone can bypass it. (Planned: derive/wrap the key from the passcode.)
- Video playback decrypts to a temporary file in the app's private cache while playing; it is deleted afterwards,
  on lock and on every app start.
- Some phone makers (Xiaomi, Oppo, Vivo, Huawei, Samsung "sleeping apps") kill background work. Set DeadVault to
  *Unrestricted* battery usage. Even if alarms are suppressed, the wipe happens the next time the app starts or the phone reboots.
- A large forward jump of the phone clock (for example a bad network time sync) can trigger a wipe.
- Unlocking gets slower as the vault grows (every item's metadata is decrypted on unlock); fine for hundreds of items.
- If you leave the app while the picker is open, the vault re-locks on return after 5 minutes.

## Project layout
```
app/src/main/java/com/deadvault/app/
  DeadVaultApp.kt          process-start enforcement
  MainActivity.kt          FLAG_SECURE, auto-lock (FragmentActivity for BiometricPrompt)
  data/                    VaultStore (encrypted storage, folders), VaultWiper, VaultPrefs, PasscodeHasher,
                           BiometricHelper, OriginalDeleter
  work/                    DeadlineEnforcer, alarm receiver, WorkManager worker, notifications
  ui/                      VaultViewModel, Compose screens (setup, lock, gallery + folders, viewer + zoom, settings)
```
