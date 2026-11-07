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


class Config(unittest.TestCase):
    def config(self, **changes) -> dict:
        members = [{"name": n, "role": r, "provider": "p", "model": "m", "effort": "high"} for n, r in MEMBERS]
        return {"min_score": 8, "member": members, **changes}

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


if __name__ == "__main__":
    unittest.main()
