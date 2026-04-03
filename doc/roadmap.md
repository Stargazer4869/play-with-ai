# Roadmap

This repository is rebuilding Codex in Java, with the CLI as the first product surface and Spring AI as the runtime backbone.

## Priority Order

These are the highest-value directions to close next. The goal is to keep the Java rebuild aligned with Codex as a runtime platform, not just a tool-driven demo.

### 1. Non-blocking CLI interaction and steering

This is the top priority because the current CLI interaction model still feels less like Codex and more like a synchronous shell wrapper around a runtime.

#### Codex reference model

Rust Codex already assumes an event-driven client:

- `turn/start` begins a turn and immediately returns while notifications continue streaming through the app-server.
- `turn/steer` appends input to the currently active regular turn, requires `expectedTurnId`, and does not start a new turn or accept turn overrides.
- `turn/interrupt` cancels the active turn, but background terminals remain independent.
- The TUI keeps running its input/render loop while turns are active and submits thread operations asynchronously.
- On user submit, the TUI first tries `turn_steer(...)` if the thread already has an active turn; only if there is no active turn does it fall back to `turn_start(...)`.
- The runtime can also wake idle sessions from queued pending input and mailbox work, so same-thread follow-up input is part of the normal session model rather than a special escape hatch.

Reference points:

- `codex-rs/app-server/README.md`
- `codex-rs/tui/src/app.rs`
- `codex-rs/core/src/codex_thread.rs`
- `codex-rs/core/src/tasks/mod.rs`

#### Current Java state

The Java runtime already has meaningful steering support:

- `DefaultCodexRuntimeGateway.turnSteer(...)` accepts same-turn input.
- `SpringAiCodexAgent` consumes steering between planner steps.
- The CLI exposes `/steer`.

But the CLI interaction model is still synchronous:

- `CodexConsoleRunner.runInteractiveLoop(...)` reads input through `Scanner`.
- After prompt submission it enters `waitForTurn(...)`.
- While waiting, the same terminal cannot naturally accept more user input.

Reference points:

- `codex-cli/src/main/java/org/dean/codex/cli/CodexConsoleRunner.java`
- `codex-runtime-spring-ai/src/main/java/org/dean/codex/runtime/springai/runtime/DefaultCodexRuntimeGateway.java`
- `codex-runtime-spring-ai/src/main/java/org/dean/codex/runtime/springai/agent/SpringAiCodexAgent.java`

#### Main gap

The backend is partially ready, but the user experience is still wrong:

- steering is real in the runtime but mostly unusable from the same interactive CLI session
- the CLI is still "submit and block" instead of "submit and stay interactive"
- normal input during an active turn does not naturally become steering
- the client does not reconcile active-turn state the way Codex does

#### Implementation direction

1. Replace synchronous `waitForTurn(...)` ownership of the terminal with an event-driven input/output loop.
2. Keep one path subscribed to app-server notifications and rendering streamed updates.
3. Keep another path reading user input while the turn is active.
4. Treat plain input during an active regular turn as `turn/steer`.
5. Treat plain input while idle as `turn/start`.
6. Preserve explicit rejection for non-steerable turn kinds such as review or manual compaction.

### 2. Thread management

We already have more than basic session persistence, but the Java runtime still does not treat a thread as fully as Codex does: a loaded, subscribable, metadata-rich runtime object.

#### Codex reference model

Rust Codex thread management includes much more than start/resume:

- lifecycle operations such as `thread/start`, `thread/resume`, `thread/fork`, `thread/archive`, `thread/unarchive`, `thread/unsubscribe`, `thread/rollback`, and `thread/compact/start`
- thread-scoped operations such as `thread/shellCommand`, `thread/backgroundTerminals/clean`, and `thread/realtime/*`
- thread metadata patching via `thread/metadata/update`
- user-facing naming via `thread/name/set`
- notifications like `thread/status/changed`, `thread/started`, `thread/closed`, `thread/archived`, and `thread/unarchived`
- richer persisted metadata including reasoning effort, sandbox policy, approval mode, token usage, first user message, git info, rollout path, and archive state

Reference points:

- `codex-rs/app-server/README.md`
- `codex-rs/state/src/model/thread_metadata.rs`
- `codex-rs/core/src/codex/rollout_reconstruction.rs`

#### Current Java state

The Java app-server/runtime already supports:

- `threadStart`, `threadResume`, `threadFork`, `threadArchive`, `threadUnarchive`, `threadRollback`, and `threadCompactStart`
- persisted thread summaries with title, model, cwd/path, archive state, and agent lineage
- thread tree navigation and related-thread reads

Reference points:

- `codex-core/src/main/java/org/dean/codex/core/appserver/CodexAppServerSession.java`
- `codex-protocol/src/main/java/org/dean/codex/protocol/conversation/ThreadSummary.java`
- `codex-runtime-spring-ai/src/main/java/org/dean/codex/runtime/springai/conversation/FileSystemConversationStore.java`

#### Main gap

The gap is no longer "can we persist sessions?" It is that our threads are still less operationally complete than Codex threads:

- no `thread/unsubscribe` and weaker loaded/not-loaded lifecycle semantics
- no `thread/name/set` or `thread/metadata/update`
- thinner metadata and weaker list/filter/index behavior
- no thread-scoped shell command surface, background terminals, or realtime sessions
- simpler reconstruction/rollback semantics than Codex rollout reconstruction

#### Implementation direction

1. Add explicit thread subscription and unload semantics.
2. Add thread naming and metadata patch operations.
3. Expand `ThreadSummary` and backing persistence with approval/sandbox/model-effort/token/git metadata.
4. Move toward stronger list/filter/index support over persisted threads.
5. Add thread-scoped runtime services such as shell-command and background-terminal ownership only after the lifecycle model is solid.

### 3. Multi-agent support

We already have real sub-agent mechanics, but Codex treats collaboration as a richer runtime/protocol feature than we currently do.

#### Codex reference model

Rust Codex supports a broad multi-agent surface:

- `spawn_agent`, `send_input`, `send_message`, `assign_task`, `resume_agent`, `wait_agent`, and `list_agents`
- mailbox-oriented waiting and richer agent-message delivery semantics in MultiAgentV2
- path-based or canonical target resolution for agents
- collaboration activity represented in the event/item stream via `collabToolCall`
- internal system-owned agent patterns such as `guardian_subagent`

Reference points:

- `codex-rs/tools/src/agent_tool.rs`
- `codex-rs/core/src/tools/handlers/multi_agents_v2`
- `codex-rs/app-server/README.md`

#### Current Java state

The Java runtime already supports:

- `spawnAgent`, `sendInput`, `waitAgent`, `resumeAgent`, `closeAgent`, and `listAgents`
- persisted sub-agent lineage in thread storage and thread summaries
- CLI tree navigation for related agent threads

Reference points:

- `codex-core/src/main/java/org/dean/codex/core/agent/AgentControl.java`
- `codex-runtime-spring-ai/src/main/java/org/dean/codex/runtime/springai/runtime/DefaultCodexRuntimeGateway.java`
- `codex-protocol/src/main/java/org/dean/codex/protocol/conversation/ThreadSummary.java`
- `codex-cli/src/main/java/org/dean/codex/cli/CodexConsoleRunner.java`

#### Main gap

The current Java implementation has multi-agent mechanics, but not yet Codex-grade collaboration semantics:

- multi-agent support is still more runtime-local than app-server/protocol-first
- there is no strong split between `send_input`, `send_message`, and `assign_task`
- waiting behavior is simpler than Codex mailbox-driven waiting
- collaboration is not yet represented as first-class protocol items/events in the same way
- there are no internal system-owned agent flows such as guardian/reviewer agents
- CLI supervision is still shallow compared with Codex’s broader agent-control model

#### Implementation direction

1. Promote multi-agent operations into the app-server/client boundary.
2. Split messaging semantics into queue-only messaging versus task-triggering assignment.
3. Add mailbox-style waiting and richer agent-status updates.
4. Represent collaboration actions as first-class thread items/events.
5. Add room for internal system-owned agents after the protocol shape is stable.

## Later Priorities

These remain important, but they should follow the top three because they depend on the runtime shape being correct first.

### 4. App-server/client lifecycle

The transport boundary should keep growing into a stronger client-facing runtime surface with clearer initialization, connection-scoped behavior, and transport semantics.

### 5. Metadata and indexing

Threads need richer metadata for listing, filtering, and resuming. This is partly a thread-management concern, but it is also a standalone indexing concern once more sessions accumulate.

### 6. Extensibility surface

Codex grows through skills, plugins, apps, and related discovery flows. The Java version should extend the skill system without hard-coding all future extension types into the CLI.

### 7. Approvals, sandboxing, and execution UX

Approval-aware execution exists, but the user workflow still needs to feel more like a first-class runtime path.

### 8. Context reconstruction and compaction

Prompt construction should keep moving out of the agent and into reusable runtime services, with compaction and replay getting closer to Codex-style behavior over time.

## How To Use This Roadmap

Keep this document short and directional. Add new items here when they affect the core runtime shape, the CLI interaction model, or the app-server boundary.
