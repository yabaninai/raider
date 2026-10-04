# <TASK-ID>: <конкретный результат>

Status: todo. Milestone: <M>. Dependencies: <accepted IDs>.

## Problem / concrete scenario

<Что пользователь запускает и что должно произойти.>

Implementation dependencies: <accepted interfaces/components>. Integration dependencies: <later end-to-end obligations>. Parent acceptance ждёт integration, child не зависит от принятия своего parent.

Lane workspace / baseline: <absolute path + commit or immutable inventory hash; Git optional for snapshot copy>.

## Context

<Relevant specification sections, symbols, baseline evidence.>

## Allowed paths / API boundary

<Точный список. Новые файлы только в перечисленных областях.>

## Required behavior

<Измеримый контракт и defaults.>

## Acceptance

- Normal: <fixture/test ID>.
- Empty/error: <fixture/test ID>.
- Cancellation/concurrency/adversarial if applicable: <fixture/test ID>.

## Non-goals

<Что явно не менять.>

## Required gates

<Targeted commands после реализации foundation, profiles, external prerequisites.>

## Compatibility / rollback

<Format/API compatibility, обратимость и recovery policy.>

## Deliverables

<Diff, tests, docs, work record, manifest.>
