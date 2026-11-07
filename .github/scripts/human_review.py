"""Fails when a PR changes a path in .github/human-review-paths.txt and lacks the human-reviewed label.

Reads BASE and HEAD (commit SHAs) and REVIEWED ("true" or "false") from the environment. CI runs main's copy of this
script and path list against the PR's diff, so a PR can't change what checks it.
Run locally with: BASE=origin/main HEAD=HEAD REVIEWED=false python3 .github/scripts/human_review.py
"""

import os
import re
import subprocess
import sys
from pathlib import Path

PATHS_FILE = Path(".github/human-review-paths.txt")


def glob_to_regex(glob: str) -> re.Pattern[str]:
    """`**` spans folders, `*` stays inside one name, everything else is literal."""
    out = ""
    i = 0
    while i < len(glob):
        if glob.startswith("**", i):
            out += ".*"
            i += 2
        elif glob[i] == "*":
            out += "[^/]*"
            i += 1
        else:
            out += re.escape(glob[i])
            i += 1
    return re.compile(out + "$")


def patterns() -> list[tuple[str, re.Pattern[str]]]:
    lines = (line.strip() for line in PATHS_FILE.read_text().splitlines())
    return [(line, glob_to_regex(line)) for line in lines if line and not line.startswith("#")]


def changed_files(base: str, head: str) -> list[str]:
    # --no-renames lists a moved file under both its old and new path, so moving a file out of a protected folder
    # still counts as touching it.
    result = subprocess.run(
        ["git", "diff", "--no-renames", "--name-only", "-z", f"{base}...{head}"],
        capture_output=True, text=True, check=True,
    )
    return [name for name in result.stdout.split("\0") if name]


def main() -> int:
    base, head = os.environ["BASE"], os.environ["HEAD"]
    reviewed = os.environ.get("REVIEWED") == "true"
    rules = patterns()
    # Globs are checked against the PR's tree, so a PR that renames a protected file away also kills its glob here.
    tracked = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", "-z", head], capture_output=True, text=True, check=True
    ).stdout.split("\0")
    dead = [glob for glob, rx in rules if not any(rx.match(name) for name in tracked)]
    if dead:
        print(f"{PATHS_FILE} has globs that match no file, so they protect nothing. Fix or delete them:")
        for glob in dead:
            print(f"  {glob}")
        return 1
    hits = [(name, glob) for name in changed_files(base, head) for glob, rx in rules if rx.match(name)]
    if not hits:
        print("No path here needs a human review.")
        return 0
    print("This PR changes paths that need a human review:")
    for name, glob in hits:
        print(f"  {name}  (matches {glob})")
    if reviewed:
        print("Reviewed: the human-reviewed label is on this commit.")
        return 0
    print("\nTo merge: read these changes, then add the `human-reviewed` label to the PR. A later commit removes it.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
