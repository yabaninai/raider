# Vendored pi agent profile corpus — provenance (RAI-032)

This directory contains an exact, hash-pinned copy of the public pi coding-agent
**subagent example profile corpus**: the four sample agent definitions
(`scout`, `planner`, `reviewer`, `worker`), the three workflow prompt presets,
their README, the agent-discovery source that defines the frontmatter parse
semantics, and the upstream MIT license file. It is the immutable input for the
Raider pi profile importer (`modules/compat-pi`) and its golden acceptance
fixture (PIIMP-01).

## Pinned upstream reference

| Field | Value |
| --- | --- |
| Upstream repository | `https://github.com/earendil-works/pi` |
| Upstream path | `packages/coding-agent/examples/extensions/subagent` |
| Pinned commit | `55a5c6914570e2ae31ca36297654483bbbf46883` |
| Commit date | 2026-11-05 14:20:32 +0100 |
| Commit subject | `fix(schema): num no longer takes nan and infinity` |
| Fetched on (UTC) | 2026-11-05 |
| License | MIT, `Copyright (c) 2025 Mario Zechner` (`upstream/LICENSE`) |

`index.ts` (the process-based subagent runtime extension) is **intentionally
not vendored**: it is runtime extension code, not profile corpus, and executing
TS extensions on the JVM is a declared non-goal of RAI-032. It remains
recoverable from the pinned commit.

## Exact fetch commands

The corpus was fetched with a sparse, shallow clone (public repository; no
paid API involved):

```sh
git clone --filter=blob:none --no-checkout --depth 1 \
  https://github.com/earendil-works/pi <tmp>/pi-upstream
git -C <tmp>/pi-upstream sparse-checkout set --no-cone \
  packages/coding-agent/examples/extensions/subagent LICENSE
git -C <tmp>/pi-upstream checkout
git -C <tmp>/pi-upstream rev-parse HEAD
# => 55a5c6914570e2ae31ca36297654483bbbf46883
```

Byte-exact copies of the listed files were then placed under
`vendor/pi-profiles/upstream/` (plain `cp`, no content edits).

Alternative reproduction from the same pin (any one is sufficient):

```sh
git -C <tmp>/pi-upstream fetch --depth 1 origin 55a5c6914570e2ae31ca36297654483bbbf46883
curl -L -o pi.tar.gz https://github.com/earendil-works/pi/archive/55a5c6914570e2ae31ca36297654483bbbf46883.tar.gz
```

## Content hashes

Every vendored file is pinned by SHA-256 in `MANIFEST.sha256`
(`sha256sum` format, paths relative to this directory). Verify with:

```sh
cd vendor/pi-profiles && shasum -a 256 -c MANIFEST.sha256
```

| File (relative) | SHA-256 |
| --- | --- |
| `upstream/agents.ts` | `cc85ecd33b89aa575bea3d2a9a9da7654a6305da4de02e5041aa5342a1de29b4` |
| `upstream/agents/planner.md` | `452f21f65957753cec3b0772004209c108071be168e627fffd7191234c0ce64e` |
| `upstream/agents/reviewer.md` | `b5c3f13b67207e8e32de631f8cd52a373d9b956028e2f8c1fd8b737b5f239265` |
| `upstream/agents/scout.md` | `3b84e8e01c6e05c8dfbff1c845c0c6371f4f16350735d4d1dbd02bfa98b1d4e6` |
| `upstream/agents/worker.md` | `ca2b2298fcbd8bfafcfe61977dc8075001a64b9c4d8c289c3b5eaa896efe6238` |
| `upstream/LICENSE` | `0457f5bcec3b3b211605dfb5d1a49042fd638f3686a410fe099c24a25af13c48` |
| `upstream/prompts/implement-and-review.md` | `019ba4bf42d596ea03fc4564be5b2cc55a06227c95db19a4d5a5bb96697a75f9` |
| `upstream/prompts/implement.md` | `65462de33c97ac1372cfed270dad9af164605b05028c069dd4c013ff9792675b` |
| `upstream/prompts/scout-and-plan.md` | `9506399391e0d2c71826c45f26cc332175dd964465f99a8bb880e109f8ed89f4` |
| `upstream/README.md` | `f911b3a0d74675f2c6daa44e00ad91d92ae0254ed3053fb82eccc637330e1283` |

## Rules for this directory

- The vendored bytes are immutable input: any content change is a new
  provenance event (new pin, new hashes, new golden) — never a silent edit.
- Updating the pin requires re-running the fetch commands, regenerating
  `MANIFEST.sha256`, re-reviewing the importer compatibility report, and
  re-pinning `contracts/compat/pi/golden/pi-import-golden.json`.
- License obligations are discharged in `THIRD_PARTY_NOTICES.md` at the
  repository root and by keeping the unmodified `upstream/LICENSE` copy here.
