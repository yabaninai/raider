# <TASK-ID>: <concrete result>

Status: todo. Milestone: <M>. Dependencies: <accepted IDs>.

## Problem / concrete scenario

<What the user runs and what should happen.>

Implementation dependencies: <accepted interfaces/components>. Integration dependencies: <later end-to-end obligations>. Parent acceptance requires integration; a child does not depend on acceptance of its parent.

Lane workspace / baseline: <absolute path + commit or immutable inventory hash; Git optional for snapshot copy>.

## Context

<Relevant specification sections, symbols, baseline evidence.>

## Allowed paths / API boundary

<Exact list. New files are allowed only in the listed areas.>

## Required behavior

<Measurable contract and defaults.>

## Acceptance

- Normal: <fixture/test ID>.
- Empty/error: <fixture/test ID>.
- Cancellation/concurrency/adversarial cases, if applicable: <fixture/test ID>.

## Non-goals

<What must explicitly remain unchanged.>

## Required gates

<Targeted commands after implementation of the foundation, profiles, and external prerequisites.>

## Compatibility / rollback

<Format/API compatibility, reversibility, and recovery policy.>

## Deliverables

<Diff, tests, docs, work record, manifest.>
