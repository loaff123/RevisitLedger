# Related tools and project positioning

The intended workflow is retrospective, offline whole-file dependency planning for
an arbitrary explicit local WARC subset. It combines conservative reference
resolution with independently hashed payload evidence and an explainable sufficient
file set. This is a workflow distinction, not a new graph algorithm or a claim to be
the first missing-reference checker.

- [warcat-rs](https://github.com/chfoo/warcat-rs/blob/cf5f71a67e5c19464039eadde41c616149c5ec11/src/verify.rs)
  already checks referenced record-ID presence and URI/date syntax. The inspected
  pinned implementation does not provide this URI/date candidate and whole-file
  subset planning workflow. Its executable was unavailable for the local comparison;
  no measured warcat-rs result is claimed.
- [warcio](https://github.com/webrecorder/warcio/blob/2a797aa8c6d67e70966ce9d1a690208ab250fbe7/warcio/checker.py)
  parses streams and checks block/payload digests. Its digest checker is not a
  cross-file revisit planner. The local baseline uses pinned warcio 1.8.1.
- [Browsertrix](https://crawler.docs.browsertrix.com/develop/dedupe/#crawl-dependency-tracking-in-wacz-datapackagejson)
  records dependency metadata during deduplicated crawling. Prefer that metadata
  for a Browsertrix-managed collection; RevisitLedger targets local files without it.
- [jwarc](https://github.com/iipc/jwarc) provides parser, validator, extraction,
  filtering and replay tooling. RevisitLedger uses its typed parser foundation
  with additional conservative payload/framing checks.
- [pywb](https://github.com/webrecorder/pywb/blob/main/pywb/warcserver/resource/resolvingloader.py)
  resolves revisit payloads for replay. This project produces a preflight report
  and file plan without running a replay service.
- [EDGI's importer issue 943](https://github.com/edgi-govdata-archiving/web-monitoring-processing/issues/943)
  supplied a concrete example of originals outside imported WARCs. A single
  low-priority issue is not evidence of broad demand.

H5Carry is a related prior project in the author's project-selection comparison:
it preserves HDF5 object references during selection/export. The general dependency
closure idea overlaps, but file-level WARC revisit selectors, digest conflicts and
import planning are distinct. No H5Carry implementation was reused or relabeled.

The fixture baseline compares the same missing-ID and URI/date-only examples. A
successful digest-check command must not be interpreted as a complete dependency
plan. See VALIDATION.md for actual commands/outcomes and unrun checks. The authored
fixtures establish narrow conformance behavior, not performance or correctness on
a representative production crawl.
