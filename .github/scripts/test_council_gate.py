"""Tests for council_gate.py: each way a record could pass while it shouldn't. Run in CI with
python3 -m unittest discover -s .github/scripts
"""

import copy
import unittest

import council_gate as gate

HEAD = "0123456789abcdef0123456789abcdef01234567"
CONFIG = {
    "min_findings": 3, "stop_at": "P3", "max_rounds": 5, "cleanup_lines": 40, "min_score": 8,
    "member": [
        {"name": "Asker", "role": "questions", "provider": "claudeAgent", "model": "m", "effort": "medium"},
        {"name": "Deep A", "role": "council", "provider": "codex", "model": "m", "reasoningEffort": "high"},
        {"name": "Deep B", "role": "council", "provider": "claudeAgent", "model": "m", "effort": "medium"},
        {"name": "Explainer", "role": "explain", "provider": "codex", "model": "m", "reasoningEffort": "low"},
        {"name": "Final", "role": "final", "provider": "codex", "model": "m", "reasoningEffort": "high"},
    ],
}
ROUNDS = {"Deep A": ["5 found, top P1", "3 found, top P3"], "Deep B": ["4 found, top P2", "4 found, top P4"]}


def record(rows=ROUNDS, questions="Asker", explained="Explainer", final=None, columns=None) -> str:
    columns = columns or [f"Round {i}" for i in range(1, len(next(iter(rows.values()))) + 1)]
    lines = ["## Council review", "", "Council commit: " + HEAD, f"Questions: {questions}",
             f"Explained: {explained}"] + ([f"Final review: {final}"] if final else []) + [
        "", "| Member | " + " | ".join(columns) + " |", "|" + " --- |" * (len(columns) + 1)]
    lines += [f"| {name} | " + " | ".join(cells) + " |" for name, cells in rows.items()]
    return "\n".join(lines) + "\n\n🤖 Generated"


def problems(text: str, cleanup: int | None = 0) -> list[str]:
    return gate.review_problems(text, HEAD, CONFIG, cleanup)


class Record(unittest.TestCase):
    def test_a_finished_review_passes(self):
        self.assertEqual(problems(record()), [])

    def test_no_section_fails(self):
        self.assertTrue(problems("nothing here"))

    def test_a_council_commit_outside_the_history_fails(self):
        self.assertTrue(problems(record(), cleanup=None))

    def test_a_last_round_above_the_stop_level_asks_for_another_round(self):
        found = problems(record(dict(ROUNDS, **{"Deep B": ["4 found, top P2", "4 found, top P2"]})))
        self.assertTrue(any("Round 3" in p for p in found), found)

    def test_too_few_findings_fails(self):
        self.assertTrue(problems(record(dict(ROUNDS, **{"Deep A": ["5 found, top P1", "2 found, top P4"]}))))

    def test_not_converging_in_five_rounds_says_to_wait_for_the_owner(self):
        rows = {name: ["3 found, top P1"] * 5 for name in ROUNDS}
        found = problems(record(rows))
        self.assertTrue(any("wait for the owner" in p for p in found), found)

    def test_more_than_five_rounds_fails(self):
        self.assertTrue(problems(record({name: ["3 found, top P4"] * 6 for name in ROUNDS})))

    def test_a_member_missing_a_round_fails(self):
        self.assertTrue(problems(record(dict(ROUNDS, **{"Deep B": ["4 found, top P4"]}),
                                        columns=["Round 1", "Round 2"])))

    def test_a_missing_member_fails(self):
        self.assertTrue(problems(record({"Deep A": ROUNDS["Deep A"]})))

    def test_a_malformed_cell_fails(self):
        self.assertTrue(problems(record(dict(ROUNDS, **{"Deep A": ["5 found, top P1", "fine"]}))))

    def test_a_second_row_for_one_member_fails(self):
        text = record(dict(ROUNDS, **{"Deep A": ["5 found, top P1", "3 found, top P1"]}))
        text = text.replace("| Deep B |", "| Deep A | 3 found, top P4 | 3 found, top P4 |\n| Deep B |", 1)
        self.assertTrue(problems(text))

    def test_a_second_table_further_down_doesnt_count(self):
        text = record(dict(ROUNDS, **{"Deep A": ["5 found, top P1", "3 found, top P1"]}))
        text = text.replace("\n\n🤖", "\n\n| Member | Round 1 | Round 2 |\n| --- | --- | --- |\n🤖")
        self.assertTrue(problems(text))

    def test_skipping_the_questions_pass_fails(self):
        self.assertTrue(problems(record(questions="")))

    def test_skipping_the_explain_pass_fails(self):
        self.assertTrue(problems(record(explained="Someone else")))

    def test_a_large_cleanup_needs_the_final_review(self):
        self.assertTrue(problems(record(), cleanup=41))

    def test_a_large_cleanup_with_a_passing_final_review_of_the_head_passes(self):
        self.assertEqual(problems(record(final=f"Final, {HEAD}, 9/10"), cleanup=41), [])

    def test_a_final_review_of_another_commit_fails(self):
        self.assertTrue(problems(record(final=f"Final, {'f' * 40}, 9/10"), cleanup=41))

    def test_a_low_final_score_fails(self):
        self.assertTrue(problems(record(final=f"Final, {HEAD}, 7/10"), cleanup=41))

    def test_a_small_cleanup_needs_no_final_review(self):
        self.assertEqual(problems(record(), cleanup=40), [])


class Config(unittest.TestCase):
    def changed(self, change) -> list[str]:
        config = copy.deepcopy(CONFIG)
        change(config)
        return gate.config_problems(config)

    def test_the_test_config_is_valid(self):
        self.assertEqual(gate.config_problems(CONFIG), [])

    def test_the_committed_config_is_valid(self):
        import tomllib
        self.assertEqual(gate.config_problems(tomllib.loads(gate.COUNCIL_FILE.read_text())), [])

    def test_numbers_must_be_whole_and_in_range(self):
        for bad in (True, 0, 21, 2.5, "3"):
            self.assertTrue(self.changed(lambda c: c.update(min_findings=bad)), bad)

    def test_stop_at_must_be_a_priority(self):
        self.assertTrue(self.changed(lambda c: c.update(stop_at="P5")))

    def test_each_role_needs_a_member(self):
        for role in gate.ROLES:
            self.assertTrue(self.changed(lambda c: c.update(member=[m for m in c["member"] if m["role"] != role])))

    def test_a_second_final_reviewer_is_refused(self):
        self.assertTrue(self.changed(lambda c: c["member"].append(dict(c["member"][4], name="Final 2"))))

    def test_an_option_the_provider_doesnt_take_is_refused(self):
        self.assertTrue(self.changed(lambda c: c["member"][1].update(effort="high")))

    def test_a_bad_option_value_is_refused(self):
        self.assertTrue(self.changed(lambda c: c["member"][0].update(effort="ultra")))

    def test_two_members_with_one_name_are_refused(self):
        self.assertTrue(self.changed(lambda c: c["member"][2].update(name="Deep A")))


class Paths(unittest.TestCase):
    RULES = [(g, gate.glob_to_regex(g)) for g in ("gradle/**", "**build.gradle.kts", "api/data/*.java")]
    TRACKED = ["gradle/a.kts", "build.gradle.kts", "api/data/Store.java"]

    def test_a_change_under_a_protected_glob_needs_review(self):
        self.assertEqual(gate.needs_review(["api/data/Store.java", "README.md"], self.TRACKED, self.RULES),
                         ([], [("api/data/Store.java", "api/data/*.java")]))

    def test_star_stays_inside_one_folder(self):
        self.assertEqual(gate.needs_review(["api/data/deep/Store.java"], self.TRACKED, self.RULES)[1], [])

    def test_nested_build_files_are_protected(self):
        self.assertEqual(gate.needs_review(["neoforge/build.gradle.kts"], self.TRACKED, self.RULES)[1],
                         [("neoforge/build.gradle.kts", "**build.gradle.kts")])

    def test_a_glob_that_matches_nothing_is_reported(self):
        self.assertEqual(gate.needs_review([], self.TRACKED[:2], self.RULES)[0], ["api/data/*.java"])


if __name__ == "__main__":
    unittest.main()
