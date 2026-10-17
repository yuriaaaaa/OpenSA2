"""Puts clips into a one-player voice bank (MPB/b03_xxx/3.csb) made from the game's own: only the
named cues' data and sample counts change, every table keeps the game's layout (40 cues, mono
22.05 kHz as constant columns). Usage: vbank.py <game 3.csb> <out 3.csb> <cue>=<mono 22050 wav> ..."""
import sys, os, wave
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import utf, adx
src, out = sys.argv[1], sys.argv[2]
clips = {int(k): v for k, v in (a.split('=') for a in sys.argv[3:])}
top = utf.read(open(src, 'rb').read())
for r in top['rows']:
    if r['name'] != 'SOUND_ELEMENT': continue
    se = utf.read(r['utf'])
    rate = se['rows'][0]['sfreq']
    for cue, path in clips.items():
        w = wave.open(path); assert w.getnchannels() == 1 and w.getframerate() == rate, path
        x = np.frombuffer(w.readframes(w.getnframes()), np.int16)[None, :]
        row = [e for e in se['rows'] if e['name'].endswith('%06d_wav' % (3000 + cue))][0]
        aax = utf.read(row['data']); aax['rows'] = [{'data': adx.encode(x, rate), 'lpflg': 0}]
        row['data'] = utf.write(aax); row['nsmpl'] = x.shape[1]
        print('cue', cue, x.shape[1], 'samples')
    r['utf'] = utf.write(se)
data = utf.write(top); open(out, 'wb').write(data); print(out, len(data))
