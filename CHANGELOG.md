# Changelog

All notable changes to this project will be documented in this file.

## [0.2.0] - 2026-11-05

- Added an installed `raider --help` entrypoint that exits 0 with an accurate command list (RAI-261).
- Added a separate installed REPL distribution: launcher `raider-repl`, release archive, and version metadata, staged alongside the headless package in local releases (RAI-262; release wiring under RAI-279).
- Reconciled the chat session close lifecycle (`chat.close()`) with the REPL facade so close settles without hanging the terminal (RAI-263).
- Delegated child agents now run under the parent's configured provider model unless delegation arguments explicitly override it; the scripted backend keeps the historical `scripted:<agent>` label (RAI-257).
- The coder base prompt now names the full current tool surface, including the all-or-nothing multi-file patch tool and the irreversible delete tool (RAI-270).

## [0.1.1] - 2026-10-23

- Fix the installed launcher to use Coursier's `--main raider.cli.Main --extra-jars` form with the bundled fat JAR; retain the Java fallback.

## [0.1.0] - 2026-10-22

- First local JVM distribution release; artifacts and formulas verified locally.

