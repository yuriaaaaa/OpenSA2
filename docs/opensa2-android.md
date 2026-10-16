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
or `.7z`) or **Browse GameBanana**: the newest Sonic Adventure 2 mods, a search box, and a tap to
download and install one. The same box takes a mod link, its id, or a one-click `sa2mm:` link,
and the browser's own **1-Click Install** on a mod page hands over to the app. Lower in the list
wins. Play copies the enabled mods' `gd_PC` files, include
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

**Texture packs.** The loader's texture packs (`textures/<name>/` with an `index.txt`, or
`textures/<name>.pvmx`) replace a whole texture list on PC. The game reads
`resource/gd_PC/PRS/<name>.pak` first and takes PNG as well as DDS, so Play writes the pack as
that PAK (cached, rebuilt when the pack changes). When the game's own archive of that name has
more textures than the pack, the rest are kept from it by position. A pack for a texture list the
game does not have — one a DLL makes, as Extra Chao Garden's are — is reported and left out.

**What still cannot work, and is marked on the mod:** DLL mods (Windows x86 code; the model and
texture files in the same mod still load, only what the DLL itself adds is missing — the mod list
says what that is for the known ones: chest jiggle, Character Select Plus, Extra Chao Garden),
`EXEData`/`DLLData` patches, and `replacetex/` loose images. RAR downloads cannot be unpacked on
the phone — re-save them as `.zip` or `.7z`.

## Characters

**Characters** plays a story character's stages as another character of the same play style:
Sonic or Shadow as either of them, Amy or Metal Sonic; Knuckles or Rouge as either, Tikal or
Chaos Zero; the two walkers as either, or the Chao Walkers. It applies from the next Play,
everywhere that character is loaded. It is the part of Character Select Plus that can be done
without its DLL (below, *Native patches*). Experimental: voices and some sounds can stay the
original character's, and a stage may expect an upgrade the stand-in does not have.

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

A code that changes the game's x86 program works only when it turns off or redirects a whole
function from its first byte (`ret` written at a function's entry, or a `writejump` from one
function's entry to another's): those are applied at the start of the next Play by the native
patch helper. Of the stock list that is Disable Music, Disable Voices, Disable Loading Tips and
Disable Fire Somersault Effects. A patch in the middle of a function (most of them, such as All
Stages Appear in Stage Select, Always Get A Rank or Instant Spin Dash) cannot work; the Codes
screen says which is which under each code.

## Native patches

The recompiled game runs no x86: each guest function is a native ARM64 function, and most calls
between them are direct, so writing x86 bytes into the game's program changes nothing. What can be
reproduced is a change to a whole function: making it return at once, sending it to another
function, or wrapping it. `lib/arm64-v8a/libopensa2patch.so` (`host/android/native/opensa2_patch.c`)
does that in the game process before the game starts: the launcher reads `libsa2game.so`'s symbol
table, writes the plan (`files/native-patches.txt`, `NativePatch.java`), and the play screen
starts the game with `LD_PRELOAD` set to the helper. The helper checks each function's first bytes
against the plan, keeps the original bodies callable through relocated trampolines, writes a jump
at each patched entry, and logs every step to the session's `game.log` (`opensa2-patch:` lines,
also shown in the session report). Nothing is preloaded when no code patch or swap is on.

For character swaps the helper also wraps the three lookups that place a character at a level's
start and end (`LoadStartPosition` and the two end-position functions): when the stand-in's own
tables have no entry for the level, the lookup is made under the character it stands in for.

**The story select on wide screens.** The 2012 port draws the story select's background, title
and button hints for a 16:9 screen measured from the left edge, so on a phone wider than 16:9 they
sit to the left with a black strip on the right (the character cards, drawn on the centred 4:3
canvas, are right). With **Settings → Centre the story select** on (the default), the helper
wraps that screen's display (0x0067C0F0): while it draws, the screen width it reads is the 16:9
width for the screen's height, so its layout is centred; the runtime's own HUD-at-4:3 guards are
told the real width meanwhile, so the viewport, projection and full-screen backgrounds keep it.
For the first frames the screen's sprite calls are logged to `game.log` (`trace` lines), to
stretch its background over the whole width next.

**SA2 Render Fix** is left out at Play: its model and motion files are loaded by its DLL, and
without it the game stops at the title screen.

`tools/opensa2_android/emu_patch_helper.py` runs the helper in an ARM64 emulator against a real
`libsa2game.so` and checks the patched code, including the game's own `LoadStartPosition`.

## Settings

Resolution (screen size, 1080/900/720/540/480 lines or 4:3), HUD at 4:3 positions, centring the
story select on wide screens (below), touch
controls, vibration and opacity, saving session logs to `Internal storage/OpenSA2/logs/`,
performance hints, extra `SA2_` lines for testing, and a reset of the copied mod files that keeps
saves.

## Building

```
python3 tools/opensa2_android/build_apk.py --release-apk SA2Recompiled.apk \
    --sdk ~/android-sdk --sdl SDL3-3.4.16 --out OpenSA2.apk [--icon picture.png]
```

The native libraries come from an SA2 Recompiled release APK. The APK holds recompiled game
code, so keep it for your own use. The native patch helper is built with `--ndk`'s clang, or a
`clang` and `ld.lld` on PATH (it is one freestanding C file); without one the APK has no helper
and code patches and character swaps say so.

Without `--keystore` the build makes `opensa2-debug.jks` next to the output and reuses it, so a
later build installs over the app as an update and keeps its saves. Keep that keystore: a build
signed with a different key has to be installed fresh, which clears the app's storage (its saves
are copied to `Internal storage/OpenSA2/saves/` after each session, so they can be brought back).
