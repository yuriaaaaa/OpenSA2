# Chaos Nazo: hand-off for the next Claude (2026-10-17)

Read this first, then `docs/HANDOFF.md` (the whole Android port) and the last sessions of
`docs/android-session-2026-10-15.md`. The repo is github.com/yuriaaaaa/OpenSA2, branch
`claude/project-thread-lpzylp`; the Android app is at 0.1.24. Keep the rules in HANDOFF.md
section 2 (source-only repo, `tools/public_check.py` before commits, no attribution trailers, the
mods and the game's files stay out of git).

## What exists

Three mods for the user's phone (zips in the hand-off bundle; never committed). They need
OpenSA2 0.1.21:

| Mod | Version | What it is |
|---|---|---|
| Nazo (no Seelkadoom) | v3 | KyronSaif/Sonicgamer11's Nazo over Sonic, with Wrath of Nazo as the alt costume. `sonicmdl/` and `sonic1mdl/` model folders. sonictex/sonic1tex are padded to 48 textures (the mod's models name textures 9, 10 and 37, and the archive past 9 crashed City Escape's start). s_efftex is recoloured silver (Nazo's spin and dash effects). |
| Chaos Nazo | v4 | The super form. `ssonicmdl/` holds Nazo's body as model 328 with rewritten UVs. `PRS/ssonictex.pak` has green fur, yellow eyes, a purple gem, white gloves with gold cuffs, black/purple/gold shoes and a green-and-black ball; it is padded to 48 with the same texture names as Nazo's sonictex. `PRS/csonefftex.pak` holds the light-green effects. |
| Nazo Voices | v7 | `MPB/b03_sne/3.csb`: the game's own one-player voice bank with cue files 9 (homing "hmmph"), 13 (hurt "No!"), 18 (laugh), 19 ("I'm back"), 20 ("Yes"), 21 ("Completely unreal") and 22 (death roar). Its mod.ini `[OpenSA2]` group sets `TransformVoices=16,17`, `TransformFlash=40FF60,302` (the flash as long as the laugh), `DeathVoice=20` and `VoiceCues=1541:18,1543:18,1545:19`. |

Mod order on the phone: Chaos Nazo, Nazo Voices, Nazo, all on. Sora's "Super Sonic / Shadow"
(DLL) mod and the original "Nazo & Seelkadoom" mod must be off. On the Characters screen, tick
"Super transformation" and leave "Always super" off.

## How it works (app side, all in the repo)

- **Transformation:** `host/android/native/opensa2_patch.c`, wrapper kind 18 around Sonic_Main
  0x717840. With at least 50 rings, Y in a jump starts a green flash: the game's ScreenFadeARGB at
  0x171CDA0, alpha up to 0xB0 in 8 frames, then down, ending at frame 226. Sonic changes form at
  frame 8:
  - LoadMDLFile("SSONICMDL.PRS") gives model 328 for CharacterModels;
  - LoadCharTextures("SSONICTEX") becomes his texture list (SonicCharObj2 +0x394);
  - the animation list's body model becomes 328;
  - Super Sonic physics (PhysicsArray[9]) and invincibility (Powerups 0x8000).

  He loses a ring a second and turns back at none, or on Y in a jump.
- **Voices:** the flash plays cue 16 as it starts and cue 17 as it ends, through the game's
  PlaySoundProbably 0x437260 (ESI = 0x3000 + cue). Wrapper kind 20 around PlayVoice 0x443130 logs
  every voice number once (`opensa2-patch: voice: the game asks for voice N from 0x...`). For the
  numbers in VoiceCues it plays a bank cue instead, while player 1 is Sonic.
- **Cue numbering (important):** the game counts a bank's cues **leaving out the empty ones**.
  An empty cue is one sample long; cues 11 and 12 are empty in the game's bank. So cue files
  18-21 are numbers 16-19 in TransformVoices and VoiceCues. Cue 9 and cue 13 are the game's own
  slots, numbered as the game plays them.
- **Effects and ball:** the E line swaps in csonefftex.pak as Sonic's effect list (SonEff
  0xA08B94) while super. Kind 19 (Sonic_Display) keeps the super textures for the spin ball when
  both texture lists have the same count. That is why Chaos Nazo's ssonictex must stay padded
  like Nazo's sonictex.
- **The launcher** (`NativePatch.java`) writes the plan lines B, D, E, N, O and X/F from the
  settings and from the mods' mod.ini.

## Rebuilding the mods (scripts in `tools/opensa2_android/modtools/`, README there)

- **Voices:**
  1. Trim and clean each clip: `prep.py <wav> <start> <end> <denoise> out.wav`.
  2. Convert to mono 22.05 kHz: `ffmpeg -ar 22050`.
  3. Build the bank from the game's own file:
     ```
     vbank.py <game b03_sne 3.csb> <out 3.csb> 9=homing.wav 13=hurt.wav 18=laugh.wav 19=back.wav 20=yes.wav 21=unreal.wav
     ```
     The game's file is in the bundle (`voice/game-b03_sne-3.csb`; from the user's phone, do not
  commit it). The bank must stay the game's layout: 40 cues, mono 22.05 kHz. A 5.8 MB bank made
  from the two-player file was silent. More lines can go into the empty cue files 22-39. Count
  the numbers the way the game does: a filled cue file stops being skipped.
- **Textures and models:**
  - `pad.py` pads an archive.
  - `chaos.py` and `chaos2.py` are the Chaos Nazo recolour and UV work.
  - `mdluv.py` lists the texture numbers a `.sa2mdl` uses. Check new models against their archive's count.
  - `render.py` draws a quick preview.

## Where it stands (user's report, 06:00 on 2026-10-17)

- **Confirmed working:**
  - Nazo in story City Escape;
  - the transformation and its green flash;
  - the voices playing;
  - the homing "hmmph".
- **v5 problem:** the transformation said "Yes" and then "Completely unreal". That was the
  cue-numbering shift, fixed in v6 but **not yet tested**.
- **Wanted, by the user:**
  1. Nice trick: "Yes". Jammin' trick: "Completely unreal". Hurt or death: "No". Transformation:
     the laugh during the green flash, then "I'm back" as it ends.
  2. Normal Nazo's spin aura silver, Chaos Nazo's green. Nazo v3 and Chaos Nazo v4 do this; not
     yet seen on the phone.
  3. A green aura around Chaos Nazo. Done in 0.1.22 (2026-10-18), not yet seen on the phone:
     the game's own Final Hazard aura (model 0x0170B47C over SSONEFFTEX) drawn around the super
     form in every stage, tinted by Chaos Nazo's mod.ini `[OpenSA2] AuraColor=40FF60` (Chaos Nazo
     v5). The Characters screen has a "Super aura" switch. Sora's SA2-Super-Sonic was the
     reference for where and how the aura is drawn; no code of it is used.

## Next steps for Sunday

1. Get logs from a run with v6. The user must close the game properly before zipping, or the
   session is not saved.
2. Read the `voice:` lines. Which numbers come up on a Nice trick and which on a Jammin' trick?
   0.1.20's City Escape log had 1543 (from 0x71D730), 1545 and 1541 (from 0x721EE4). The
   VoiceCues mapping is a guess from those; fix it in Nazo Voices' mod.ini. No APK is needed for
   that.
3. If hurt does not say "No", check which cue the game plays for damage (the doc lists Sonic
   Damage as file 13).
4. The aura: done in 0.1.22 (above). If it looks wrong on the phone, the Super aura switch turns
   it off; game.log's `aura:` lines say whether it was drawn.
5. Build an APK only for helper or launcher changes: HANDOFF.md section 3. Bump the version to
   the next (0.1.25 / 12600). Use the same keystore. Run the emulator tests (`emu_patch_helper.py`, must end
   ALL PASS), then deliver as 28 MiB 7z parts.

## Credits to keep in the mod.ini files

- Nazo models and textures: KyronSaif and Sonicgamer11.
- Voice lines: Chakra-X's "Sonic: The Wrath of Nazo" animatic. Private use, credited, and
  cleaned with AI tools (the user says so openly).
- Super ball texture: from the SuperHedgehogs mod.
- The transformation approach: after Sora's SA2-Super-Sonic.
- Everything stays free.
