# OpenSA2 on Android

OpenSA2 is this fork's Android app: SA2 Recompiled's game with its own launcher, touch
controls, mods and codes. It installs as `com.yuriaaaaa.opensa2`, next to the original app.

## Setting up

1. Copy your own Steam copy of Sonic Adventure 2 (the 2012 PC game's whole folder:
   `sonic2app.exe`, `resource/` and the rest) to `Internal storage/OpenSA2/game/`.
2. Open OpenSA2, tap **Allow file access** and switch it on. The game, mods and logs stay in
   `Internal storage/OpenSA2/`, where any file manager can copy to them.
3. Tap **Play**. The launcher does not hash-check the game files; the game still checks that
   `sonic2app.exe` is the Steam build.

## In game

A three-finger tap (or the phone's Back) opens the menu: touch controls on/off and opacity, the
touch layout editor, cheats and codes, resolution and HUD (next start), debug info and Exit.
The touch controls are an Xbox pad with a D-pad; a real controller takes over as soon as it is
used. The layout editor keeps three profiles.

## Mods

Mods for sa2-mod-loader (SA Mod Manager) go in `Internal storage/OpenSA2/mods/`, one folder
each with its `mod.ini`. Add one from the **Mods** screen with **Add mod from file** (a `.zip`
or `.7z`) or **Install from GameBanana** (a mod link, its id, or a one-click `sa2mm:` link the
browser hands over). Lower in the list wins. Play copies the enabled mods' `gd_PC` files, include
folders and `[ReplaceFiles]` into the app's own storage; the game folder is never changed. A
`mod.ini` path that leaves the mod folder (an absolute path or one with `..`) is refused.

**Character model folders.** A mod that replaces a character by a folder of split models
(`gd_PC/sonicmdl/` with `sonicmdl.ini` and `*.sa2mdl` files, the sa2-mod-loader `LoadMDLFile`
feature) is built into the single model file the game reads (`sonicmdl.prs`, big-endian,
PRS-compressed) at Play, because the recompiled game has no loader to assemble the folder itself.
The build is cached in the app's storage and redone only when the folder's files change.

**Texture archives.** When a mod replaces a `.pak` texture archive, Play writes to the log how it
differs from the game's — a texture in another position in the list, or under another global
index, is what makes a modded texture appear in the wrong place (a wrong loading-screen icon, for
example).

**What still cannot work, and is marked on the mod:** DLL mods (Windows x86 code; the model and
texture files in the same mod still load, only what the DLL itself adds is missing),
`EXEData`/`DLLData` patches, and the loader's loose texture-pack folders (`textures/`,
`replacetex/`). RAR downloads cannot be unpacked on the phone — re-save them as `.zip` or `.7z`.

## Saves and logs

The game's saves live in the app's own storage, which an uninstall clears, so after each session
they are copied to `Internal storage/OpenSA2/saves/`, and before each Play a save there the app
does not already have is copied back in. To move a save between devices, or in from the PC game,
drop the `SONIC2B__*` file into that folder.

Each session's logs are copied to `Internal storage/OpenSA2/logs/<session>/` (the player's and
game's logs, the run records and the launcher's own log of the Play), the newest few kept, so a
session that failed can be read on a computer. The reason a session gives for failing is shown on
the launcher when it comes back. This is on by default; **Settings → Save session logs** turns it
off. The paths the report names are the public `OpenSA2/logs/` ones, not the app's private
folder.

## Codes

**Codes** downloads the mod loader's `Codes.lst` from X-Hax/sa2-mod-loader. Codes switched on
(and the active mods' required codes) are written into the game's memory while it plays.
Patches that change the game's x86 code (writenop, writejump and code writes) do nothing in
the recompiled game; the debug info lists them.

## Settings

Resolution (screen size, 1080/900/720/540/480 lines or 4:3), HUD at 4:3 positions, touch
controls, vibration and opacity, saving session logs to `Internal storage/OpenSA2/logs/`,
performance hints, extra `SA2_` lines for testing, and a reset of the copied mod files that keeps
saves.

## Building

```
python3 tools/opensa2_android/build_apk.py --release-apk SA2Recompiled.apk \
    --sdk ~/android-sdk --sdl SDL3-3.4.16 --out OpenSA2.apk [--icon picture.png]
```

The native libraries come from an SA2 Recompiled release APK. The APK holds recompiled game
code, so keep it for your own use.

Without `--keystore` the build makes `opensa2-debug.jks` next to the output and reuses it, so a
later build installs over the app as an update and keeps its saves. Keep that keystore: a build
signed with a different key has to be installed fresh, which clears the app's storage (its saves
are copied to `Internal storage/OpenSA2/saves/` after each session, so they can be brought back).
