"""Updates the public GitHub repository of the project: the complete source code (GPL-3.0-or-later),
the README, the documentation and the licence texts.

    python tools/publish-pages.py           shows what would change, sends nothing
    python tools/publish-pages.py --push    commits and pushes to the main branch

What is public is decided by tools/public_files.py: the files tracked by git, minus signing keys,
local settings, notes for coding assistants and build outputs. Nothing is sent if a file holds a
personal path or a word of `.private-words`.

The repository's history is kept: each run adds one ordinary commit, never a force-push. Releases,
tags and their files are not touched (the app's updates read the releases only).

Needs git, with access to the repository (for example after `gh auth login`).
"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from public_files import ROOT, problems, public_files  # noqa: E402

REPO = "https://github.com/BigFatDeuce/Chud-Music-More.git"
# The account's public no-reply address: never a personal one.
AUTHOR = ["-c", "user.name=BigFatDeuce", "-c", "user.email=67258247+BigFatDeuce@users.noreply.github.com"]


def git(cwd, *args, capture=False):
    env = dict(os.environ, TZ="UTC")  # the commit date says nothing about where it was made
    r = subprocess.run(["git", *args], cwd=cwd, check=True, text=True, capture_output=capture, encoding="utf-8", env=env)
    return r.stdout if capture else ""


def main():
    push = "--push" in sys.argv[1:]
    # Each --message= is one paragraph of the commit message (the first one is its title).
    messages = [a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("--message=")] or ["Update the project"]
    files = public_files()
    bad = problems(files)
    if bad:
        print("NOT PUBLISHED. Fix these first:")
        print("\n".join("  " + b for b in bad))
        sys.exit(1)
    with tempfile.TemporaryDirectory() as tmp:
        clone = Path(tmp) / "page"
        git(tmp, "clone", "--quiet", "--depth", "1", "--branch", "main", REPO, str(clone))
        # The repository is replaced as a whole: a file removed here disappears there too.
        for p in clone.iterdir():
            if p.name == ".git":
                continue
            shutil.rmtree(p) if p.is_dir() else p.unlink()
        for rel in files:
            (clone / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / rel, clone / rel)
        git(clone, "add", "--all")
        if (clone / "gradlew").is_file():
            git(clone, "update-index", "--chmod=+x", "gradlew")
        status = git(clone, "status", "--short", capture=True)
        print(f"{len(files)} public files.")
        if not status.strip():
            print("The GitHub repository is already up to date.")
            return
        print(status.rstrip())
        if not push:
            print("\nNothing was sent. Run again with --push to publish these changes.")
            return
        git(clone, *AUTHOR, "commit", "--quiet", *[x for m in messages for x in ("-m", m)])
        git(clone, "push", "--quiet", "origin", "main")
        print("\nPublished: https://github.com/BigFatDeuce/Chud-Music-More")


if __name__ == "__main__":
    main()
