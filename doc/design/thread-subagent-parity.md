# Thread And Sub-Agent Parity Tracker

## Goal

Move the Java Codex rebuild from single-thread REPL-style session handling to Codex-style thread lifecycle management plus real sub-agent orchestration, using the upstream reference repo at `dedd1c386`.

## Current Gap

Today the Java implementation has:

- basic thread lifecycle only: `thread/start`, `thread/resume`, `thread/list`, `thread/read`, and `thread/compact/start`
- thin thread metadata centered on `ThreadSummary`
- no archive, fork, rollback, or loaded-thread model
- no sub-agent runtime, protocol, persistence, or CLI UX

Target behavior is:

- threads are first-class runtime objects with lifecycle, status, filters, and richer metadata
- thread operations include fork, archive, unarchive, rollback, and loaded-thread discovery
- sub-agents can be spawned, messaged, waited on, resumed, listed, and closed
- thread and sub-agent state is navigable from the CLI and app-server

## Delivery Cutlines

- Thread-management MVP: Issues 1 through 6
- Sub-agent MVP: Issues 7 through 9
- User-visible parity foundation for this area: Issues 1 through 10

## Dependency Order

1. Issue 1 -> Issue 2 -> Issue 3 -> Issue 4
2. Issue 5 depends on Issues 1 through 4
3. Issue 6 depends on Issues 1 through 4
4. Issue 7 depends on Issues 1 through 4
5. Issue 8 depends on Issue 7
6. Issue 9 depends on Issues 7 and 8
7. Issue 10 depends on Issues 2 through 9

## Issue 1: Redesign the Java thread model to match Codex runtime metadata

Status: `completed`

Depends on: none

Scope:

- add richer thread metadata: source, cwd, runtime status, model/provider, archive state, preview, and optional agent metadata
- keep existing Java APIs source-compatible where practical during migration
- make the protocol capable of representing the upstream `Thread` shape

Acceptance:

- [x] replace `ThreadSummary`-only thinking with a richer thread record in `codex-protocol`
- [x] represent archive state, loaded state, and runtime status
- [x] represent sub-agent metadata such as nickname, role, and path
- [x] keep current thread list/read flows compiling during the transition

Likely touch points:

- `codex-protocol`
- `codex-core`
- `codex-runtime-spring-ai`

## Issue 2: Expand app-server thread RPCs to Codex-style lifecycle operations

Status: `completed`

Depends on: Issue 1

Scope:

- add `thread/fork`, `thread/archive`, `thread/unarchive`, `thread/rollback`, and `thread/loaded/list`
- add richer params to `thread/list` and `thread/read`
- route the new methods through JSON-RPC and in-process app-server layers

Acceptance:

- [x] add protocol types for the missing thread lifecycle methods
- [x] route the new methods through `CodexAppServerSession`
- [x] update JSON-RPC transport dispatch and typed response handling
- [x] keep existing thread start/resume/read flows working

Likely touch points:

- `codex-protocol`
- `codex-core`
- `codex-runtime-spring-ai`
- `codex-cli`

## Issue 3: Introduce real thread runtime state and loaded-thread tracking

Status: `completed`

Depends on: Issue 2

Scope:

- distinguish loaded threads from persisted-but-not-loaded threads
- add runtime thread status instead of treating resume as a no-op selection
- make subscriptions and resume semantics depend on actual loaded runtime state

Acceptance:

- [x] add a loaded-thread registry in the runtime gateway
- [x] distinguish `thread/read` from `thread/resume`
- [x] return loaded-thread ids through `thread/loaded/list`
- [x] preserve restart-safe semantics for persisted threads

Likely touch points:

- `codex-core`
- `codex-runtime-spring-ai`

## Issue 4: Implement thread list/read parity with filtering, pagination, and includeTurns

Status: `completed`

Depends on: Issue 3

Scope:

- add pagination and filter params for thread listing
- add `includeTurns` semantics to thread reads
- make metadata-only thread reads cheap enough for navigation and backfill flows

Acceptance:

- [x] support pagination cursor and limit on `thread/list`
- [x] support archived/source/provider/cwd/search filters
- [x] support `includeTurns` on `thread/read`
- [x] add tests for filtered thread lookup and metadata-only thread reads

Likely touch points:

- `codex-protocol`
- `codex-runtime-spring-ai`
- `codex-cli`

## Issue 5: Implement thread fork with per-thread overrides

Status: `completed`

Depends on: Issue 4

Scope:

- fork from persisted canonical history
- allow per-thread overrides such as cwd, model, sandbox, approval policy, and instructions
- make forked thread history diverge cleanly from the parent

Acceptance:

- [x] add `thread/fork` protocol and runtime handling
- [x] support thread-level override fields on fork
- [x] make forked threads replay parent history correctly
- [x] cover follow-up turns on a forked thread with tests

Likely touch points:

- `codex-protocol`
- `codex-core`
- `codex-runtime-spring-ai`

## Issue 6: Implement archive, unarchive, and rollback

Status: `completed`

Depends on: Issue 4

Scope:

- persist archive state and expose it in list/read responses
- implement rollback of recent turns without pretending to undo filesystem side effects
- make archived threads disappear from default active lists

Acceptance:

- [x] add archive and unarchive operations
- [x] add rollback of `n` turns with explicit history-only semantics
- [x] return updated thread state after rollback
- [x] cover archived filtering and rollback replay with tests

Likely touch points:

- `codex-protocol`
- `codex-runtime-spring-ai`
- `codex-cli`

## Issue 7: Add a Java `AgentControl` layer for sub-agent lifecycle

Status: `completed`

Depends on: Issues 1 through 4

Scope:

- introduce an `AgentControl`-style core for spawned-agent lifecycle
- persist parent-child spawn edges and agent metadata
- track agent depth and enforce spawn-depth rules

Acceptance:

- [x] add core interfaces for spawn, send, wait, resume, close, and list agents
- [x] persist agent metadata including parent relationship and task path
- [x] enforce configurable depth limits
- [x] expose agent status to runtime and protocol layers

Likely touch points:

- `codex-core`
- `codex-runtime-spring-ai`

## Issue 8: Implement sub-agent tools and collaboration behavior

Status: `completed`

Depends on: Issue 7

Scope:

- add `spawn_agent`, `send_input`, `wait_agent`, `resume_agent`, `close_agent`, and `list_agents`
- support structured inter-agent messaging and lifecycle events
- start with a minimal shape, but keep a path toward MultiAgentV2-style behavior

Acceptance:

- [x] add protocol and runtime support for the collaboration tool set
- [x] spawn agents with task name, role, and optional overrides
- [x] support queueing messages to existing agents
- [x] support waiting, resuming, listing, and closing agents

Likely touch points:

- `codex-protocol`
- `codex-core`
- `codex-runtime-spring-ai`

## Issue 9: Support sub-agent discovery and navigation from thread state

Status: `completed`

Depends on: Issues 7 and 8

Scope:

- discover sub-agent threads from loaded-thread state and spawn edges
- expose enough metadata for thread-tree navigation
- backfill previously spawned subagents on session reconnect

Acceptance:

- [x] add spawn-tree lookup for a primary thread
- [x] support loaded-subagent backfill after reconnect or resume
- [x] surface agent metadata in thread read/list responses
- [x] cover rehydration and navigation with tests

Likely touch points:

- `codex-runtime-spring-ai`
- `codex-cli`

## Issue 10: Add CLI UX for thread and sub-agent management

Status: `completed`

Depends on: Issues 2 through 9

Scope:

- add CLI commands or subcommands for resume, fork, archive, rollback, and sub-agent management
- display loaded/archived/subagent state clearly
- support navigating and inspecting spawned-agent trees

Acceptance:

- [x] add user-facing thread lifecycle commands beyond `:new`, `:threads`, and `:use`
- [x] add user-facing sub-agent inspection and control commands
- [x] render agent and thread status clearly in CLI output
- [x] cover the new commands with CLI tests

Likely touch points:

- `codex-cli`
- `codex-protocol`
- `codex-runtime-spring-ai`

## Notes

- Keep the existing thread persistence and compaction work compatible while the richer thread model is introduced.
- Do not bolt sub-agents directly onto the current single-thread CLI assumptions; thread metadata and lifecycle need to be expanded first.
- Guardian-style approval-review subagents should wait until basic agent lifecycle and thread metadata exist.
