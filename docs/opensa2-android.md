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
each with its `mod.ini`, or add a `.zip` from the **Mods** screen. Lower in the list wins.
Play copies the enabled mods' `gd_PC` files, include folders and `[ReplaceFiles]` into the app's
own storage; the game folder is never changed. DLL mods, `EXEData`/`DLLData` and texture-pack
folders cannot work here and are marked on the mod.

## Codes

**Codes** downloads the mod loader's `Codes.lst` from X-Hax/sa2-mod-loader. Codes switched on
(and the active mods' required codes) are written into the game's memory while it plays.
Patches that change the game's x86 code (writenop, writejump and code writes) do nothing in
the recompiled game; the debug info lists them.

## Settings

Resolution (screen size, 1080/900/720/540/480 lines or 4:3), HUD at 4:3 positions, touch
controls, vibration and opacity, debug logs (copied to `Internal storage/OpenSA2/logs/` after
each session), performance hints, extra `SA2_` lines for testing, and a reset of the copied
mod files that keeps saves.

## Building

```
python3 tools/opensa2_android/build_apk.py --release-apk SA2Recompiled.apk \
    --sdk ~/android-sdk --sdl SDL3-3.4.16 --out OpenSA2.apk [--icon picture.png]
```

The native libraries come from an SA2 Recompiled release APK. The APK holds recompiled game
code, so keep it for your own use.
