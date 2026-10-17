# Mod-making tools (Nazo, Chaos Nazo, Nazo Voices)

Small Python scripts used in the 2026-10-17 sessions to build the Nazo / Chaos Nazo / Nazo Voices
mods for OpenSA2. They work on the user's own mod and game files; none of those are kept here.
Needs `numpy` and `Pillow` (`capstone` for the disassembly helpers). Run with `python3 -I`.

| Script | What it does |
|---|---|
| `pakx.py` | Reads and writes SA2 PC `.pak` texture archives (header, entry names, data). |
| `mdluv.py <file.sa2mdl> <out.json>` | Walks an SA2MDL (v3, little-endian) chunk model: node transforms, weighted vertex chunks, strip chunks with UVs; writes the triangles (texture id, position, UV) and `<out>.ents` (every strip entry's UV file offset), and prints the texture ids used. Used to find which texture ids a model names (the Nazo crash: ids 9, 10, 37 past a 9-texture archive). |
| `render.py` / `uvplot.py` | Flat-shaded front/side preview of a model with an archive's textures; UV layout plot coloured by 3D position. |
| `green.py <dir of pakx.py> <src sonictex.pak> <out ssonictex.pak> <preview dir>` | Copies a character texture archive under another list name (inner names, `.inf` name, fresh GBIX numbers so the game's texture cache does not hand back the original), turning blue hues green. |
| `chaos.py` | Chaos Nazo: rewrites the body model's UVs (gloves, cuffs, shoe palette) and repaints the textures (green fur, yellow eyes, purple gem, gold cuffs, black/purple shoes). |
| `chaos2.py` | Chaos Nazo's spin ball (a recoloured Super Sonic ball) and `csonefftex.pak` (Sonic's effects in light green). |
| `pad.py <dir of pakx.py> <src.pak> <out.pak> <count> <fill texture> <gbix base hex>` | Pads an archive to N textures with copies of one, so models naming texture numbers past its end do not crash. Keep a super form's archive padded with the same names as its base, so the helper's "same textures" check (kind 19) still holds. |
| `utf.py`, `adx.py` | CRI `@UTF` table reader/writer (CSB banks, AAX) and an ADX (type 3, 4-bit) encoder/decoder. |
| `prep.py <wav> <start s> <end s> <denoise 0-1> <out.wav>` | Trims a voice clip, spectral-subtracts against its quietest frames, fades and normalises to 44.1 kHz mono. |
| `voicebank.py <src 3.csb> <out 3.csb> <new prefix> <cue>=<wav> ...` | Builds a bank-3 voice CSB from another (e.g. the Nazo mod's b03_sn_btl_e), renaming it, with cues replaced by ADX-encoded clips. |
| `gdis.py <sub_name>`, `calls.py <sub_name>...` | Disassemble a lifted function of libsa2game.so / list its direct calls. Set `SA2_GAME_LIB` (the APK's lib/arm64-v8a/libsa2game.so) and `SA2_SUBS` (one `sub_<va> <symbol value hex> <size>` line per lifted function, from its symbol table). |
