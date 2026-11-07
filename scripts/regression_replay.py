#!/usr/bin/env python3
"""Regression replay: proves every fixed bug is still guarded by a test that fails without its fix.

For each entry in regressions/regressions.toml it applies regressions/<id>.patch (which undoes the fix) in a
throwaway git worktree at your HEAD, runs only that entry's guard tests, and expects every one of them to fail.
Your own working tree is never touched: the patch only ever lands in build/regression-replay/tree.

    python3 scripts/regression_replay.py                 # every entry (or ./gradlew regressionReplay)
    python3 scripts/regression_replay.py --only ID ...   # some entries
    python3 scripts/regression_replay.py list            # entries and their guards, nothing run
    python3 scripts/regression_replay.py refresh ID      # rewrite a STALE entry's patch against HEAD
    python3 scripts/regression_replay.py save ID         # after resolving refresh's conflicts by hand

Every Gradle run takes the shared lock (/tmp/hadron-ingame.lock, or $HADRON_GRADLE_LOCK) so it never overlaps
another in-game run on this machine. See docs/testing.md, "Regression replay".
"""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import tomllib
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "regressions" / "regressions.toml"
PATCHES = ROOT / "regressions"
WORK = ROOT / "build" / "regression-replay"
TREE = WORK / "tree"
LOGS = WORK / "logs"
LOCK = os.environ.get("HADRON_GRADLE_LOCK", "/tmp/hadron-ingame.lock")
ID_PATTERN = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
GRADLE_TIMEOUT = 45 * 60

# Where a test's class lives decides its tier and the Gradle task that runs it.
FAST_PROJECTS = {"api": ":api", "common": ":common", "neoforge": ":neoforge", "examples/hello": ":examples:hello"}
IN_GAME_PROJECT = "selftest"


class ManifestError(Exception):
    pass


@dataclass(frozen=True)
class Guard:
    test: str  # Class.method, as the reports name it
    tier: str  # "fast" or "in-game"; checked against where the class lives

    @property
    def cls(self) -> str:
        return self.test.split(".")[0]

    @property
    def method(self) -> str:
        return self.test.split(".")[1]


@dataclass(frozen=True)
class Entry:
    id: str
    bug: str
    fix: str  # the commit the fix landed in; the patch was first derived from it
    paths: tuple[str, ...]  # the files the fix changed that the patch undoes
    guards: tuple[Guard, ...]
    unguarded: str | None  # why no test can guard it, when guards is empty
    note: str | None  # how the patch differs from a plain revert of `paths`, for whoever refreshes it

    @property
    def patch(self) -> Path:
        return PATCHES / f"{self.id}.patch"

    @property
    def in_game(self) -> bool:
        return any(g.tier == "in-game" for g in self.guards)


def load_manifest(require_patches: bool = True) -> list[Entry]:
    with MANIFEST.open("rb") as f:
        data = tomllib.load(f)
    entries: list[Entry] = []
    seen: set[str] = set()
    allowed = {"id", "bug", "fix", "paths", "guards", "unguarded", "note"}
    for raw in data.get("regression", []):
        where = f"{MANIFEST.relative_to(ROOT)}, entry {raw.get('id', '<no id>')!r}"
        extra = set(raw) - allowed
        if extra:
            raise ManifestError(f"{where}: unknown key(s) {sorted(extra)}; the keys are {sorted(allowed)}.")
        for key in ("id", "bug", "fix", "paths"):
            if key not in raw:
                raise ManifestError(f"{where}: missing `{key}`.")
        eid = raw["id"]
        if not ID_PATTERN.match(eid):
            raise ManifestError(f"{where}: id must be lowercase words joined by '-', like 'tnt-cancel-lights-fire'.")
        if eid in seen:
            raise ManifestError(f"{where}: id {eid!r} is used twice; give one of them another name.")
        seen.add(eid)
        guards = []
        for g in raw.get("guards", []):
            test, tier = g.get("test", ""), g.get("tier", "")
            if not re.match(r"^[A-Za-z0-9_]+\.[A-Za-z0-9_]+$", test):
                raise ManifestError(f"{where}: guard test {test!r} must be Class.method, like 'DropTests.aMinedBlock'.")
            if tier not in ("fast", "in-game"):
                raise ManifestError(f"{where}: guard {test} needs tier = \"fast\" or \"in-game\", not {tier!r}.")
            guards.append(Guard(test, tier))
        unguarded = raw.get("unguarded")
        if not guards and not unguarded:
            raise ManifestError(f"{where}: no guards. Name the test that fails without the fix in `guards`, or say "
                                f"in `unguarded` why no test can.")
        if guards and unguarded:
            raise ManifestError(f"{where}: has guards and `unguarded`; remove `unguarded`.")
        entry = Entry(eid, raw["bug"], raw["fix"], tuple(raw["paths"]), tuple(guards), unguarded, raw.get("note"))
        if require_patches and not entry.patch.exists():
            raise ManifestError(f"{where}: {entry.patch.relative_to(ROOT)} is missing. Make it with "
                                f"`python3 scripts/regression_replay.py refresh {eid} --from-fix`.")
        entries.append(entry)
    orphans = {p.stem for p in PATCHES.glob("*.patch")} - seen
    if orphans:
        raise ManifestError(f"Patches with no manifest entry: {sorted(orphans)}. Add an entry for each to "
                            f"{MANIFEST.relative_to(ROOT)} or delete the patch.")
    return entries


# ---------------------------------------------------------------------------------------------------------------
# The throwaway tree. Everything that changes files happens in TREE, never in ROOT.


def git(*args: str, cwd: Path = ROOT, check: bool = True, input: str | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=cwd, check=check, text=True, input=input, capture_output=True)


def tree_ready(base: str) -> None:
    """Makes TREE a clean detached worktree at base, reusing its build outputs from earlier runs."""
    git("worktree", "prune")
    if not (TREE / ".git").exists():
        if TREE.exists():
            shutil.rmtree(TREE)
        TREE.parent.mkdir(parents=True, exist_ok=True)
        git("worktree", "add", "--detach", "--force", str(TREE), base)
    tree_reset(base)


def tree_reset(base: str) -> None:
    """Puts TREE back to base exactly: tracked files reverted, files a patch added removed, build outputs kept."""
    git("merge", "--abort", cwd=TREE, check=False)
    git("reset", "--hard", "--quiet", base, cwd=TREE)
    git("clean", "-fdq", cwd=TREE)


def apply_patch(entry: Entry, three_way_conflicts_ok: bool = False) -> str | None:
    """Applies the entry's patch in TREE. Returns None when it applied cleanly, else why it didn't."""
    patch = str(entry.patch)
    if git("apply", "--index", patch, cwd=TREE, check=False).returncode == 0:
        return None
    result = git("apply", "--index", "--3way", patch, cwd=TREE, check=False)
    if result.returncode == 0:
        return None
    if not three_way_conflicts_ok:
        tree_reset(git("rev-parse", "HEAD", cwd=TREE).stdout.strip())
    return (result.stderr or result.stdout).strip()


# ---------------------------------------------------------------------------------------------------------------
# Running guards


@dataclass
class GuardRun:
    """What one Gradle run said about each guard: True failed, False passed, missing means it never ran."""
    failed: dict[str, bool] = field(default_factory=dict)
    trouble: str | None = None  # a reason the results can't be trusted at all
    log: Path | None = None


def locate(guard: Guard) -> tuple[str, str]:
    """Returns (gradle project or 'selftest', fully qualified class) for a guard, from where its source lives."""
    hits = [p for p in ROOT.glob(f"**/src/test/java/**/{guard.cls}.java") if "build" not in p.relative_to(ROOT).parts]
    if len(hits) != 1:
        found = ", ".join(str(h.relative_to(ROOT)) for h in hits) or "nothing"
        raise ManifestError(f"Guard {guard.test}: expected one {guard.cls}.java under a src/test/java, found {found}. "
                            f"Fix the class name in {MANIFEST.relative_to(ROOT)}.")
    rel = hits[0].relative_to(ROOT).as_posix()
    module, _, java = rel.partition("/src/test/java/")
    fqcn = java.removesuffix(".java").replace("/", ".")
    tier = "in-game" if module == IN_GAME_PROJECT else "fast"
    if module != IN_GAME_PROJECT and module not in FAST_PROJECTS:
        raise ManifestError(f"Guard {guard.test} lives in {module}, which the replay doesn't know how to run. Add it to "
                            f"FAST_PROJECTS in scripts/regression_replay.py.")
    if tier != guard.tier:
        raise ManifestError(f"Guard {guard.test} is in {module}, so its tier is \"{tier}\", not \"{guard.tier}\". "
                            f"Fix it in {MANIFEST.relative_to(ROOT)}.")
    return (IN_GAME_PROJECT if tier == "in-game" else FAST_PROJECTS[module]), fqcn


TIMED_OUT = -1


def stop(proc: subprocess.Popen) -> None:
    """Ends the build and everything it started (flock, the Gradle client, a test server), so nothing still reads
    the tree once the replay resets it."""
    for sig, wait in ((signal.SIGTERM, 30), (signal.SIGKILL, 30)):
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            return
        try:
            proc.wait(timeout=wait)
            return
        except subprocess.TimeoutExpired:
            continue


def gradle(args: list[str], log: Path) -> int:
    log.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["flock", LOCK, "./gradlew", "--console=plain", *args]
    with log.open("w") as out:
        out.write("$ " + " ".join(cmd) + "\n")
        out.flush()
        # Its own process group, so a timeout or an interrupt can end the whole build, not only flock.
        proc = subprocess.Popen(cmd, cwd=TREE, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            return proc.wait(timeout=GRADLE_TIMEOUT)
        except subprocess.TimeoutExpired:
            stop(proc)
            out.write(f"\nreplay: Gradle ran past {GRADLE_TIMEOUT // 60} minutes and was stopped\n")
            return TIMED_OUT
        except BaseException:
            stop(proc)
            raise


def compile_trouble(log: Path) -> str | None:
    text = log.read_text(errors="replace")
    m = re.search(r"Execution failed for task '([^']*compile[^']*)'", text, re.IGNORECASE)
    if m:
        return f"{m.group(1)} failed, so the patch leaves code that doesn't compile (see {log.relative_to(ROOT)})"
    return None


def run_fast(guards: list[Guard], label: str) -> GuardRun:
    run = GuardRun(log=LOGS / f"{label}-fast.log")
    by_project: dict[str, list[tuple[Guard, str]]] = {}
    for g in guards:
        project, fqcn = locate(g)
        by_project.setdefault(project, []).append((g, fqcn))
    args: list[str] = ["--continue"]
    for project, items in by_project.items():
        results = TREE / project.strip(":").replace(":", "/") / "build" / "test-results" / "test"
        shutil.rmtree(results, ignore_errors=True)
        args.append(f"{project}:test")
        for g, fqcn in items:
            args += ["--tests", f"{fqcn}.{g.method}"]
    if gradle(args, run.log) == TIMED_OUT:
        run.trouble = f"Gradle timed out, so its results can't be trusted (see {run.log.relative_to(ROOT)})"
        return run
    for project, items in by_project.items():
        results = TREE / project.strip(":").replace(":", "/") / "build" / "test-results" / "test"
        for g, fqcn in items:
            xml = results / f"TEST-{fqcn}.xml"
            if not xml.exists():
                continue
            every = list(ET.parse(xml).getroot().iter("testcase"))
            cases = [c for c in every if c.get("name", "").split("(")[0] == g.method]
            if not cases and sum(1 for other, _ in items if other.cls == g.cls) == 1:
                # A parameterized test's cases are named by their arguments ("[1] .loaded"), not the method. The
                # filter ran only this class's one guard, so its invocations are that guard's. Only invocations count:
                # a class that failed to load reports "initializationError", which is not the guard failing.
                cases = [c for c in every if c.get("name", "").startswith("[")]
            if cases:
                run.failed[g.test] = any(c.find("failure") is not None or c.find("error") is not None for c in cases)
    if len(run.failed) < len(guards):
        run.trouble = compile_trouble(run.log) or f"no result for some tests (see {run.log.relative_to(ROOT)})"
    return run


def run_in_game(guards: list[Guard], label: str) -> GuardRun:
    run = GuardRun(log=LOGS / f"{label}-in-game.log")
    for g in guards:
        locate(g)
    report = TREE / "neoforge" / "build" / "hadron-test" / "report.json"
    report.unlink(missing_ok=True)
    args = [":selftest:hadronTest"]
    for g in guards:
        args += ["--tests", g.test]
    if gradle(args, run.log) == TIMED_OUT:
        run.trouble = f"Gradle timed out, so its results can't be trusted (see {run.log.relative_to(ROOT)})"
        return run
    # The next run overwrites the server's log, so keep this one's beside the Gradle log.
    server_log = TREE / "neoforge" / "build" / "hadron-test" / "run" / "logs" / "latest.log"
    if server_log.exists():
        shutil.copyfile(server_log, LOGS / f"{label}-server.log")
    if not report.exists():
        run.trouble = compile_trouble(run.log) or f"the test server wrote no report (see {run.log.relative_to(ROOT)})"
        return run
    data = json.loads(report.read_text())
    for suite in data.get("suites", []):
        if suite.get("broken"):
            run.trouble = f"the suite didn't run: {suite['broken']}"
        for t in suite.get("tests", []):
            run.failed[t["id"]] = t["status"] != "PASS"
    if data.get("problems"):
        run.trouble = run.trouble or "the run itself failed: " + "; ".join(map(str, data["problems"]))
    return run


def run_guards(guards: list[Guard], label: str) -> GuardRun:
    """Runs fast and in-game guards, each tier in one Gradle invocation, and merges what they said."""
    merged = GuardRun()
    for tier, runner in (("fast", run_fast), ("in-game", run_in_game)):
        chosen = [g for g in guards if g.tier == tier]
        if not chosen:
            continue
        run = runner(chosen, label)
        merged.failed.update(run.failed)
        merged.trouble = merged.trouble or run.trouble
        merged.log = run.log
    return merged


# ---------------------------------------------------------------------------------------------------------------
# Verdicts


class Verdict(Enum):
    GUARDED = "guarded"
    NOT_GUARDED = "NOT GUARDED"
    STALE = "STALE"
    BROKEN = "BROKEN"
    NO_GUARD = "no guard"


@dataclass
class Result:
    entry: Entry
    verdict: Verdict
    detail: str
    seconds: float


def judge(entry: Entry, run: GuardRun) -> tuple[Verdict, str]:
    missing = [g.test for g in entry.guards if g.test not in run.failed]
    if missing:
        return Verdict.BROKEN, f"never ran {', '.join(missing)}: {run.trouble or 'no result'}"
    passed = [g.test for g in entry.guards if not run.failed[g.test]]
    if passed:
        return Verdict.NOT_GUARDED, f"passed with the fix undone: {', '.join(passed)}"
    if run.trouble:
        return Verdict.BROKEN, f"every guard failed, but {run.trouble}"
    return Verdict.GUARDED, f"{len(entry.guards)} guard(s) failed with the fix undone"


def replay(entries: list[Entry], base: str) -> list[Result]:
    results: list[Result] = []
    runnable = [e for e in entries if e.guards]
    for e in entries:
        if not e.guards:
            results.append(Result(e, Verdict.NO_GUARD, e.unguarded or "", 0.0))

    # Baseline: every guard must pass with nothing undone, or its failure later proves nothing.
    started = time.monotonic()
    every = sorted({g for e in runnable for g in e.guards}, key=lambda g: (g.tier, g.test))
    print(f"Baseline: {len(every)} guard test(s) at {base[:10]}, nothing undone ...", flush=True)
    base_run = run_guards(every, "baseline")
    bad_at_base: dict[str, str] = {}
    for g in every:
        if g.test not in base_run.failed:
            bad_at_base[g.test] = f"didn't run at baseline ({base_run.trouble or 'no result'})"
        elif base_run.failed[g.test]:
            bad_at_base[g.test] = "fails even with the fix in place"
    print(f"Baseline done in {time.monotonic() - started:.0f} s", flush=True)

    # Fast-only entries first: they're cheap, and a broken tree shows up before any server boots.
    for e in sorted(runnable, key=lambda e: e.in_game):
        started = time.monotonic()
        broken = [f"{t} {bad_at_base[t]}" for t in (g.test for g in e.guards) if t in bad_at_base]
        if broken:
            results.append(Result(e, Verdict.BROKEN, "; ".join(broken), 0.0))
            print(f"  {e.id}: BROKEN at baseline", flush=True)
            continue
        print(f"  {e.id}: undoing the fix, running {len(e.guards)} guard(s) ...", flush=True)
        try:
            why = apply_patch(e)
            if why is not None:
                detail = f"patch no longer applies ({why.splitlines()[-1] if why else 'git apply refused it'}). " \
                         f"Refresh it: python3 scripts/regression_replay.py refresh {e.id}"
                results.append(Result(e, Verdict.STALE, detail, time.monotonic() - started))
                continue
            verdict, detail = judge(e, run_guards(list(e.guards), e.id))
            results.append(Result(e, verdict, detail, time.monotonic() - started))
        finally:
            tree_reset(base)
        print(f"  {e.id}: {results[-1].verdict.value} ({results[-1].seconds:.0f} s)", flush=True)
    order = {e.id: i for i, e in enumerate(entries)}
    return sorted(results, key=lambda r: order[r.entry.id])


def print_report(results: list[Result], base: str, seconds: float) -> None:
    width = max(len(r.entry.id) for r in results)
    print(f"\nRegression replay at {base[:10]}")
    for r in results:
        print(f"{r.verdict.value:<12} {r.entry.id:<{width}}  {r.detail}")
    counts: dict[Verdict, int] = {}
    for r in results:
        counts[r.verdict] = counts.get(r.verdict, 0) + 1
    print("\n" + ", ".join(f"{n} {v.value}" for v, n in counts.items()) + f" in {seconds / 60:.1f} min")
    bad = [r for r in results if r.verdict in (Verdict.NOT_GUARDED, Verdict.STALE, Verdict.BROKEN)]
    print("RESULT: " + ("FAILED" if bad else "PASSED"))
    if bad:
        print("A NOT GUARDED entry needs a test that fails without its fix; STALE needs its patch refreshed (above); "
              "BROKEN means the result can't be trusted: read the entry's log in build/regression-replay/logs/.")
    report = {
        "base": base, "minutes": round(seconds / 60, 1), "result": "FAILED" if bad else "PASSED",
        "entries": [{"id": r.entry.id, "verdict": r.verdict.name, "detail": r.detail, "seconds": round(r.seconds)}
                    for r in results],
    }
    (WORK / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Report: {(WORK / 'report.json').relative_to(ROOT)}")


# ---------------------------------------------------------------------------------------------------------------
# Commands


def head() -> str:
    return git("rev-parse", "HEAD").stdout.strip()


def pick(entries: list[Entry], ids: list[str]) -> list[Entry]:
    if not ids:
        return entries
    known = {e.id: e for e in entries}
    unknown = [i for i in ids if i not in known]
    if unknown:
        raise ManifestError(f"No entry {unknown}. The ids are: {', '.join(known)}")
    return [known[i] for i in ids]


def cmd_run(args: argparse.Namespace) -> int:
    entries = pick(load_manifest(), args.only)
    base = head()
    if git("status", "--porcelain", "--untracked-files=no").stdout.strip():
        print(f"Note: replaying your last commit {base[:10]}; uncommitted changes aren't part of it.", flush=True)
    started = time.monotonic()
    tree_ready(base)
    try:
        results = replay(entries, base)
    finally:
        tree_reset(base)
    print_report(results, base, time.monotonic() - started)
    return 1 if any(r.verdict in (Verdict.NOT_GUARDED, Verdict.STALE, Verdict.BROKEN) for r in results) else 0


def cmd_list(_: argparse.Namespace) -> int:
    for e in load_manifest():
        guards = ", ".join(f"{g.test} ({g.tier})" for g in e.guards) or f"none: {e.unguarded}"
        print(f"{e.id}\n    {e.bug}\n    fix {e.fix}; guards: {guards}")
    return 0


def cmd_refresh(args: argparse.Namespace) -> int:
    entry = pick(load_manifest(require_patches=False), [args.id])[0]
    if not args.from_fix and not entry.patch.exists():
        raise ManifestError(f"{entry.patch.relative_to(ROOT)} doesn't exist yet; derive it with --from-fix.")
    base = head()
    tree_ready(base)
    if args.from_fix:
        fix = git("rev-parse", entry.fix).stdout.strip()
        diff = git("diff", "--binary", fix, f"{fix}^1", "--", *entry.paths).stdout
        if not diff.strip():
            raise ManifestError(f"{entry.fix} changed none of {entry.paths}; fix `paths` in the manifest.")
        entry.patch.write_text(diff)
    why = apply_patch(entry, three_way_conflicts_ok=True)
    if why is None:
        return save(entry, base)
    print(f"{entry.id}: the patch conflicts with HEAD.\n{why}\n")
    print(f"Resolve the conflicts in {TREE.relative_to(ROOT)} so the code reads as it did before the fix "
          f"({entry.bug}){' ' + entry.note if entry.note else ''}, then run:\n"
          f"    python3 scripts/regression_replay.py save {entry.id}")
    return 1


def save(entry: Entry, base: str) -> int:
    git("add", "-A", cwd=TREE)
    diff = git("diff", "--binary", "--cached", base, cwd=TREE).stdout
    if "<<<<<<<" in diff or ">>>>>>>" in diff:
        print(f"{TREE.relative_to(ROOT)} still has conflict markers; resolve them, then run save again.")
        return 1
    if not diff.strip():
        print(f"{TREE.relative_to(ROOT)} has no changes, so there's nothing to save; run refresh {entry.id} first.")
        return 1
    entry.patch.write_text(diff)
    tree_reset(base)
    print(f"Wrote {entry.patch.relative_to(ROOT)} against {base[:10]}. Check it with: "
          f"python3 scripts/regression_replay.py --only {entry.id}")
    return 0


def cmd_save(args: argparse.Namespace) -> int:
    entry = pick(load_manifest(require_patches=False), [args.id])[0]
    return save(entry, head())


def interrupted(*_: object) -> None:
    raise KeyboardInterrupt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", action="append", default=[], metavar="ID", help="replay only this entry (repeat)")
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("list", help="print every entry and its guards")
    refresh = sub.add_parser("refresh", help="rewrite an entry's patch so it applies to HEAD")
    refresh.add_argument("id")
    refresh.add_argument("--from-fix", action="store_true",
                         help="derive the patch afresh from the entry's fix commit and paths (drops hand edits)")
    save_cmd = sub.add_parser("save", help="save the replay tree's changes as an entry's patch")
    save_cmd.add_argument("id")
    args = parser.parse_args()

    # An interrupt or kill still runs the finally blocks that put the throwaway tree back.
    signal.signal(signal.SIGTERM, interrupted)
    if args.command != "list":
        # One replay at a time owns the throwaway tree: another would reset or patch it under this one's build.
        WORK.mkdir(parents=True, exist_ok=True)
        tree_lock = (WORK / "tree.lock").open("w")
        try:
            fcntl.flock(tree_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(f"regression replay: another replay or refresh is using {TREE.relative_to(ROOT)}; wait for it to "
                  "finish, then run this again.", file=sys.stderr)
            return 3
    try:
        return {"list": cmd_list, "refresh": cmd_refresh, "save": cmd_save}.get(args.command, cmd_run)(args)
    except ManifestError as e:
        print(f"regression replay: {e}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("\nInterrupted; the throwaway tree was put back. Your working tree was never touched.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
