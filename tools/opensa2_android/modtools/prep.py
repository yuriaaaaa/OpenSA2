import sys, wave
import numpy as np
def load(path):
    w = wave.open(path); ch = w.getnchannels(); r = w.getframerate(); sw = w.getsampwidth()
    x = np.frombuffer(w.readframes(w.getnframes()), np.int16).reshape(-1, ch).astype(np.float64).mean(1)
    return x, r
def resample(x, r, to=44100):
    if r == to: return x
    n = int(len(x) * to / r); return np.interp(np.arange(n) * r / to, np.arange(len(x)), x)
def denoise(x, r, strength=1.0):
    """Spectral subtraction: the noise floor is the quietest tenth of the frames."""
    N = 1024; H = 256; win = np.hanning(N)
    pad = np.concatenate([np.zeros(N), x, np.zeros(N)])
    frames = [pad[i:i + N] * win for i in range(0, len(pad) - N, H)]
    S = np.array([np.fft.rfft(f) for f in frames]); mag = np.abs(S); ph = np.angle(S)
    e = (mag ** 2).sum(1); q = np.argsort(e)[:max(4, len(e) // 10)]
    noise = mag[q].mean(0)
    g = np.clip(1 - strength * 1.5 * noise / np.maximum(mag, 1e-9), 0.06, 1.0)
    g = np.apply_along_axis(lambda c: np.convolve(c, np.ones(3) / 3, 'same'), 0, g)   # smooth over time
    Y = mag * g * np.exp(1j * ph)
    out = np.zeros(len(pad)); norm = np.zeros(len(pad))
    for k, i in enumerate(range(0, len(pad) - N, H)):
        out[i:i + N] += np.fft.irfft(Y[k], N) * win; norm[i:i + N] += win ** 2
    out = out / np.maximum(norm, 1e-6)
    return out[N:N + len(x)]
def prep(path, a, b, strength, outp):
    x, r = load(path); x = resample(x, r)
    seg = x[int(a * 44100):int(b * 44100)]
    if strength > 0: seg = denoise(seg, 44100, strength)
    seg = seg - seg.mean()
    f = int(0.01 * 44100); seg[:f] *= np.linspace(0, 1, f); f = int(0.03 * 44100); seg[-f:] *= np.linspace(1, 0, f)
    seg *= 26000 / max(1, np.abs(seg).max())
    w = wave.open(outp, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(44100)
    w.writeframes(np.round(seg).astype(np.int16).tobytes()); w.close()
    print(outp, round(len(seg) / 44100, 2), 's')
if __name__ == '__main__':
    prep(sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]), sys.argv[5])
