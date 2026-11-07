"""Tests for council_gate.py: each way a record could pass while it shouldn't. Run in CI with
python3 -m unittest discover -s .github/scripts
"""

import copy
import unittest

import council_gate as gate

HEAD = "0123456789abcdef0123456789abcdef01234567"
CONFIG = {
    "min_findings": 3, "stop_at": "P3", "max_rounds": 5,
    "member": [
        {"name": "Asker", "role": "questions", "provider": "claudeAgent", "model": "m", "effort": "medium"},
        {"name": "Deep A", "role": "council", "provider": "codex", "model": "m", "reasoningEffort": "high"},
        {"name": "Deep B", "role": "council", "provider": "claudeAgent", "model": "m", "effort": "medium"},
        {"name": "Explainer", "role": "explain", "provider": "codex", "model": "m", "reasoningEffort": "low"},
        {"name": "Final", "role": "final", "provider": "codex", "model": "m", "reasoningEffort": "high"},
    ],
}


def line(name="Final", sha=HEAD, top="P4") -> str:
    return f"Final review: {name}, {sha}, 2 found, top {top}"


def problems(body: str) -> list[str]:
    return gate.review_problems(body, HEAD, CONFIG)


class FinalReview(unittest.TestCase):
    def test_a_passing_final_review_of_the_head_passes(self):
        self.assertEqual(problems(f"Some text\n\n{line()}\n\n🤖 Generated"), [])

    def test_the_stop_level_itself_passes(self):
        self.assertEqual(problems(line(top="P3")), [])

    def test_one_level_more_serious_than_the_stop_level_fails(self):
        self.assertTrue(problems(line(top="P2")))

    def test_no_final_review_fails(self):
        self.assertTrue(problems("nothing here"))

    def test_a_final_review_of_another_commit_fails(self):
        self.assertTrue(problems(line(sha="f" * 40)))

    def test_a_short_sha_fails(self):
        self.assertTrue(problems(line(sha=HEAD[:12])))

    def test_a_final_review_by_someone_else_fails(self):
        self.assertTrue(problems(line(name="Deep A")))

    def test_a_stale_pass_beside_a_failing_pass_fails(self):
        self.assertTrue(problems(line() + "\n" + line(top="P0")))


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

    def test_members_written_as_one_table_get_a_message_not_a_crash(self):
        self.assertTrue(self.changed(lambda c: c.update(member={"name": "x"})))

    def test_a_member_without_a_name_is_refused(self):
        self.assertTrue(self.changed(lambda c: c["member"][1].pop("name")))

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
