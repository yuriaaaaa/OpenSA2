import sys, wave, struct, re
import numpy as np
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import utf, adx
SRC, OUT, NEWPREFIX = sys.argv[1], sys.argv[2], sys.argv[3]
clips = dict(a.split('=') for a in sys.argv[4:])        # cue id = wav
b = open(SRC, 'rb').read()
top = utf.read(b)
old = None
def ren(s):
    global old
    m = re.search(r'b03_[a-z_]+?(?=\d{6})', s) if isinstance(s, str) else None
    if m: old = m.group(0); return s.replace(m.group(0), NEWPREFIX)
    return s
def renall(t):
    for r in t['rows']:
        for k, v in list(r.items()):
            if isinstance(v, str): r[k] = ren(v)
for r in top['rows']:
    sub = utf.read(r['utf'])
    if sub['name'] != 'TBLSDL': renall(sub)
    if sub['name'] == 'TBLSDL':
        renall(sub)
        for cue, path in clips.items():
            cue = int(cue)
            w = wave.open(path); nch = w.getnchannels(); rate = w.getframerate()
            x = np.frombuffer(w.readframes(w.getnframes()), np.int16).reshape(-1, nch).astype(np.float64).mean(1)
            x *= 26000 / max(1, np.abs(x).max())
            f = min(len(x), int(rate * 0.012)); x[-f:] *= np.linspace(1, 0, f)
            pcm = np.round(x).astype(np.int16)[None, :]
            data = adx.encode(pcm, rate)
            dec, _ = adx.decode(data)
            snr = 10 * np.log10((pcm.astype(float) ** 2).sum() / max(1, ((dec[0, :pcm.shape[1]] - pcm[0]) ** 2).sum()))
            row = [e for e in sub['rows'] if e['name'].endswith('%06d_wav' % (3000 + cue))][0]
            aax = utf.read(row['data']); aax['rows'] = [{'data': data, 'lpflg': 0}]
            row['data'] = utf.write(aax); row['nch'] = 1; row['sfreq'] = rate; row['nsmpl'] = pcm.shape[1]
            print('cue', cue, row['name'], 'samples', pcm.shape[1], 'adx', len(data), 'snr %.1f dB' % snr)
    r['utf'] = utf.write(sub)
out = utf.write(top)
open(OUT, 'wb').write(out)
# check: read back
t2 = utf.read(out)
for r in t2['rows']:
    s = utf.read(r['utf']); print(r['name'], s['name'], len(s['rows']))
print('prefix', old, '->', NEWPREFIX, len(out))
