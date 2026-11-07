"""Fails when a PR changes a path in .github/council-paths.txt without a finished council review.

The record is a "## Council review" section in the PR description, written by the council skill:

    ## Council review

    Council commit: <the commit the last council round reviewed>
    Questions: Claude Haiku 5.5, GPT-6.1 Terra
    Explained: Claude Haiku 5.5 (explainer), GPT-6 Luna
    Final review: GPT-6.1 Sol (final), <the PR's head>, 9/10      (only when the cleanup was large)

    | Member | Round 1 | Round 2 |
    | --- | --- | --- |
    | GPT-6.1 Sol | 5 found, top P1 | 3 found, top P3 |
    | Claude Opus 5.5 | 4 found, top P2 | 4 found, top P4 |

It passes when every council member found at least min_findings issues in every round, the last round found nothing
more serious than stop_at (P0 is the most serious), every question asker and explainer is listed, and, if the code changed by more than cleanup_lines lines
since the council commit, the final reviewer passed the PR's head. The cleanup size comes from git, not the record.

Reads BASE and HEAD (commit SHAs) and PR_BODY from the environment. CI runs main's copy of this script, the path list
and the member list against the PR, so a PR can't change what checks it.
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
    for key, low, high in (("min_findings", 1, 20), ("max_rounds", 1, 10), ("cleanup_lines", 0, 10_000),
                           ("min_score", 1, 10)):
        if not whole(config.get(key), low, high):
            problems.append(f"{COUNCIL_FILE} needs {key} = <a whole number from {low} to {high}>.")
    if config.get("stop_at") not in ("P0", "P1", "P2", "P3", "P4"):
        problems.append(f"{COUNCIL_FILE} needs stop_at = one of \"P0\" to \"P4\".")
    members = config.get("member", [])
    for m in members:
        name = m.get("name", "<no name>")
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


def table(text: str) -> tuple[list[str], dict[str, list[str]], list[str]]:
    """The round columns and each row's cells, from the one table under the "Member" header and its separator."""
    lines = text.splitlines()
    starts = [i for i, line in enumerate(lines) if re.match(r"^\s*\|\s*Member\s*\|", line)]
    if len(starts) != 1:
        return [], {}, ["The record needs exactly one table with a \"| Member |\" header."]
    cells = lambda line: [c.strip() for c in line.strip().strip("|").split("|")]
    block = []
    for line in lines[starts[0] + 1:]:
        if not line.strip().startswith("|"):
            break
        block.append(cells(line))
    if not block or not all(set(c) <= set("-: ") for c in block[0]):
        return [], {}, ["The table needs its \"| --- |\" separator row right below the header."]
    rows, problems = {}, []
    for row in block[1:]:
        if row[0] in rows:
            problems.append(f"\"{row[0]}\" has two rows in the table; keep one.")
        rows[row[0]] = row[1:]
    return cells(lines[starts[0]])[1:], rows, problems


def record_section(body: str) -> str | None:
    """The text of the PR description's "## Council review" section, or None when there isn't one."""
    section = re.search(r"^## Council review\s*$(.*?)(?=^## |\Z)", body, re.M | re.S)
    return section.group(1) if section else None


def council_commit(text: str) -> str | None:
    line = re.search(r"^Council commit:[ \t]*([0-9a-f]{12,40})[ \t]*$", text, re.M)
    return line.group(1) if line else None


def listed(text: str, label: str) -> list[str]:
    # [ \t], not \s, so an empty line can't borrow the names on the next one.
    line = re.search(rf"^{label}:[ \t]*([^\n]+)$", text, re.M)
    return [name.strip() for name in line.group(1).split(",")] if line else []


def review_problems(body: str, head: str, config: dict, cleanup: int | None) -> list[str]:
    """Why the PR description's record doesn't pass for this head commit; empty when it does.

    `cleanup` is the number of code lines changed from the council commit to the head, or None when the council
    commit isn't an ancestor of the head.
    """
    text = record_section(body)
    if text is None:
        return ["The PR description has no \"## Council review\" section."]
    members = {role: [m["name"] for m in config["member"] if m["role"] == role] for role in ROLES}
    problems = []

    if council_commit(text) is None:
        problems.append("The record needs \"Council commit: <sha>\", at least 12 characters, naming the commit the "
                        "last council round reviewed.")
    elif cleanup is None:
        problems.append("The council commit isn't in this PR's history: rebasing or force-pushing after the council "
                        "needs a new council round on the rewritten commits.")

    columns, rows, table_problems = table(text)
    problems += table_problems
    rounds = len(columns)
    if columns != [f"Round {i}" for i in range(1, rounds + 1)] or not 1 <= rounds <= config["max_rounds"]:
        problems.append(f"The table's columns are {columns}; they must be \"Round 1\" up to "
                        f"\"Round {config['max_rounds']}\", in order.")
    stop = int(config["stop_at"][1])
    last_tops = []
    for name in members["council"]:
        cells = rows.get(name)
        if cells is None or len(cells) != rounds:
            problems.append(f"Council member \"{name}\" needs a result in every round (names must match "
                            f"{COUNCIL_FILE}).")
            continue
        for column, cell in zip(columns, cells):
            m = re.fullmatch(r"(\d+) found, top P([0-4])", cell)
            if not m:
                problems.append(f"{name}'s {column} is \"{cell}\", not \"<n> found, top P<0-4>\".")
            elif int(m.group(1)) < config["min_findings"]:
                problems.append(f"{name} found {m.group(1)} issue(s) in {column}; each member must find at least "
                                f"{config['min_findings']} per round.")
            elif column == columns[-1]:
                last_tops.append(int(m.group(2)))
    if len(last_tops) == len(members["council"]) and any(top < stop for top in last_tops):
        if rounds >= config["max_rounds"]:
            problems.append(f"The council didn't converge in {config['max_rounds']} rounds. Stop changing code, write "
                            "\"Architecture reconsidered\" in the PR (.claude/skills/council/SKILL.md), and wait for "
                            "the owner.")
        else:
            problems.append(f"Round {rounds} still found something more serious than {config['stop_at']}: fix it "
                            f"and run Round {rounds + 1}.")

    for label, role in (("Questions", "questions"), ("Explained", "explain")):
        missing = sorted(set(members[role]) - set(listed(text, label)))
        if missing:
            problems.append(f"\"{label}:\" must list {', '.join(missing)}: run the {role} pass "
                            f"(.claude/skills/{role}/SKILL.md).")

    if cleanup is not None and cleanup > config["cleanup_lines"]:
        final = re.search(r"^Final review:\s*(.+?),\s*([0-9a-f]{12,40}),\s*(\d+(?:\.\d+)?)/10\s*$", text, re.M)
        if not final or final.group(1) != members["final"][0] or not head.startswith(final.group(2)) \
                or float(final.group(3)) < config["min_score"]:
            problems.append(f"{cleanup} code lines changed after the council commit (more than "
                            f"{config['cleanup_lines']}), so {members['final'][0]} must review the head: "
                            f"\"Final review: {members['final'][0]}, {head}, <score>/10\", at least "
                            f"{config['min_score']}/10.")
    return problems


# A changed binary file's size isn't known in lines, so it counts as more than any threshold.
BINARY = 10_000


def count_cleanup(numstat: str, rules: list[tuple[str, re.Pattern[str]]]) -> int:
    """Lines added plus removed in `git diff --numstat -z` output, not counting .md files outside the protected paths
    (the review skills are .md, and they count)."""
    total = 0
    for entry in numstat.split("\0"):
        if not entry:
            continue
        added, removed, path = entry.split("\t", 2)
        if path.endswith(".md") and not any(rx.match(path) for _, rx in rules):
            continue
        total += BINARY if added == "-" else int(added) + int(removed)
    return total


def cleanup_lines(commit: str, head: str, rules: list[tuple[str, re.Pattern[str]]]) -> int | None:
    """Lines changed from the council commit to the head, or None when the commit isn't in the head's history."""
    if git("merge-base", "--is-ancestor", commit, head).returncode != 0:
        return None
    diff = git("diff", "--no-renames", "--numstat", "-z", commit, head)
    if diff.returncode != 0:
        raise SystemExit(f"git diff {commit} {head} failed, so the cleanup can't be measured: {diff.stderr.strip()}")
    return count_cleanup(diff.stdout, rules)


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
        print("No path here needs a council review.")
        return 0
    print("This PR changes paths that need a council review:")
    for name, glob in hits:
        print(f"  {name}  (matches {glob})")
    body = os.environ.get("PR_BODY") or ""
    commit = council_commit(record_section(body) or "")
    cleanup = cleanup_lines(commit, head, rules) if commit else None
    problems = review_problems(body, head, config, cleanup)
    if not problems:
        print(f"Passed: the council converged and every pass ran, for {head[:12]}.")
        return 0
    print("\n".join(f"  {p}" for p in problems))
    return 1


if __name__ == "__main__":
    sys.exit(main())
