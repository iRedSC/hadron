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
from dataclasses import dataclass
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
# Each provider's one option, as delegate_task names it, and the values it takes.
EFFORTS = ("low", "medium", "high", "xhigh", "max", "ultra")
PROVIDER_OPTIONS = {
    "claudeAgent": {"effort": EFFORTS, "thinking": (True, False)},
    "codex": {"reasoningEffort": EFFORTS},
}
MAX_ROUNDS = 3
CONFIRM = "Confirm"


def council() -> dict:
    return tomllib.loads(COUNCIL_FILE.read_text())


def config_problems(config: dict) -> list[str]:
    """Why .github/council.toml can't run a council; empty when it can."""
    problems = []
    score = config.get("min_score")
    # bool is an int in Python, so `min_score = true` would otherwise pass as 1.
    if isinstance(score, bool) or not isinstance(score, int) or not 1 <= score <= 10:
        problems.append(f"{COUNCIL_FILE} needs min_score = <a whole number from 1 to 10>.")
    members = config.get("member", [])
    for m in members:
        name = m.get("name", "<no name>")
        for key in ("name", "role", "provider", "model"):
            if not m.get(key):
                problems.append(f"{COUNCIL_FILE}: member {name} has no {key}.")
        if m.get("role") and m["role"] not in ROLES:
            problems.append(f"{COUNCIL_FILE}: {name} has role \"{m['role']}\"; roles are {', '.join(ROLES)}.")
        allowed = PROVIDER_OPTIONS.get(m.get("provider"))
        if allowed is None:
            problems.append(f"{COUNCIL_FILE}: {name}'s provider \"{m.get('provider')}\" isn't one the council knows "
                            f"({', '.join(PROVIDER_OPTIONS)}); add its option to PROVIDER_OPTIONS in "
                            ".github/scripts/council_gate.py.")
            continue
        options = sorted(set(m) - {"name", "role", "provider", "model"})
        if len(options) != 1 or options[0] not in allowed:
            problems.append(f"{COUNCIL_FILE}: {name} has {options or 'no option'}; a {m['provider']} member takes "
                            f"exactly one of {', '.join(allowed)}.")
        elif m[options[0]] not in allowed[options[0]]:
            problems.append(f"{COUNCIL_FILE}: {name}'s {options[0]} is {m[options[0]]!r}; it takes one of "
                            f"{', '.join(map(str, allowed[options[0]]))}.")
    names = [m.get("name") for m in members]
    if len(set(names)) != len(names):
        problems.append(f"{COUNCIL_FILE}: two members share a name, so their rows in the table can't be told apart.")
    for role in ROLES:
        if not any(m.get("role") == role for m in members):
            problems.append(f"{COUNCIL_FILE} has no \"{role}\" member; a council needs at least one of each role "
                            f"({', '.join(ROLES)}).")
    return problems


# ---- The table, parsed into results before any rule looks at it --------------------------------------------------

@dataclass(frozen=True)
class Score:
    value: float | None  # None is "no score"


@dataclass(frozen=True)
class Counted:
    total: int  # questions asked, or misreadings found
    open: int


CELL_FORMATS = {"deep": "a score out of 10 (0 to 10) or \"no score\"", "questions": "\"<n> asked, <n> open\"",
                "explain": "\"<n> misread, <n> open\""}


def parse_cell(role: str, cell: str) -> Score | Counted | None:
    if role == "deep":
        if cell == "no score":
            return Score(None)
        m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*/\s*10", cell)
        return Score(float(m.group(1))) if m and float(m.group(1)) <= 10 else None
    word = "asked" if role == "questions" else "misread"
    m = re.fullmatch(rf"(\d+)\s+{word},\s*(\d+)\s+open", cell)
    if not m or int(m.group(2)) > int(m.group(1)):
        return None
    return Counted(int(m.group(1)), int(m.group(2)))


def parse_table(text: str, members: list[tuple[str, str]]) -> tuple[list[str], dict[str, list], list[str]]:
    """The columns and each member's parsed results, from the one table under the "Delegate" header.

    Only the rows directly after the header and its separator count, so a second table or a note further down can't
    stand in for the review. Every cell of every member must parse for its role.
    """
    lines = text.splitlines()
    starts = [i for i, line in enumerate(lines) if re.match(r"^\s*\|\s*Delegate\s*\|", line)]
    if len(starts) != 1:
        return [], {}, ["The council review needs exactly one table with a \"Delegate\" header row."]
    split = lambda line: [c.strip() for c in line.strip().strip("|").split("|")]
    columns = split(lines[starts[0]])[1:]
    block = []
    for line in lines[starts[0] + 1:]:
        if not line.strip().startswith("|"):
            break
        block.append(split(line))
    if not block or not all(set(c) <= set("-: ") for c in block[0]):
        return columns, {}, ["The council table's header needs its \"| --- |\" separator row right below it."]
    problems = []
    roles = dict(members)
    seen = set()
    results = {}
    for row in block[1:]:
        name, cells = row[0], row[1:]
        if name in seen:
            problems.append(f"\"{name}\" has two rows in the council table; keep one.")
            continue
        seen.add(name)
        if name not in roles:
            continue
        if len(cells) != len(columns):
            problems.append(f"{name}'s row has {len(cells)} result(s) for {len(columns)} column(s): every member "
                            "reports in every pass.")
            continue
        parsed = [parse_cell(roles[name], cell) for cell in cells]
        for column, cell, result in zip(columns, cells, parsed):
            if result is None:
                problems.append(f"{name}'s {column} result is \"{cell}\", not {CELL_FORMATS[roles[name]]}.")
        if all(r is not None for r in parsed):
            results[name] = parsed
    return columns, results, problems


def review_problems(body: str, head: str, min_score: int, members: list[tuple[str, str]]) -> list[str]:
    """Why the PR description's council review doesn't pass for this head commit; empty when it does.

    The table's columns are passes: "Round 1" up to "Round 3", then at most one "Confirm", which reruns the whole
    council to rule on what the last round left and may add nothing new. Every member is judged on the last column.
    """
    section = re.search(r"^## Council review\s*$(.*?)(?=^## |\Z)", body, re.M | re.S)
    if not section:
        return ["The PR description has no \"## Council review\" section."]
    text = section.group(1)
    problems = []
    commit = re.search(r"^Reviewed commit:\s*`?([0-9a-f]{7,40})`?\s*$", text, re.M)
    if not commit:
        problems.append("The council review has no \"Reviewed commit: <sha>\" line naming the commit it reviewed.")
    elif len(commit.group(1)) < 12:
        problems.append(f"\"Reviewed commit: {commit.group(1)}\" is too short to be sure of the commit: give at least "
                        "12 characters of the SHA (the full one is best).")
    elif not head.startswith(commit.group(1)):
        problems.append(f"The council reviewed {commit.group(1)}, but the PR's head is {head[:12]}: commits pushed "
                        "after the review need a fresh pass.")
    columns, results, table_problems = parse_table(text, members)
    problems += table_problems
    rounds = [c for c in columns if c != CONFIRM]
    confirm = columns[-1:] == [CONFIRM]
    if columns and (not rounds or rounds != [f"Round {i}" for i in range(1, len(rounds) + 1)]
                    or columns != rounds + [CONFIRM] * confirm):
        problems.append(f"The table's columns are {columns}; they must be \"Round 1\" onwards, then at most one "
                        f"\"{CONFIRM}\".")
    if len(rounds) > MAX_ROUNDS:
        problems.append(f"The council ran {len(rounds)} rounds; it gets at most {MAX_ROUNDS}. Stop changing code, "
                        "write \"Architecture reconsidered\" in the PR (.claude/skills/council/SKILL.md), and wait for "
                        "the owner: only a redesign the owner approves starts a new council.")
    stated = re.search(r"^Rounds:\s*(\d+)\s*$", text, re.M)
    if columns and (not stated or int(stated.group(1)) != len(rounds)):
        problems.append(f"The \"Rounds:\" line must say {len(rounds)}, the number of Round columns.")
    for name, role in members:
        if name not in results:
            if not any(name in p for p in table_problems):
                problems.append(f"No row for council member \"{name}\" (names must match {COUNCIL_FILE}).")
            continue
        last = results[name][-1]
        if role == "deep":
            if last.value is None or last.value < min_score:
                problems.append(f"{name} gave {'no score' if last.value is None else f'{last.value:g}/10'} in the "
                                f"last column; each deep reviewer needs at least {min_score}/10.")
            if confirm and len(results[name]) > 1:
                before = results[name][-2]
                if before.value is None or before.value < min_score:
                    problems.append(f"{name} didn't pass {rounds[-1]}, so no Confirm may follow it; a failed last "
                                    "round means reconsidering the architecture.")
        else:
            kind = "question" if role == "questions" else "misreading"
            if last.open > 0:
                how = ("Settle each in the skill's order (make it impossible to ask, rename, a checked comment, and a "
                       "PR answer only for history or a choice)" if role == "questions" else
                       "Fix the code a misreading came from, or answer in the PR why it's the model's own limit")
                problems.append(f"{name} has {last.open} open {kind}(s). {how}, then rerun.")
            if confirm and len(results[name]) > 1 and last.total != results[name][-2].total:
                problems.append(f"{name}'s Confirm counts {last.total} {kind}(s) against {results[name][-2].total} in "
                                f"{rounds[-1]}: a Confirm pass rules only on what the last round left, and adds none.")
    return problems


def needs_review(changed: list[str], tracked: list[str], rules: list[tuple[str, re.Pattern[str]]]) \
        -> tuple[list[str], list[tuple[str, str]]]:
    """The globs that match no tracked file (and so protect nothing), and each changed file a glob protects."""
    dead = [glob for glob, rx in rules if not any(rx.match(name) for name in tracked)]
    hits = [(name, glob) for name in changed for glob, rx in rules if rx.match(name)]
    return dead, hits


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
    dead, hits = needs_review(changed_files(base, head), tracked, rules)
    if dead:
        print(f"{PATHS_FILE} has globs that match no file, so they protect nothing. Fix or delete them:")
        for glob in dead:
            print(f"  {glob}")
        return 1
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
