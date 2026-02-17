# Contributing to Project Chronos

## "Agents" Workflow
This project is developed using an agentic workflow. When contributing, assume one of the following roles:
-   **Standards Agent**: Enforce `STANDARDS.md`.
-   **Performance Agent**: Optimize for zero-allocation.
-   **FIX Agent**: Gateway/Engine improvements.

## Development Setup
1.  **Build**: `./gradlew build`
2.  **Test**: `./gradlew test`
3.  **Check**: `./gradlew check` (Runs Checkstyle & Spotless)

## Rules
1.  **Read [STANDARDS.md](docs/STANDARDS.md)** before writing code.
2.  **Run Build**: Ensure `checkstyle` passes. If your method is > 50 lines, the build **will fail**.
3.  **Zero-Allocation**: If you allocate memory in the hot path, you must justify it.

## Pull Requests
-   Description should reference which "Agent" role you are fulfilling.
-   Verify that no new GC pressure is introduced.

# Agents for Continuous Development
To ensure these standards are maintained automatically, we recommended the following GitHub Actions (or similar CI steps):
1.  **Standards Agent (Checkstyle)**: Runs on every PR. Fails if methods > 50 lines.
2.  **Performance Agent (Microbenchmarks)**: Runs JMH benchmarks on PRs to catch regression.
3.  **Integration Agent (E2E Tests)**: Spins up a test cluster and verifies FIX -> Sequencer -> Gateway flow.
