#!/usr/bin/env python3
"""Vendored-corpus provenance audit (RAI-288 vendor-profile gate `vendor-audit`).

Driver (card RAI-288, gate-policy class; RAI-281 precedent): the importer
lanes RAI-032/RAI-033 vendor third-party corpora under `vendor/<corpus>/` and
add a root `THIRD_PARTY_NOTICES.md`, but the quality classifier mapped those
paths to no profile, so `make quality-changed QUALITY_EXECUTE=1` failed closed
with "changed files map to no profile (not skipped): THIRD_PARTY_NOTICES.md,
vendor/..." on both lanes (reproduced pre-change on 2026-11-05 from a scratch
copy of `lane/rai-032` — identical diagnostics). This card adds real policy
coverage, never a silent skip: `vendor/**` and `THIRD_PARTY_NOTICES.md` map to
the new component `vendor` profile, and this gate gives that coverage a real
check — every vendored byte is pinned and re-verified on every run.

Policy enforced per `vendor/<corpus>/` directory present in the audited root
(both lane corpus shapes are supported; a corpus must use exactly one):

  * SHAPE A (pi-profiles, lane/rai-032): `MANIFEST.sha256` in `sha256sum`
    format (`<64-hex>  <relative-path>` per line, paths relative to the
    corpus dir) plus a `PROVENANCE.md` provenance doc whose Markdown table
    rows record the upstream repository URL, the pinned commit, and the
    license.
  * SHAPE B (opencode-profiles, lane/rai-033): `MANIFEST.json` whose
    `upstream` object records `repo` (URL), `commit` (full 40-hex git sha)
    and `license`, and whose `files` array pins every corpus file
    (`{path, sha256, upstreamPath?}`; `derived` entries are informational).

Checks (card contract):
  * a manifest exists (neither shape -> VEN-MANIFEST-MISSING; both shapes at
    once -> VEN-MANIFEST-MALFORMED, ambiguous spec is a defect);
  * an upstream ref is recorded: repository URL + full 40-hex pinned commit
    (VEN-REF-MISSING when absent or not a full hex sha — both lane corpora
    pin full commits; a tag/short-sha pin is a conscious future policy
    decision, not silently accepted here);
  * a license is recorded (VEN-LICENSE-MISSING);
  * the pinned file list is complete and ACTUALLY matches the bytes on disk:
    every listed file exists (VEN-FILE-MISSING) and its sha256 matches
    (VEN-HASH-MISMATCH); every corpus file except the audit-meta files
    (`MANIFEST.json`, `MANIFEST.sha256`, `PROVENANCE.md` at the corpus root)
    is listed (VEN-UNLISTED-FILE) — the manifests of both lane corpora pin
    everything except themselves;
  * `THIRD_PARTY_NOTICES.md` exists at the audited root and references each
    corpus as `vendor/<corpus>` (VEN-NOTICES-MISSING) — the license
    obligation is discharged there, so a corpus without a notices entry is
    a defect, not a formality;
  * only `vendor/<corpus>/` directories are corpora; a stray file directly
    under `vendor/` is VEN-CORPUS-SHAPE (fail-closed, never ignored).

Typed diagnostics (one stderr line per finding,
`VEN-<CODE> corpus[ path[:line]]: message`): VEN-MANIFEST-MISSING,
VEN-MANIFEST-MALFORMED (unparseable JSON, bad sha256sum line/hash, duplicate
listed paths, path escaping the corpus dir, empty file list),
VEN-REF-MISSING, VEN-LICENSE-MISSING, VEN-FILE-MISSING, VEN-HASH-MISMATCH,
VEN-UNLISTED-FILE, VEN-NOTICES-MISSING, VEN-CORPUS-SHAPE. Findings are
emitted in a fixed class order per corpus, sorted by path inside each class,
so output is deterministic.

Exit codes: 0 = every present corpus fully pinned and byte-verified (or no
`vendor/` directory at all); 1 = findings (corpus policy violations);
2 = tool error (audit could not complete: unreadable file during hashing,
undecodable notices file, failed self-test) — a tool error is never a pass.

Default audited root is the repository root. `--root PATH` points the audit
at a fixture tree instead (self-test and lane red/green evidence use temp
copies; the real corpora are owned by the importer lanes/cards, not by this
gate's evidence). `--self-test` builds embedded positive/negative fixture
corpora of BOTH shapes in a temp dir and runs them through the same
audit_root() production path first — the gate must prove it detects every
failure class with the expected typed code and exit code, not merely that a
tree is clean — and finishes by proving the repository root itself audits
clean (fail-closed on a repo whose present corpora violate the pin policy).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import shutil
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
VENDOR_DIR = "vendor"
NOTICES_FILE = "THIRD_PARTY_NOTICES.md"
# Audit-meta files at the corpus root: consumed by the audit itself, exempt
# from the every-file-must-be-listed requirement (both lane corpora leave
# their manifest/provenance unlisted by exactly this set).
META_FILES = frozenset({"MANIFEST.json", "MANIFEST.sha256", "PROVENANCE.md"})
MANIFEST_JSON = "MANIFEST.json"
MANIFEST_SHA = "MANIFEST.sha256"
PROVENANCE_MD = "PROVENANCE.md"

_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
_COMMIT_RE = re.compile(r"^[0-9a-f]{40}$", re.IGNORECASE)


class Finding:
    """One typed audit diagnostic: stable code + corpus + optional path."""

    def __init__(self, code: str, corpus: str, message: str,
                 path: str | None = None) -> None:
        self.code = code
        self.corpus = corpus
        self.path = path
        self.message = message

    def render(self) -> str:
        loc = self.corpus if self.path is None else f"{self.corpus} {self.path}"
        return f"{self.code} {loc}: {self.message}"


class AuditToolError(Exception):
    """The audit could not complete (exit 2), e.g. unreadable bytes."""


class CorpusManifest:
    """Parsed, shape-normalized manifest: provenance fields + pinned files."""

    def __init__(self) -> None:
        self.repo: str | None = None
        self.commit: str | None = None
        self.license: str | None = None
        self.files: dict[str, str] = {}  # rel path -> sha256 (insertion order)

    def ref_and_license_findings(self, corpus: str) -> list[Finding]:
        findings: list[Finding] = []
        if not self.repo or not self.repo.startswith(("http://", "https://")):
            findings.append(Finding(
                "VEN-REF-MISSING", corpus,
                "upstream repository URL not recorded (expected a table row "
                "'Upstream repository' in PROVENANCE.md or upstream.repo in "
                "MANIFEST.json, an http(s) URL)"))
        if not self.commit or not _COMMIT_RE.match(self.commit):
            findings.append(Finding(
                "VEN-REF-MISSING", corpus,
                "pinned upstream commit not recorded as a full 40-hex git sha "
                "(expected 'Pinned commit' in PROVENANCE.md or upstream.commit "
                "in MANIFEST.json)"))
        if not self.license:
            findings.append(Finding(
                "VEN-LICENSE-MISSING", corpus,
                "license not recorded (expected a 'License' row in "
                "PROVENANCE.md or upstream.license in MANIFEST.json)"))
        return findings


def _provenance_rows(text: str) -> list[tuple[str, str]]:
    """Parse Markdown table rows `| Field | Value |` from a PROVENANCE.md:
    returns (field, value) pairs with backticks/whitespace stripped. Header
    and separator rows are skipped; rows without a value cell are ignored
    (a missing record surfaces as VEN-REF-MISSING/VEN-LICENSE-MISSING)."""
    rows: list[tuple[str, str]] = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("|"):
            continue
        cells = [c.strip().strip("`").strip() for c in stripped.strip("|").split("|")]
        if len(cells) < 2:
            continue
        field, value = cells[0], cells[1]
        if not field or not value:
            continue
        if field.lower() == "field" or set(field) <= set("-= "):
            continue  # header / separator row
        rows.append((field, value))
    return rows


def _load_manifest(corpus_dir: pathlib.Path, corpus: str
                   ) -> tuple[CorpusManifest | None, list[Finding]]:
    """Load MANIFEST.json (shape B) or MANIFEST.sha256+PROVENANCE.md (shape
    A). Returns (manifest or None, findings). A missing manifest yields
    VEN-MANIFEST-MISSING; an ambiguous/unparseable/invalid one yields
    VEN-MANIFEST-MALFORMED with the reason (fail-closed, never guessed)."""
    has_json = (corpus_dir / MANIFEST_JSON).is_file()
    has_sha = (corpus_dir / MANIFEST_SHA).is_file()
    if has_json and has_sha:
        return None, [Finding(
            "VEN-MANIFEST-MALFORMED", corpus,
            "both manifest shapes present (MANIFEST.json and "
            "MANIFEST.sha256); a corpus must pin exactly one manifest shape")]
    if not has_json and not has_sha:
        return None, [Finding(
            "VEN-MANIFEST-MISSING", corpus,
            "no manifest found (expected MANIFEST.json with pinned "
            "files+upstream, or MANIFEST.sha256 plus PROVENANCE.md)")]

    manifest = CorpusManifest()
    findings: list[Finding] = []
    if has_json:
        try:
            doc = json.loads((corpus_dir / MANIFEST_JSON).read_text(
                encoding="utf-8"))
        except (OSError, UnicodeDecodeError) as exc:
            raise AuditToolError(f"cannot read {MANIFEST_JSON}: {exc}") from exc
        except json.JSONDecodeError as exc:
            return None, [Finding(
                "VEN-MANIFEST-MALFORMED", corpus,
                f"{MANIFEST_JSON} is not valid JSON: {exc}")]
        if not isinstance(doc, dict):
            return None, [Finding(
                "VEN-MANIFEST-MALFORMED", corpus,
                f"{MANIFEST_JSON} root must be a JSON object")]
        upstream = doc.get("upstream")
        if isinstance(upstream, dict):
            repo = upstream.get("repo")
            commit = upstream.get("commit")
            license_id = upstream.get("license")
            manifest.repo = repo.strip() if isinstance(repo, str) else None
            manifest.commit = commit.strip() if isinstance(commit, str) else None
            manifest.license = (license_id.strip()
                                if isinstance(license_id, str) else None)
        files = doc.get("files")
        if not isinstance(files, list) or not files:
            findings.append(Finding(
                "VEN-MANIFEST-MALFORMED", corpus,
                f"{MANIFEST_JSON} must list at least one pinned file in a "
                "'files' array"))
        else:
            for entry in files:
                if not isinstance(entry, dict):
                    findings.append(Finding(
                        "VEN-MANIFEST-MALFORMED", corpus,
                        f"'files' entry is not an object: {entry!r}"[:160]))
                    continue
                path = entry.get("path")
                sha = entry.get("sha256")
                if not isinstance(path, str) or not path:
                    findings.append(Finding(
                        "VEN-MANIFEST-MALFORMED", corpus,
                        f"'files' entry without a path: {entry!r}"[:160]))
                    continue
                problem = _path_problem(corpus, path)
                if problem:
                    findings.append(problem)
                    continue
                if not isinstance(sha, str) or not _SHA256_RE.match(sha):
                    findings.append(Finding(
                        "VEN-MANIFEST-MALFORMED", corpus,
                        f"listed path {path!r} has no valid sha256 hex digest",
                        path=path))
                    continue
                if path in manifest.files:
                    findings.append(Finding(
                        "VEN-MANIFEST-MALFORMED", corpus,
                        f"path {path!r} is listed more than once",
                        path=path))
                    continue
                manifest.files[path] = sha.lower()
        return manifest, findings

    # SHAPE A: MANIFEST.sha256 (sha256sum format) + PROVENANCE.md records.
    try:
        text = (corpus_dir / MANIFEST_SHA).read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        raise AuditToolError(f"cannot read {MANIFEST_SHA}: {exc}") from exc
    for lineno, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        parts = line.strip().split(None, 1)
        if len(parts) != 2 or not _SHA256_RE.match(parts[0]):
            findings.append(Finding(
                "VEN-MANIFEST-MALFORMED", corpus,
                f"line {lineno} is not '<64-hex sha256> <relative-path>' "
                f"(sha256sum format): {line.strip()!r}"[:160]))
            continue
        sha, path = parts[0].lower(), parts[1].strip()
        problem = _path_problem(corpus, path)
        if problem:
            findings.append(problem)
            continue
        if path in manifest.files:
            findings.append(Finding(
                "VEN-MANIFEST-MALFORMED", corpus,
                f"path {path!r} is listed more than once", path=path))
            continue
        manifest.files[path] = sha
    if not manifest.files:
        findings.append(Finding(
            "VEN-MANIFEST-MALFORMED", corpus,
            f"{MANIFEST_SHA} lists no files"))
    prov = corpus_dir / PROVENANCE_MD
    if not prov.is_file():
        findings.append(Finding(
            "VEN-MANIFEST-MALFORMED", corpus,
            f"the {MANIFEST_SHA} shape requires {PROVENANCE_MD} with the "
            "upstream pin and license records"))
    else:
        try:
            prov_text = prov.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            raise AuditToolError(f"cannot read {PROVENANCE_MD}: {exc}") from exc
        for field, value in _provenance_rows(prov_text):
            low = field.lower()
            if "commit" in low and manifest.commit is None:
                manifest.commit = value
            elif "repo" in low and manifest.repo is None:
                manifest.repo = value
            elif "license" in low and manifest.license is None:
                manifest.license = value
    return manifest, findings


def _path_problem(corpus: str, path: str) -> Finding | None:
    """A listed path must stay inside the corpus dir (no absolute paths, no
    '..' escapes) — a manifest that escapes its corpus is malformed, not a
    file check."""
    if path.startswith("/") or pathlib.PurePosixPath(path).is_absolute():
        return Finding("VEN-MANIFEST-MALFORMED", corpus,
                       f"listed path is absolute: {path!r}", path=path)
    parts = pathlib.PurePosixPath(path).parts
    if any(part == ".." for part in parts) or (pathlib.PurePosixPath(path)
                                               .is_reserved()):
        return Finding("VEN-MANIFEST-MALFORMED", corpus,
                       f"listed path escapes the corpus directory: {path!r}",
                       path=path)
    if not parts or path.endswith("/"):
        return Finding("VEN-MANIFEST-MALFORMED", corpus,
                       f"listed path is empty or a directory: {path!r}",
                       path=path)
    return None


def audit_corpus(corpus_dir: pathlib.Path, corpus: str,
                 notices_text: str | None) -> list[Finding]:
    """Audit one vendored corpus directory; findings in fixed class order:
    manifest -> ref/license -> listed-file checks -> unlisted files ->
    notices coverage. Deterministic (sorted within each class)."""
    findings: list[Finding] = []
    try:
        manifest, mf_findings = _load_manifest(corpus_dir, corpus)
    except AuditToolError as exc:
        return [Finding("VEN-MANIFEST-MALFORMED", corpus, str(exc))]
    findings.extend(mf_findings)
    if manifest is None:
        # Without a parseable manifest the file-level checks cannot run;
        # the manifest findings above are the corpus's typed defects.
        return findings
    findings.extend(manifest.ref_and_license_findings(corpus))

    actual: set[str] = set()
    for p in sorted(corpus_dir.rglob("*")):
        if p.is_symlink() or not p.is_file():
            continue
        rel = p.relative_to(corpus_dir).as_posix()
        if rel in META_FILES:
            continue
        actual.add(rel)
    # Symlinked entries are corpora-shape defects too (a symlink is not the
    # pinned byte content): any symlink inside the corpus is unlisted unless
    # it is an audit-meta file name, and meta symlinks are also rejected.
    for p in sorted(corpus_dir.rglob("*")):
        if p.is_symlink():
            rel = p.relative_to(corpus_dir).as_posix()
            findings.append(Finding(
                "VEN-UNLISTED-FILE", corpus,
                "symlinked entry inside a vendored corpus is not pinned "
                "byte content", path=rel))

    listed = manifest.files
    # Every listed file must exist and hash-match (sorted by path).
    for path in sorted(listed):
        target = corpus_dir / path
        if not target.is_file() or target.is_symlink():
            findings.append(Finding(
                "VEN-FILE-MISSING", corpus,
                "file listed in the manifest is missing (or not a regular "
                "file) on disk", path=path))
            continue
        try:
            digest = _sha256_file(target)
        except OSError as exc:
            raise AuditToolError(
                f"cannot hash listed file {path!r}: {exc}") from exc
        if digest != listed[path]:
            findings.append(Finding(
                "VEN-HASH-MISMATCH", corpus,
                f"content sha256 {digest} does not match the pinned "
                f"{listed[path]} — the vendored bytes were changed without a "
                "new provenance event", path=path))
    # Every non-meta file must be listed (sorted).
    for path in sorted(actual - set(listed)):
        findings.append(Finding(
            "VEN-UNLISTED-FILE", corpus,
            "file present in the corpus but not pinned in the manifest",
            path=path))
    # Notices coverage: the root THIRD_PARTY_NOTICES.md must reference
    # vendor/<corpus>.
    if notices_text is None:
        findings.append(Finding(
            "VEN-NOTICES-MISSING", corpus,
            f"{NOTICES_FILE} is missing at the repository root — the corpus's "
            "license notice is not discharged"))
    elif f"{VENDOR_DIR}/{corpus}" not in notices_text:
        findings.append(Finding(
            "VEN-NOTICES-MISSING", corpus,
            f"{NOTICES_FILE} does not reference this corpus "
            f"(expected a '{VENDOR_DIR}/{corpus}' entry with its license "
            "notice)"))
    return findings


def _sha256_file(p: pathlib.Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def audit_root(root: pathlib.Path) -> tuple[list[Finding], list[str]]:
    """Audit every corpus under `root/vendor/`; returns (findings, corpora
    audited, sorted). Notices text is read once (missing file -> None).
    An unreadable notices file or vendor root is a tool error (exit 2)."""
    vendor = root / VENDOR_DIR
    corpora: list[str] = []
    if vendor.is_dir():
        for entry in sorted(vendor.iterdir()):
            if entry.is_dir() and not entry.is_symlink():
                corpora.append(entry.name)
            else:
                findings = [Finding(
                    "VEN-CORPUS-SHAPE", f"{VENDOR_DIR}",
                    f"expected a corpus directory '{VENDOR_DIR}/<name>/', "
                    f"found a non-directory entry: {entry.name}")]
                return findings, []
    if not corpora:
        return [], []
    notices_path = root / NOTICES_FILE
    notices_text: str | None = None
    if notices_path.is_file():
        try:
            notices_text = notices_path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            raise AuditToolError(f"cannot read {NOTICES_FILE}: {exc}") from exc
    findings: list[Finding] = []
    for corpus in sorted(corpora):
        findings.extend(audit_corpus(vendor / corpus, corpus, notices_text))
    return findings, sorted(corpora)


def _print_report(findings: list[Finding]) -> None:
    for f in findings:
        print(f"[vendor-audit] FAIL {f.render()}", file=sys.stderr)
    counts: dict[str, int] = {}
    for f in findings:
        counts[f.code] = counts.get(f.code, 0) + 1
    summary = ", ".join(f"{k}={v}" for k, v in sorted(counts.items()))
    print(f"[vendor-audit] summary: {len(findings)} finding(s)"
          + (f": {summary}" if summary else ""), file=sys.stderr)


def _fixture_sha(text: bytes) -> str:
    return hashlib.sha256(text).hexdigest()


def _write_manifest_json(corpus_dir: pathlib.Path,
                         files: dict[str, bytes]) -> None:
    """Shape B fixture: MANIFEST.json pinning `files` with real hashes."""
    entries = []
    for path in sorted(files):
        entries.append({"path": path, "sha256": _fixture_sha(files[path])})
    doc = {
        "corpus": corpus_dir.name,
        "manifestVersion": 1,
        "upstream": {
            "repo": "https://example.invalid/upstream/fixture",
            "commit": "a" * 40,
            "pinnedAt": "2026-11-05",
            "license": "MIT",
            "licenseFile": sorted(files)[0] if files else "LICENSE",
        },
        "files": entries,
    }
    (corpus_dir / MANIFEST_JSON).write_text(json.dumps(doc, indent=2) + "\n",
                                            encoding="utf-8")


def _write_manifest_sha(corpus_dir: pathlib.Path, files: dict[str, bytes]) -> None:
    """Shape A fixture: MANIFEST.sha256 (sha256sum format) + PROVENANCE.md."""
    lines = [f"{_fixture_sha(data)}  {path}" for path, data in sorted(files.items())]
    (corpus_dir / MANIFEST_SHA).write_text("\n".join(lines) + "\n",
                                           encoding="utf-8")
    (corpus_dir / PROVENANCE_MD).write_text(
        "# Vendored fixture corpus — provenance\n\n"
        "| Field | Value |\n| --- | --- |\n"
        "| Upstream repository | `https://example.invalid/upstream/fixture` |\n"
        "| Pinned commit | `{'a' * 40}` |\n"
        "| License | MIT (`LICENSE`) |\n".replace("{'a' * 40}", "a" * 40),
        encoding="utf-8")


def _build_fixture(root: pathlib.Path, corpus: str, shape: str,
                   files: dict[str, bytes], notices_entry: bool = True,
                   notices_text: str | None = None) -> None:
    corpus_dir = root / VENDOR_DIR / corpus
    corpus_dir.mkdir(parents=True)
    for rel, data in files.items():
        target = corpus_dir / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    if shape == "json":
        _write_manifest_json(corpus_dir, files)
    else:
        _write_manifest_sha(corpus_dir, files)
    text = notices_text if notices_text is not None else (
        "# Third-party notices\n\n"
        + (f"## Fixture corpus (`{VENDOR_DIR}/{corpus}`)\n\nMIT notice.\n"
           if notices_entry else "# Third-party notices\n\nNo entry.\n"))
    (root / NOTICES_FILE).write_text(text, encoding="utf-8")


def _run_audit(root: pathlib.Path) -> tuple[int, list[Finding], list[str]]:
    """Production audit path for fixtures; returns (exit_code, findings,
    corpora). Mirrors main()'s exit semantics without process exit."""
    try:
        findings, corpora = audit_root(root)
    except AuditToolError as exc:
        print(f"[vendor-audit] TOOL ERROR: {exc}", file=sys.stderr)
        return 2, [], []
    if findings:
        return 1, findings, corpora
    return 0, [], corpora


def _expect(name: str, root: pathlib.Path, codes: set[str],
            exit_code: int) -> list[Finding]:
    code, findings, _corpora = _run_audit(root)
    got_codes = {f.code for f in findings}
    if code != exit_code or got_codes != codes:
        _self_test_fail(
            name, f"exit {exit_code} with codes {sorted(codes)}",
            f"exit {code} with codes {sorted(got_codes)}", findings)


def _self_test_fail(name: str, expected: str, got: str,
                    findings: list[Finding]) -> None:
    detail = "; ".join(f.render() for f in findings) or "no findings"
    print(f"[vendor-audit] SELF-TEST FAIL: case {name} expected {expected}, "
          f"got {got}; findings: {detail}", file=sys.stderr)
    raise SystemExit(2)


def _self_test() -> None:
    """Embedded fixtures through the production audit_root() path: every
    failure class must be detected with the expected typed code and exit
    code, both corpus shapes must pass when valid, and the repository root
    itself must audit clean. Any miss is a failed self-test (exit 2)."""
    lic = b"MIT License\n\nCopyright (c) 2025 fixture\n"
    doc = b"# fixture upstream file\n\ndeterministic bytes 12345\n"
    base_files = {"upstream/LICENSE": lic, "upstream/README.md": doc}
    td = pathlib.Path(tempfile.mkdtemp(prefix="vendor-audit-selftest-"))
    failures = 0  # counted RED fixture cases below (for the summary line)
    try:
        # -- GREEN controls: valid corpus of each shape (exit 0, no findings)
        for shape, corpus in (("sha256+provenance", "fixture-a"),
                              ("json", "fixture-b")):
            root = td / f"green-{shape}"
            root.mkdir()
            _build_fixture(root, corpus, shape, dict(base_files))
            _expect(f"green-{shape}", root, set(), 0)
            shutil.rmtree(root)

        # -- RED: corrupt hash (flip one byte of a pinned file)
        root = td / "red-hash"; root.mkdir()
        _build_fixture(root, "fixture-a", "sha256+provenance", dict(base_files))
        (root / VENDOR_DIR / "fixture-a" / "upstream" / "README.md").write_bytes(
            doc.replace(b"12345", b"12346"))
        _expect("corrupt-hash", root, {"VEN-HASH-MISMATCH"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: provenance removed (shape A loses its ref+license records)
        root = td / "red-provenance"; root.mkdir()
        _build_fixture(root, "fixture-a", "sha256+provenance", dict(base_files))
        (root / VENDOR_DIR / "fixture-a" / PROVENANCE_MD).unlink()
        _expect("provenance-removed", root,
                {"VEN-REF-MISSING", "VEN-LICENSE-MISSING",
                 "VEN-MANIFEST-MALFORMED"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: manifest removed entirely
        root = td / "red-manifest"; root.mkdir()
        _build_fixture(root, "fixture-b", "json", dict(base_files))
        (root / VENDOR_DIR / "fixture-b" / MANIFEST_JSON).unlink()
        _expect("manifest-removed", root, {"VEN-MANIFEST-MISSING"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: unlisted extra file smuggled into the corpus
        root = td / "red-unlisted"; root.mkdir()
        files = dict(base_files)
        files["upstream/extra.md"] = b"smuggled\n"
        _build_fixture(root, "fixture-a", "sha256+provenance", files)
        # extra.md IS listed by the fixture writer; remove its manifest entry
        # by rebuilding the manifest from the base file set only.
        corpus_dir = root / VENDOR_DIR / "fixture-a"
        _write_manifest_sha(corpus_dir, dict(base_files))
        _expect("unlisted-file", root, {"VEN-UNLISTED-FILE"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: listed file deleted from disk
        root = td / "red-missing"; root.mkdir()
        _build_fixture(root, "fixture-b", "json", dict(base_files))
        (root / VENDOR_DIR / "fixture-b" / "upstream" / "LICENSE").unlink()
        _expect("listed-file-missing", root, {"VEN-FILE-MISSING"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: upstream ref missing (no commit row in PROVENANCE.md)
        root = td / "red-ref"; root.mkdir()
        _build_fixture(root, "fixture-a", "sha256+provenance", dict(base_files))
        prov = root / VENDOR_DIR / "fixture-a" / PROVENANCE_MD
        prov.write_text("\n".join(
            line for line in prov.read_text(encoding="utf-8").splitlines()
            if "commit" not in line.lower()) + "\n", encoding="utf-8")
        _expect("ref-missing", root, {"VEN-REF-MISSING"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: upstream ref not a full 40-hex sha (short sha)
        root = td / "red-shortsha"; root.mkdir()
        _build_fixture(root, "fixture-a", "sha256+provenance", dict(base_files))
        prov = root / VENDOR_DIR / "fixture-a" / PROVENANCE_MD
        prov.write_text(prov.read_text(encoding="utf-8").replace(
            "a" * 40, "abc123"), encoding="utf-8")
        _expect("ref-short-sha", root, {"VEN-REF-MISSING"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: upstream repository URL missing (shape A)
        root = td / "red-repourl"; root.mkdir()
        _build_fixture(root, "fixture-a", "sha256+provenance", dict(base_files))
        prov = root / VENDOR_DIR / "fixture-a" / PROVENANCE_MD
        prov.write_text("\n".join(
            line for line in prov.read_text(encoding="utf-8").splitlines()
            if "repository" not in line.lower()) + "\n", encoding="utf-8")
        _expect("repo-url-missing", root, {"VEN-REF-MISSING"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: license record missing (shape B upstream.license removed)
        root = td / "red-license"; root.mkdir()
        _build_fixture(root, "fixture-b", "json", dict(base_files))
        mp = root / VENDOR_DIR / "fixture-b" / MANIFEST_JSON
        doc_json = json.loads(mp.read_text(encoding="utf-8"))
        del doc_json["upstream"]["license"]
        mp.write_text(json.dumps(doc_json, indent=2) + "\n", encoding="utf-8")
        _expect("license-missing", root, {"VEN-LICENSE-MISSING"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: notices entry omitted
        root = td / "red-notices"; root.mkdir()
        _build_fixture(root, "fixture-a", "sha256+provenance", dict(base_files),
                       notices_entry=False)
        _expect("notices-entry-omitted", root, {"VEN-NOTICES-MISSING"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: notices file absent at the root
        root = td / "red-nonotices"; root.mkdir()
        _build_fixture(root, "fixture-a", "sha256+provenance", dict(base_files),
                       notices_text="# Third-party notices\n\nNo corpora.\n")
        (root / NOTICES_FILE).unlink()
        _expect("notices-file-absent", root, {"VEN-NOTICES-MISSING"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: unparseable MANIFEST.json
        root = td / "red-badjson"; root.mkdir()
        _build_fixture(root, "fixture-b", "json", dict(base_files))
        (root / VENDOR_DIR / "fixture-b" / MANIFEST_JSON).write_text(
            "{not json", encoding="utf-8")
        _expect("manifest-bad-json", root, {"VEN-MANIFEST-MALFORMED"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: both manifest shapes at once (ambiguous spec)
        root = td / "red-bothshapes"; root.mkdir()
        _build_fixture(root, "fixture-a", "sha256+provenance", dict(base_files))
        _write_manifest_json(root / VENDOR_DIR / "fixture-a", dict(base_files))
        _expect("both-manifest-shapes", root, {"VEN-MANIFEST-MALFORMED"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: bad sha256sum line (short digest)
        root = td / "red-badline"; root.mkdir()
        _build_fixture(root, "fixture-a", "sha256+provenance", dict(base_files))
        mp = root / VENDOR_DIR / "fixture-a" / MANIFEST_SHA
        lines = mp.read_text(encoding="utf-8").splitlines()
        lines[0] = "deadbeef  upstream/README.md"
        mp.write_text("\n".join(lines) + "\n", encoding="utf-8")
        # The destroyed pin also leaves that file unpinned on disk, so the
        # unlisted-file finding follows from the malformed line (fail-closed).
        _expect("manifest-bad-line", root,
                {"VEN-MANIFEST-MALFORMED", "VEN-UNLISTED-FILE"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: empty file list (shape B)
        root = td / "red-empty"; root.mkdir()
        _build_fixture(root, "fixture-b", "json", dict(base_files))
        mp = root / VENDOR_DIR / "fixture-b" / MANIFEST_JSON
        doc_json = json.loads(mp.read_text(encoding="utf-8"))
        doc_json["files"] = []
        mp.write_text(json.dumps(doc_json, indent=2) + "\n", encoding="utf-8")
        # An empty file list pins nothing, so every on-disk file surfaces as
        # unlisted on top of the malformed manifest (fail-closed).
        _expect("manifest-empty-file-list", root,
                {"VEN-MANIFEST-MALFORMED", "VEN-UNLISTED-FILE"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: manifest path escaping the corpus dir
        root = td / "red-escape"; root.mkdir()
        _build_fixture(root, "fixture-b", "json", dict(base_files))
        mp = root / VENDOR_DIR / "fixture-b" / MANIFEST_JSON
        doc_json = json.loads(mp.read_text(encoding="utf-8"))
        doc_json["files"].append(
            {"path": "../outside.txt",
             "sha256": _fixture_sha(b"outside")})
        mp.write_text(json.dumps(doc_json, indent=2) + "\n", encoding="utf-8")
        _expect("manifest-path-escape", root, {"VEN-MANIFEST-MALFORMED"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: duplicate listed path
        root = td / "red-dup"; root.mkdir()
        _build_fixture(root, "fixture-b", "json", dict(base_files))
        mp = root / VENDOR_DIR / "fixture-b" / MANIFEST_JSON
        doc_json = json.loads(mp.read_text(encoding="utf-8"))
        doc_json["files"].append(dict(doc_json["files"][0]))
        mp.write_text(json.dumps(doc_json, indent=2) + "\n", encoding="utf-8")
        _expect("manifest-duplicate-path", root, {"VEN-MANIFEST-MALFORMED"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: stray file directly under vendor/ (not a corpus dir)
        root = td / "red-stray"; root.mkdir()
        _build_fixture(root, "fixture-a", "sha256+provenance", dict(base_files))
        (root / VENDOR_DIR / "loose.txt").write_bytes(b"stray\n")
        _expect("stray-vendor-entry", root, {"VEN-CORPUS-SHAPE"}, 1)
        failures += 1
        shutil.rmtree(root)

        # -- RED: symlink inside a vendored corpus (not pinned byte content)
        root = td / "red-symlink"; root.mkdir()
        _build_fixture(root, "fixture-b", "json", dict(base_files))
        corpus_dir = root / VENDOR_DIR / "fixture-b"
        (corpus_dir / "link.md").symlink_to("upstream/README.md")
        _expect("symlink-entry", root, {"VEN-UNLISTED-FILE"}, 1)
        failures += 1
        shutil.rmtree(root)

        # The repository root itself must audit clean (fail-closed on a repo
        # whose PRESENT corpora violate the pin policy; a repository with no
        # vendor/ directory audits clean with zero corpora).
        code, repo_findings, corpora = _run_audit(ROOT)
        if code != 0:
            _self_test_fail("repository-root", "exit 0", f"exit {code}",
                            repo_findings)
        print(f"[vendor-audit] self-test: {failures} red fixture cases + 2 "
              f"green controls passed through the production audit path "
              f"(both corpus shapes clean when valid; corrupt hash, removed "
              f"provenance, removed manifest, unlisted file, missing listed "
              f"file, missing/short ref, missing repo URL, missing license, "
              f"omitted/absent notices, bad JSON, dual manifests, bad "
              f"sha256sum line, empty file list, path escape, duplicate path, "
              f"stray vendor/ entry, symlink entry detected with typed codes "
              f"and exit 1); repository root audits clean "
              f"({len(corpora)} corpus(corpora) present)")
    finally:
        shutil.rmtree(td, ignore_errors=True)


def main() -> int:
    ap = argparse.ArgumentParser(
        prog="vendor_audit",
        description="Audit vendored corpora provenance under vendor/ (RAI-288).")
    ap.add_argument("--root", type=pathlib.Path, default=ROOT,
                    help=f"tree whose vendor/ corpora are audited "
                         f"(default: {ROOT})")
    ap.add_argument("--self-test", action="store_true",
                    help="run embedded positive/negative fixtures first")
    args = ap.parse_args()
    if args.self_test:
        _self_test()
    root = args.root if args.root.is_absolute() else ROOT / args.root
    try:
        findings, corpora = audit_root(root)
    except AuditToolError as exc:
        print(f"[vendor-audit] TOOL ERROR: {exc}", file=sys.stderr)
        return 2
    if findings:
        _print_report(findings)
        return 1
    print(f"[vendor-audit] OK {root}: {len(corpora)} vendored corpus(corpora) "
          f"({', '.join(corpora) if corpora else 'no vendor/ directory'}) "
          "fully pinned: upstream ref + license recorded, sha256 manifest "
          "complete, all pinned bytes re-verified, notices entry present")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
