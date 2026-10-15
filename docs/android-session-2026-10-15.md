# OpenSA2 Android — session handoff (2026-10-15)

This continues PR #1 (“OpenSA2 Android: launcher, Xbox touch pad, mods, codes and settings”).
It is written to bring another assistant up to speed on what changed, why, and what is left. All
the work is on the branch `claude/project-thread-lpzylp`; the desktop/runtime code is untouched.

## What this session set out to fix

1. **Debug/session logs saved to the app's private storage, not a place the player can read.** The
   PR copied logs only with a “Debug logs” toggle on, named the app's private `/data/user/0/…`
   paths in the report, and tied the copy to one code path.
2. **A texture/model mod (Sonikko) only partly loaded.** In game the spin-dash ball and life icon
   showed the mod (light blue), but the loading-screen character icon was wrong (Shadow's black
   icon). The mod replaces Sonic by a **folder of split models** plus `.pak` texture archives.
3. **Build an installable APK** (Galaxy S25).
4. **GameBanana one-click install** in the Mods screen.
5. **The DLL error** (`SonicChestJiggle.dll`) — make it correct and clear.
6. (raised mid-session) **Saves** survive an app replace; **app icon**; a **handoff**.

## Root causes

- **Model folders.** `gd_PC/sonicmdl/` (with `sonicmdl.ini` = index→label and `*.sa2mdl` files)
  is the sa2-mod-loader `LoadMDLFile` feature: on PC the loader assembles the folder into the
  character's models at load time (see `sa2-mod-loader/SA2ModLoader/dllmain.cpp` `LoadMDLFile_ri`).
  The recompiled game has **no loader**, so the folder did nothing and the game kept its own
  `sonicmdl.prs`. The textures loaded (they are plain file replacements), the models did not —
  which is exactly the “textures right, some things wrong” the player saw.
- **Loading-screen icon.** The character icons on the loading/stage screens are in
  `gd_PC/PRS/stageMap.pak` (`stg_stagechr_sn` = Sonic, `…_sd` = Shadow, …). The game fills a
  texture list **in the archive's `.inf` order** and de-duplicates by global index. A modded
  `.pak` whose textures sit in a different position, or under a different global index, than the
  game's can therefore show the wrong icon. This needs the player's real game files to confirm the
  exact index, so this session adds a **diagnostic** (below) rather than guessing a patch.
- **DLL.** The game is statically recompiled ahead of time; no x86 runs and there is nothing for a
  Windows DLL to hook. `SonicChestJiggle.dll` only adds chest-jiggle physics
  (`MainMemory/SonicChestJiggle`); the mod's models and textures are unaffected.
- **Saves/keys.** The app writes saves into its private overlay. Replacing the app with an APK
  signed by a **different key** forces an uninstall, which clears that storage. The PR's build
  script makes and reuses `opensa2-debug.jks`, so a rebuild **with the same keystore installs as an
  update** and keeps saves.

## Changes (all under `host/android/`)

New Java (`host/android/java/com/sa2recomp/app/`):

| File | What it does |
|---|---|
| `ModelFolder.java` | Builds a `gd_PC/<name>/` model folder into the big-endian, PRS-compressed `<name>.prs` the game reads. Reads each `*.sa2mdl` (v3), lays the models out after the index table, and turns the reachable structures (objects, chunk models, vertex/poly chunk lists) big-endian in place with pointers relocated. Mirrors SA Tools `SplitMDL.cs` `SA2MDL.Build` and the loader's `ModelInfo`. |
| `Prs.java` | PRS (SEGA LZSS) compressor + decompressor. The game reads PRS; the compressor is greedy over 3-byte hash chains. |
| `Pak.java` | Reads a `.pak` texture archive's `.inf` (names, global indices, sizes) and reports how a mod's archive differs from the game's — the loading-screen-icon diagnostic. |
| `SevenZ.java` | A 7z reader: LZMA/LZMA2 + Copy/BCJ-x86/Delta, compressed headers, solid folders decoded streaming to a temp file. Refuses BCJ2/AES/RAR by name. |
| `Lzma.java` | LZMA + LZMA2 decoder (LZMA SDK reference), streaming through a sliding window. |
| `ModInstall.java` | Shared archive install (zip via `ZipInputStream`, 7z via `SevenZ`); finds the `mod.ini` folder and moves it under `mods/`. |
| `GameBanana.java` | Turns a mod URL / id / `sa2mm:` one-click link into a download (apiv11 `ProfilePage`, prefers `.zip` then `.7z`) and installs it. |
| `SessionLogs.java` | Copies each session's logs to `Internal storage/OpenSA2/logs/<session>/`, rewrites private paths to the public ones, and reads the failure reason from `player.log`/`game.log`. |
| `Saves.java` | Copies the game's saves out to `Internal storage/OpenSA2/saves/` after a session and back in before Play (handles the runtime's whiteout delete markers). |

Changed:

- `ModManager.java` — builds model folders at Play (cached, rebuilt on change); counts a model
  folder as one game file; **path safety** for `IncludeDir`/`[ReplaceFiles]`/`Codes` (absolute or
  `..` refused); clearer DLL note; `.pak` diff diagnostic; `apply()` split into a testable
  `applyMods(...)`; overlay deletes guarded to stay inside the overlay.
- `ModsActivity.java` — **Add mod from file** (zip or 7z) and **Install from GameBanana**; handles
  an incoming `sa2mm:` intent.
- `Sa2SetupActivity.java` — session end now always copies saves + logs and shows the report (with
  the failure reason) whether or not the launcher stayed open; writes a `launcher.log`.
- `SettingsActivity.java` — “Debug logs” → “Save session logs” (on by default).
- `OpenSa2.java` — `writeBytes`; doc for the new `logs/` and `saves/` folders.
- `GameMenu.java` — in-game debug info shows public log paths.
- `AndroidManifest.xml` — `ModsActivity` exported with an `sa2mm:` intent filter.
- `tools/opensa2_android/build_apk.py` — version 0.1.1 / code 10200.
- `docs/opensa2-android.md` — mods, saves, logs, build-key note.

## How to build (Galaxy S25)

```bash
python3 tools/opensa2_android/build_apk.py \
    --release-apk SA2Recompiled.apk \       # an SA2 Recompiled release APK (native libs + game)
    --sdk ~/android-sdk --sdl SDL3-3.4.16 \  # build-tools 36.0.0, platform-36; SDL 3.4.16 sources
    --out OpenSA2.apk [--keystore opensa2-debug.jks]
```

**Use the same `--keystore` as the installed app** (the `opensa2-debug.jks` the first build made)
so it installs as an update and keeps saves. A new key means a fresh install (saves are in
`OpenSA2/saves/` after any session, so they can be restored).

This session built with: SA2 Recompiled release `v0.1.0-alpha.1` (sha256
`2c4175ccacc6a4da70560c19f2ef64ae5995b8f443c575c487c24f5c0fb1e401`), `build-tools;36.0.0`,
`platforms;android-36`, SDL `release-3.4.16` (commit `fa2c02bb6`). Package `com.yuriaaaaa.opensa2`,
versionName `0.1.1-opensa2`, versionCode `10200`.

## Testing done (offline, on the desktop toolchain)

- **Model build**: the Java `ModelFolder` output parses back identically to the 64 + 38 source
  `sonicmdl`/`ssonicmdl` models (an independent Python re-parser), and its PRS round-trips.
- **PRS**: compress→decompress round-trips over random, repetitive and edge sizes.
- **7z**: `SevenZ` unpacks the real `sonikko.7z` **byte-for-byte identical** to `py7zr` (35 files,
  including the BCJ-x86 DLL and the LZMA2-compressed header); synthetic LZMA1, Copy, Delta and
  LZMA2 archives also match.
- **GameBanana**: `parse()` handles a mod URL, a bare id and an `sa2mm:` link; a live download of
  mod 245239's `.7z` unpacks and the `Sonikko/mod.ini` folder is found.
- **Mod apply**: on a fake game tree, model folders build, a malicious `..`/absolute `[ReplaceFiles]`
  and `IncludeDir` are refused, the `.pak` diagnostic reports a moved texture and a changed global
  index, and a second apply copies nothing (unchanged).
- Whole app compiles against `android-36` (min API 29, release 11). `tools/public_check.py` passes
  (860 files). `tools/test_source.py`: 176 pass; 8 fail only because this environment has no
  `bwrap` sandbox binary (installer/launch C tests, unrelated to the Android changes).

## Open items / limitations

- **The loading-screen icon is diagnosed, not auto-corrected.** `Pak.compare` writes the mismatch
  to the Play log. Confirm on device what `stageMap.pak`'s `stg_stagechr_sn` resolves to; if the
  recompiled player needs it, the real fix is in the texture loader (global-index dedup), which
  needs the player's own game files to reproduce. Relevant player code:
  `host/player/player.cpp` `texture_from_file`; runtime `runtime/sa2_d3d9.c` `d3dx_from_memory`.
- **Not device-tested this session.** Build on the S25, then: enable a model-folder mod and check
  Sonic's model; read the Play log for the `.pak` diff and model-build lines; confirm `OpenSA2/logs/`
  and `OpenSA2/saves/` fill; try a GameBanana `.zip` and `.7z` install.
- **7z**: BCJ2, AES-encrypted and RAR archives are refused with a message to re-save as `.zip`/`.7z`.
- **DLL behaviour** (chest jiggle etc.) is not reimplemented — it is x86 the recompiled game can't
  run. A future option is to port a specific DLL's effect to native code if one matters.
