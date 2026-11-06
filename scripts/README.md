# scripts/

Development and bootstrap glue. Product code is Scala 3 + ZIO (`modules/**`,
`contracts/**`, `examples/**`); anything here is unshipped tooling (language
policy, `docs/PLAN.md`).

## bootstrap.sh — fresh-checkout provisioning

`artifacts/` is gitignored, so a fresh clone or a new git worktree ships with
**no** local dependency store. Two things live there:

- `artifacts/toolchain` plus the toolchain jars under `artifacts/tools/m2`
  (pinned Scala 3.9.0 dotc, ZIO, JLine, coursier interface — RAI-001/RAI-218);
- the pinned consumer closure `artifacts/tools/m2/**` (31 jars pinned with
  sha256 in `contracts/acceptance/cross-ecosystem/consumer-deps.json` — RAI-239),
  required by the cross-ecosystem corpus (`compile_all`, the llm4s and
  langchain4j consumers, the independent checker).

Without provisioning you will see, for example:

- `compile-fixtures` gate red, `sh scripts/build/compile.sh` exit 21:
  `missing artifacts/tools/m2/... — run scripts/bootstrap.sh`;
- cross-ecosystem `compile_all` exit 2: `FATAL: classes did not compile`
  (wave-D incident signature; the consumer jars were missing).

**Precondition: run the bootstrap once after cloning or creating a worktree,
before any compile/gate work.**

```sh
sh scripts/bootstrap.sh            # toolchain + m2 closure (download mode)
```

### Commands

| command | effect |
| --- | --- |
| `sh scripts/bootstrap.sh` | `all`: provision the pinned REPL toolchain and the pinned m2 consumer closure, verify everything, exit 0. |
| `sh scripts/bootstrap.sh toolchain` | toolchain only (RAI-001/RAI-218 behavior, unchanged). |
| `sh scripts/bootstrap.sh m2 --source DIR` | provision the 31 pinned artifacts by copying from a populated m2 cache root `DIR` (a directory containing `com/`, `org/`, ... — for example an existing checkout's `artifacts/tools/m2`). Offline. |
| `sh scripts/bootstrap.sh m2 --download` | provision the 31 pinned artifacts from Maven Central (URLs derived from `group:artifact:version` in `consumer-deps.json`). Default when `--source` is absent. |
| `m2 --manifest FILE` / `m2 --target DIR` | override the pin manifest / the m2 root to provision (defaults: `contracts/acceptance/cross-ecosystem/consumer-deps.json`, `artifacts/tools/m2`). |

`--source` and `--download` are mutually exclusive. HTTPS downloads resolve a
TLS CA bundle first (`scripts/bootstrap_ca.py`, RAI-218); a caller-provided
`SSL_CERT_FILE` is honored untouched.

### Verification guarantees (RAI-239)

- Every artifact is checked (sha256 + size) against the pin manifest before it
  is installed, and the **whole** pinned closure is re-verified from disk
  before exit. The summary prints one `OK/VERIFIED <sha256> <path>` line per
  artifact and a final `verified 31/31` line.
- A corrupted or missing artifact is named on stderr (`FAIL <path>: ...`) and
  the exit code is nonzero; the store is left untouched for failed artifacts
  (install is an atomic same-filesystem rename of a verified temp file).
- Provisioning is concurrency-safe: a store-level lock
  (`artifacts/tools/.m2-bootstrap.lock`) serializes concurrent invocations, so
  two bootstraps against the same target cannot corrupt each other; reruns are
  idempotent (already-verified artifacts are not re-fetched).
- The script never deletes or rewrites anything outside the pinned artifact
  paths, so unrelated jars in the store (for example the dotc toolchain set)
  are preserved. A corrupt pinned artifact that is already in the store is
  repaired from the source cache or re-downloaded when a verified copy is
  available; if it is not, the run fails naming the artifact.

Exit codes: `0` all pinned artifacts verified; `1` at least one artifact
corrupted/missing/unverifiable (named on stderr); `2` usage or manifest error;
`64` unknown command.

### Scope notes

- The m2 closure provisioning covers the 31 pinned consumer artifacts. The
  toolchain jars needed by `scripts/build/compile.sh` and the scenario drivers
  come from the `toolchain` half (`all` provisions both).
- No sbt/coursier configuration is touched; the script is additive and
  rollback is a revert of this file.

## repro-jar — deterministic adapter jar packaging (RAI-240/RAI-251)

`scripts/repro-jar` (also `make repro-jar`) runs the same sbt assembly as
`make raider-jar` and then repacks the fat jar with a canonical packaging
policy, so consecutive builds of an unchanged tree produce a **byte-identical**
jar at the binding path `modules/cli/target/scala-3.9.0/raider-cli.jar`
(the raw assembly is kept beside it as `raider-cli-raw.jar`):

- entries sorted by UTF-8 name bytes; every timestamp fixed at
  1980-01-01 00:00:00 (ZIP/DOS epoch);
- regular entries re-deflated with zlib level 9; directories STORED;
- fixed zip metadata (create_system=0, fixed external attrs, no extra
  fields, no comments, no zip64);
- manifest bytes passed through with `Main-Class` verification
  (default `raider.cli.Main`).

Byte-identity holds for consecutive rebuilds of an unchanged tree. Across
`make clean` rounds the dotc compiler itself varies 3 entries
(`raider/core/ids$package*`, measured in RAI-240); removing that last
variance is RAI-252's product-source change.

### Usage

```sh
make repro-jar                                # sbt build + canonical repack (wired by RAI-251)
scripts/repro-jar                             # same, invoked directly
scripts/repro-jar --from-assembly PATH        # canonicalize an existing raw jar, skip sbt
scripts/repro-jar --out PATH                  # override the output path
scripts/repro-jar --main-class a.b.Main       # override the verified Main-Class
```

`make repro-jar` is a plain delegation with no extra arguments; if the
wrapper fails, `make` aborts and reports the wrapper's exit code in its
`*** [repro-jar] Error N` message, while make's own exit status is 2.
`COURSIER_CACHE` is honored (same Makefile default `/tmp/cc-master`).

### When to prefer it over `make raider-jar`

- `make raider-jar` is the raw sbt-assembly build. It stays the previously
  pinned flow until RAI-253 re-pins `adapterJarSha256` in
  `contracts/acceptance/cross-ecosystem/expected/golden-checks.json`; its
  output is NOT byte-stable across clean recompiles (measured: dotc pickling
  and forwarder permutation churn, RAI-240).
- Prefer `make repro-jar` whenever the jar bytes matter: corpus/acceptance
  work, evidence hashing, CI caches keyed on the artifact, any two builds
  that must be comparable.

Both targets write the same binding path; the last one run wins. Running
`make raider-jar` after `make repro-jar` replaces the canonical jar with a
raw one; `make repro-jar` re-canonicalizes (and is idempotent:
canonicalizing an already-canonical jar reproduces it exactly).

### Exit-code contract

| exit | meaning |
| --- | --- |
| `0` | canonical jar published. |
| `3` | missing input or tool: raw assembly not found, output directory missing, sbt absent from PATH. |
| `4` | malformed input: not a readable zip, zero entries, no `META-INF/MANIFEST.MF`, or `Main-Class` mismatch. |
| `5` | structural anomaly: duplicate entry names in the input jar. |
| `6` | I/O failure while staging a copy (raw-keep capture): the staging file is removed before exiting, so no partial raw copy is left behind (RAI-265). |
| other | sbt's own exit code, passed through when the build fails. |

Failures are diagnosed on stderr (`repro-jar: ERROR: ...`) and no output jar
is published.

### Concurrency constraints

- Full wrapper builds run the sbt build: one sbt JVM per worktree, no
  concurrent mutable builds in one checkout.
- Concurrent **packaging** invocations must write to **separate output
  directories**. Publication is atomic per process (per-pid staging temp
  `.repro-jar-tmp.<pid>` + `os.replace` in the output directory), which is
  why the staged temp and the rename are confined to the output directory;
  pointing two runs at the same output directory is unsupported.
- `--from-assembly` runs skip sbt entirely and are the supported way to
  parallelize repacking of an already-built jar (separate `--out` paths).
