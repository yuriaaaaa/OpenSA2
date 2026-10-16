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

