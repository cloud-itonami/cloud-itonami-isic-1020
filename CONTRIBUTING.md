# Contributing

Thank you for your interest in contributing to cloud-itonami-isic-1020!

## Getting Started

1. **Fork and Clone**: Fork this repository and clone your fork locally.
2. **Install Dependencies**: Ensure you have Clojure 1.12+ and `clj` installed.
3. **Run Tests**: `clojure -M:test` to verify the test suite passes.

## Development Workflow

1. Create a feature branch from `main`.
2. Make your changes, keeping code portable (`.cljc`).
3. Add tests for new functionality.
4. Run `clojure -M:lint` to check for style/correctness issues.
5. Commit with clear messages explaining the "why" of your change.
6. Push to your fork and open a pull request.

## Code Standards

- **Portable code**: All source in `src/` must be `.cljc` (ClojureScript-compatible).
  No `:clj`-only constructs (JVM interop, stateful mutation, etc.).
  
- **Testing**: Every public function needs a test. Aim for >90% coverage.
  
- **Documentation**: Update docstrings and `README.md` as needed.
  
- **Linting**: Run `clojure -M:lint` before submitting. Fix all errors.

## Architecture Decisions

Changes to the Governor's checks, the Advisor protocol, phase gates, or
operation flow should include:

1. An ADR (Architectural Decision Record) at the superproject level
   explaining the rationale.
2. Updated `README.md` and relevant docstrings.
3. Tests covering the new behavior and any regressions.

## Testing

Run the full suite:
```bash
clojure -M:test
```

Run a single test namespace:
```bash
clojure -M:test -k seafoodprocessing.facts-test
```

## Scope Exclusions

This blueprint does NOT include:

- **Equipment control**: Processing lines, freezers, etc. remain operator-controlled.
- **Direct food-safety determinations**: Histamine limits, temperature ranges,
  etc., are encoded as domain facts and regulations, not LLM-generated.
- **Regulatory compliance**: This actor coordinates operations metadata;
  operators must independently verify compliance with local law.

## Licensing

By contributing, you agree that your contributions are licensed under
AGPL-3.0-or-later (see `LICENSE`).

## Questions?

Open an issue or discussion in this repository. The maintainers are happy
to help clarify design choices or discuss proposed changes.
