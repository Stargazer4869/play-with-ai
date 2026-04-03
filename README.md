# Java Codex Foundation

This repository is now a phase-1 multi-module Maven foundation for rebuilding Codex in Java with Spring AI, while keeping the earlier demos isolated in an `experimental` module.

## Modules

- `codex-protocol` - shared records, enums, and contracts
- `codex-core` - pure Java orchestration interfaces and core services
- `codex-tools-local` - local workspace tools for file, patch, search, and shell operations
- `codex-runtime-spring-ai` - Spring AI runtime wiring and agent implementations
- `codex-cli` - the primary runnable CLI application
- `experimental` - non-product demos, samples, and playground code

## Package Conventions

- Product code lives under `org.dean.codex.*`
- Experimental code lives under `org.dean.experimental.*`

## Configuration

- Shared LLM/runtime defaults live in [`codex-runtime-defaults.yml`](/Users/chenzhu/Git/play-with-ai/codex-runtime-spring-ai/src/main/resources/codex-runtime-defaults.yml)
- Spring AI prompt/completion logging is off by default there and can be enabled with `CODEX_CHAT_LOG_PROMPT=true` / `CODEX_CHAT_LOG_COMPLETION=true`
- The CLI owns its runnable config in [`application.yml`](/Users/chenzhu/Git/play-with-ai/codex-cli/src/main/resources/application.yml)
- Runnable modules import shared defaults explicitly through `spring.config.import`
- Conversation threads are persisted under `codex.storage-root`, which defaults to `${user.home}/.codex-java`
- Compacted thread memory is stored separately from raw turns, with the number of recent turns preserved controlled by `codex.context.preserve-recent-turns`
- Shell execution is approval-aware through `codex.shell.approval-mode`, which defaults to `review-sensitive`
- Secrets are expected through environment variables such as `OPENAI_API_KEY`

## CLI Usage

Run the CLI from the repo root:

```bash
mvn -pl codex-cli -am spring-boot:run
```

Run the standalone stdio app-server from the repo root:

```bash
mvn -pl codex-runtime-spring-ai -am spring-boot:run -Dspring-boot.run.mainClass=org.dean.codex.runtime.springai.appserver.CodexAppServerStdioApplication
```

Inside the console:

- `:help` shows the available shell commands
- `:new` starts a new thread
- `:threads` lists persisted threads
- `:skills` lists discovered skills and how to invoke them
- `:use <thread-id-prefix>` switches the active thread
- `:history` prints the current thread history
- `:compact` compacts older completed turns into durable thread memory for the active thread
- `:approvals` lists shell commands waiting for approval in the active thread
- `:approve <approval-id-prefix>` runs a pending approved command and resumes the paused turn
- `:reject <approval-id-prefix> [reason]` rejects a pending command and resumes the paused turn
- `:interrupt` requests cooperative interruption for the latest active turn
- `:steer <message>` sends additional guidance to the latest active turn
- `exit` or `quit` exits the CLI

The runtime now stores structured thread, turn, item, and command-approval data on disk, and the CLI is a thin client over a transport-agnostic `CodexAppServer` contract instead of calling runtime internals directly.
That app-server boundary is now connection-oriented: clients `connect`, send `initialize`, acknowledge with `initialized`, and then issue operational requests on a session-scoped contract.
Each app-server session can opt out of exact notification methods during initialization, which gives the Java runtime the first slice of Codex-style per-connection capabilities.
The runtime now also has a first transport slice for that boundary: newline-delimited JSON-RPC-style messages over stdio, with a dispatcher that maps wire methods onto the existing app-server session API.
`codex-cli` now uses that transport path by default through a small JSON-RPC stdio client that launches the app-server entrypoint as a local child process.
The current app-server surface models Codex-style lifecycle operations such as `thread/start`, `thread/resume`, `thread/read`, `thread/compact/start`, `turn/start`, `turn/resume`, `turn/interrupt`, `turn/steer`, and `skills/list`, with streamed notifications for thread and turn updates.
CLI app-server launch settings live under `codex.cli.app-server.*` (`main-class`, `command`, and request timeout), with defaults wired for local development.
Underneath that contract, `CodexRuntimeGateway` remains the async runtime surface that owns per-thread execution and notifications.
The CLI now streams typed turn items as a turn runs, including user messages, plans, tool calls, tool results, approvals, runtime errors, and assistant messages.
The runtime now also has a first-class skill discovery layer. It scans user and workspace `SKILL.md` files, exposes them through the runtime gateway, supports `:skills` in the CLI, and injects explicitly selected skills such as `$reviewer` into the agent prompt for the current turn.
The runtime now has a dedicated context-management seam as well. Older completed turns can be compacted into a separate durable `ThreadMemory` record, and a `ThreadContextReconstructionService` rebuilds structured prompt context from that memory plus recent turns, messages, and activities before the agent plans the next step.
`turn/steer` is currently cooperative: steering inputs are incorporated between planner steps instead of interrupting an in-flight LLM call.
The local toolset includes workspace search, targeted patching, and shell commands that can be auto-allowed, flagged for approval, blocked by policy, and explicitly approved or rejected from the CLI. Repository inspection or mutation can still happen through normal command execution when appropriate. Approval-required commands pause the current turn and continue the same task flow after approval or rejection.

## Build and Test

```bash
mvn test
```

## Documentation

Start with [`doc/README.md`](/Users/chenzhu/Git/play-with-ai/doc/README.md).
