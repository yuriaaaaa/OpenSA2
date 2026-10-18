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
The build is cached in the app's storage and redone only when the folder's files change. When the game's own model file is there, an index the folder's ini names but no file in the
folder holds (Nazo's sonicmdl.ini names the upgrades, 13, 14 and 25-27, with labels none of its
files has) keeps the game's model, copied with its pointers moved, instead of being left empty for
the game to draw as nothing.

**Texture archives.** When a mod replaces a `.pak` texture archive, Play writes to the log how it
differs from the game's — a texture in another position in the list, or under another global
index, is what makes a modded texture appear in the wrong place (a wrong loading-screen icon, for
example). When several mods replace the same archive (Sonikko, Shady the Hedgehog and Character
Select Plus each ship a stageMap.pak with only their own character changed), Play merges them:
the last mod's archive, with every texture it leaves as the game's taken from the earlier mod that
changed it, so each character keeps its mod's icon (cached in the app's storage).

**Very large textures.** Some character mods ship uncompressed 2048x2048 textures without
mipmaps (Sonikko's sonictex.pak is 75 MB): the game stalls for seconds after a save loads while
they upload and their smaller levels are built. With **Settings → Lighter mod textures** on (the
default), Play writes any mod archive over 8 MB with such textures halved to at most 1024 pixels
and given their mipmaps (`TexSlim`; Sonikko's sonictex.pak becomes 33 MB), cached in the app's
storage.

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

**Every character for every slot** (for testing): each story character's picker lists all
the characters, Super Sonic (LoadSuperSonic, 0x0049A950) and Super Shadow (0x0049ABB0) among them;
a stand-in from another play style gets the stage's layout for its kind (CharID 4 and 5 hunters,
6 and 7 walkers) when the SA2 Randomizer's layouts are installed, and hunters outside the hunting
stages get the level gates below. Super Sonic and Super Shadow can be chosen (not in random: they still crash in normal stages);
outside Final Hazard their loaders' LastBossPlayerManager_Load (0x0049ECE0) and Exec_Load
(0x006EDEE0) do not run (level gate, set 1), nor does PResetPos (0x00469050) while a super form
plays (helper kind 17, by CharID or CharID2 9 and 10), as the SA2 Randomizer patches them (it also changes a few instructions inside
Super_DispDelay3 and CheckBreakObject, which this port cannot).

**Super transformation** (Characters, on by default), after Sora's SA2 Super Sonic (a DLL mod:
it keeps Sonic and, on transforming, swaps in the super model, textures, motions and physics).
The helper does the core of it natively, around Sonic_Main (0x00717840, kind 18): with 50 rings
or more, Y (Buttons_Y, 0x200, from Controllers at 0x01DEFC00 +0x10 press) in a jump (Action 6)
makes Sonic or Shadow the super form: the game's LoadMDLFile (0x00459590) on SSONICMDL.PRS or
SSHADOWMDL.PRS puts model 328 or 355 in CharacterModels, LoadCharTextures (0x0044C510) on SSONICTEX
or SSHADOWTEX gives the player's texture list (SonicCharObj2 +0x394), the animation list's body
model (the commonest ModelNum) becomes the super model so their own motions drive it, the physics
(CharObj2Base +0xC0) become PhysicsArray's Super Sonic or Super Shadow entry, and the
invincibility bit (Powerups 0x8000) is set. A ring a second goes (RingCount 0x0174B028, on
FrameCountIngame % 60 unless TimerStopped); at none, or on Y in a jump again, all of it is put
back. Loaded once per character object; a new one (a new stage, a restart) puts the shared
animation list back. Not in Final Hazard (H set 1). Sonic_Display (0x00720090, kind 19) draws the
ball, which has no super model, with the old textures. **Always super** (off) makes them the super
form from their first frame instead. With a D line the transformation flashes (ScreenFadeARGB 0x0171CDA0, which the game draws over the screen while its alpha is not 0: up to 0xB0 in 8 frames, where the form changes, a third held, gone at the line's last frame) and plays bank 3 cues through the game's PlaySoundProbably (0x00437260, sound 0x3000 + cue in ESI) as it starts and ends; a mod's mod.ini [OpenSA2] group names them (TransformVoices=<cue>,<cue>, TransformFlash=<rrggbb>,<frames>). Kind 20 around PlayVoice (0x00443130) logs each voice number once ("voice: the game asks for voice N") and plays a cue instead for the numbers of VoiceCues=<voice>:<cue>,... while player 1 is Sonic. A mod's gd_PC/PRS/csonefftex.pak (the layout of s_efftex) stands in for Sonic's effect list (SonEff, 0x00A08B94) while he is the super form (E line; the name is written past .data's end, 0x02670E00). Kind 19 leaves the ball with the super textures when both lists name the same textures in the same order (a mod's ssonictex made from its sonictex). Kind 21 around Super_DispDelay3 (0x0049CD50) gives the super forms Final Hazard's aura in every other stage (0.1.22, the Characters screen's Super aura, on by default): on transforming, SSONEFFTEX is loaded (LoadTextureList 0x0044C350 with the name at 0x008BAA1C into 0x0170ACD4, as LoadSuperSonic does) and the player's task (MainCharacter, 0x01DEA6E0) gets Super_DispDelay3 as its DisplaySub_Delayed4 (task +0x28; its own is put back when the form ends). The game's body reads Final Hazard's data (FinalHazardPlayerData, null elsewhere) and SuperSonicCharObj2, so outside Final Hazard (H set 1) the wrapper draws the aura itself through the game's own calls and returns through the bare ret (J line): SSONEFFTEX as the texture list (RenderInfo +0x20), the model's texture number (0x0170ACEE) cycling 3-8 for Sonic and 9-14 for Shadow, the matrix pushed in place (as the game does), njTranslate (0x00427470) to the character's position, ResetRenderSpace (0x0042D340), njRotateZ/X/Y (0x00427680, 0x004274E0, 0x004275B0; Y as 0x8000 - Y), njScale (0x00427750), pushed again and 3 units down, and ProcessChunkModelsWithCallback (0x0042EB30) on the aura model (0x0170B47C) with ProcessChunkModel (0x0042D650); the matrix stack, texture list and number are put back after. Nothing is drawn at the stage's end (animation 54). A mod's mod.ini [OpenSA2] AuraColor=<rrggbb> tints Super Sonic's (c line): SaveControl3D, OnControl3D (EAX 0x10, constant material), SetMaterial (1, r, g, b) around the draw, then ResetMaterial and LoadControl3D. The a lines give the functions' offsets in the order of NativePatch.AURA_FNS; any of them missing, or the display redirected by a code, leaves the aura out. Sora's SA2-Super-Sonic (aura.cpp) was the reference for where the aura is drawn and how; none of its code is used. No music, flight or intro animation. 0.1.20 moved Always super to a new setting, so 0.1.18's test box no longer carries over. A mod's
`ssonicmdl/` folder and `ssonictex.pak` give another super look (the Chaos Nazo add-on: Nazo's
body as index 328 and his textures recoloured green, with their own GBIX numbers so the texture
cache does not hand back Nazo's blue ones). The helper calls the game's functions on the wrapped
call's State (`guest_call`: EAX/ECX set, a return address pushed, the registers put back).

**Amy's and Metal Sonic's Spin Dash** (Characters, on by default) follows MainMemory's
SA2-Amy-Metal-SpinDash: Sonic's spin dash check (0x00723DD5, where that mod nops a CharID2 test)
runs with Amy read as Sonic and Metal Sonic as Shadow; the spin dash particles (0x00757B30,
0x00757CD0), which pick Sonic's or Shadow's effect textures by CharID, get Amy's or Metal Sonic's
(their texture lists trade places for the call, helper kind 15); and 0x00723851 leaves field 0x36A
at 2 (kind 16). The 0.1.7 attempt crashed on the particles' empty texture list.

**Random each stage** loads a random character of the same play style each time a story
character is loaded (the choices are set aside while it is on). The game's restart handlers
(0x0043BA75 and 0x0043BAAF, cases of its GameState switch) keep the character object, so a
restart or a death would keep the pick; with random on, both are sent down the game's own
restart-with-reload handler (0x0043BB60: fade, tear down, GameState 5, which deletes and loads the
characters again) unless a checkpoint was touched in the stage (CheckpointRecords, 0x01934B88),
so before a checkpoint a restart or a death picks again. **Walkers in speed stages** puts Tails's,
Eggman's and the Chao Walker in the speed group too; a stand-in with no start or end position for
the stage takes that of a character who has one. A walker cannot get past a speed stage's dash
panels and loops; the SA2 Randomizer mod (GameBanana 48932) has, in its Options folder, each
stage's object layout per kind of character, and its DLL copies the one for the character it
picked (1 for Knuckles and Rouge, 2 for the walkers). With that mod installed (on or off), each Play stages its walker
layouts, and the helper wraps the game's stage layout loader (LoadStageSETFile, 0x00488F60): as a
speed stage's layout loads, the walkers' layout or the stage's usual one is copied over the file
the game reads, for the character picked for it (`StageLayouts.java`, helper kind 13). A restart
keeps the stage's layout, so it picks among the same kind. The hunters join the speed group too (the hunters'
layout when the SA2 Randomizer is there; their emerald manager, LoadEmeraldManager 0x0073AA70, and
level bounds, 0x00737B50, run only in the hunting stages, as Character Select Plus has it, helper
kind 14, which cured the black screen they gave there in 0.1.8). The Dark Chao Walker crashes as a story stage loads it (every time
in 0.1.10 and 0.1.11, guest read at 0x6B6E617A, the walker-main wrapper notwithstanding), so random
leaves it out. **The Chao Walkers** in story stages: the walker's main (0x00741530) takes paths by
`CharObj2Base.CharID2` that only Tails's and Eggman's walkers have data for (the Dark Chao Walker
crashed as it loaded, the Chao Walker when it shot with the story's upgrades), so whenever they can
turn up the helper wraps it: while it runs they read as Tails's and Eggman's walkers, and their
upgrades are cleared.

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

**Menus on wide screens.** On a phone wider than 16:9 (2340x1080) the story select's and
Settings' backgrounds and the stage map stopped at the 16:9 width with a black strip on the right.
Everything else on those screens is placed right. The backgrounds are 16:9 pictures (853x480 in
a 1024x512 texture), drawn by the port's sprite call (SOCDisplaySprite, 0x0041DC80) from the
screen's left edge to its right one a texel a unit, so past the picture the empty rest of the
texture shows. The stage map is laid out at a fixed 853.33-unit width from the screen's left edge
(DisplayStageMap). With **Settings → Menus on wide screens** on (the default), the helper wraps
the sprite call so such a background draws its picture across the whole screen (u to the picture's
width, v cropped about its centre by the same factor, so nothing is stretched), and lays the stage
map's display and main (0x006777C0, 0x00677350) out as on a 16:9 screen, which centres the map
(HorizontalResolution reading the 16:9 width while they run, the runtime's HUD-at-4:3 guards keeping
the viewport and projection at the real width). Worked out from a drawing log on the phone (0.1.4:
the helper can still log calls, kinds 4-8) and by running the game's own code in the emulator.

**SA2 Render Fix** is left out at Play: its model and motion files are loaded by its DLL, and
without it the game stops at the title screen.

`tools/opensa2_android/emu_patch_helper.py` runs the helper in an ARM64 emulator against a real
`libsa2game.so` and checks the patched code, including the game's own `LoadStartPosition`.

## Settings

Resolution (screen size, 1080/900/720/540/480 lines or 4:3), HUD at 4:3 positions, the story
menus on wide screens (above), touch
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
