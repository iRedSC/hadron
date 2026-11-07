"""Tests for council_gate.py: each way a council table could pass while it shouldn't. Run in CI with
python3 -m unittest discover -s .github/scripts
"""

import unittest

import council_gate as gate

HEAD = "0123456789abcdef0123456789abcdef01234567"
MEMBERS = [("Deep", "deep"), ("Asker", "questions"), ("Explainer", "explain")]


def body(rows: dict[str, list[str]], columns: list[str], rounds: int | None = None, commit: str = HEAD) -> str:
    stated = sum(1 for c in columns if c != gate.CONFIRM) if rounds is None else rounds
    lines = ["## Council review", "", f"Reviewed commit: {commit}", f"Rounds: {stated}", "",
             "| Delegate | " + " | ".join(columns) + " |", "|" + " --- |" * (len(columns) + 1)]
    lines += [f"| {name} | " + " | ".join(cells) + " |" for name, cells in rows.items()]
    return "\n".join(lines) + "\n\n🤖 Generated"


PASSING = {"Deep": ["7/10", "9/10"], "Asker": ["4 asked, 2 open", "5 asked, 0 open"],
           "Explainer": ["2 misread, 1 open", "2 misread, 0 open"]}


def problems(text: str) -> list[str]:
    return gate.review_problems(text, HEAD, 8, MEMBERS)


class ReviewTable(unittest.TestCase):
    def test_a_passing_review_passes(self):
        self.assertEqual(problems(body(PASSING, ["Round 1", "Round 2"])), [])

    def test_a_confirm_pass_after_round_three_passes(self):
        rows = {name: cells + [cells[-1]] + [cells[-1]] for name, cells in PASSING.items()}
        self.assertEqual(problems(body(rows, ["Round 1", "Round 2", "Round 3", "Confirm"])), [])

    def test_no_section_fails(self):
        self.assertTrue(problems("no review here"))

    def test_a_review_of_another_commit_fails(self):
        self.assertTrue(problems(body(PASSING, ["Round 1", "Round 2"], commit="f" * 40)))

    def test_a_low_score_fails(self):
        rows = dict(PASSING, Deep=["9/10", "7/10"])
        self.assertTrue(problems(body(rows, ["Round 1", "Round 2"])))

    def test_an_open_question_fails(self):
        rows = dict(PASSING, Asker=["4 asked, 0 open", "5 asked, 1 open"])
        self.assertTrue(problems(body(rows, ["Round 1", "Round 2"])))

    def test_an_open_misreading_fails(self):
        rows = dict(PASSING, Explainer=["2 misread, 0 open", "3 misread, 1 open"])
        self.assertTrue(problems(body(rows, ["Round 1", "Round 2"])))

    def test_a_missing_member_fails(self):
        rows = {k: v for k, v in PASSING.items() if k != "Explainer"}
        self.assertTrue(problems(body(rows, ["Round 1", "Round 2"])))

    def test_no_score_fails(self):
        rows = dict(PASSING, Deep=["7/10", "no score"])
        self.assertTrue(problems(body(rows, ["Round 1", "Round 2"])))

    def test_a_member_that_skipped_the_last_round_fails(self):
        # Its passing round 1 must not count for round 2.
        rows = dict(PASSING, Explainer=["0 misread, 0 open"])
        self.assertTrue(problems(body(rows, ["Round 1", "Round 2"])))

    def test_more_than_three_rounds_fails(self):
        rows = {name: [cells[-1]] * 4 for name, cells in PASSING.items()}
        self.assertTrue(problems(body(rows, ["Round 1", "Round 2", "Round 3", "Round 4"])))

    def test_a_rounds_line_that_disagrees_with_the_columns_fails(self):
        self.assertTrue(problems(body(PASSING, ["Round 1", "Round 2"], rounds=1)))

    def test_two_confirm_passes_fail(self):
        rows = {name: cells + [cells[-1]] * 2 for name, cells in PASSING.items()}
        self.assertTrue(problems(body(rows, ["Round 1", "Round 2", "Confirm", "Confirm"])))

    def test_out_of_order_columns_fail(self):
        self.assertTrue(problems(body(PASSING, ["Round 2", "Round 1"])))

    def test_a_confirm_after_a_failed_round_fails(self):
        # A Confirm must not stand in for a fourth round.
        rows = {"Deep": ["7/10", "7/10", "7/10", "9/10"], "Asker": ["1 asked, 0 open"] * 4,
                "Explainer": ["0 misread, 0 open"] * 4}
        self.assertTrue(problems(body(rows, ["Round 1", "Round 2", "Round 3", "Confirm"])))

    def test_a_confirm_that_adds_questions_fails(self):
        rows = dict(PASSING, Deep=["7/10", "9/10", "9/10"], Asker=["4 asked, 2 open", "5 asked, 1 open",
                                                                   "6 asked, 0 open"],
                    Explainer=["2 misread, 1 open", "2 misread, 0 open", "2 misread, 0 open"])
        self.assertTrue(problems(body(rows, ["Round 1", "Round 2", "Confirm"])))

    def test_a_confirm_that_settles_what_was_left_passes(self):
        rows = {"Deep": ["7/10", "9/10", "9/10"], "Asker": ["4 asked, 2 open", "5 asked, 1 open", "5 asked, 0 open"],
                "Explainer": ["2 misread, 1 open", "2 misread, 1 open", "2 misread, 0 open"]}
        self.assertEqual(problems(body(rows, ["Round 1", "Round 2", "Confirm"])), [])

    def test_a_second_row_for_one_member_fails(self):
        text = body(dict(PASSING, Deep=["7/10", "3/10"]), ["Round 1", "Round 2"])
        text = text.replace("| Asker |", "| Deep | 9/10 | 9/10 |\n| Asker |", 1)
        self.assertTrue(problems(text))

    def test_a_second_table_further_down_doesnt_count(self):
        failing = dict(PASSING, Deep=["7/10", "3/10"])
        text = body(failing, ["Round 1", "Round 2"]).replace(
            "\n\n🤖", "\n\nNotes:\n\n| Deep | 9/10 | 9/10 |\n\n🤖")
        self.assertTrue(problems(text))

    def test_a_blank_earlier_result_fails(self):
        self.assertTrue(problems(body(dict(PASSING, Asker=["", "5 asked, 0 open"]), ["Round 1", "Round 2"])))

    def test_a_score_above_ten_fails(self):
        self.assertTrue(problems(body(dict(PASSING, Deep=["7/10", "80/10"]), ["Round 1", "Round 2"])))

    def test_more_open_than_asked_fails(self):
        self.assertTrue(problems(body(dict(PASSING, Asker=["1 asked, 3 open", "5 asked, 0 open"]),
                                      ["Round 1", "Round 2"])))

    def test_a_short_commit_gets_its_own_message(self):
        found = problems(body(PASSING, ["Round 1", "Round 2"], commit=HEAD[:8]))
        self.assertTrue(any("too short" in p for p in found), found)

    def test_the_round_cap_message_says_to_wait_for_the_owner(self):
        rows = {name: [cells[-1]] * 4 for name, cells in PASSING.items()}
        found = problems(body(rows, ["Round 1", "Round 2", "Round 3", "Round 4"]))
        self.assertTrue(any("wait for the owner" in p for p in found), found)


class Config(unittest.TestCase):
    def config(self, **changes) -> dict:
        members = [{"name": n, "role": r, "provider": "claudeAgent", "model": "m", "effort": "high"}
                   for n, r in MEMBERS]
        return {"min_score": 8, "member": members, **changes}

    def test_min_score_must_be_a_whole_number_from_1_to_10(self):
        for bad in (True, 0, 11, -1, 8.5, "8"):
            self.assertTrue(gate.config_problems(self.config(min_score=bad)), bad)

    def test_a_codex_member_with_a_claude_option_is_refused(self):
        config = self.config()
        config["member"][0]["provider"] = "codex"
        self.assertTrue(gate.config_problems(config))

    def test_a_member_needs_exactly_one_option(self):
        none, two = self.config(), self.config()
        del none["member"][0]["effort"]
        two["member"][0]["thinking"] = True
        self.assertTrue(gate.config_problems(none))
        self.assertTrue(gate.config_problems(two))

    def test_an_option_value_must_be_one_it_takes(self):
        config = self.config()
        config["member"][0]["effort"] = "hgih"
        self.assertTrue(gate.config_problems(config))

    def test_an_unknown_provider_is_refused(self):
        config = self.config()
        config["member"][0]["provider"] = "gemini"
        self.assertTrue(gate.config_problems(config))

    def test_a_full_council_is_valid(self):
        self.assertEqual(gate.config_problems(self.config()), [])

    def test_a_council_without_an_explainer_is_refused(self):
        config = self.config()
        config["member"] = [m for m in config["member"] if m["role"] != "explain"]
        self.assertTrue(gate.config_problems(config))

    def test_a_misspelled_role_is_named(self):
        config = self.config()
        config["member"][2]["role"] = "explian"
        found = gate.config_problems(config)
        self.assertTrue(any("explian" in p and "Explainer" in p and "explain" in p for p in found), found)

    def test_a_misspelled_option_is_refused(self):
        config = self.config()
        config["member"][0]["reasoningEfort"] = "high"
        self.assertTrue(gate.config_problems(config))

    def test_two_members_with_one_name_are_refused(self):
        config = self.config()
        config["member"][1]["name"] = "Deep"
        self.assertTrue(gate.config_problems(config))

    def test_the_committed_council_is_valid(self):
        self.assertEqual(gate.config_problems(gate.council()), [])


class Paths(unittest.TestCase):
    RULES = [(g, gate.glob_to_regex(g)) for g in ("gradle/**", "**build.gradle.kts", "api/data/*.java")]

    def test_a_change_under_a_protected_glob_needs_review(self):
        dead, hits = gate.needs_review(["api/data/Store.java", "README.md"],
                                       ["gradle/a.kts", "build.gradle.kts", "api/data/Store.java"], self.RULES)
        self.assertEqual(dead, [])
        self.assertEqual(hits, [("api/data/Store.java", "api/data/*.java")])

    def test_star_stays_inside_one_folder(self):
        _, hits = gate.needs_review(["api/data/deep/Store.java"], ["gradle/a", "build.gradle.kts",
                                                                   "api/data/A.java"], self.RULES)
        self.assertEqual(hits, [])

    def test_a_glob_that_matches_nothing_is_reported(self):
        dead, _ = gate.needs_review([], ["gradle/a", "build.gradle.kts"], self.RULES)
        self.assertEqual(dead, ["api/data/*.java"])

    def test_nested_build_files_are_protected(self):
        _, hits = gate.needs_review(["neoforge/build.gradle.kts"], ["gradle/a", "build.gradle.kts",
                                                                     "api/data/A.java"], self.RULES)
        self.assertEqual(hits, [("neoforge/build.gradle.kts", "**build.gradle.kts")])


if __name__ == "__main__":
    unittest.main()
