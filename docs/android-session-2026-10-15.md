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

---

# Second session (2026-10-16): GameBanana browsing, texture packs, native patches

Asked for: search and download GameBanana mods inside the app; make the user's mods work
(Character Select Plus, Extra Chao Garden, Shady the Hedgehog, Sonikko); "mimic the DLL stuff"
where possible; the stage-select code ("All Stages Appear in Stage Select") did nothing; Extra
Chao Garden played its music but not its textures. Version 0.1.2-opensa2 / 10300.

## What the DLL question came down to (read this before trying again)

- The lifted game calls functions **directly** (`bl sub_<va>`), not only through the runtime's
  function table: `sub_442cf0` (PlayMusic) has 133 direct native callers. Replacing a table entry
  (`sa2_ft_add`) therefore misses most calls. A hook has to patch the **native entry** of
  `sub_<va>` in `libsa2game.so`.
- Guest `.text` is filled with `int3` except the overlay runs (`runtime/sa2_exe_overlay.h`), and
  nothing reads it as code, so x86 bytes written there (a code-list `Patch`, a DLL's
  `WriteData`/`WriteJump`) change nothing.
- What can be reproduced: changes to a **whole function from its first byte** — `ret` at an
  entry, a jump from one entry to another, a wrapper around a function. What cannot: patches in
  the middle of a function. Of `Codes.lst`, 4 patches are whole-function (Disable Music, Disable
  Voices, Disable Loading Tips, Disable Fire Somersault Effects); 24 are mid-function, including
  **All Stages Appear in Stage Select** (`write32 006773BC`, inside `StageMapExec_Main` 0x677350,
  probably a `setcc bl` forced to `mov bl,1`; its lifted body is 23 KB of optimised ARM64 with
  state kept in registers, so there is no safe native equivalent without the original bytes).
- **Character Select Plus** (Justin113D, no licence file — nothing copied) is ~60 patches, most
  mid-function (`WriteData` nops/short jumps, `WriteJump`s into the middle of functions, a
  `FastFunctionHook` on GoalRing_Main). Its menu cannot run. Its core effect — playing stages as
  other characters — is reproduced instead by redirecting the `Load<Character>` functions (all
  `void(int player)`, cdecl) and wrapping the position lookups (below): the **Characters** screen.
- **Extra Chao Garden** builds a fourth garden in code (its README §8): new texture lists
  (`al_stg_twilight_tex`, `al_toy_tw`) that only its DLL loads, save juggling, a lobby door.
  Without the DLL its `gd_PC` files (music, light, set layout, a `.prs` texture archive the game's
  `.pak` outranks) land on the Neutral Garden — hence music but no textures. The mod list now says
  to leave it off.
- Jiggle DLLs are cosmetic; a native port would need new code calling game functions — possible
  with the helper's wrapper mechanism, not attempted.
- `rea` (the reverse-engineering MCP the user found) wraps Ghidra/IDA/Hopper; the mod manager and
  loader are open source, so there was nothing to reverse there, and the binary that matters
  (`sonic2app.exe`) is the user's.

## Native patch helper (new)

`host/android/native/opensa2_patch.c` → `lib/arm64-v8a/libopensa2patch.so`, freestanding (raw
syscalls, no libc, no exported symbols), built by `build_apk.py` with `--ndk`'s clang or a
system `clang` + `ld.lld`. `NativePatch.java` reads `libsa2game.so`'s `.symtab` (24,037
`sub_<va>` functions), makes a plan (`files/native-patches.txt`), and `Sa2Activity.onCreate`
sets `LD_PRELOAD` + `OPENSA2_PATCHES` before SDL starts the session (the session copies its
environment into the game's; `SA2_` variables are the only ones it drops). In the game process,
before `main`, the helper:

1. finds `libsa2game.so`'s base in `/proc/self/maps` (does nothing in any other program);
2. checks each patched entry's first 16 bytes against the plan;
3. builds trampolines (first 4 instructions relocated — ADR/ADRP rewritten, branches/literal
   loads refused; 99.8% of lifted prologues qualify) and stubs in an anonymous page (RW → RX);
4. writes `ldr x16,#8; br x16; .quad stub` over each entry, taking the page RX → RWX → RX (never
   dropping X, so only `execmem` is needed, not `execmod`).

Plan lines: `R` redirect (stub sets `w1` = target VA, jumps to the target or its trampoline, so
swaps A↔B work), `W` wrapper, `G` the guest-memory base pointer (`sa2_guest_fast_base`), `C`
CurrentLevel/MainCharObj2/CharID offset/invalid level, `T` per-character position tables, `P`
partners. remill `State` offsets used: EAX 0x8A8, ECX 0x8C8, ESP 0x908, EIP 0x9A8, SS base 0x848,
DS base 0x888.

**Character swap wrappers.** `LoadStartPosition` (0x43D8E0, player in ECX), the end position
(0x43DF30, EAX) and the mission 2/3 end (0x43DD50, EAX) pick a table by
`CharObj2Base.CharID` (offset 1). When the stand-in's table lacks the current level and its
partner's has it, the wrapper sets CharID to the partner's for the length of the game's own call.
Alt characters (Amy, Metal Sonic, Tikal, Chaos, Chao Walkers) share their base character's CharID.
Tables are the Steam exe's (`SA2Variables.h`: `SonicStartArray` 0x1746F20 …).

**Tested (emulator, `tools/opensa2_android/emu_patch_helper.py`, unicorn):** the helper runs
against the real release `libsa2game.so`; a refused bad-bytes entry; `sub_442cf0` returning at
once through the ret function (ESP+4, EIP = return address); LoadSonic↔LoadShadow both ways
reaching the other body with identical registers and stack to a direct call; a moved ADRP; and
the game's **own lifted LoadStartPosition** (function table brought up with `sa2_ft_init` +
`sa2_ft_register_generated`, the release's `.text` overlay written into guest memory) — unpatched
it finds nothing for Shadow in City Escape, patched it returns Sonic's start, restores CharID,
returns normally. The Java planner's plan for the real `Codes.lst` + swaps was fed to the helper:
10 of 10 applied, all checks pass.

**Not tested — check on the phone first** (game.log / the session report's "Native patches:"
lines): that Android loads an `LD_PRELOAD` library from the app's lib folder for the game
process (the linker namespace), and that `mprotect` to RWX on the game's code is allowed
(`execmem`). If either fails the helper (or the linker) says so and the game runs unpatched.
Then: Disable Music on → silence; Characters: Sonic → Shadow, play City Escape → Shadow at the
start line; finish a stage → normal results; Knuckles → Tikal in Pumpkin Hill.

## Other changes

- **GameBanana browse/search** (`GbBrowseActivity`, `GameBanana.search/browse`, apiv11
  `Util/Search/Results` and `Game/5855/Subfeed`): newest mods on open, search box, thumbnails,
  tap → confirm → download + install with a progress log; a link/id/`sa2mm:` in the box installs
  directly. Checked live against the API.
- **Texture packs** (`TexturePack.java`): `textures/<name>/index.txt` or `.pvmx` → the game's
  `PRS/<name>.pak` (inf rows: name, gbix, size from the index's `WxH` or the image; flags
  0x80000000; images stored as `<name>\<tex>.dds` whatever the format, as SA Tools does); the
  game's remaining textures kept by position; packs for lists the game lacks are skipped with a
  note.
- **Codes screen** shows under each code that writes the game's program whether it works here.
  `CodeEngine` says "applied at the start by the native helper" for those instead of "skipped".
- **Mod list**: known DLLs explained (jiggle, Character Select Plus, Extra Chao Garden).
- Shady the Hedgehog's model folders build and verify (sshadowmdl 58/58, teriosmdl 76/76, 0
  mismatches against the sources).

## Ideas not done

- A native port of a jiggle DLL: a `W` wrapper around the character display function that runs
  new C code calling lifted functions (push args/return address on the guest stack, call the
  native function with the live State) — the mechanism is in place, the physics is not.
- Stage select: find the ARM64 equivalent of 0x6773BC by lifting a patched `sonic2app.exe`
  through the project's own pipeline and diffing `sub_677350`, then patch that native
  instruction from the plan (an `X <off> <bytes>` plan line would be easy to add).
- Cross-play-style swaps (Sonic in a Knuckles stage) need what Character Select Plus patches
  mid-function (goal ring, emerald manager); not offered.

---

# Third session (2026-10-16): first device results, the story select, Render Fix

**On the phone (Galaxy S25, 0.1.2):** the helper loads (`LD_PRELOAD` accepted, the probe passed)
and patches the game's code (`mprotect` to RWX allowed): game.log showed "1 of 1 patch(es)
applied" and Disable Music, Disable Voices and Disable Loading Tips worked. Data codes (All
Upgrades) work through the code engine. As expected, All Stages Appear in Stage Select, Disable
Omochao (its second half nops 20 bytes inside 0x006C0A90) and All Events Skippable did not, and
Character Select Plus's menu does not run (the Characters screen had not been tried).

**The story select's offset.** The background, title and button hints sat in a 16:9-wide frame
from the screen's left edge (1920 of 2340 px, a black strip on the right); the cards were centred.
Only sub_67c0f0 (the display RF calls "Story Something", 0x0067C21F is its one SOC sprite call,
0x0041DC80) reads HorizontalResolution among the story select's functions, so the helper gained:

- `W` kind 3, laid out at an aspect (`A` line: H and V addresses, 16:9): for the length of the
  call H reads V x 16/9 when the screen is wider, and the runtime's HUD-at-4:3 state is set as for
  its own swap (`U` line: `g_hud43`=1, `g_hud43_swapped`=1 (a 1-byte bool in this build),
  `g_hud43_real`=the real H), so its guards (viewport 0x420210, scissor 0x420390, viewport getter
  0x4200F0, njSetScreen 0x77E980, projection 0x42B230, full-screen background 0x41B0C0) run with
  the real width; all three and H are put back after. Verified in the guard's disassembly
  (`__wrap_sub_420210` reads `g_hud43` at .data+0x8e6f300 and the swapped byte) and in the
  emulator (`layout_checks`).
- `W` kind 4, traced: logs return address, EAX and N stack words of each call while a kind-3
  wrapper is on (48 lines a session). It wraps SOCDisplaySprite (EAX = the sprite, then PosX,
  PosY, Width, Height, pri, ?, ?, U, V, colour) so the next session's game.log says exactly how
  the story select draws, to stretch its background over the whole width (or adjust further).

Expected on the phone: the story select's frame centred with equal strips both sides. **Not yet
seen on the device** — check the screenshot and game.log's `laid out at 16:9` and `trace` lines.

**SA2 Render Fix** (GameBanana 452445) was tried by the user: the game stopped right after
loading its `PLCOMMTN.PRS` (trap: a read outside guest space). RF's models and motions are
loaded by its own DLL code (`rfpl_mdlprs.c` and friends), and its menu fixes (rfm_menus.c: fades,
the stage map's stretching and fill) do not touch the story select anyway. `ModManager.Mod.blocked()`
now leaves it out at Play with that explanation.

---

# Fourth session (2026-10-16): the story select, round two (0.1.4)

**On the phone (0.1.3):** game.log said "laid out at 16:9: width 2340 -> 1920 ..." (so the
display ran under the swap), but the story select looked exactly as before, and the SOC sprite
trace logged nothing: sub_67c0f0's one direct SOCDisplaySprite call did not run. So the frame is
not laid out from HorizontalResolution inside that function. What static reading showed since:

- The draw primitives (SOC 0x41DC80, njDrawSprite2D 0x77D0B0, njDrawSprite2D_0 0x66EF40,
  njDrawTexture 0x77F510, njDrawTextureEx 0x77F6B0, njDrawPolygon 0x77F7F0, njDrawPolygon2D
  0x490FA0) never read H; the functions that do are exactly the runtime's HUD-guarded ones
  (0x41B0C0, 0x42B230, 0x4200F0, 0x420210, 0x420390, 0x77E980). So a layout-time swap cannot
  cancel itself out at draw time: whatever draws the frame does not take its x from H in the
  code the swap covered.
- The game keeps a second copy of the screen size (HorizontalResolution_Float 0x2670328 /
  VerticalResolution_Float 0x267032C in the mod loader's headers), read by 0x458EE0 (a 3D
  projection set-up with 24 callers) and 0x42C390; njDrawSprite2D reads 0x2670320. If either
  still holds the launcher config's 1280x720 (16:9!) while the screen is 2340x1080, a layout
  from it would be exactly a 16:9 frame. `vars` lines in game.log now show both.
- sub_67c0f0 calls 0x666F90 (shared by 37 menu functions, it ends in njDrawTextureEx via
  0x6B5A60), the viewport (0x420210), njSetScreen (0x77E980) and SetLight (0x487060): it draws
  a 3D part too.
- Render Fix (now readable: github.com/shaddatic/sa2b-render-fix) has no story select layout fix;
  its menu code (rfm_menus.c) fades the SOC sprites and fixes the stage map. Its 2D maths
  (rj_sprite.c, rj_polygon.c) confirms the 2D space: 640x480 centred, a wide screen showing
  x from -(ratio-1)*320 to 640+(ratio-1)*320.

**0.1.4 is a diagnostic build with one more experiment:**

- the story select's main (storyMainExec 0x67BDF0) is also laid out at 16:9 (the frame may be
  placed there and drawn later);
- helper kinds 7 (memory at a pointer argument: register State offset or stack index, +0x1000 to
  follow the first word) and 8 (a screen function whose first run opens the log), a `V` plan line
  (guest memory logged when the log opens and once the screen has settled), per-function budgets
  (16 lines as the screen opens, 48 after it has been up 4 s), `@ms` and `in <screen function>`
  on every line, re-opening when another screen function first runs 3 s or more later, and a
  spinlock so lines from different threads do not mix (`-mno-outline-atomics` keeps it inline);
- traced: SOC sprite, full-screen background, viewport, screen rectangle, 0x4200F0, 0x42B230,
  0x666F90, njDrawSprite2D (both), njDrawTextureEx, njDrawTexture, njDrawPolygon,
  njDrawPolygon2D, njSetScreen, and the dispatch (first call of each address).

**What to do with the log:** find the lines whose geometry is the frame (a background quad or
sprite spanning 0..1920 px, or -200..653 in 640x480 units, at y 0..480), read their `ret`
(the guest call site) and `in`, and fix it there: a wrapper that shifts x by
(H - V*16/9)/2 for those call sites (or stretches the background to the full width), or, if the
`vars` show the 1280x720 copy, keeping that copy at the real size while the story select runs.

---

# Fifth session (2026-10-16): the menu offset found and fixed (0.1.5)

**What the 0.1.4 log and a video showed:** the offset is on every menu with a full-screen
picture (story select, Settings) and on the stage map, with the HUD option and the experiment on
or off, at native and at 720 lines. Everything is placed right: the title, the cards and the
button hints come out exactly where the centred 640x480 layout puts them (measured on a video
frame: card edges at 720/1078 and 1282/1640 px for x 120..279 and 370..529 at scale 2.25, offset
450). The backgrounds are requested across the whole screen: SOCDisplaySprite from 0x668227
with x -200, width 1040, height 480, u 0..1.015625, v 0..0.9375. The viewport the 2D draws use
is the full screen (0x420210 maps 640x480 to H x V: its constants at 0x9057B0/0x9057B8 are 480
and 640, read from the user's own exe). The background's right edge on screen is exactly 1920 px.

**Why:** u 1.015625 = 1040/1024 and v 0.9375 = 480/512: the background is a 1024x512 texture
drawn one texel a unit. Its picture is 16:9, 853.33x480 (the PC port's menus were made for
16:9); past x = -200 + 853.33 = 653.33 (1920 px) the empty rest of the texture shows. The stage
map (DisplayStageMap 0x675DF0) draws its map at x = the screen's left edge with the fixed width
853 (.rdata 0x907434); emulated with H 2340 it draws at x -200, with H 1920 at -106.67.

**How it was worked out:** the user's sonic2app.exe (kept local: .rdata and .data read straight
from the file; the .text is SteamStub 2.1-encrypted and was not needed) backed the emulator's
guest memory, so the game's own lifted code ran with its real constants (`/home/claude/re/emu/`
scripts, not in the repo: fake device object, skipped texture and shader setters).

**0.1.5:**
- helper kind 9 (WRAP_FILL) on SOCDisplaySprite: a call that spans the screen (x = 320 - W/2,
  width = W, W = 480 H/V > 853.33) a texel a unit ((u1 - u0) x 1024 = width) gets u1 = u0 +
  853.33/1024 and v cropped about its centre by width/853.33: the picture fills the screen, not
  stretched. Arguments put back after the call. Tested in the emulator (`fill_checks`).
- kind 3 (16:9 layout) on StageMapExec_Display 0x6777C0 and StageMapExec_Main 0x677350: the
  map, its icons and text bar are laid out as on a 16:9 screen, centred, black bars both sides.
- the story select layout wrappers, Menu_StorySelect gate and all traces are gone from the plan
  (the helper keeps kinds 4-8 for the next screen that needs working out). Settings: "Menus on wide
  screens: backgrounds fill the screen, stage map centred".

Not seen on the device yet; Render Fix's stage map changes (purple fill, icons moved left) are a
different look and not copied.

**Also in 0.1.5: merged texture archives.** Sonikko, Shady the Hedgehog and Character Select
Plus all ship stageMap.pak, each CSP's 25-texture archive with only their own character's portrait
changed (stg_stagechr_sn, stg_stagechr_sd); the loader keeps only the last one, so Sonikko's and
Shady's portraits were lost. `Pak.merge` (used by `ModManager.applyMods` for any .pak two or more
enabled mods replace, when the game's own is there) writes the last mod's archive with each
texture it leaves as the game's taken from the latest earlier mod that changed it (.inf row's size
too, place and global index kept). Checked on the three real archives (CSP's standing in for the
game's). The user also reported a loading-screen "spin ball" in Shadow's colours instead of
Sonikko's light blue; which texture or model that is was not found yet (asked for a screenshot).

# Sixth session (2026-10-16): 0.1.6

- On the phone 0.1.5 fixed the menus (story select, stage map, Settings). The loading screen's
  grey ball is not a texture: every picture in the game's PRS and SOC folders was checked
  (NowLoading.pak is the gold ring and text, model.pak the Chao departure machine, the left strip
  screenEffect.pak); it is drawn by the game's code, the same on PC with any mod. Left as is.
- Sonic's head on Sonikko in cutscenes: events swap in their own face models; Sonikko ships none.
- The slow stretch after a save loads: 30-60 frames a second dropping to ~18 for ~12 s, only with
  mods; the player's texture counters show ~90 MB uploaded and mip levels generated on the CPU.
  Sonikko's sonictex.pak (75 MB) and ssonictex.pak (104 MB) hold uncompressed 2048x2048 DDS with
  no mipmaps. `TexSlim` (new) halves those to at most 1024 and adds the mip chain at Play for any
  archive over 8 MB (Settings: "Lighter mod textures", default on); manifest gains largeHeap.
  Not yet measured on the phone.
- Modern SA2: The Trial is DLL-driven (physics, Rouge, menus); not workable here.


# Seventh session (2026-10-16): 0.1.7, random characters and Amy's Spin Dash

- **Characters → Random each stage.** Each time the game loads a story character it loads a
  random member of that character's play-style group instead (Sonic, Shadow, Amy, Metal Sonic;
  Knuckles, Rouge, Tikal, Chaos Zero; the walkers). Helper kind 10: every member's Load function
  is wrapped, and the wrapper calls a random member's original body (getrandom seed, xorshift),
  logging `random: <pc> played as <va>`. Plan lines `Z <off> <guest va>` give each member's
  guest address; the position wrappers get each pair as partners (a second `P` line per id is a
  second partner). Emulator: 40 LoadSonic calls reached Amy 12, Shadow 10, Metal 10, Sonic 8.
- **Amy and Metal Sonic's Spin Dash.** They run Sonic's action code, which starts a Spin Dash
  only when `CharObj2Base.CharID2` (offset 3) is Sonic (0) or Shadow (1): the lifted
  `sub_723dd5` (the chunk holding the spin dash start, 0x723E11 is its charge delay) and
  `Sonic_Somersault` (0x723880, rolling out of a somersault) each read CharID2 and skip
  `Sonic_StartSpindash` (0x7234C0) for anyone else. Found by scanning the lifted Sonic code for
  CharID2 reads (`add w1, wN, #3; bl read8`) followed by the `<= 1` flag pattern; the other reads
  pick sounds, effects and animations. Both checks are mid-function, so helper kind 11 wraps the
  two functions and, while they run, a player whose CharID2 is an `M <off> <from> <to>` line's
  `from` reads `to` (Amy 8 → 0, Metal 12 → 1), put back after. Characters: "Amy and Metal Sonic
  can Spin Dash" (default on; used only when a swap or random play can bring them in). Amy's own
  somersault (the hammer swing) was already there; the Fire Somersault effects with upgrades are
  a separate known crash (the "Disable Fire Somersault Effects" code works natively). Not yet
  tried on the phone: sounds inside those two functions may be Sonic's or Shadow's.

# Eighth session (2026-10-16): 0.1.8

- The random pick worked on the phone (Shadow and Amy seen in City Escape). The restart after a
  death runs `sub_43c970` (part of RestartInitStageRuntime), which calls LoadCharacters, so the
  character is reloaded and random picks again. New: a `K <CheckpointRecords va>` plan line; when
  a reload is in the same level as the last pick and CheckpointRecords[0].level is that level (a
  checkpoint was touched), the last pick is kept (`random: ... kept as ...`).
- "Anyone in speed stages" (Characters, under Random): the hunt and mech members' kind-10 lines
  carry bit 2 of the fourth field, which also puts them in group 0, so LoadSonic/LoadShadow draw
  from all twelve. The position wrappers fall back to the first character whose table has the
  level when neither the stand-in's nor its partners' do (Knuckles in City Escape starts at
  Sonic's start). Emulator: 240 LoadSonic calls reached all twelve; LoadKnuckles stayed in the
  hunt group; a checkpointed reload kept its pick 20 of 20. Not tried: hunters' emerald managers
  and walkers on speed-stage objects (the board, rails).

# Ninth session (2026-10-16): 0.1.9

- On the phone (0.1.8): the walkers work in City Escape; Tikal and Chaos give a black screen with
  the emerald radar (their loaders bring the hunting stage's setup); a crash was seen (game.out
  traps: reads at 0x4 / 0xC / 0x6B6E617A, also once with Metal Sonic in 0.1.7, and once in a
  session with no patches, so not tied to one character yet). The hunters are out of the speed
  group again (bit 2 now only on the mech members).
- The pause menu's Restart kept the character: correction to the eighth session, a death or a
  restart does not reload the character. The GameState switch's cases 0x43BA75 (teardown,
  0x43CE20, the level-start init) and 0x43BAAF (teardown, RestartInitStageRuntime, which reads
  CheckpointRecords, LoadLevelManager) both bump TimesRestartedOrDied and keep the objects;
  0x43BB60 fades, tears down and sets GameState 5, whose case 0x43AA00 runs 0x43C970
  (DeleteObject_ on the characters, LoadCharacters, InitCharacterSound), InitCurrentLevelAndScreenCount
  and LoadLevelManager, then GameState 7. Helper kind 12 wraps both restart cases and, unless a
  checkpoint was touched (CheckpointRecords[0].level == CurrentLevel), runs 0x43BB60's body instead
  (`Y <off> <va>`), with a flag that makes the next random pick a new one. Emulator-checked with
  stand-ins; not yet tried in the game: which case is the pause Restart and which the death is a
  guess, so both take the checkpoint rule (a Restart after a checkpoint keeps the character).

# Tenth session (2026-10-16): 0.1.10

- On the phone (0.1.9): the restart re-pick works (`restart: 0x43baaf goes the way of 0x43bb60`).
  Crashes, each reproduced: the Dark Chao Walker (0x7412F0) as it loads (guest read at
  0x6B6E617A, same stack twice); the Chao Walker when it shoots (read at 0x4, from an object's
  main via RunObjectIndex); Amy when she spin dashes with the 0.1.7 patch.
- The Amy/Metal Spin Dash wrappers are gone from the plan and the Characters screen (Sonic's spin
  dash and ball code reads per-character data Amy's set lacks; not worth chasing blind).
- Kind 11 gained sets (M's optional fifth field; the wrapper's fourth field picks one, bit 4 also
  zeroes the player's Upgrades at CharObj2Base+0x24). Set 1 wraps the walker's main 0x741530 (where
  Character Select Plus forces the branches at 0x741690 and 0x7416DC, and the scan finds CharID2
  compared with 7): Chao Walker 13 reads as 6, Dark Chao Walker 14 as 7, upgrades cleared (the
  code list's "Fix Chao Walker Cannon Upgrade Crash" is the upgraded-shot draw, 0x75D914, which a
  cleared upgrade set never reaches). Emitted whenever random is on or a swap picks a Chao Walker.
  Emulator-checked with stand-ins only; whether it is the main that crashed is not proven.

# Eleventh session (2026-10-16): 0.1.11

- On the phone (0.1.10): the Chao Walker no longer crashed shooting; in City Escape it got stuck
  at the dash panels. The user pointed at the SA2 Randomizer (GameBanana 48932, a DLL mod). Its
  Options/setNNNN_s/0-4.bin are per-kind object layouts; Rando.dll (0x1004FB78) picks the file:
  0 for Sonic/Shadow, 1 Knuckles/Rouge, 4 Tails/Eggman on foot, 3 its id 9, 2 everyone else (the
  walkers). Its gd_PC set files are about its variant 0.
- StageLayouts (launcher): with random walkers on and that mod installed, stages for each
  Options setNNNN_s/_hd_s folder the usual file (the overlay's, else the game's) and 2.bin in
  files/modbuild/layouts, writes `L <level> <0|2> <file> <overlay path>` lines, and before the
  next Play's mods are applied deletes the overlay files it may have overwritten.
- Helper kind 13 on LoadStageSETFile (0x488F60, called by each stage's init; CityEscape_Init
  0x5DCD50 loads character animations, so the characters load first): as a listed stage's layout
  loads, the class of the group-0 pick (the walkers' own group is 2) chooses which file is copied
  over the overlay path (raw openat/read/write). If the layout loads first, the pick is made there
  and LoadSonic takes it. A forced restart picks among the same class (the layout stays).
  Emulator-checked for both orders, and that a stage with no lines is left alone.

# Twelfth session (2026-10-16): 0.1.12

- On the phone (0.1.11): the stage layout switch works (`layout: stage 0xd set out for a walker
  (2 of 2 file(s))`, then as usual for Amy and Sonic). The Dark Chao Walker still crashes on load
  (same trap at 0x6B6E617A, twice); the user asked to leave it out and to put Knuckles, Rouge and
  Eggman in speed stages. One Amy session crashed with a read at 0xC from 0x0042CBA7's caller, the
  same kind of trap as a session in 0.1.x with no swaps, so not tied to the swap yet.
- Random leaves the Dark Chao Walker out (no kind-10 line); Knuckles and Rouge carry bit 2.
- Layout classes are now the pick's own group: 0 speed (the usual file), 1 hunter (Options 1.bin),
  2 walker (2.bin). StageLayouts stages 1.bin too; the helper takes L lines of class 0-2, and a
  forced restart picks among the class the layout was set out for.

# Thirteenth session (2026-10-16): 0.1.13

- On the phone (0.1.12): Tails's and Eggman's walkers play City Escape; Knuckles gave the same
  black screen with the emerald radar as Tikal and Chaos. Character Select Plus does two things
  for hunters outside the hunting stages: it nops the LoadEmeraldManager call in all four hunter
  loaders (0x728141, 0x728491, 0x7288B7, 0x728B64) unless the level is a hunting one, and hooks
  Knuckles_LevelBounds (0x737B50, usercall EAX/ECX, returns EAX) to return 0 outside the hunters'
  stage-select levels.
- Helper kind 14 (level gate): `H <level>` lines list the hunting levels (5 7 8 16 18 25 26 32 41
  44 46 49 50 59), `J <off> <va>` the lifted bare ret (0x6FE430); elsewhere the wrapped function
  returns at once through it (fourth field 1: EAX 0 first). Wrapped: 0x73AA70 and 0x737B50, when
  random's speed stages take Knuckles and Rouge. Emulator-checked (pops the return address, EAX 0
  in City Escape; the body runs in Pumpkin Hill). Whether these were the black screen's cause is
  not proven yet.

# Fourteenth session (2026-10-16): 0.1.14

- On the phone (0.1.13): Knuckles and Rouge play City Escape with the level gates (the black
  screen is gone). Tikal and Chaos, which gave the same black screen in 0.1.8, are back in the
  speed group (every hunter and walker member but the Dark Chao Walker carries bit 2).

# Fifteenth session (2026-10-16): 0.1.15

- Amy's Spin Dash again, following MainMemory's SA2-Amy-Metal-SpinDash (the user's link; its
  source: nop at 0x723E1F, WriteData<2>(0x72386C, 2), and the spin dash particles' texture list
  chosen by CharID2 at 0x757BB2 and 0x757D52). The lifted particle code (sub_757b30) reads CharID
  (offset 1): 0 gives SonEff (0xA08B94), anything else ShadEff (0xA08D94); Amy is CharID 0 with
  AmyEff (0xA08F94) loaded, so SonEff had no textures, which is the 0.1.7 crash. Now: kind 11 set 0
  on the spin dash chunk only (not Sonic_Somersault), kind 15 (S lines) trading SonEff/AmyEff and
  ShadEff/MetEff while Amy or Metal Sonic plays, around 0x757B30 and 0x757CD0, and kind 16 (Q line)
  writing 2 to EDI+0x36A after 0x723851 (lifted: write16 [edi+0x36A], 0). MAX_WRAPS 24 -> 48.
- Characters: every character in every slot's picker (testing), Super Sonic (0x49A950, CharID 9)
  and Super Shadow (0x49ABB0, CharID 10) added (void loaders; LoadSonic's stack argument is left
  for the caller). Chosen cross-style swaps get the hunters' level gates, the stage layouts (the
  setload wrapper takes the class from MainCharObj2[0]'s CharID when there is no random group) and
  the C line. Tails and Eggman on foot are not offered (their loaders take the player in EAX).
- The user's "Nazo & Seelkadoom" mod crashed after the stage select. Its sonicmdl.ini names the
  upgrade models (13, 14, 25-27) by labels none of its files has; the loader (LoadMDLFile_ri) and
  ModelFolder both leave them out. ModelFolder now takes the game's model file and keeps the game's
  model for such indices (the game's big-endian MDL appended whole, the reached objects' and chunk
  models' pointers moved). For the user, a Nazo-only repack without the Seelkadoom files, the event
  AFS and the edited plcommtn.prs, with the stage map's Shadow portrait put back (not committed).

# Sixteenth session (2026-10-16): 0.1.16

- On the phone (0.1.15): Super Sonic and Super Shadow crash in City Escape (guest read of 2 bytes
  at 0x178 under a call from 0x0042416D, both forms). The SA2 Randomizer, which plays Super Sonic
  as its character 9, first runs Rando.dll 0x1003D950 outside Final Hazard (level 0x42): byte
  patches nopping the LastBossPlayerManager_Load call in LoadSuperSonic (0x49AB61) and
  LoadSuperShadow (0x49ADC5), two in Super_DispDelay3 (0x49CD64, 0x49CD6D), data writes at 0x6DF2DA
  and 0x6DF067, `ret` at Exec_Load (0x6EDEE0) and four bytes in CheckBreakObject (0x46ECE7-0x46ED01).
- The whole-function ones are reproduced: kind 14's H lines take a set (`H <level> [set]`, the
  wrapper's fourth field bits 4-7); set 1 is Final Hazard; 0x49ECE0 (called only by the two super
  loaders) and 0x6EDEE0 return at once elsewhere when a super form can be played. The rest are
  mid-function and not done; whether these two were the crash is to be seen.

# Seventeenth session (2026-10-16): 0.1.17

- 0.1.16 on the phone: the LastBossPlayerManager gate applied (`level gate: 0x49ece0 skipped in
  stage 0xd`); both super forms still crash, now with a one-byte read at 0 from an object's main
  (return 0x0046F941 in RunObjectIndex), three times.
- Rando.dll also writes a 5-byte nop at 0x49B12E in SuperSonic_Main's chunk 0x49B106: the lifted
  chunk shows that call's target, 0x469050 (PResetPos, called by 151 functions, every character's
  main). Helper kind 17 (character gate) returns at once from it while a player's CharID or
  CharID2 is 9 or 10, outside H set 1 (Final Hazard). Its other super patches (Super_Delete
  0x49BF8B, Super_DispDelay3, 0x49AACB-0x49AAD0 in LoadSuperSonic, 0x6DF2DA, 0x6DF067,
  CheckBreakObject) are inside functions and not done. Super forms are out of random until they
  stop crashing; chosen swaps still offer them.

# Eighteenth session (2026-10-16): 0.1.18

- 0.1.17: the super forms still crash (`character gate: 0x469050 skipped`), a jump to address 0
  from an object's main. Left there: their Final Hazard objects are not reproducible.
- The user found Sora's SA2 Super Sonic (GameBanana 340288; source github.com/Sora-yx/SA2-Super-Sonic):
  a 4,100-line DLL that transforms Sonic in place. Its TransfoSuperSonic: CharacterModels[328] from
  LoadMDLFile("SSONICMDL.PRS"), TextureList = LoadCharTextures("SSONICTEX"), an AnimationInfo list
  whose entries use model 328 with Sonic's own motions (ball 30 keeps model 6), Super physics,
  invincibility; its own Sonic_Display draws the ball with its ss_efftex.
- Native first cut (kinds 18/19, X/F plan lines, `guest_call`): SonicCharObj2 offsets from the
  lifted Sonic_Display: AnimInfo.Current +0x178, Animations +0x198, TextureList +0x394; the name
  strings are the game's own (.rdata 0x8BA9F0..0x8BAA4C). The animation list (static, per
  character) is changed in place once. Emulator-checked with the bare `ret` standing in for the
  game's loaders (it hands EAX back). Super Sonic and Super Shadow stay out of random.
- 0.1.18 on the phone: Super Sonic on City Escape with Sonic's moves ("Super form works").

# Nineteenth session (2026-10-17): 0.1.19

- The user asked for their own super mod: transform at 50 rings, with Nazo as a green "Chaos
  Nazo". Kind 18 now transforms (plan line `B <button> <rings>`): Y in a jump with 50 rings,
  super physics (X lines' fifth field, the PhysicsArray index 9/10) and invincibility, a ring a
  second, back at none or on Y in a jump; everything it changed (textures, the shared animation
  list's body model, physics, the Powerups bit) is put back. Without a B line ("Always super") it
  is the 0.1.18 behaviour. Emulator-checked (no button, too few rings, transform, drain, back,
  again, back on the button).
- Chaos Nazo add-on (delivered, not committed): `gd_PC/ssonicmdl/` with Nazo's 0.sa2mdl as index
  328 (the game's ssonicmdl.prs fills 329-352) and `PRS/ssonictex.pak`, Nazo's sonictex with the
  blue hues (185-265 degrees) turned emerald green and the inner names and GBIX numbers changed
  (Sora notes the GBIX texture cache: shared numbers would give back the loaded blue textures).
- Not done: the aura (Sora's draws its own models with ss_efftex in a task of its own), music,
  flight, the transformation animation.

