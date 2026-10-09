# Task: repo-search (RAI-081, EVAL-01)

Workspace: the `fixture/` copy you receive — a small read-only Scala service
skeleton ("orders-billing"). Do NOT modify any file in the workspace.

Answer the four questions below by searching the repository, then write a
single answer file `ANSWER.md` in the workspace root using the exact line
format under "Answer format". No other files may be created or changed.

## Questions

Q1. Which module directory (relative path, form `src/<module>`) contains the
    definition of `final case class Invoice`?

Q2. How many `TODO` markers are there in total across all files matching
    `src/**/*.scala`?

Q3. Which files under `src/` contain the call `Money.legacyParse(`? List the
    relative paths, comma-separated, in alphabetical order.

Q4. Which default currency is configured in `config/app.conf`?

## Answer format

`ANSWER.md` must contain exactly these four lines (case-sensitive values):

```
Q1: <module path>
Q2: <integer>
Q3: <comma-separated relative paths>
Q4: <currency code>
```

Grading is deterministic; a checker outside the workspace compares the answers
against ground truth and verifies that no workspace file was modified.
