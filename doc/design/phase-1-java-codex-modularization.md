# Phase 1 Rebuild Plan: Java Codex Maven Foundation

## Summary

The repository has been restructured from a single Spring AI playground into a Maven multi-module build with one product path and one quarantined experiments path.

Phase-1 modules:

- `codex-parent`
- `codex-protocol`
- `codex-core`
- `codex-tools-local`
- `codex-runtime-spring-ai`
- `codex-cli`
- `experimental`

The immediate product focus is the CLI experience. UI- and app-server-style surfaces are intentionally deferred.

## Design Goals

- establish clean module boundaries early
- keep product code independent from playground/demo code
- centralize shared LLM/runtime defaults without duplicating `application.yml`
- converge the product path on a single Codex-style runtime instead of preserving experimental agent variants
- keep Spring dependencies out of `codex-protocol` and `codex-core`

## Module Responsibilities

### `codex-protocol`

Defines shared records such as conversation ids, thread summaries, messages, runtime events, workspace settings, and tool result envelopes.

### `codex-core`

Defines pure Java orchestration contracts including:

- `CodexAgent`
- `ConversationStore`
- `ModelGateway`
- `ToolRegistry`
- `ToolExecutor`
- `WorkspacePolicy`

### `codex-tools-local`

Contains local workspace tools:

- file read
- file write
- shell command execution with approval policy
- workspace search
- targeted patch editing

These implementations preserve workspace-root validation and command timeout behavior.

### `codex-runtime-spring-ai`

Owns Spring AI integration:

- Spring configuration
- typed configuration properties
- runtime defaults
- single Codex agent implementation

This module ships `codex-runtime-defaults.yml`, not `application.yml`.

### `codex-cli`

Owns the CLI launcher and interactive command loop. It is the only repackaged Spring Boot artifact in phase 1.

### `experimental`

Contains non-product demos and playground code under `org.dean.experimental.*`. Product modules must not depend on it.

## Configuration Ownership

- shared defaults live in `codex-runtime-spring-ai`
- each runnable module owns its own `application.yml`
- runnable modules import the shared defaults explicitly with `spring.config.import`
- secrets are externalized through environment variables instead of committed YAML values

## Dependency Direction

- `codex-protocol` -> no internal dependencies
- `codex-core` -> `codex-protocol`
- `codex-tools-local` -> `codex-core`
- `codex-runtime-spring-ai` -> `codex-core`, `codex-tools-local`
- `codex-cli` -> `codex-runtime-spring-ai`, `codex-tools-local`
- `experimental` -> may depend on shared/runtime modules, but never the other way around

## Phase-1 Acceptance

- the full root build passes with `mvn test`
- `codex-cli` is the only Boot-repackaged product artifact
- `codex-protocol` and `codex-core` compile without Spring dependencies
- local tool tests are preserved in `codex-tools-local`
- runtime agent tests are preserved in `codex-runtime-spring-ai`
- CLI smoke tests live in `codex-cli`
- `experimental` builds independently
- shared runtime defaults are imported explicitly

## Current Runtime Direction

The current implementation has already moved beyond the initial module split:

- conversation threads and turns are now persisted on disk
- turn execution is handled by a dedicated runtime service
- the CLI sits on top of thread and turn primitives instead of talking directly to the model runtime
- shell approvals are now persisted and can be approved or rejected from the CLI
- approval-required commands now pause the active turn and resume that same turn after approval or rejection

## Next Likely Phases

- richer tool surface such as git and structured patch planning
- protocol/event streaming for future app-server integrations
- sandboxing and policy controls
