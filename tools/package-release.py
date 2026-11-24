"""Prepares the files of a GitHub release in release/ (ignored by git):

    ChudMusic-<version>.apk           the signed release APK (built beforehand with assembleRelease)
    ChudMusic-<version>-sources.zip   the complete source code (GPL-3.0-or-later): the files of
                                      tools/public_files.py, without keys or assistant notes
    SHA256SUMS.txt                    checksums of both

Usage (from the project root):  python tools/package-release.py
The version is read from app/build.gradle.kts (versionName default).
"""
import hashlib
import re
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

sys.path.insert(0, str(ROOT / "tools"))
from public_files import problems, public_files  # noqa: E402 (same files as the public repository)


def version() -> str:
    text = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'\?:\s*"(\d+\.\d+\.\d+)"', text)
    if not m:
        sys.exit("versionName introuvable dans app/build.gradle.kts")
    return m.group(1)


def source_files():
    for rel in public_files():
        yield ROOT / rel, rel


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    v = version()
    bad = problems(public_files())
    if bad:
        sys.exit("ARRÊT, à corriger d'abord :\n" + "\n".join("  " + b for b in bad))
    out = ROOT / "release"
    out.mkdir(exist_ok=True)

    apk_src = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not apk_src.exists():
        sys.exit("APK release absent : lancez d'abord  gradlew assembleRelease")
    apk = out / f"ChudMusic-{v}.apk"
    shutil.copy2(apk_src, apk)

    zpath = out / f"ChudMusic-{v}-sources.zip"
    prefix = f"ChudMusic-{v}-sources"
    count = 0
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p, rel in source_files():
            info = zipfile.ZipInfo.from_file(p, f"{prefix}/{rel.as_posix()}")
            info.compress_type = zipfile.ZIP_DEFLATED
            # gradlew must stay executable once unpacked on Linux or macOS.
            info.external_attr = (0o100755 if rel.name == "gradlew" else 0o100644) << 16
            z.writestr(info, p.read_bytes(), compresslevel=9)
            count += 1
    with zipfile.ZipFile(zpath) as z:
        bad = [n for n in z.namelist() if "/keystore/" in n or n.lower().endswith((".jks", ".keystore", ".p12")) or n.endswith("release.properties")]
    if bad:
        zpath.unlink()
        sys.exit(f"ARRÊT : des fichiers de clé se trouvaient dans l'archive : {bad}")

    sums = out / "SHA256SUMS.txt"
    sums.write_text("".join(f"{sha256(f)}  {f.name}\n" for f in (apk, zpath)), encoding="utf-8", newline="\n")
    print(f"Version {v}")
    print(f"  {apk.relative_to(ROOT)}  ({apk.stat().st_size // 1024} Kio)")
    print(f"  {zpath.relative_to(ROOT)}  ({count} fichiers, {zpath.stat().st_size // 1024} Kio, sans clé de signature)")
    print(f"  {sums.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
