# LOCAL-YABANIN-20261028: local demo-stand launch setup

Status: partial acceptance. Local setup is functionally complete; all mandatory
gates executed successfully, but current-snapshot evidence freshness is unresolved.
Date: 2026-10-28 UTC. Baseline: `531d57c`; working tree clean before setup.

## Resulting behavior

From the checkout, `./.raider/yabanin.sh` starts the interactive chat interface
(`raider.repl.Main --chat`); `./.raider/yabanin.sh repl` starts the compiler-backed
Scala REPL. Both use `https://demo.yabanin.ai/gw/v1`, OpenAI-compatible transport,
and `glm-5.3`. The caller's working directory is the agent workspace.

Credentials are resolved through `RAIDER_API_KEY_CMD`, referencing the existing
private demo-stand key file in the neighboring infrastructure checkout. The
launcher clears stale inherited credentials and request overrides, and refuses
to start if the referenced key file or generated classpath is missing. No key
was copied into setup files. The original `.env.yabanin` remains unchanged.

## Changed paths

- `.raider/yabanin.sh`: local launcher, mode 0700, ignored by Git.
- `.raider/yabanin.env`: non-secret profile/helper reference, mode 0600, ignored.
- `artifacts/local-yabanin-20261028/`: local task card, sanitized probes,
  startup transcripts and evidence hashes; ignored by Git.
- `artifacts/user-examples/` and generated build outputs: refreshed using the
  existing documented build command.
- This work record. No product source, public API, build definition, gate policy
  or neighboring repository was modified.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Current checkout build | Passed with JDK 21.0.1 | `artifacts/user-examples/build.log`, `classpath.txt` |
| Stand authentication and model availability | GET /models HTTP 200; five models; glm-5.3 and glm-5.3-flash available | `artifacts/local-yabanin-20261028/models-probe.json` |
| Live-configured Scala REPL | Live streaming backend banner; `21 * 2` evaluated to 42; clean exit | `artifacts/local-yabanin-20261028/repl-startup.log` |
| Interactive chat startup | Real PTY displayed live backend, chat mode and `you>`; `:quit` exited 0 | Direct PTY tool execution plus `artifacts/local-yabanin-20261028/chat-startup.json` and `chat-startup.pty.log` |
| Headless CLI wiring | Built-in scout completed with the scripted mock provider, exit 0 | `artifacts/local-yabanin-20261028/headless-mock.log` and run artifacts; no claim of repository analysis |
| Secret hygiene | Credential absent from inspected setup files and captured startup output | Local exact-value check; key was never printed |
| Inference requests | Zero in this setup session | Only authenticated GET /models and no-task startup/evaluation checks |
| Mandatory fast gate execution | 10/10 passed, no skips or timeouts; sbt-test 619.09 seconds | `artifacts/quality/20261028-044247-53192-0001/manifest.json` |
| Manifest freshness | Refused: three files in other active worktrees changed during the run | `make quality-verify` exit 2; paths below |

## Commands

| Command | Exit | Result / evidence |
| --- | --- | --- |
| `git status --short` | 0 | Initially empty |
| `java -version` | 0 | OpenJDK 21.0.1 |
| `sh scripts/build/user-examples.sh` inside sandbox | 1 | sbt could not open `~/.sbt/boot/sbt.boot.lock`; permission failure |
| `sh scripts/build/user-examples.sh` with authorized sandbox escalation | 0 | Current checkout compiled; example JAR and classpath generated |
| `curl -IL --connect-timeout 8 --max-time 15 https://demo.yabanin.ai/gw/v1/models` | 0 | HEAD returned HTTP 405, Allow GET; not an authentication pass |
| `curl -fsSL --connect-timeout 8 --max-time 15 https://demo.yabanin.ai/gw/v1/models` | 56 | Unauthenticated GET returned HTTP 401 |
| `curl -fsSL --config - --max-redirs 0 --connect-timeout 8 --max-time 20 https://demo.yabanin.ai/gw/v1/models` | 0 | After authorized network escalation; authorization header supplied on stdin from the existing key file, response reduced to non-secret availability flags |
| `sh -n .raider/yabanin.sh .raider/yabanin.env` | 0 | Shell syntax valid |
| `printf 'val setupCheck = 21 * 2\nprintln(setupCheck)\n:quit\n' \| ./.raider/yabanin.sh repl` | 0 | Real compiler evaluation, no model request |
| `./.raider/yabanin.sh`, then `:quit` in a real PTY | 0 | Live chat startup and orderly exit |
| `java -cp "$RAIDER_SETUP_CP" raider.cli.Main run --agent scout --provider mock --input 'Summarize the repository purpose from README.md' --workspace . --out artifacts/local-yabanin-20261028/headless-mock` | 0 | Classpath loaded from `artifacts/user-examples/classpath.txt`; scripted CLI smoke |
| `make quality-changed` | 0 | Minimum fast profile; operational record and ignored local setup are excluded from product-input changes |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | All ten fast gates passed; manifest `artifacts/quality/20261028-044247-53192-0001/manifest.json` |
| `make quality-verify MANIFEST=artifacts/quality/20261028-044247-53192-0001/manifest.json PROFILES=fast` | 2 | Source fingerprint changed in three worktree files; no stale override used |
| `git diff --check` | 0 | No tracked whitespace errors |

The first automated PTY harness waited for `raider>` instead of the chat
interface's `you>` and timed out after 60 seconds (child exit 143). Its output
already showed successful chat startup. This was a smoke-harness prompt
expectation error, not a passing check; first-attempt artifacts are retained.
The direct PTY run used the actual prompt and exited normally. Early Python
network probes also failed with URLError; the successful authenticated curl
probe is the authentication evidence.

## Limitations / blockers

The gate runner includes nested `wt/` files in its source inventory. The manifest
fingerprint was `1c731c3607f367f533d9825cd9721d97502fda476c6b9ec13277443007963c2b`.
Verification found these three externally modified paths:

- `wt/rai-226/docs/work/records/RAI-226.md`
- `wt/rai-229/contracts/acceptance/cross-ecosystem/scenarios/lib.sh`
- `wt/rai-230/docs/work/records/RAI-230.md`

No other source-inventory entries differed when diagnosed, and root Git status
contained only this new work record. These other lanes were left untouched.
The successful gate execution is retained as historical evidence, not a fresh
acceptance of the whole current inventory. Final task acceptance needs a stable
candidate and a fresh affected run/verification after concurrent work settles.
This does not invalidate the separate local startup and authentication checks.

This verifies local setup, compiler/chat startup and current authentication/model
listing. It does not re-run paid model scenarios or establish new live coding
quality. Historical live transport/tool-round evidence remains scoped to
[RAI-184](RAI-184.md), including its pending Anthropic leg and unknown pricing.
The ordinary interactive launcher does not add a monetary cap.

The local launcher depends on this checkout, its generated classpath and the
existing private credential file. Rebuild with `sh scripts/build/user-examples.sh`
after source/dependency updates. It is not an installed or published release.

## Review and next task

Visible read-only reviewer: `status_review` (Luna). Final evidence review found
no setup blocker. After the freshness refusal, it adjudicated local setup as
functionally ready while keeping formal task acceptance pending on stable evidence.
It verified the corrected chat PTY evidence, credential-helper reference,
preserved caller workspace and no-inference startup. The local task card is
`artifacts/local-yabanin-20261028/task.md`.

The backlog lists RAI-226 through RAI-231 as ready; changing worktree files show
that RAI-226/229/230 already have external lane activity, so coordinate ownership
before assigning them. RAI-228 (scanner target-directory hygiene) is another
ready follow-up in the backlog. The current backlog marks RAI-206 blocked on
demand evidence, superseding the older board entry's ready status. None of these
engineering changes is part of this local setup request.

Product evidence remains tied to its checked source. This local startup report
does not rebind previous integrated gate manifests to a new candidate.
