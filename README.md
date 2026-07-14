# cloud-itonami-isic-1020

Open Business Blueprint for **ISIC 1020**: processing and preserving
of fish, crustaceans and molluscs — the second representative *food-manufacturing*
(食) vertical of the 衣食住 scaffold batch (ADR-2607122200).

**Maturity: `:blueprint`** — this repository publishes the business
blueprint only. There is **no actor implementation yet**, and none is
claimed. ISIC division 10-12 (food) sits in **rollout Wave 3
(production/robotics)** of the reverse-toposort plan (ADR-2607121000):
implementation is gated on the robotics premise (ADR-2607011000) — a
real robot fleet plus an independent governor with an accident-free
audit ledger. Publishing the blueprint now is deliberate ammunition
loading for when that gate opens (ADR-2607122100 Track A).

## What the implemented actor will be

**SeafoodOps-LLM ⊣ Seafood Processing Governor** — the fleet-standard
pattern: the advisor LLM drafts intake, quality-testing scheduling,
traceability and recall assessments; the independent
`:seafood-processing-governor` (a keyword unique fleet-wide) gates every
action; physical-domain work (filleting, freezing, cold-chain handling)
is executed by robots under `kotoba-lang/robotics` safety classes,
never dispatched directly by the LLM. Food-safety-critical actions
require human sign-off.

Operating states: `intake → inspect → process → freeze/preserve → package → audit`.

## Scope: ISIC 1020 seafood processing domains

- **Finfish** (salmon, tuna, cod, mackerel)
- **Crustaceans** (shrimp, crab, lobster)
- **Molluscs** (oysters, clams, squid, scallops)

Hazards specific to ISIC 1020:
- **Histamine formation** (scombroid fish, warm temperature)
- **Vibrio spp.** (raw, chilled seafood)
- **Parasites** (roundworms, tapeworms)
- **Cold-chain integrity** (critical for all products)
- **Shelf-life limits** (species + storage temp dependent)
- **Allergen management** (shellfish proteins, iodine)

## Why open

AGPL-3.0-or-later, forkable by any qualified operator, so local seafood
processors never surrender production and traceability data to a
closed SaaS. Part of the [cloud-itonami](https://itonami.cloud) open
business fleet.

## Project structure

- `src/seafoodprocessing/` — portable `.cljc` actor modules
  - `facts.cljc` — domain facts (products, jurisdictions, evidence requirements)
  - `registry.cljc` — pure food-safety validation functions
  - `governor.cljc` — independent compliance layer
  - `advisor.cljc` — LLM advisor protocol & mock
  - `operation.cljc` — main operation flow
  - `store.cljc` — batch metadata store (seam for Datomic/kotoba-server)
  - `phase.cljc` — rollout phase gates
  - `sim.cljc` — simulation harness

- `test/seafoodprocessing/` — comprehensive test suite
- `deps.edn` — dependencies (langgraph, langchain, test-runner, clj-kondo)
- `blueprint.edn` — itonami blueprint metadata

## Testing

```bash
clojure -M:test
```

## Development / demo

```bash
clojure -M:dev:run
```

## Static analysis

```bash
clojure -M:lint
```

## Governance

See `GOVERNANCE.md` for contributor & maintainer roles.

## Security

See `SECURITY.md` for responsible disclosure.

## Code of Conduct

See `CODE_OF_CONDUCT.md`.
