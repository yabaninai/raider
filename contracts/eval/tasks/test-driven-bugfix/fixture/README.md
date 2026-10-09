# calc.sh — spec

`bin/calc.sh` is a tiny integer calculator. All operands are 32-bit integers.

## Commands

- `abs N` — absolute value of N.
- `max N N ...` — maximum of at least two integers.
- `median N N ...` — middle value of the sorted operands. With an even number
  of operands the LOWER middle value is returned (e.g. `median 10 20 30 40`
  prints `20`).

## Errors

Any invalid operand or unknown command must exit with code 2 and print a
diagnostic starting with `error:` to stderr.

## Tests

Run the suite from the repo root: `sh tests/run-tests.sh`.
One test currently fails — make it pass without breaking the others.
