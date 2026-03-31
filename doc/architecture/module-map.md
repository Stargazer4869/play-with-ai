# Module Map

## Product Path

| Module | Purpose | Spring-free |
| --- | --- | --- |
| `codex-protocol` | shared contracts, ids, modes, result envelopes | yes |
| `codex-core` | orchestration interfaces and core services | yes |
| `codex-tools-local` | local tool implementations for workspace operations, search, patching, and approval-aware shell execution | no |
| `codex-runtime-spring-ai` | Spring AI runtime wiring and agent implementations | no |
| `codex-cli` | interactive terminal application | no |

## Experimental Path

| Module | Purpose |
| --- | --- |
| `experimental` | demos, playground code, and non-product samples |

## Dependency Direction

```text
codex-protocol
    ^
    |
codex-core
    ^
    |
codex-tools-local
    ^
    |
codex-runtime-spring-ai
    ^
    |
codex-cli

experimental ----> codex-runtime-spring-ai
```

## Config Ownership

- `codex-runtime-spring-ai` publishes shared defaults in [`codex-runtime-defaults.yml`](/Users/chenzhu/Git/play-with-ai/codex-runtime-spring-ai/src/main/resources/codex-runtime-defaults.yml)
- `codex-cli` owns [`application.yml`](/Users/chenzhu/Git/play-with-ai/codex-cli/src/main/resources/application.yml)
- `experimental` owns [`application.yml`](/Users/chenzhu/Git/play-with-ai/experimental/src/main/resources/application.yml)
- runnable modules import shared defaults explicitly through `spring.config.import`
- shell approval behavior is configured from the runnable module through `codex.shell.*`
- context compaction behavior is configured from the runnable module through `codex.context.*`

## Runtime Notes

- threads, turns, compacted thread memory, and approval requests are persisted under `codex.storage-root`
- the runtime exposes a synchronous `CodexRuntime` façade, an async `CodexRuntimeGateway`, and a transport-agnostic `CodexAppServer` contract for clients
- `CodexAppServer` is now a connection factory, and each `CodexAppServerSession` enforces an `initialize` then `initialized` handshake before operational requests are accepted
- app-server capabilities are scoped to a single session, including exact-match notification opt-out configured during initialization
- `CodexAppServerSession` currently supports `thread/start`, `thread/resume`, `thread/list`, `thread/read`, `thread/compact/start`, `turn/start`, `turn/resume`, `turn/interrupt`, `turn/steer`, and `skills/list`
- the runtime now includes a JSON-RPC-style app-server transport slice over stdio, with newline-delimited messages and a transport dispatcher layered on top of `CodexAppServerSession`
- `codex-runtime-spring-ai` now ships a standalone stdio entrypoint: `org.dean.codex.runtime.springai.appserver.CodexAppServerStdioApplication`
- `codex-cli` now talks to the runtime through a JSON-RPC stdio transport client and launches that app-server entrypoint as a local process by default
- `CodexRuntimeGateway` remains the lower-level async execution surface with per-thread runtime notifications
- turns now persist typed `TurnItem` data alongside legacy event history for compatibility during the transition
- the runtime now includes a `ContextManager` seam that produces durable `ThreadMemory` snapshots separate from raw turn storage
- the runtime now includes a `ThreadContextReconstructionService` seam that rebuilds structured thread context from compacted memory plus recent turns, messages, and activities
- prompt construction now consumes reconstructed thread context instead of reading conversation storage ad hoc inside the agent
- `codex-cli` receives streamed turn items while a turn is executing
- `turn/steer` is implemented cooperatively through `TurnControl`, so steering is consumed between planner steps
- the runtime now includes a `SkillService` seam for discovering user/workspace `SKILL.md` files and resolving explicit skill references from turn input
- planner responses can include structured `editPlan` data that is streamed before edits are applied
- approval-required commands pause a turn and resume that same turn after `:approve` or `:reject`

## Namespace Rules

- `org.dean.codex.*` is reserved for product/runtime code
- `org.dean.experimental.*` is reserved for demos and playground code
- product modules must not depend on `experimental`
