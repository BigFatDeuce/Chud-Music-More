"""Which files of the project may be made public, and a last check of their content.

Used by publish-pages.py (the GitHub repository) and package-release.py (the sources zip), so both
always hold the same set: the files tracked by git, minus what must stay private.

Private, whatever git says:
  - signing keys and local settings (keystore/, *.jks, release.properties, local.properties, .env);
  - local working notes;
  - build outputs and release files.

The content check refuses keys and tokens, and any word listed in `.private-words` at the project
root: one word or phrase per line, ignored by git and never published (names, e-mail addresses, the
PC's name, personal folders...).
"""
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

PRIVATE_NAMES = {"AGENTS.md", "CLAUDE.md", "CLAUDE.local.md", "GEMINI.md", "local.properties",
                 "release.properties", ".env", ".private-words"}
PRIVATE_DIRS = {"keystore", ".claude", ".cursor", ".tools", "release", "build", ".gradle", ".kotlin", ".idea"}
PRIVATE_SUFFIXES = {".jks", ".keystore", ".p12", ".pem", ".apk", ".aab", ".iml", ".pyc"}
PRIVATE_WORDS_IN_NAMES = ("prompt",)

TOKEN = re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|-----BEGIN [A-Z ]*PRIVATE KEY-----")


def is_private(rel: Path) -> bool:
    parts = rel.parts
    name = parts[-1]
    return (
        name in PRIVATE_NAMES
        or name.startswith(".env")
        or any(p in PRIVATE_DIRS for p in parts[:-1])
        or rel.suffix.lower() in PRIVATE_SUFFIXES
        or any(w in name.lower() for w in PRIVATE_WORDS_IN_NAMES)
    )


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True).stdout
    return [Path(n) for n in out.decode("utf-8").split("\0") if n]


def public_files():
    """The files that may be published, relative to the project root."""
    return [rel for rel in tracked_files() if not is_private(rel) and (ROOT / rel).is_file()]


def private_words():
    f = ROOT / ".private-words"
    if not f.is_file():
        return []
    return [w.strip() for w in f.read_text(encoding="utf-8").splitlines() if w.strip() and not w.startswith("#")]


def problems(files):
    """Lines of the given files that must not be published. Empty when everything is fine."""
    words = [w.lower() for w in private_words()]
    found = []
    for rel in files:
        if is_private(rel):
            found.append(f"{rel}: private file")
            continue
        data = (ROOT / rel).read_bytes()
        if b"\0" in data[:8000]:
            continue  # binary (images, the Gradle wrapper)
        for n, line in enumerate(data.decode("utf-8", "replace").splitlines(), 1):
            low = line.lower()
            if TOKEN.search(line) or any(w in low for w in words):
                found.append(f"{rel}:{n}: {line.strip()[:120]}")
    if not words:
        found.append(".private-words is missing or empty: names and addresses cannot be checked")
    return found
