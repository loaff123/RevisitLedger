#!/usr/bin/env python3
"""Independent, test-only oracle for the supported abstract dependency contract.

No production Java code, output logic, parser, or test helper is imported here.
Candidate sets are *input facts*, already determined by selector agreement. This
oracle proves chain digest consistency and whole-file inclusion; selector syntax,
WARC parsing, corpus hashing, and resource limits are deliberately outside it.

Algorithmic independence:
* Outcomes are the least fixed point of simultaneous proof rules, with the
  remaining finite functional-graph components classified as cycles.
* Reachability is a saturated binary relation, not a recursive/iterative walk.
* For each seed set we enumerate EVERY file subset satisfying all successful
  chosen-chain requirements, then intersect those admissible supersets. This is
  intentionally exponential and suitable only for tiny qualification cases.

Successful chains require the files of all intermediate records and the concrete
provider. Failed chains add no dependency files. Every revisit in each included
file contributes its own requirements or blocker. Ambiguity requires an explicit
choice among enumerated candidates; equal payload digests alone are not corrupt.
A provider's declared digest is checked both through incoming chains and as an
integrity blocker when its own file is included. Incomplete scans conservatively
block every revisit before choosing any edge and always block certification.

Protocol v1 in graph_cases/cases.tsv (tab separated, no escaping is needed):
  GRAPH name scan_complete
  FILE relative_file
  RECORD file ordinal id kind declared_digest concrete_digest candidate_ids
  CHOICE source_id target_id
  OUTCOME record_id status
  CASE seeds included_files complete blockers
  END
Lists are comma-separated; '-' means absent/empty. A blocker is id=status;
'$input=input_incomplete' represents an incomplete corpus scan. Digests are
canonical SHA-256 hex tokens. Ordinals are one-based within each file. All
identifiers are delimiter-safe ASCII. 'unsupported' is an unsupported-profile
revisit. Candidate alternatives in generated fixtures are digest-equivalent
concrete providers, allowing independent Java tests to express them as genuine
digest-only selectors. A single candidate can be expressed as a record-ID
selector. JSON contains the same facts plus full expected cases.

Run `python3 verification/graph_oracle.py --write` to deliberately regenerate the
checked-in fixtures; `--check` computes results twice and compares both encodings
byte-for-byte with their checked-in forms. Neither mode calls the network/Java.
"""
from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path
import random
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
CASE_DIR = Path(__file__).resolve().parent / "graph_cases"
D1, D2, D3 = (token * 32 for token in ("11", "22", "33"))
RANDOM_SEED = 20261017
TOKEN = re.compile(r"[A-Za-z0-9_.-]+\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")


def powerset(values):
    """Enumerate the finite universe by cardinality, then lexical order."""
    ordered = sorted(values)
    return (frozenset(items) for count in range(len(ordered) + 1)
            for items in itertools.combinations(ordered, count))


def _validate(spec):
    files = spec["files"]
    if len(files) != len(set(files)) or not all(TOKEN.fullmatch(f) for f in files):
        raise ValueError("files must be unique delimiter-safe tokens")
    if not isinstance(spec.get("scan_complete", True), bool):
        raise ValueError("scan_complete must be boolean")
    records = {}
    locators = set()
    for row in spec["records"]:
        ident = row["id"]
        if not TOKEN.fullmatch(ident) or ident in records:
            raise ValueError("record IDs must be unique delimiter-safe tokens")
        if row["file"] not in files or type(row["ordinal"]) is not int or row["ordinal"] < 1:
            raise ValueError("invalid record location")
        locator = (row["file"], row["ordinal"])
        if locator in locators:
            raise ValueError("record locations must be unique")
        locators.add(locator)
        if row["kind"] not in {"provider", "revisit", "unsupported"}:
            raise ValueError("unknown abstract record kind")
        for field in ("declared_digest", "concrete_digest"):
            value = row.get(field)
            if value is not None and not DIGEST.fullmatch(value):
                raise ValueError("digests must be canonical SHA-256 hex tokens")
        if row["kind"] == "provider" and row.get("concrete_digest") is None:
            raise ValueError("concrete provider requires independently known digest")
        if row["kind"] != "provider" and row.get("concrete_digest") is not None:
            raise ValueError("only concrete providers may carry concrete digests")
        candidates = row.get("candidates", [])
        if len(candidates) != len(set(candidates)):
            raise ValueError("candidate identities must be unique")
        if row["kind"] == "provider" and candidates:
            raise ValueError("concrete providers cannot have candidates")
        records[ident] = row
    for row in records.values():
        if any(candidate not in records for candidate in row.get("candidates", [])):
            raise ValueError("candidate IDs must enumerate existing corpus records")
    for source, target in spec.get("choices", {}).items():
        if source not in records or records[source]["kind"] == "provider":
            raise ValueError("choice source must identify an existing revisit")
        if not isinstance(target, str) or not TOKEN.fullmatch(target):
            raise ValueError("choice targets must be delimiter-safe tokens")
    return records


def _prove(spec):
    records = _validate(spec)
    outcomes = {}
    terminals = {}
    bindings = {}
    for ident, row in records.items():
        kind = row["kind"]
        declared = row.get("declared_digest")
        if kind == "provider":
            outcomes[ident] = ("digest_mismatch" if declared is not None and
                               declared != row["concrete_digest"] else "resolved")
            if outcomes[ident] == "resolved":
                terminals[ident] = ident
        elif not spec.get("scan_complete", True):
            outcomes[ident] = "input_incomplete"
        elif kind == "unsupported":
            outcomes[ident] = "unsupported"
        else:
            candidates = row.get("candidates", [])
            choice = spec.get("choices", {}).get(ident)
            if choice is not None and choice not in candidates:
                outcomes[ident] = "selector_conflict"
            elif not candidates:
                outcomes[ident] = "missing_in_corpus"
            elif ident in spec.get("choices", {}):
                bindings[ident] = spec["choices"][ident]
            elif len(candidates) == 1:
                bindings[ident] = candidates[0]
            else:
                outcomes[ident] = "ambiguous"

    # Apply all currently justified proofs simultaneously. Unlike a production
    # walker, this has no work queue, recursion, visit colors, or path cache.
    while True:
        next_outcomes = {}
        next_terminals = {}
        for source, target in bindings.items():
            if source in outcomes or target not in outcomes:
                continue
            status = outcomes[target]
            if status == "resolved":
                terminal = terminals[target]
                declared = records[source].get("declared_digest")
                concrete = records[terminal]["concrete_digest"]
                status = "digest_mismatch" if declared is not None and declared != concrete else "resolved"
                if status == "resolved":
                    next_terminals[source] = terminal
            next_outcomes[source] = status
        if not next_outcomes:
            break
        outcomes.update(next_outcomes)
        terminals.update(next_terminals)
    # In a finite outdegree-one relation, any record left without a sink proof
    # reaches a cycle. This includes both cycle members and incoming tails.
    outcomes.update((ident, "cycle") for ident in records if ident not in outcomes)

    relation = set(bindings.items())
    while True:
        expanded = relation | {(a, d) for a, b in relation for c, d in relation if b == c}
        if expanded == relation:
            break
        relation = expanded
    requirements = {
        ident: frozenset(records[target]["file"] for source, target in relation if source == ident)
        for ident, row in records.items()
        if row["kind"] != "provider" and outcomes[ident] == "resolved"
    }
    return records, outcomes, requirements


def solve(spec, seed_files):
    """Return exact least whole-file closure and scoped blockers for one seed set."""
    records, outcomes, requirements = _prove(spec)
    seeds = frozenset(seed_files)
    universe = frozenset(spec["files"])
    if not seeds <= universe:
        raise ValueError("seed files must belong to the supplied corpus")
    admissible = [subset for subset in powerset(universe)
                  if seeds <= subset and all(
                      records[source]["file"] not in subset or required <= subset
                      for source, required in requirements.items())]
    included = frozenset.intersection(*admissible)
    # Intersection is itself admissible because the requirements are positive
    # implications. Check the proof rather than silently relying on that fact.
    assert seeds <= included
    assert all(records[source]["file"] not in included or required <= included
               for source, required in requirements.items())
    blockers = {ident: outcomes[ident] for ident, row in records.items()
                if row["file"] in included and outcomes[ident] != "resolved"}
    if not spec.get("scan_complete", True):
        blockers["$input"] = "input_incomplete"
    return {"seeds": sorted(seeds), "included_files": sorted(included),
            "complete": not blockers, "blockers": dict(sorted(blockers.items())),
            "outcomes": dict(sorted(outcomes.items()))}


def enumerate_cases(spec):
    return [solve(spec, seeds) for seeds in powerset(spec["files"])]


def _record(ident, file, kind="revisit", declared=D1, concrete=None, candidates=()):
    return {"id": ident, "file": file, "ordinal": 0, "kind": kind,
            "declared_digest": declared, "concrete_digest": concrete,
            "candidates": list(candidates)}


def _provider(ident, file, digest=D1, declared=None):
    return _record(ident, file, "provider", digest if declared is None else declared, digest)


def _spec(name, rows, files=None, choices=None, complete=True):
    counters = {}
    for row in rows:
        file = row["file"]
        counters[file] = counters.get(file, 0) + 1
        row["ordinal"] = counters[file]
    return {"name": name, "files": sorted(files or counters), "records": rows,
            "choices": choices or {}, "scan_complete": complete}


def build_specs():
    """Hand-built edge cases plus tiny reproducible random abstract graphs."""
    specs = [
        _spec("empty-files", [], ["A", "B"]),
        _spec("provider-only", [_provider("p", "A")], ["A", "B"]),
        _spec("whole-file-unrelated-revisit", [
            _record("a", "A", candidates=["b"]), _provider("b", "B"),
            _record("extra", "B", candidates=["c"]), _provider("c", "C"),
            _provider("unrelated", "D", D2)]),
        _spec("three-file-chain", [_record("a", "A", candidates=["b"]),
                                   _record("b", "B", candidates=["c"]), _provider("c", "C")]),
        _spec("self-cycle", [_record("a", "A", candidates=["a"])]),
        _spec("cycle-with-tail", [_record("a", "A", candidates=["b"]),
                                  _record("b", "B", candidates=["c"]),
                                  _record("c", "C", candidates=["b"])]),
        _spec("missing-terminal", [_record("a", "A", candidates=["b"]), _record("b", "B")]),
        _spec("unsupported-intermediate", [_record("a", "A", candidates=["b"]),
                                           _record("b", "B", "unsupported", candidates=["c"]),
                                           _provider("c", "C")]),
        _spec("outer-digest-mismatch", [_record("a", "A", declared=D2, candidates=["b"]),
                                        _record("b", "B", candidates=["c"]), _provider("c", "C")]),
        _spec("intermediate-digest-mismatch", [_record("a", "A", candidates=["b"]),
                                               _record("b", "B", declared=D2, candidates=["c"]),
                                               _provider("c", "C")]),
        _spec("provider-digest-mismatch", [_record("a", "A", candidates=["b"]),
                                          _provider("b", "B", D1, declared=D2),
                                          _provider("unrelated", "C")]),
        _spec("alternatives-unbound", [_record("a", "A", candidates=["b", "c"]),
                                       _provider("b", "B"), _provider("c", "C")]),
        _spec("choice-left", [_record("a", "A", candidates=["b", "c"]),
                              _provider("b", "B"), _provider("c", "C"),
                              _record("extra", "C", declared=D2, candidates=["d"]),
                              _provider("d", "D", D2)], choices={"a": "b"}),
        _spec("choice-right-whole-file", [_record("a", "A", candidates=["b", "c"]),
                                          _provider("b", "B"), _provider("c", "C"),
                                          _record("extra", "C", declared=D2, candidates=["d"]),
                                          _provider("d", "D", D2)], choices={"a": "c"}),
        _spec("same-file-chain", [_record("a", "A", candidates=["b"]),
                                  _record("b", "A", candidates=["c"]), _provider("c", "A")],
              files=["A", "B"]),
        _spec("unrelated-cycle-excluded", [_record("a", "A", candidates=["b"]), _provider("b", "B"),
                                          _record("cycle", "C", candidates=["cycle"])]),
        _spec("added-file-unresolved-revisit", [_record("a", "A", candidates=["b"]), _provider("b", "B"),
                                               _record("extra", "B")]),
        _spec("incomplete-scan", [_record("a", "A", candidates=["b"]), _provider("b", "B")], complete=False),
        _spec("incomplete-empty-corpus", [], files=["A"], complete=False),
    ]
    rng = random.Random(RANDOM_SEED)
    files = ["A", "B", "C", "D"]
    for number in range(24):
        # Three concrete payloads deliberately include equivalent alternatives.
        rows = [_provider("p0", rng.choice(files), D1), _provider("p1", rng.choice(files), D1),
                _provider("p2", rng.choice(files), D2)]
        choices = {}
        names = ["p0", "p1", "p2", "r0", "r1", "r2", "r3", "r4"]
        for i in range(5):
            ident = "r" + str(i)
            mode = rng.randrange(6)
            kind = "unsupported" if mode == 0 else "revisit"
            digest = rng.choice([D1, D1, D2])
            candidates = [] if mode in (0, 1) else [rng.choice(names)]
            if mode == 5:
                candidates, digest = ["p0", "p1"], D1
                if rng.randrange(2):
                    choices[ident] = rng.choice(candidates)
            rows.append(_record(ident, rng.choice(files), kind, digest, candidates=candidates))
        specs.append(_spec(f"random-{number:02d}", rows, files, choices))
    return specs


def _document():
    return {"schema": "revisitledger-independent-graph-v1", "random_seed": RANDOM_SEED,
            "method": "simultaneous proof rules; relation saturation; exhaustive admissible file subsets",
            "graphs": [{"spec": spec, "cases": enumerate_cases(spec)} for spec in build_specs()]}


def render_json():
    return json.dumps(_document(), sort_keys=True, indent=2) + "\n"


def _list(values):
    return ",".join(sorted(values)) or "-"


def render_tsv():
    lines = ["# revisitledger-independent-graph-v1", "# See verification/graph_oracle.py for the protocol."]
    for spec in build_specs():
        lines.append("\t".join(["GRAPH", spec["name"], str(spec["scan_complete"]).lower()]))
        lines.extend("FILE\t" + file for file in sorted(spec["files"]))
        for row in sorted(spec["records"], key=lambda r: (r["file"], r["ordinal"])):
            lines.append("\t".join(["RECORD", row["file"], str(row["ordinal"]), row["id"], row["kind"],
                                    row.get("declared_digest") or "-", row.get("concrete_digest") or "-",
                                    _list(row.get("candidates", []))]))
        for source, target in sorted(spec["choices"].items()):
            lines.append("\t".join(["CHOICE", source, target]))
        cases = enumerate_cases(spec)
        for ident, status in cases[0]["outcomes"].items():
            lines.append("\t".join(["OUTCOME", ident, status]))
        for case in cases:
            lines.append("\t".join(["CASE", _list(case["seeds"]), _list(case["included_files"]),
                                    str(case["complete"]).lower(),
                                    _list(source + "=" + status for source, status in case["blockers"].items())]))
        lines.append("END")
    return "\n".join(lines) + "\n"


def check(directory=CASE_DIR):
    """Check fresh deterministic renders, then compare committed artifact bytes."""
    errors = []
    for filename, render in (("cases.json", render_json), ("cases.tsv", render_tsv)):
        first = render().encode("utf-8")
        if first != render().encode("utf-8"):
            errors.append(f"{filename}: repeated generation is nondeterministic")
        path = Path(directory) / filename
        if not path.is_file():
            errors.append(f"{filename}: checked-in fixture is missing")
        elif path.read_bytes() != first:
            errors.append(f"{filename}: checked-in bytes disagree with the independent oracle")
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)
    if args.write:
        CASE_DIR.mkdir(parents=True, exist_ok=True)
        for name, render in (("cases.json", render_json), ("cases.tsv", render_tsv)):
            (CASE_DIR / name).write_text(render(), encoding="utf-8", newline="\n")
    errors = check()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    specs = build_specs()
    print(f"Graph oracle: {len(specs)} graphs, {sum(2 ** len(s['files']) for s in specs)} seed subsets; "
          "JSON/TSV expectations and repeat determinism agree.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
