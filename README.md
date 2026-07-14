# cloud-itonami-isco-3113

Open Occupation Blueprint for **ISCO-08 3113**: Electrical Engineering Technicians.

This repository designs a forkable OSS system for electrical field testing and inspection management: a test/inspection robot collects and records electrical measurement data under a governor-gated actor, so the project maintains its own electrical system records, safety logs, and inspection ledger instead of managing paper or closed SaaS systems.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here an electrical test/inspection robot performs electrical test data recording, inspection logging, and site documentation under an actor that proposes actions and an
independent **Electrical Engineering Governor** that gates them. The governor never dispatches
hardware itself; `:high`/`:safety-critical` actions (such as electrical hazard escalation,
or site access approval) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is
rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) —
pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
electrical system registration + baseline tests + inspection schedule
        |
        v
Test Advisor -> Electrical Eng Governor -> record/log, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or escalate an electrical hazard without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `3113`). Required capabilities:

- :robotics
- :identity
- :survey-forms
- :dmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Reference implementation (`:maturity :implemented`)

Full itonami Actor pattern (per ADR-2607011000 / CLAUDE.md's Actors
section, alongside `cloud-itonami-isco-2411`, `-2161`, `-6130`, `-8160`,
`-2166`, `-2641`, `-2651`, `-2652`, `-2654`, `-1219`, `-1223`, `-1330`,
`-1341`, `-1349`, `-1412`, `-1439`, `-2144`, `-2320`, and `-3112`): a real
[`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph)
`StateGraph`, with the Advisor and Governor as distinct graph nodes and
human-in-the-loop interrupt/resume via checkpointing.

```text
:intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                           +-> :request-approval   (:escalate? true, interrupt-before)
                                           +-> :hold               (:hard? true)
```

- `src/eleng/store.cljc` — `Store` protocol + `MemStore`:
  registered electrical projects/sites, committed test/inspection records, an append-only audit ledger.
- `src/eleng/advisor.cljc` — `Advisor` protocol; `mock-advisor`
  (deterministic, default) proposes a test/inspection operation from a request; `llm-advisor`
  wraps a `langchain.model/ChatModel` — either way the advisor only ever
  produces a `:propose`-effect proposal, never a committed record, and LLM parse
  failures always yield `confidence 0.0` (forces escalation, never fabricated confidence).
- `src/eleng/governor.cljc` — `ElenGGovernor/check`: a pure function,
  wired as its own `:govern` node. Hard invariants (unregistered project,
  a proposal whose `:effect` isn't `:propose`) always route to `:hold`.
  Escalation invariants (`:flag-electrical-hazard` or low advisor confidence)
  always route to `:request-approval` — an `interrupt-before` node that the
  graph checkpoints and only resumes on explicit human approval (`actor/approve!`),
  matching the README's robotics-premise statement that electrical hazards
  always require human sign-off.
- `src/eleng/actor.cljc` — `build-graph`, `run-request!`, `approve!`:
  the `langgraph.graph/state-graph` wiring itself.

```bash
clojure -M:test
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation).

## License

AGPL-3.0-or-later.
