# Task: review-planted-bug (RAI-081, EVAL-01)

Workspace: the `fixture/` copy you receive. `src/Checkout.scala` implements
coupon discounts at checkout; the coupon table it must satisfy is documented
in `README.md`.

One functional defect has been planted in the code. Review the file against
the documented coupon table and find it. The file may also contain benign
style issues — do not spend your report on them.

Do NOT modify any file. Write your finding to `FINDINGS.md` in the workspace
root using EXACTLY this labeled format (each label on its own line):

```
FILE: <relative path of the defective file>
LINE: <line number of the defective line>
SEVERITY: critical | major | minor
CAUSE: <one-sentence description of the defect>
```

## Grading

A protected checker outside the workspace verifies that the finding points at
the actually planted defect (correct file, correct line, correct cause), and
that no workspace file was modified. Extra prose is ignored; the four labeled
fields are what counts.
