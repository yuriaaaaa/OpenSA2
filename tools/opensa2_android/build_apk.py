#!/usr/bin/env python3
"""OpenSA2's Android APK: the launcher, touch controls, mods and codes in host/android around a
release's native libraries.

Builds the app's Java (SDL 3's android-project classes and host/android/java) and resources, and
packages them with lib/arm64-v8a/*.so and assets/ taken from an SA2 Recompiled release APK, so no
NDK build is needed. The app id is com.yuriaaaaa.opensa2 (aapt2 --rename-manifest-package), so
it installs next to the original app.

  python3 tools/opensa2_android/build_apk.py --release-apk SA2Recompiled.apk \\
      --sdk ~/android-sdk --sdl SDL3-3.4.16 --out OpenSA2.apk [--icon art.png] [--keystore k.jks]

--sdk needs build-tools/36.0.0 and platforms/android-36. --sdl is the SDL3 source tree whose
version matches the release's libSDL3.so (3.4.16). --icon is a square picture for the launcher
icon (kept out of the repository; the default icon is host/android's). Without --keystore a
debug key is made next to the output and reused by later builds, so updates install over each
other.

The native patch helper (host/android/native/opensa2_patch.c -> lib/arm64-v8a/libopensa2patch.so,
code-list patches and the character swap) is one freestanding C file with no C library. It is
built with --ndk's clang, or with a clang and ld.lld on PATH that can target
aarch64-linux-android; without either the APK is built without it and those features say so.

The APK holds recompiled game code (libsa2game.so): it is for your own use, not for sharing.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "host" / "android"
BUILD_TOOLS = "36.0.0"
PLATFORM = "android-36"
MIN_SDK, TARGET_SDK = 29, 35
PACKAGE = "com.yuriaaaaa.opensa2"


def run(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def make_icon(src: Path, res: Path) -> None:
    """An adaptive icon: the picture in the visible middle of the foreground, over a blurred,
    enlarged copy of itself, and a plain square for launchers without adaptive icons."""
    from PIL import Image, ImageDraw, ImageFilter
    art = Image.open(src).convert("RGBA")
    side = min(art.size)
    art = art.crop(((art.width - side) // 2, (art.height - side) // 2,
                    (art.width + side) // 2, (art.height + side) // 2))
    full = 432  # 108 dp at xxxhdpi
    bg = art.resize((full, full), Image.LANCZOS).filter(ImageFilter.GaussianBlur(10))
    fg = Image.new("RGBA", (full, full), (0, 0, 0, 0))
    inner = 304
    fg.paste(art.resize((inner, inner), Image.LANCZOS), ((full - inner) // 2, (full - inner) // 2))
    d = res / "drawable-xxxhdpi"
    d.mkdir(parents=True, exist_ok=True)
    bg.convert("RGB").save(d / "ic_launcher_bg.png")
    fg.save(d / "ic_launcher_fg.png")
    (res / "drawable" / "ic_launcher_fg.xml").unlink(missing_ok=True)
    for name, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        m = res / f"mipmap-{name}"
        m.mkdir(parents=True, exist_ok=True)
        icon = art.resize((px, px), Image.LANCZOS)
        mask = Image.new("L", (px, px), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, px - 1, px - 1), radius=px // 6, fill=255)
        icon.putalpha(mask)
        icon.save(m / "ic_launcher.png")
    (res / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_bg" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_fg" />\n'
        '</adaptive-icon>\n')


HELPER_SRC = ANDROID / "native" / "opensa2_patch.c"
HELPER_FLAGS = ["--target=aarch64-linux-android29", "-O2", "-fPIC", "-fvisibility=hidden", "-ffreestanding",
                "-fno-builtin", "-fno-stack-protector", "-fno-exceptions", "-fno-asynchronous-unwind-tables",
                "-mno-outline-atomics", "-nostdlib", "-shared", "-fuse-ld=lld", "-Wl,-soname,libopensa2patch.so",
                "-Wl,-z,max-page-size=16384", "-Wl,--hash-style=both", "-Wl,-z,now", "-Wl,--build-id=sha1",
                "-Wall", "-Wextra", "-Werror"]


def build_helper(ndk, out: Path):
    """libopensa2patch.so, or None (with the reason printed) when no suitable clang is found."""
    cands = []
    if ndk:
        cands += sorted(Path(ndk).glob("toolchains/llvm/prebuilt/*/bin/clang"))
    env_ndk = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if env_ndk:
        cands += sorted(Path(env_ndk).glob("toolchains/llvm/prebuilt/*/bin/clang"))
    if shutil.which("clang"):
        cands.append(Path(shutil.which("clang")))
    for cc in cands:
        try:
            run([cc, *HELPER_FLAGS, "-o", out, HELPER_SRC])
            return out
        except (subprocess.CalledProcessError, OSError) as e:
            print(f"note: {cc} could not build the patch helper ({e})", file=sys.stderr)
    print("warning: no clang that targets aarch64-linux-android (with ld.lld) was found: the APK is built "
          "without libopensa2patch.so, so code patches and character swaps will be off", file=sys.stderr)
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--release-apk", type=Path, required=True)
    ap.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", "")))
    ap.add_argument("--sdl", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--icon", type=Path)
    ap.add_argument("--keystore", type=Path)
    ap.add_argument("--ndk", type=Path, help="an Android NDK, for the native patch helper's clang")
    ap.add_argument("--version-name", default="0.1.8-opensa2")
    ap.add_argument("--version-code", type=int, default=10900)
    a = ap.parse_args()

    bt = a.sdk / "build-tools" / BUILD_TOOLS
    jar = a.sdk / "platforms" / PLATFORM / "android.jar"
    sdl_java = a.sdl / "android-project" / "app" / "src" / "main" / "java"
    for p in (bt / "aapt2", bt / "d8", bt / "zipalign", bt / "apksigner", jar, sdl_java, a.release_apk):
        if not p.exists():
            print(f"missing: {p}", file=sys.stderr)
            return 1

    with tempfile.TemporaryDirectory(prefix="opensa2-apk-") as t:
        w = Path(t)
        # Java: SDL's classes, then ours (host/android/java's org/libsdl/app files sit beside SDL's).
        sources = sorted(sdl_java.rglob("*.java")) + sorted((ANDROID / "java").rglob("*.java"))
        (w / "sources.txt").write_text("\n".join(str(s) for s in sources))
        classes = w / "classes"
        run(["javac", "--release", "11", "-nowarn", "-encoding", "UTF-8", "-classpath", jar,
             "-d", classes, f"@{w / 'sources.txt'}"])
        dex = w / "dex"
        dex.mkdir()
        run([bt / "d8", "--release", "--min-api", MIN_SDK, "--lib", jar, "--output", dex,
             *sorted(classes.rglob("*.class"))])

        # Resources, with the icon when one is given.
        res = w / "res"
        shutil.copytree(ANDROID / "res", res)
        if a.icon:
            make_icon(a.icon, res)
        flat = w / "flat.zip"
        run([bt / "aapt2", "compile", "--dir", res, "-o", flat])
        linked = w / "linked.apk"
        run([bt / "aapt2", "link", "-o", linked, "-I", jar, "--manifest", ANDROID / "AndroidManifest.xml",
             "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
             "--version-code", a.version_code, "--version-name", a.version_name,
             "--rename-manifest-package", PACKAGE, flat])

        helper = build_helper(a.ndk, w / "libopensa2patch.so")

        # The release's native libraries and assets, the patch helper, then the dex.
        unsigned = w / "unsigned.apk"
        shutil.copy(linked, unsigned)
        with zipfile.ZipFile(a.release_apk) as rel, zipfile.ZipFile(unsigned, "a") as z:
            for info in rel.infolist():
                n = info.filename
                if n.startswith("lib/arm64-v8a/") or n.startswith("assets/"):
                    # Native libraries stored, as the release has them (extractNativeLibs).
                    comp = zipfile.ZIP_STORED if n.endswith(".so") else zipfile.ZIP_DEFLATED
                    z.writestr(zipfile.ZipInfo(n, date_time=info.date_time), rel.read(n), compress_type=comp)
            if helper:
                z.writestr(zipfile.ZipInfo("lib/arm64-v8a/libopensa2patch.so", date_time=(2026, 1, 1, 0, 0, 0)),
                           helper.read_bytes(), compress_type=zipfile.ZIP_STORED)
            z.write(dex / "classes.dex", "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
        aligned = w / "aligned.apk"
        run([bt / "zipalign", "-f", "-p", "4", unsigned, aligned])

        ks = a.keystore or a.out.parent / "opensa2-debug.jks"
        if not ks.exists():
            ks.parent.mkdir(parents=True, exist_ok=True)
            run(["keytool", "-genkeypair", "-keystore", ks, "-storepass", "android", "-keypass", "android",
                 "-alias", "opensa2", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                 "-dname", "CN=OpenSA2 debug"])
        a.out.parent.mkdir(parents=True, exist_ok=True)
        run([bt / "apksigner", "sign", "--ks", ks, "--ks-pass", "pass:android", "--ks-key-alias", "opensa2",
             "--out", a.out, aligned])
        run([bt / "apksigner", "verify", a.out])
    print(f"built {a.out} ({a.out.stat().st_size // (1 << 20)} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
