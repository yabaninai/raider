Third-party material vendored into this repository, with the license notices
required by the respective licenses. Each vendored corpus carries its exact
upstream pin and content hashes in its own `PROVENANCE.md`/`MANIFEST.sha256`;
this file carries the license notices. No other third-party code or content is
vendored; product dependencies and their licenses are recorded in
`docs/adr/ADR-016-tui-renderer.md` (rejected-license policy) and the build
pins (`build.sbt`, coordinator-owned).

## 1. pi subagent example profile corpus (`vendor/pi-profiles/`)

Vendored, unmodified copies of agent definition Markdown files (`scout`,
`planner`, `reviewer`, `worker`), workflow prompt presets, an example README,
the agent-discovery source file `agents.ts`, and the upstream `LICENSE` file,
from the pi repository at pinned commit
`55a5c6914570e2ae31ca36297654483bbbf46883`
(see `vendor/pi-profiles/PROVENANCE.md` for the full pin and hashes).

The corpus is used as import input and acceptance fixture for the Raider pi
profile importer (`modules/compat-pi`, card RAI-032). Derived, Raider-specific
import reports under `contracts/compat/pi/` are Raider project code and carry
the repository's own license; the notices below cover the vendored upstream
material.

MIT License applies to the vendored corpus:

```text
MIT License

Copyright (c) 2025 Mario Zechner

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Upstream source: <https://github.com/earendil-works/pi>.

## OpenCode profile corpus (RAI-033)

- Path: `vendor/opencode-profiles/`
- Upstream: https://github.com/anomalyco/opencode
- Pinned commit: `d7d85817160120fd59fb27ee6ff354f45d18b994` (pinned 2026-11-05)
- License: MIT, see `vendor/opencode-profiles/LICENSE`
- Manifest: `vendor/opencode-profiles/MANIFEST.json` (per-file SHA-256 and provenance)
- Use: compatibility corpus for `modules/compat-opencode` (RAI-033). Upstream
  TypeScript files are vendored as provenance for the extracted JSON semantics;
  they are not executed on the JVM. Derivative code mirrors documented upstream
  behavior at the pinned commit.
