"""Fails when a PR changes a path in .github/council-paths.txt without a passing council review of its head commit.

A passing review is a "## Council review" section in the PR description, as the council skill writes it:

    ## Council review

    Reviewed commit: <the PR's head SHA>
    Rounds: 2

    | Delegate | Round 1 | Round 2 |
    | --- | --- | --- |
    | GPT-6.1 Sol | 7/10 | 9/10 |
    | Claude Haiku 5.5 | 6 asked, 2 open | 6 asked, 0 open |
    ...

Every member in .github/council.toml needs a row. In its last round a deep reviewer must score at least min_score, and
a question asker must have no question left open. The review counts only for the commit it names, so a push after it
needs a fresh round.

Reads BASE and HEAD (commit SHAs) and PR_BODY from the environment. CI runs main's copy of this script, the path list
and the member list against the PR's diff, so a PR can't change what checks it.
Run locally with: BASE=origin/main HEAD=$(git rev-parse HEAD) PR_BODY="$(gh pr view --json body -q .body)" \\
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


ROLES = ("deep", "questions")


def council() -> tuple[int, list[tuple[str, str]]]:
    config = tomllib.loads(COUNCIL_FILE.read_text())
    return int(config["min_score"]), [(m["name"], m["role"]) for m in config["member"]]


def review_problems(body: str, head: str, min_score: int, members: list[tuple[str, str]]) -> list[str]:
    """Why the PR description's council review doesn't pass for this head commit; empty when it does."""
    section = re.search(r"^## Council review\s*$(.*?)(?=^## |\Z)", body, re.M | re.S)
    if not section:
        return ["The PR description has no \"## Council review\" section."]
    text = section.group(1)
    problems = []
    commit = re.search(r"^Reviewed commit:\s*`?([0-9a-f]{7,40})`?\s*$", text, re.M)
    if not commit:
        problems.append("The council review has no \"Reviewed commit: <sha>\" line naming the commit it reviewed.")
    elif not head.startswith(commit.group(1)) or len(commit.group(1)) < 12:
        problems.append(f"The council reviewed {commit.group(1)}, but the PR's head is {head[:12]}: commits pushed "
                        "after the review need a fresh round (and at least 12 characters of the SHA).")
    rows = {}
    for line in text.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 2 and cells[0] and cells[0] != "Delegate" and not set(cells[0]) <= set("-: "):
            rows[cells[0]] = cells[1:]
    for name, role in members:
        if name not in rows:
            problems.append(f"No row for council member \"{name}\" (names must match {COUNCIL_FILE}).")
            continue
        last = rows[name][-1] if rows[name] else ""
        if role == "deep":
            score = re.fullmatch(r"(\d+(?:\.\d+)?)\s*/\s*10", last)
            if not score:
                problems.append(f"{name}'s last round is \"{last}\", not a score out of 10.")
            elif float(score.group(1)) < min_score:
                problems.append(f"{name} scored {last} in the last round; each deep reviewer needs at least "
                                f"{min_score}/10.")
        else:
            asked = re.fullmatch(r"(\d+)\s+asked,\s*(\d+)\s+open", last)
            if not asked:
                problems.append(f"{name}'s last round is \"{last}\", not \"<n> asked, <n> open\".")
            elif int(asked.group(2)) > 0:
                problems.append(f"{name} still has {asked.group(2)} open question(s); answer each in the PR or change "
                                "the code so it no longer applies, then rerun the round.")
    return problems


def main() -> int:
    base, head = os.environ["BASE"], os.environ["HEAD"]
    rules = patterns()
    min_score, members = council()
    roles = [role for _, role in members]
    if any(role not in ROLES for role in roles) or "deep" not in roles or "questions" not in roles:
        print(f"{COUNCIL_FILE} needs at least one \"deep\" and one \"questions\" member, and no other roles.")
        return 1
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
        print("No path here needs a council review.")
        return 0
    print("This PR changes paths that need a council review:")
    for name, glob in hits:
        print(f"  {name}  (matches {glob})")
    problems = review_problems(os.environ.get("PR_BODY") or "", head, min_score, members)
    if not problems:
        print(f"Passed: every deep reviewer scored at least {min_score}/10 and no question is open, on {head[:12]}.")
        return 0
    print()
    for problem in problems:
        print(f"  {problem}")
    print("\nTo merge: run a council on this PR (say \"council\"; .claude/skills/council/SKILL.md), fix what it finds, "
          f"and let it write its table into the description with \"Reviewed commit: {head}\".")
    return 1


if __name__ == "__main__":
    sys.exit(main())
