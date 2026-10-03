# Nakara — Android Port (Unofficial, Bring-Your-Own-Data)

An open-source Android launcher/port for **Nakara**, the commercial GZDoom
game by [R3dspike](https://r3dspike.itch.io/nakara).

> **This project contains no game data and never will.** It is only the
> engine and launcher. To play, you need a legally purchased copy of Nakara
> (sold on [itch.io](https://r3dspike.itch.io/nakara), DLsite, and Steam)
> and you supply the game's `Nk.ipk3` file yourself. This is a fan project:
> it is not affiliated with, or endorsed by, the game's creator. If the
> creator asks for this repository to come down, it will.

Current version: **0.1.0** — playable, with known limitations listed below.
Tested on a Moto G Play 2024 (Android 14, arm64).

---

## How it works

1. Install the launcher APK.
2. On first launch it explains that it contains no game data. Tap
   **Select Nk.ipk3** and pick the `Nk.ipk3` from your purchased PC copy
   (copy it to your phone first — Downloads is a good spot).
3. The launcher verifies the file really is the Nakara archive, applies the
   Android compatibility patch to *its copy* of the file (your original is
   never touched), and boots the game. Setup needs ~1.3 GB free and takes a
   few minutes for the 651 MB file.
4. Later launches integrity-check the imported data and boot straight into
   the game — no menus in the way.

## The compatibility patch (what it does and why)

On PC, Nakara runs on the creator's custom engine fork
([gzdoom-nakara](https://github.com/r3dspike/gzdoom-nakara)), which provides
engine-level features the game's scripts call into. This port is based on
the UZDoom Android engine, which doesn't have those natives. At import time
the launcher patches your `Nk.ipk3`:

- **Fish/particle stubs.** The water level's scripts call
  `NakaraParticleFX.SpawnFishSchoolF` (and two ambient-particle functions)
  via ACS `ScriptCall`. Without the class, the level aborts back to the
  title screen. The patch appends no-op stub classes with those exact names
  so the calls resolve and the level loads and plays. (The particle effects
  themselves don't render — see Known issues.)
- **Gallery handler.** Registers `NakaraGalleryHandler` in MAPINFO's
  `AddEventHandlers`, so the **G** touch button (which sends the K key)
  can advance gallery rooms — see the gallery note under Known issues.
- **Key bindings.** Repoints the gallery bind to K and arms the gallery
  command in KEYCONF.

All patching is idempotent: importing an already-patched file is a no-op.
The exact patch content ships in the APK as
`assets/compat/zscript_fix.zsc` — it's our own code, not the game's.

## Features

- Boots straight into the game; no launcher UI in the way
- Custom touch controls, built for the game:
  - Left virtual stick to move, right stick for relative mouse look
  - **A / B / X / Y** as real keyboard keys (Enter / Esc / Space / E)
  - **SKIP** button (C / crouch) that reliably skips cutscenes and dialogue
  - **L / R** mouse buttons next to the right stick for combat
  - **G** button (K key) for gallery room advance
- Save/load, hub-and-levels structure, intro and lore cutscenes all playable
- ZScript error logging: if the game hits a script error, a log is dropped
  in your Downloads folder (`nakara-zscript-errors.log`) for bug reports
- First-import validation: checks the archive structure and records a
  SHA-256 of the patched file; every boot re-verifies it

## Controls summary

| Button | Sends | In game |
| ------ | ----- | ------- |
| A | Enter | Confirm / advance |
| B | Escape | Back / menu |
| X | Space | Alt action |
| Y | E | Interact / advance dialogue |
| SKIP | C (`+crouch`) | Skip cutscene/dialogue |
| L / R | Mouse left / right | Attack / alt fire |
| G | K | Gallery room advance |

## Known issues

- **Gallery room advance is unverified.** Pressing G fires exactly the same
  `GalleryChecks` script the game itself calls internally (verified firing,
  no errors), but a *visual* room swap inside unlocked gallery scenes has
  not yet been confirmed on hardware — reaching that state needs real
  play progression. This is the #1 thing testers can help with.
- **No fish.** The fish-school and underwater ambient particle calls are
  no-op stubs (see the patch notes above). The water level plays fine;
  those effects just don't render. Reimplementing the PC engine's particle
  system is a much bigger job.
- **External gamepads are untested.** The touch overlay is the supported
  control scheme for now.
- **Performance varies.** It's a big game (651 MB data) on a mobile GPU;
  budget devices may need the resolution divider raised in settings.
- Import is slow by necessity: ~651 MB copied and re-zipped once.

## Building

Requirements: JDK 17, Android SDK 36, and the Android NDK (only needed if
you rebuild the native engine; prebuilt `.so` libraries are included under
`doom/src/main/libs`, so a plain Gradle build works without touching C++).

```
./gradlew :doom:assembleLauncherDebug   # public data-less launcher (com.nakara.launcher)
./gradlew :doom:assembleBundledDebug    # dev flavor (expects data; see below)
```

Flavors (see `doom/build.gradle.kts`):

- **launcher** — the public build described above (`BuildConfig.DATA_LESS`).
- **bundled** — a developer convenience build that expects an `Nk.ipk3` to be
  injected into the APK as `assets/Nk.ipk3` after Gradle packaging (it's how
  the one-file test builds were made). It contains no data in this repo
  either; you'd inject your own copy.

Debug builds are signed with your local debug key. For any release, set up
your own keystore — never ship debug-signed builds publicly.

Engine source lives under `doom/src/main/jni/` (UZDoom/GZDoom lineage with
Nakara compatibility work). `build_native.sh` rebuilds the native libraries.

## Legal

- **Nakara** and all of its game data are © R3dspike. Buy the game; this
  project gives you nothing of his to play with.
- No game assets are included in this repository.
- The engine code is GPL-3.0 (GZDoom/UZDoom lineage) — see `LICENSE`.
  The Android compatibility code in this repo is offered under the same
  terms so the combined work stays license-clean.
- This port exists because one fan wanted the game on his phone. It will be
  taken down or made private if the creator prefers.

## Credits

- **R3dspike** — Nakara and the gzdoom-nakara engine fork
- **GZDoom / UZDoom teams** — the engine this is built on
- **marcelpasteur/gzdoom-android-2026** — the Android base this port grew from
- Everyone who tested on real hardware and sent error logs

## Helping out

Bug reports with the Downloads error log attached are gold. If you reach
the gallery in normal play, please report whether G advances rooms — that
single test closes the biggest open question in this port.
