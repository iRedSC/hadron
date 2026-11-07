"""Fails when a PR changes a path in .github/council-paths.txt without a passing council review of its head commit.

A passing review is a "## Council review" section in the PR description, as the council skill writes it:

    ## Council review

    Reviewed commit: <the PR's head SHA>
    Rounds: 2

    | Delegate | Round 1 | Round 2 | Confirm |
    | --- | --- | --- | --- |
    | GPT-6.1 Sol | 7/10 | 9/10 | 9/10 |
    | Claude Haiku 5.5 | 6 asked, 2 open | 6 asked, 1 open | 6 asked, 0 open |
    | Claude Haiku 4.5 | 3 misread, 1 open | 1 misread, 1 open | 1 misread, 0 open |
    ...

Every member in .github/council.toml needs a row with a result in every column, and is judged on the last one: a deep
reviewer must score at least min_score, a question asker must have no question left open, and an explainer no
misreading left open. At most 3 rounds, then at most one Confirm pass. The review counts only for the commit it names, so a push after it
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


ROLES = ("deep", "questions", "explain")
# The one option each provider takes, as delegate_task names it. Haiku 4.5 takes `thinking` instead of `effort`.
OPTIONS = ("effort", "reasoningEffort", "thinking")
MAX_ROUNDS = 3
CONFIRM = "Confirm"


def council() -> dict:
    return tomllib.loads(COUNCIL_FILE.read_text())


def config_problems(config: dict) -> list[str]:
    """Why .github/council.toml can't run a council; empty when it can."""
    problems = []
    if not isinstance(config.get("min_score"), int):
        problems.append(f"{COUNCIL_FILE} needs min_score = <a whole number out of 10>.")
    members = config.get("member", [])
    for m in members:
        name = m.get("name", "<no name>")
        for key in ("name", "role", "provider", "model"):
            if not m.get(key):
                problems.append(f"{COUNCIL_FILE}: member {name} has no {key}.")
        if m.get("role") and m["role"] not in ROLES:
            problems.append(f"{COUNCIL_FILE}: {name} has role \"{m['role']}\"; roles are {', '.join(ROLES)}.")
        unknown = sorted(set(m) - {"name", "role", "provider", "model", *OPTIONS})
        if unknown:
            problems.append(f"{COUNCIL_FILE}: {name} has {', '.join(unknown)}, which delegate_task wouldn't use; "
                            f"its option is one of {', '.join(OPTIONS)}.")
    names = [m.get("name") for m in members]
    if len(set(names)) != len(names):
        problems.append(f"{COUNCIL_FILE}: two members share a name, so their rows in the table can't be told apart.")
    for role in ROLES:
        if not any(m.get("role") == role for m in members):
            problems.append(f"{COUNCIL_FILE} has no \"{role}\" member; a council needs at least one of each role "
                            f"({', '.join(ROLES)}).")
    return problems


def review_problems(body: str, head: str, min_score: int, members: list[tuple[str, str]]) -> list[str]:
    """Why the PR description's council review doesn't pass for this head commit; empty when it does.

    The table's columns are rounds: "Round 1" up to "Round 3", then at most one "Confirm" pass (the whole council again
    after settling only questions and misreadings). Every member is judged on the last column, so results from
    different rounds can't pass together.
    """
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
    table = [[c.strip() for c in line.strip().strip("|").split("|")] for line in text.splitlines()
             if line.strip().startswith("|")]
    header = next((row for row in table if row and row[0] == "Delegate"), None)
    if header is None:
        return problems + ["The council review has no table with a \"Delegate\" header row."]
    columns = header[1:]
    rounds = [c for c in columns if c != CONFIRM]
    if rounds != [f"Round {i}" for i in range(1, len(rounds) + 1)] or columns != rounds + [CONFIRM] * (
            len(columns) - len(rounds)) or columns.count(CONFIRM) > 1 or not rounds:
        problems.append(f"The table's columns are {columns}; they must be \"Round 1\" onwards, then at most one "
                        f"\"{CONFIRM}\".")
    if len(rounds) > MAX_ROUNDS:
        problems.append(f"The council ran {len(rounds)} rounds; it gets at most {MAX_ROUNDS}. Past that, stop and "
                        "reconsider the architecture (.claude/skills/council/SKILL.md, \"Three rounds at most\"), and "
                        "start a new council from round 1 on the redesign.")
    stated = re.search(r"^Rounds:\s*(\d+)\s*$", text, re.M)
    if not stated or int(stated.group(1)) != len(rounds):
        problems.append(f"The \"Rounds:\" line must say {len(rounds)}, the number of Round columns.")
    rows = {row[0]: row[1:] for row in table if row and row[0] not in ("Delegate", "") and not set(row[0]) <= set("-: ")}
    for name, role in members:
        if name not in rows:
            problems.append(f"No row for council member \"{name}\" (names must match {COUNCIL_FILE}).")
            continue
        if len(rows[name]) != len(columns):
            problems.append(f"{name}'s row has {len(rows[name])} result(s) for {len(columns)} column(s): every member "
                            "reports in every round, so the last column is the same pass for all of them.")
            continue
        last = rows[name][-1]
        if role == "deep":
            score = re.fullmatch(r"(\d+(?:\.\d+)?)\s*/\s*10", last)
            if not score:
                problems.append(f"{name}'s last column is \"{last}\", not a score out of 10.")
            elif float(score.group(1)) < min_score:
                problems.append(f"{name} scored {last} in the last column; each deep reviewer needs at least "
                                f"{min_score}/10.")
        elif role == "questions":
            asked = re.fullmatch(r"(\d+)\s+asked,\s*(\d+)\s+open", last)
            if not asked:
                problems.append(f"{name}'s last column is \"{last}\", not \"<n> asked, <n> open\".")
            elif int(asked.group(2)) > 0:
                problems.append(f"{name} still has {asked.group(2)} open question(s). Settle each in the skill's "
                                "order (make it impossible to ask, rename, a checked comment, and a PR answer only for "
                                "history or a choice), then rerun.")
        elif role == "explain":
            misread = re.fullmatch(r"(\d+)\s+misread,\s*(\d+)\s+open", last)
            if not misread:
                problems.append(f"{name}'s last column is \"{last}\", not \"<n> misread, <n> open\".")
            elif int(misread.group(2)) > 0:
                problems.append(f"{name}'s explanation still has {misread.group(2)} open misreading(s). Fix the code a "
                                "misreading came from, or answer in the PR why it's the model's own limit, then rerun.")
        else:
            problems.append(f"{name} has role \"{role}\"; roles are {', '.join(ROLES)}.")
    return problems


def main() -> int:
    base, head = os.environ["BASE"], os.environ["HEAD"]
    rules = patterns()
    config = council()
    bad_config = config_problems(config)
    if bad_config:
        for problem in bad_config:
            print(problem)
        return 1
    min_score = config["min_score"]
    members = [(m["name"], m["role"]) for m in config["member"]]
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
        print(f"Passed: in the council's last pass every deep reviewer scored at least {min_score}/10, and no question "
              f"or misreading is open, on {head[:12]}.")
        return 0
    print()
    for problem in problems:
        print(f"  {problem}")
    print("\nTo merge: run a council on this PR (say \"council\"; .claude/skills/council/SKILL.md), fix what it finds, "
          f"and let it write its table into the description with \"Reviewed commit: {head}\".")
    return 1


if __name__ == "__main__":
    sys.exit(main())
