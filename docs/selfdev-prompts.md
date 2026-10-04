# Raider Self-Dev: Prompts (2026-10-04)

Prerequisites: local llama.cpp server on `127.0.0.1:8081` (OpenAI-compatible, 27B).
The coder agent reads/searches/edits/runs commands ONLY inside the workspace
(= CWD at launch; realpath containment, symlink escapes are denied).
Mutations — only via `proc_run` with EXPLICIT argv (no shell), visible in every artifact.

## Launch format

```sh
cd /path/to/scalaagent
sh scripts/selfdev/raider.sh run --agent coder \
  --provider openai --base-url http://127.0.0.1:8081/v1 --model local \
  --workspace . \
  --input "<PROMPT>" \
  --out artifacts/selfdev
```

Result: `artifacts/selfdev/work-*/run.json` (validated RunResult),
`events.jsonl`, `summary.md`, `transcript.json`. Exit code per ci-runtime §8.

## Prompt 1 — reconnaissance (read-only, safe first run)

```text
Read the file build.sbt in the root via fs_read (max_lines 120) and list
the project module names. Do not modify anything.
```

## Prompt 2 — code search (read-only)

```text
Use fs_search to find all occurrences of "AgentLoop" in scala files
(max 20 matches) and list the files where the agent loop is defined
and used. Do not modify anything.
```

## Prompt 3 — build check (proc_run, no source mutations)

```text
Run the build check via proc_run with argv
["sbt","--batch","compile"] and timeout_s 240, then report:
is the build green and what are the last lines of output.
Do not modify source files.
```

## Prompt 4 — self-correction (mutation via proc_run)

```text
Create the file TODO-SELFDEV.txt via proc_run with argv
["python3","-c","open('TODO-SELFDEV.txt','w').write('Raiedr self-dev start\\n')"]
then fix the typo "Raiedr" to "Raider" via proc_run with argv
["python3","-c","p='TODO-SELFDEV.txt'; s=open(p).read(); open(p,'w').write(s.replace('Raiedr','Raider'))"],
then read the file via fs_read and show the final content.
```

## Prompt 5 — self-hosting main loop

```text
You are working on the Raider project (Scala 3 + ZIO). Plan:
1) fs_search "obligation" in docs/work/board.md (max 10 matches);
2) fs_read one of the mentioned files (max 60 lines);
3) proc_run ["sbt","--batch","test"] timeout_s 300;
4) briefly: which obligation to close next and why.
Do not modify source files — analysis and build status only.
```

## Prompt 6 — edit via fs_edit (primary mutation mechanism)

```text
1) fs_read {"path":"NOTES.md","max_lines":10} — remember the sha256
2) fs_edit {"path":"NOTES.md","content":"new content","expected_sha256":"<sha from step 1>"}
3) fs_read {"path":"NOTES.md","max_lines":10} — verify the result
```

## Limitations (honest)

- 27B local model: keep prompts small and specific; `--input` is single-line
  (multi-line — via `--input-json <file>`).
- Mutations without an edit tool (RAI-015 not built): only proc_run argv.
- Non-streaming wire: long generations hit callTimeoutMs (180s).
- Workspace = CWD at launch: launching outside the repo = agent can't see the repo.
- Every proc_run is visible in events/logs; no hidden write channels.
