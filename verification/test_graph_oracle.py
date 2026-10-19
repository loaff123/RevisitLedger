"""Independent abstract graph-oracle tests; no production code is imported."""
import importlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
D1 = "11" * 32
D2 = "22" * 32


def graph(records, files=None, choices=None, complete=True):
    """Tiny hand-specified graph input, deliberately independent of fixture generation."""
    return {"name": "unit", "files": files or sorted({r["file"] for r in records}),
            "records": records, "choices": choices or {}, "scan_complete": complete}


def record(name, file, kind="revisit", digest=D1, candidates=(), concrete=None, ordinal=1):
    return {"id": name, "file": file, "ordinal": ordinal, "kind": kind,
            "declared_digest": digest, "concrete_digest": concrete,
            "candidates": list(candidates)}


def provider(name, file, digest=D1, ordinal=1):
    return record(name, file, "provider", digest, concrete=digest, ordinal=ordinal)


class GraphOracleTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(importlib.util.find_spec("graph_oracle"),
                             "independent graph oracle has not been implemented")
        self.oracle = importlib.import_module("graph_oracle")

    def plan(self, spec, seeds):
        return self.oracle.solve(spec, seeds)

    def test_added_provider_file_activates_unrelated_revisit(self):
        spec = graph([record("a", "A", candidates=["b"]), provider("b", "B"),
                      record("extra", "B", candidates=["c"], ordinal=2), provider("c", "C"),
                      provider("irrelevant", "D")])
        result = self.plan(spec, ["A"])
        self.assertEqual(result["included_files"], ["A", "B", "C"])
        self.assertTrue(result["complete"])
        self.assertEqual(result["blockers"], {})

    def test_every_subset_of_known_whole_file_example(self):
        spec = graph([record("a", "A", candidates=["b"]), provider("b", "B"),
                      record("extra", "B", candidates=["c"], ordinal=2), provider("c", "C")])
        expected = {(): [], ("A",): ["A", "B", "C"], ("B",): ["B", "C"], ("C",): ["C"],
                    ("A", "B"): ["A", "B", "C"], ("A", "C"): ["A", "B", "C"],
                    ("B", "C"): ["B", "C"], ("A", "B", "C"): ["A", "B", "C"]}
        actual = {tuple(case["seeds"]): case["included_files"]
                  for case in self.oracle.enumerate_cases(spec)}
        self.assertEqual(actual, expected)

    def test_successful_chain_includes_intermediate_files(self):
        spec = graph([record("a", "A", candidates=["b"]), record("b", "B", candidates=["c"]),
                      provider("c", "C")])
        result = self.plan(spec, ["A"])
        self.assertEqual(result["included_files"], ["A", "B", "C"])
        self.assertEqual(result["outcomes"], {"a": "resolved", "b": "resolved", "c": "resolved"})

    def test_every_declared_chain_digest_is_checked(self):
        for bad in ("a", "b", "c"):
            records = [record("a", "A", candidates=["b"]), record("b", "B", candidates=["c"]),
                       provider("c", "C")]
            next(r for r in records if r["id"] == bad)["declared_digest"] = D2
            with self.subTest(bad=bad):
                result = self.plan(graph(records), ["A"])
                self.assertEqual(result["included_files"], ["A"])
                self.assertEqual(result["outcomes"]["a"], "digest_mismatch")
                self.assertFalse(result["complete"])

    def test_cycle_and_tail_are_blocked_without_adding_files(self):
        spec = graph([record("a", "A", candidates=["b"]), record("b", "B", candidates=["c"]),
                      record("c", "C", candidates=["b"])])
        result = self.plan(spec, ["A"])
        self.assertEqual(set(result["outcomes"].values()), {"cycle"})
        self.assertEqual(result["included_files"], ["A"])
        self.assertEqual(result["blockers"], {"a": "cycle"})

    def test_missing_unsupported_and_ambiguous_propagate(self):
        cases = [([], "revisit", "missing_in_corpus"),
                 ([], "unsupported", "unsupported"),
                 (["p", "q"], "revisit", "ambiguous")]
        for candidates, kind, status in cases:
            spec = graph([record("a", "A", candidates=["b"]),
                          record("b", "B", kind=kind, candidates=candidates),
                          provider("p", "C"), provider("q", "D")])
            with self.subTest(status=status):
                result = self.plan(spec, ["A"])
                self.assertEqual(result["outcomes"]["a"], status)
                self.assertEqual(result["blockers"], {"a": status})

    def test_choice_requires_chosen_provider_whole_file(self):
        spec = graph([record("a", "A", candidates=["b", "c"]), provider("b", "B"),
                      provider("c", "C"), record("extra", "C", candidates=["d"], ordinal=2),
                      provider("d", "D")], choices={"a": "c"})
        result = self.plan(spec, ["A"])
        self.assertEqual(result["included_files"], ["A", "C", "D"])
        self.assertTrue(result["complete"])

    def test_choice_cannot_bypass_declared_digest(self):
        spec = graph([record("a", "A", digest=D2, candidates=["p"]), provider("p", "B")],
                     choices={"a": "p"})
        result = self.plan(spec, ["A"])
        self.assertEqual(result["blockers"], {"a": "digest_mismatch"})

    def test_incomplete_scan_never_certifies_even_empty_seed(self):
        spec = graph([provider("p", "A")], complete=False)
        result = self.plan(spec, [])
        self.assertEqual(result["included_files"], [])
        self.assertEqual(result["blockers"], {"$input": "input_incomplete"})
        self.assertFalse(result["complete"])

    def test_incomplete_scan_blocks_edges_before_adding_files(self):
        spec = graph([record("a", "A", candidates=["p"]), provider("p", "B")], complete=False)
        result = self.plan(spec, ["A"])
        self.assertEqual(result["included_files"], ["A"])
        self.assertEqual(result["outcomes"]["a"], "input_incomplete")
        self.assertEqual(result["blockers"], {"$input": "input_incomplete", "a": "input_incomplete"})

    def test_standalone_provider_integrity_error_blocks_included_file(self):
        bad = provider("p", "A")
        bad["declared_digest"] = D2
        spec = graph([bad, provider("good", "B")])
        self.assertEqual(self.plan(spec, ["A"])["blockers"], {"p": "digest_mismatch"})
        self.assertTrue(self.plan(spec, ["B"])["complete"])

    def test_nonenumerated_choices_block_instead_of_creating_edges(self):
        for target in ("not_in_corpus", "q"):
            spec = graph([record("a", "A", candidates=["p"]), provider("p", "B"),
                          provider("q", "C")], choices={"a": target})
            with self.subTest(target=target):
                result = self.plan(spec, ["A"])
                self.assertEqual(result["included_files"], ["A"])
                self.assertEqual(result["blockers"], {"a": "selector_conflict"})

    def test_empty_file_and_all_seeds_are_preserved(self):
        spec = graph([provider("p", "A")], files=["A", "B"])
        self.assertEqual(self.plan(spec, ["B"])["included_files"], ["B"])
        self.assertTrue(self.plan(spec, ["B"])["complete"])
        self.assertEqual(self.plan(spec, ["A", "B"])["included_files"], ["A", "B"])

    def test_reordering_spec_does_not_change_result(self):
        spec = graph([record("a", "A", candidates=["b", "c"]), provider("b", "B"),
                      provider("c", "C")], choices={"a": "b"})
        reversed_spec = json.loads(json.dumps(spec))
        reversed_spec["files"].reverse()
        reversed_spec["records"].reverse()
        reversed_spec["records"][-1]["candidates"].reverse()
        self.assertEqual(self.plan(spec, ["A", "C"]), self.plan(reversed_spec, ["C", "A"]))

    def test_unknown_seed_and_duplicate_identity_are_rejected(self):
        with self.assertRaises(ValueError):
            self.plan(graph([provider("p", "A")]), ["absent"])
        with self.assertRaises(ValueError):
            self.plan(graph([provider("p", "A"), provider("p", "B")]), ["A"])

    def test_all_seed_subsets_and_checked_in_results_are_deterministic(self):
        specs = self.oracle.build_specs()
        names = [spec["name"] for spec in specs]
        self.assertEqual(len(names), len(set(names)))
        self.assertTrue(any(name.startswith("random-") for name in names))
        for spec in specs:
            cases = self.oracle.enumerate_cases(spec)
            self.assertEqual(len(cases), 2 ** len(spec["files"]))
            for case in cases:
                self.assertTrue(set(case["seeds"]) <= set(case["included_files"]))
                self.assertEqual(case, self.plan(spec, case["seeds"]))
        self.assertEqual(self.oracle.render_json(), self.oracle.render_json())
        self.assertEqual(self.oracle.render_tsv(), self.oracle.render_tsv())
        self.assertEqual(self.oracle.check(), [])

    def test_generated_alternatives_are_realizable_digest_only_candidates(self):
        for spec in self.oracle.build_specs():
            by_id = {row["id"]: row for row in spec["records"]}
            for row in spec["records"]:
                if len(row["candidates"]) < 2:
                    continue
                with self.subTest(graph=spec["name"], record=row["id"]):
                    expected = {ident for ident, candidate in by_id.items()
                                if candidate["kind"] == "provider"
                                and candidate["concrete_digest"] == row["declared_digest"]}
                    self.assertEqual(set(row["candidates"]), expected)

    def test_json_and_tsv_describe_identical_cases(self):
        document = json.loads(self.oracle.render_json())
        expected = {entry["spec"]["name"]: entry for entry in document["graphs"]}
        current = None
        case_index = 0
        for line in self.oracle.render_tsv().splitlines():
            if line.startswith("#"):
                continue
            fields = line.split("\t")
            tag = fields[0]
            if tag == "GRAPH":
                current = expected[fields[1]]
                case_index = 0
                self.assertEqual(fields[2], str(current["spec"]["scan_complete"]).lower())
            elif tag == "CASE":
                case = current["cases"][case_index]
                split = lambda value: [] if value == "-" else value.split(",")
                self.assertEqual(split(fields[1]), case["seeds"])
                self.assertEqual(split(fields[2]), case["included_files"])
                self.assertEqual(fields[3], str(case["complete"]).lower())
                self.assertEqual(dict(item.split("=") for item in split(fields[4])), case["blockers"])
                case_index += 1
            elif tag == "OUTCOME":
                self.assertEqual(fields[2], current["cases"][0]["outcomes"][fields[1]])
            elif tag == "END":
                self.assertEqual(case_index, len(current["cases"]))

    def test_check_reports_missing_and_modified_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(len(self.oracle.check(tmp)), 2)
            path = Path(tmp)
            (path / "cases.json").write_text(self.oracle.render_json(), encoding="utf-8")
            (path / "cases.tsv").write_text(self.oracle.render_tsv(), encoding="utf-8")
            self.assertEqual(self.oracle.check(tmp), [])
            with (path / "cases.tsv").open("a", encoding="utf-8") as output:
                output.write("\n")
            self.assertEqual(self.oracle.check(tmp), [
                "cases.tsv: checked-in bytes disagree with the independent oracle"])


if __name__ == "__main__":
    unittest.main()
