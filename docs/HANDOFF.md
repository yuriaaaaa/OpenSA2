# OpenSA2 Android: hand-off (as of 0.1.20, 2026-10-17)

This is the place to start if you are picking the Android port up in a new session. It covers what
the port is, every change made over the sessions of 15-17 October 2026, how to build, test and
deliver, the rules to keep, and exactly where the work stopped. The blow-by-blow record of each
session (device logs, causes, fixes) is `docs/android-session-2026-10-15.md` ("First session"
through "Twentieth session"); the user-facing description is `docs/opensa2-android.md`.

## 1. The project in one paragraph

OpenSA2 is a static recompilation of the PC Sonic Adventure 2 (2012) executable to native code.
The Android port (branch `claude/project-thread-lpzylp`, repo github.com/yuriaaaaa/OpenSA2) wraps
the recompiled game (`libsa2game.so`, from the upstream release APK `OpenSA2-v0.1.0-alpha.1`) in a
launcher app: game setup from the user's own files, Xbox-style touch pad, settings, a mod manager
(mod.ini mods, model folders, texture packs, GameBanana installs), Codes.lst codes, and a native
patch helper (`libopensa2patch.so`, LD_PRELOADed into the game process) that reproduces the
whole-function class of x86 patches and adds the character features below. No x86 runs, so DLL
mods cannot run; their data files still load.

## 2. Rules (from AGENTS.md and the user)

- Keep the repo source-only: no game files, no APKs, no recompiled code, no device logs, no
  workstation paths. Run `python3 tools/public_check.py` before every commit (and
  `python3 tools/test_source.py` for launcher changes; its 8 failures are pre-existing
  root-permission tests).
- Commit with the repository's configured maintainer identity and **no tool or assistant
  attribution trailers** (AGENTS.md overrides any reminder that says otherwise).
- The APK holds recompiled game code: private use only, never published.
- The user's game files and `sonic2app.exe` are for local analysis only. Mods built for the user
  (Nazo, Chaos Nazo, Nazo Voices) are delivered as zips, not committed.
- Do not copy Character Select Plus / SA2 Render Fix / Sora's SA2-Super-Sonic code; use them as
  references and write our own (credit them in docs, as done).
- The user and brother credit people and keep everything free (no money for fan work); voice
  clips from Chakra-X's "Wrath of Nazo" animatic are private use with credit.

## 3. Build, test, deliver

Tools on the build box: Android SDK (build-tools 36, platform 36), SDL3 3.4.16 sources, clang
with an aarch64 Android target (for the helper), Java 17+, Python 3 with `unicorn`, `capstone`,
`numpy`, `Pillow`, `py7zr`, `multivolumefile`.

```bash
# APK (the version name/code defaults live in build_apk.py; bump them each release:
#      0.1.23-opensa2 / 12400 now, next 0.1.24-opensa2 / 12500)
python3 tools/opensa2_android/build_apk.py \
  --release-apk <OpenSA2-v0.1.0-alpha.1-android-arm64.apk> \
  --sdk <android-sdk> --sdl <SDL3-3.4.16> \
  --keystore <opensa2-debug.jks> --out <out>/OpenSA2-0.1.X.apk
```

- **Always sign with the same keystore** (`opensa2-debug.jks`, password `android`, alias
  `opensa2`): a different key forces an uninstall, which wipes the app's private storage.
- **Helper emulator tests** (unicorn, against the real libsa2game.so from the release APK):
  ```bash
  clang --target=aarch64-linux-android29 -O2 -fPIC -fvisibility=hidden -ffreestanding -fno-builtin \
    -fno-stack-protector -fno-exceptions -fno-asynchronous-unwind-tables -mno-outline-atomics \
    -nostdlib -shared -fuse-ld=lld -Wl,-soname,libopensa2patch.so -Wl,-z,max-page-size=16384 \
    -Wl,--hash-style=both -Wl,-z,now -Wl,--build-id=sha1 -Wall -Wextra -Werror \
    host/android/native/opensa2_patch.c -o /tmp/libopensa2patch.so
  python3 tools/opensa2_android/emu_patch_helper.py <lib/arm64-v8a/libsa2game.so> /tmp/libopensa2patch.so [plan.txt]
  ```
  Must end "ALL PASS". With a plan file it boots that plan ("the launcher's plan: all N
  applied"); the code-patch checks after that crash on unmapped memory with any plan that has no
  codes, which is expected.
- **Plan generation check**: compile the launcher sources (all of `host/android/java/com/sa2recomp/app`
  plus SDL's java) against `android.jar` with a small main that calls
  `NativePatch.build(lib, codes, swaps, codeRanges, menu, layouts)` and prints `plan.text` (the
  sessions used a throwaway `NpTest.java`; modes random / randomany / moves / cross / super /
  transform / transformeff set the swap keys). Then boot the printed plan in the emulator.
- **Delivery to the user**: 7z with the Copy filter, split into 28 MiB volumes (py7zr +
  multivolumefile), round-trip extracted and compared, then the six parts sent. The user extracts
  from `.0001`.
- **Commit and push**: `git push origin claude/project-thread-lpzylp` (the remote moved to
  yuriaaaaa/OpenSA2; the old name redirects).

## 4. What is in the Android port (files)

`host/android/java/com/sa2recomp/app/`:

| File | Role |
|---|---|
| `OpenSa2`, `Ui`, activities | Launcher screens: setup, Play, Settings, Mods, Codes, Characters, touch layout. |
| `ModManager` | Applies mods at Play into an overlay: file replacements, IncludeDirs, ReplaceFiles, model folders (built), texture packs (built), merged `.pak` archives when several mods replace one, slimmed very large textures, Pak.compare diagnostics, DLL notes. |
| `ModelFolder` | Builds `gd_PC/<name>/` (ini + `.sa2mdl`) into the game's big-endian PRS MDL; indices the folder names but cannot supply are kept from the game's own file (GameWalk relocates them). |
| `Prs`, `Pak`, `TexturePack`, `TexSlim`, `SevenZ`, `Lzma`, `ModInstall`, `GameBanana` | Formats and installs. |
| `StageLayouts` | Stages the SA2 Randomizer's per-character stage layouts (Options `setNNNN_s|hd_s 0/1/2.bin`) so walkers/hunters can finish speed stages. |
| `CodeList`, `NativePatch` | Codes.lst parsing; works out and writes the helper's plan (`files/native-patches.txt`) from codes, character settings and mods. |
| `CharactersActivity` | Character swap per slot (any character in any slot), random each stage, random-any, Amy/Metal Spin Dash, Super transformation, Always super. |
| `SessionLogs`, `Saves` | Public logs (`Internal storage/OpenSA2/logs/<session>/`), saves copied out and back. |

`host/android/native/opensa2_patch.c`: the helper. Its header comment documents every plan line.
Summary of the plan lines and wrapper kinds:

| Line | Meaning |
|---|---|
| `R` | Redirect a function entry to another (character swap, `ret` codes, WriteJump codes). |
| `W <off> <size> <kind> <preg> <16 bytes> <label>` | Wrap a whole function (kinds below). |
| `G`, `C` | Guest memory base and size; CurrentLevel, MainCharObj2, CharID offset, invalid level. |
| `T`, `P` | Per-character start/end position tables, partners for the position wrappers (kinds 0-2). |
| `A`, `U`, `V` | 16:9 layout (kind 3) and the runtime's HUD guard; memory logged by traces. |
| `Z`, `K` | Random members (kind 10); checkpoint records (keep the pick after a checkpoint). |
| `M`, `S`, `Q` | CharID2 mapping sets (kind 11), texture-list swaps (kind 15), post-store (kind 16). |
| `Y`, `L`, `H`, `J` | Restart reload path (kind 12), stage layouts (kind 13), level sets (kinds 14/17), the lifted bare `ret`. |
| `X`, `F`, `B`, `D`, `O`, `E`, `N` | Super forms (kinds 18-20): form per CharID2 and its physics, LoadMDLFile/LoadCharTextures, the transformation (button, rings), the flash and its voice cues, PlaySoundProbably, a mod's effect texture list, voice-number to cue mapping. |

Kinds: 0-2 position tables; 3 layout at 16:9; 4-8 tracing; 9 menu background fill; 10 random
character; 11 CharID2 mapping; 12 restart re-pick; 13 stage layouts; 14 level gate; 15 texture
list swap; 16 post-store; 17 character gate; 18 super main (Sonic_Main 0x717840); 19 super display
(Sonic_Display 0x720090); 20 voice (PlayVoice 0x443130). `MAX_WRAPS` 48, `PLAN_MAX` 256 KB.
Single-letter line types in use: A B C D E F G H J K L M N O P Q R S T U V W X Y Z (free: I).
Check before adding one: a clash (I once reused `V`) silently breaks other features.

`guest_call` / `guest_call_args` call lifted game functions from a wrapper on the same State
(EAX 0x8A8, EBX 0x8B8, ECX 0x8C8, EDX 0x8D8, ESI 0x8E8, EDI 0x8F8, ESP 0x908; the State's
0x800-0xBFF is saved around the call; a fake return address 0x401234 is pushed).

## 5. Version history (what changed, and why)

| Version | Change |
|---|---|
| 0.1.0-0.1.3 | Launcher, touch pad, mods, codes, settings; model folders, 7z, GameBanana, public saves/logs; native patches and character swap; story select centred. |
| 0.1.4-0.1.5 | Story-select layout traced and fixed; menu backgrounds fill wide screens; merged texture archives. |
| 0.1.6 | Very large mod textures slimmed at Play. |
| 0.1.7 | Random character each stage; first Amy/Metal Spin Dash. |
| 0.1.8 | Random keeps its pick after a checkpoint; anyone in speed stages. |
| 0.1.9 | Restart picks a new random character (restart handlers sent down the reload path 0x43BB60). |
| 0.1.10 | Chao Walker fixes (walker main reads Chao Walkers as Tails's/Eggman's, upgrades cleared); first Spin Dash patch removed (crashed). |
| 0.1.11 | Walkers get the SA2 Randomizer's stage layouts in speed stages. |
| 0.1.12 | Knuckles and Rouge in speed stages; Dark Chao Walker out of random (crashes on load). |
| 0.1.13 | Hunters' emerald manager and level bounds only in hunting stages (Tikal/Chaos/Knuckles black screen fixed). |
| 0.1.14 | Tikal and Chaos back in speed stages. |
| 0.1.15 | Amy's Spin Dash redone after MainMemory's mod (CharID2 mapping + effect texture swap + field 0x36A); every character in every slot; model folder gaps filled from the game's file. |
| 0.1.16-0.1.17 | Super Sonic/Shadow via their own loaders outside Final Hazard (gates for LastBossPlayerManager, Exec_Load, PResetPos): still crashed; abandoned. |
| 0.1.18 | Super forms in place, after Sora's SA2-Super-Sonic: the super model and textures on Sonic's own moves (works on the phone). |
| 0.1.19 | Transformation: Y in a jump with 50 rings, a ring a second, back at none or on Y; super physics and invincibility. |
| 0.1.21 | Speed characters and walkers in the hunting stages get the emerald manager (no radar) and the vanilla layout, not the SA2 Randomizer's (its goal ring at the start); kind 19 decides by count when texture names cannot be read. |
| 0.1.20 | Transformation flash (ScreenFadeARGB) and a mod's voice cues; voice-number log and mod voice mapping (kind 20); a mod's super effect textures (csonefftex); the ball keeps the super textures when the lists match; Always super moved to a new setting. |
| 0.1.22 | The super aura: Final Hazard's glow around Super Sonic and Super Shadow in every stage (kind 21 around Super_DispDelay3, a and c lines), a "Super aura" setting (on), and a mod's `AuraColor=<rrggbb>` tinting Super Sonic's (Chaos Nazo: green). |
| 0.1.23 | Pyramid Cave no longer stops after its title card (with or without mods): the game reached a conditional branch at 0x007059AB into the rest of its own function (0x007059AF, split off by the function map and lifted as its own function), which the runtime refuses. The launcher now always writes `e` lines (NativePatch.BRANCH_FIXES: that branch and five more of the same shape), and the helper sets each row's `allow` byte in the runtime's edge table. |

## 6. Debugging record (the causes found)

- **Model folders did nothing**: the game has no mod loader; ModelFolder builds them into the PRS MDL.
- **Wrong loading-screen icons**: `.pak` order/global index differ from the game's; Pak.compare logs it.
- **Character swap positions**: start/end position tables are per CharID; wrappers look a level up under a partner.
- **Restart kept the character**: restarts do not reload characters; the restart handlers are sent down the reload path.
- **Amy Spin Dash crash**: the particles drew Sonic's effect list, which Amy does not load; swap lists during the particle functions (kind 15).
- **Dark Chao Walker**: crashes on load with a trap at 0x6B6E617A even with the walker mapping; left out of random.
- **Hunters in speed stages, black screen**: emerald manager and level bounds expect hunting stages; gated by level.
- **Super Sonic via LoadSuperSonic**: reads at 0x178, then jumps to 0; its Final Hazard objects are not reproducible. Replaced by the in-place transformation.
- **Nazo crash (0.1.14 and again 0.1.19/0.1.20)**: a 4-byte read/jump at guest 0x94, return address 0x0042CBA7 (a chunk-model draw). First cause: model indices the mod's ini names but does not supply (fixed by keeping the game's models). Second cause, found 2026-10-17: the Nazo models name texture numbers 9, 10 and 37 while the mod's sonictex/sonic1tex have 9 textures; drawing them reads past the list (the City Escape board at the stage start). Fixed in the Nazo mod (v2) by padding the archives to 48 (`modtools/pad.py`). The same padding is in Chaos Nazo v4's ssonictex. Not yet confirmed on the phone.
- **Amy sessions' read at 0xC**: an older, separate trap, not reproduced since.
- **Voice not playing (0.1.19)**: the Nazo Voices mod was never switched on; in one-player the game reads `gd_PC/MPB/b03_sne/3.csb` (not b03_sn_btl_e).
- **Always super stuck on**: 0.1.18's test box carried into 0.1.19 under a new meaning; 0.1.20 gives Always super a new setting key.
- **Helper line-type clash**: a new `V` line shadowed the existing `V` (memory log) and broke the layout tests; renamed `D`.

## 7. Key game facts (addresses are guest VAs in the PC exe)

MainCharObj2 0x1DE9600, MainCharObj1 0x1DEA6C0, CharacterModels 0x1DE9620 (8 bytes each),
PhysicsArray 0x17391E8 (0x84 each; Super Sonic 9, Super Shadow 10), RingCount 0x174B028 (int16[2]),
FrameCountIngame 0x174B03C, TimerStopped 0x174AFDA, GameState 0x1934BE0 (16 in game), CurrentLevel
0x1934B70, Controllers 0x1DEFC00 (0x34 each, press at +0x10; Y = 0x200), ScreenFadeARGB 0x171CDA0
(alpha byte at +3; drawn by 0x478790), SonEff texlist 0xA08B94. CharObj2Base: CharID +1, CharID2
+3, Powerups +0x10 (invincible 0x8000), Upgrades +0x24, PhysData +0xC0. SonicCharObj2: AnimInfo
Current +0x178, Animations +0x198, TextureList +0x394. Functions: Sonic_Main 0x717840,
Sonic_Display 0x720090, LoadMDLFile 0x459590 (EAX = name), LoadCharTextures 0x44C510 (ECX = name),
PlaySoundProbably 0x437260 (ESI = sound id, three stack args; bank 3 cue n = 0x3000 + n),
PlayVoice 0x443130 (EDX, then the voice number on the stack). Strings: SSONICMDL.PRS 0x8BA9FC,
SSONICTEX 0x8BA9F0, SSONEFFTEX 0x8BAA1C. Unused guest memory used by the helper: 0x2670E00 (past
.data's end; .data has a table at 0x2670340-0x267073C, keep clear of it). Sonic's voice bank in
one-player: `MPB/b03_sne/3.csb`; cue 9 jump dash/homing, 13 damage, 18/19 unused for Sonic.
Sonic's PlayVoice numbers in his action code: 1542-1545, 1560-1567, 2666-2675 (per character);
which are the trick lines is not known yet.

## 8. The Nazo mods (built for the user, not committed)

- **Nazo (no Seelkadoom) v2**: KyronSaif/Sonicgamer11's Nazo repacked: `sonicmdl/` and
  `sonic1mdl/` model folders (Wrath of Nazo is the alt costume), sonictex/sonic1tex padded to 48,
  s_wind fix, s_efftex (silver since v3, the user's wish: Nazo silver, Chaos Nazo green), zanki/mission textures, `MPB/b03_sn_btl_e/3.csb`, stageMap with Shadow's
  portrait restored. Seelkadoom, event voices, edited motions and the DLL left out.
- **Chaos Nazo v4**: `ssonicmdl/` (Nazo's body as index 328 with rewritten UVs) +
  `PRS/ssonictex.pak` (green fur, yellow eyes with black pupils and edge, purple gem, white gloves
  with gold cuffs, black/purple/gold shoes, green-and-black ball from SuperHedgehogs' Super Sonic
  ball, padded to 48 with names matching Nazo's sonictex) + `PRS/csonefftex.pak` (light-green
  effects).
- **Nazo Voices v2**: `MPB/b03_sne/3.csb` built from the Nazo mod's two-player bank, with cues
  09 homing, 13 hurt "No!", 18 laugh, 19 "I'm back", 20 "Yes", 21 "Completely unreal" (clips from
  the Wrath of Nazo animatic, trimmed and denoised). Its mod.ini `[OpenSA2]` group:
  `TransformVoices=18,19`, `TransformFlash=40FF60,226`, `VoiceCues=1560:20,1562:20,1564:21`
  (the last is a first guess).
- Mod order on the phone: Chaos Nazo, Nazo Voices, Nazo all on; Super Sonic / Shadow (Sora's DLL
  mod) off; the original "Nazo & Seelkadoom" off.
- Scripts: `tools/opensa2_android/modtools/` (see its README).

## 9. Where it stopped (next steps)

1. **Confirm the Nazo crash fix** (Nazo v2 + Chaos Nazo v4): City Escape from the start, with
   and without transforming. If the 0x94 trap remains, look for other texture ids past an
   archive's end (`mdluv.py` prints them) and consider a helper wrapper that clamps texture
   numbers to the current list's count.
2. **Find the trick voice numbers**: in the next logs, `opensa2-patch: voice: the game asks for
   voice N from 0x...` lines appear as tricks are done; set `VoiceCues` in Nazo Voices' mod.ini
   to the trick ("Yes") and awesome-rank ("Completely unreal") numbers.
3. **Check the transformation on the phone**: the flash, the laugh during it and "I'm back" after;
   Chaos Nazo's green ball and light-green effects (needs the Nazo mod on for the ball).
4. **The green aura** around Chaos Nazo: done in 0.1.22 (Final Hazard's aura, drawn by the
   helper outside it; Chaos Nazo's mod.ini `[OpenSA2] AuraColor=40FF60`). Not yet seen on the
   phone; the session log says `aura: player 0 glows, tinted 0x40ff60` when it is drawn.
5. **Voices did not play in 0.1.20** (laugh, "I'm back", homing, hurt): the bank v2 was 5.8 MB
   (the game's one-player bank is 78 KB) and its @UTF tables had 1 in the first header field where
   CRI's have 0. Nazo Voices v3: field 0, every element cut to the samples it plays, our clips at
   22.05 kHz: 388 KB; still silent. The user then sent the game's own `MPB/b03_sne/3.csb` (78 KB,
   40 cues, mono 22.05 kHz with nch/sfreq as constant columns). Nazo Voices v5 is that file with
   cues 9, 13, 18-21 replaced (`modtools/vbank.py`, 216 KB); `utf.py` now rewrites the game's file
   byte for byte (it had stored "<NULL>" twice in the string pool). On the phone the lines play, but
   a sound id's cue number counts only the bank's non-empty cues (nsmpl 1 = empty): with cues 11
   and 12 empty, id 0x3012 played cue file 20. Nazo Voices v6: TransformVoices=16,17,
   VoiceCues=1541:18,1543:18,1545:19 (which voice numbers are "Nice" and "Jammin'" tricks is
   still a guess from the City Escape log).
6. More voice lines from the user, as cues in Nazo Voices (`voicebank.py`).
7. Open, older: the Dark Chao Walker crash; the Amy 0xC trap; resuming after the app is switched
   away; codes that patch the middle of a function.

## 10. Related projects (the brother's)

- Marathon Recompiled (Sonic '06) Android port: github.com/yuriaaaaa/MarathonRecomp (docs/ANDROID_PORT.md, a Baton Pass hand-off doc).
- Sonic R decompilation with Android build and netplay: github.com/yuriaaaaa/sonic-r-android-build- (the netplay hub notes: docs/netplay-hub.md).
