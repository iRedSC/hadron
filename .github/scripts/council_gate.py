"""Fails when a PR changes a path in .github/council-paths.txt without a final review of its head.

The record is one line in the PR description, written by the final-review skill (.claude/skills/final-review):

    Final review: GPT-6.1 Sol (final), <the PR's full head SHA>, 2 found, top P4

It passes when there is exactly one such line, by the "final" member of .github/council.toml, naming the head, and
its most serious finding is no worse than stop_at (P0 is the most serious). Whether a change also needed the full
council (.claude/skills/council) is the author's judgement, recorded in the PR; the gate doesn't check it.

Reads BASE and HEAD (commit SHAs) and PR_BODY from the environment. CI runs main's copy of this script, the path list
and the member list against the PR, so a PR can't change what checks it. Run locally with:
    BASE=$(git merge-base origin/main HEAD) HEAD=$(git rev-parse HEAD) PR_BODY="$(gh pr view --json body -q .body)" \\
        python3 .github/scripts/council_gate.py
"""

import os
import re
import subprocess
import sys
import tomllib
from pathlib import Path

PATHS_FILE = Path(".github/council-paths.txt")
COUNCIL_FILE = Path(".github/council.toml")
ROLES = ("questions", "council", "explain", "final")
# Each provider's one option, as delegate_task names it, and the values it takes.
EFFORTS = ("low", "medium", "high", "xhigh", "max")
PROVIDER_OPTIONS = {"claudeAgent": {"effort": EFFORTS}, "codex": {"reasoningEffort": EFFORTS}}


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], capture_output=True, text=True)


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


def needs_review(changed: list[str], tracked: list[str], rules: list[tuple[str, re.Pattern[str]]]) \
        -> tuple[list[str], list[tuple[str, str]]]:
    """The globs that match no tracked file (and so protect nothing), and each changed file a glob protects."""
    dead = [glob for glob, rx in rules if not any(rx.match(name) for name in tracked)]
    hits = [(name, glob) for name in changed for glob, rx in rules if rx.match(name)]
    return dead, hits


def whole(value: object, low: int, high: int) -> bool:
    # bool is an int in Python, so `true` would otherwise pass as 1.
    return isinstance(value, int) and not isinstance(value, bool) and low <= value <= high


def config_problems(config: dict) -> list[str]:
    """Why .github/council.toml can't run a council; empty when it can."""
    problems = []
    for key, low, high in (("min_findings", 1, 20), ("max_rounds", 1, 10)):
        if not whole(config.get(key), low, high):
            problems.append(f"{COUNCIL_FILE} needs {key} = <a whole number from {low} to {high}>.")
    if config.get("stop_at") not in ("P0", "P1", "P2", "P3", "P4"):
        problems.append(f"{COUNCIL_FILE} needs stop_at = one of \"P0\" to \"P4\".")
    members = config.get("member", [])
    if not isinstance(members, list) or not all(isinstance(m, dict) for m in members):
        return problems + [f"{COUNCIL_FILE} lists members as [[member]] tables, one per member."]
    for m in members:
        name = m.get("name", "<no name>")
        if not isinstance(m.get("name"), str) or not m["name"].strip():
            problems.append(f"{COUNCIL_FILE}: every member needs a name; one has {m.get('name')!r}.")
        if m.get("role") not in ROLES:
            problems.append(f"{COUNCIL_FILE}: {name} has role {m.get('role')!r}; roles are {', '.join(ROLES)}.")
        allowed = PROVIDER_OPTIONS.get(m.get("provider"), {})
        options = sorted(set(m) - {"name", "role", "provider", "model"})
        if not allowed or not m.get("model"):
            problems.append(f"{COUNCIL_FILE}: {name} needs a model and a provider from {', '.join(PROVIDER_OPTIONS)}.")
        elif len(options) != 1 or options[0] not in allowed or m[options[0]] not in allowed[options[0]]:
            option = next(iter(allowed))
            problems.append(f"{COUNCIL_FILE}: {name} takes exactly one option, {option} = one of "
                            f"{', '.join(allowed[option])}.")
    names = [m.get("name") for m in members]
    if len(set(names)) != len(names):
        problems.append(f"{COUNCIL_FILE}: two members share a name, so the record can't tell them apart.")
    for role in ROLES:
        count = sum(m.get("role") == role for m in members)
        if count == 0 or (role == "final" and count > 1):
            problems.append(f"{COUNCIL_FILE} needs {'exactly one' if role == 'final' else 'at least one'} "
                            f"\"{role}\" member.")
    return problems


FINAL = re.compile(r"^Final review:[ \t]*(.+?),[ \t]*([0-9a-f]{40}),[ \t]*(\d+) found, top P([0-4])[ \t]*$", re.M)


def review_problems(body: str, head: str, config: dict) -> list[str]:
    """Why the PR description's final review doesn't pass for this head commit; empty when it does."""
    final = next(m["name"] for m in config["member"] if m["role"] == "final")
    stop = int(config["stop_at"][1])
    expected = (f"\"Final review: {final}, {head}, <n> found, top P<k>\", by .claude/skills/final-review/SKILL.md, "
                f"with nothing more serious than {config['stop_at']}")
    lines = FINAL.findall(body)
    if len(lines) != 1:
        # Two lines could leave a stale pass beside a failing one, so only one may stand.
        return [f"The PR description needs exactly one final review line ({len(lines)} found): {expected}."]
    name, sha, _, top = lines[0]
    if name != final or sha != head or int(top) < stop:
        return [f"The final review line must read {expected}; it reads \"{name}, {sha}, top P{top}\"."]
    return []


def main() -> int:
    base, head = os.environ["BASE"], os.environ["HEAD"]
    config = tomllib.loads(COUNCIL_FILE.read_text())
    problems = config_problems(config)
    if problems:
        print("\n".join(problems))
        return 1
    rules = patterns()
    # --no-renames lists a moved file under both paths, so moving a file out of a protected folder still counts.
    changed, tracked = (git("diff", "--no-renames", "--name-only", "-z", f"{base}...{head}"),
                        git("ls-tree", "-r", "--name-only", "-z", head))
    for result in (changed, tracked):
        if result.returncode != 0:
            # Never "no review needed" because git couldn't say what changed.
            print(f"{' '.join(result.args)} failed: {result.stderr.strip()}")
            return 1
    dead, hits = needs_review([c for c in changed.stdout.split("\0") if c], tracked.stdout.split("\0"), rules)
    if dead:
        print(f"In this PR's tree, {PATHS_FILE} has globs that match no file, so they protect nothing: "
              f"{', '.join(dead)}. If the PR removes those files on purpose, take their globs out of {PATHS_FILE} "
              "in a PR of its own first.")
        return 1
    if not hits:
        print("No path here needs a review.")
        return 0
    print("This PR changes paths that need a final review:")
    for name, glob in hits:
        print(f"  {name}  (matches {glob})")
    problems = review_problems(os.environ.get("PR_BODY") or "", head, config)
    if not problems:
        print(f"Passed: the final review found nothing more serious than {config['stop_at']} on {head[:12]}.")
        return 0
    print("\n".join(f"  {p}" for p in problems))
    return 1


if __name__ == "__main__":
    sys.exit(main())
