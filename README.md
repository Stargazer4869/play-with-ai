# Java Codex Foundation

This repository is now a phase-1 multi-module Maven foundation for rebuilding Codex in Java with Spring AI, while keeping the earlier demos isolated in an `experimental` module.

## Modules

- `codex-protocol` - shared records, enums, and contracts
- `codex-core` - pure Java orchestration interfaces and core services
- `codex-tools-local` - local workspace tools for file and shell operations
- `codex-runtime-spring-ai` - Spring AI runtime wiring and agent implementations
- `codex-cli` - the primary runnable CLI application
- `experimental` - non-product demos, samples, and playground code

## Package Conventions

- Product code lives under `org.dean.codex.*`
- Experimental code lives under `org.dean.experimental.*`

## Configuration

- Shared LLM/runtime defaults live in [`codex-runtime-defaults.yml`](/Users/chenzhu/Git/play-with-ai/codex-runtime-spring-ai/src/main/resources/codex-runtime-defaults.yml)
- The CLI owns its runnable config in [`application.yml`](/Users/chenzhu/Git/play-with-ai/codex-cli/src/main/resources/application.yml)
- Runnable modules import shared defaults explicitly through `spring.config.import`
- Conversation threads are persisted under `codex.storage-root`, which defaults to `${user.home}/.codex-java`
- Shell execution is approval-aware through `codex.shell.approval-mode`, which defaults to `review-sensitive`
- Secrets are expected through environment variables such as `OPENAI_API_KEY`

## CLI Usage

Run the CLI from the repo root:

```bash
mvn -pl codex-cli -am spring-boot:run
```

Inside the console:

- `:help` shows the available shell commands
- `:new` starts a new thread
- `:threads` lists persisted threads
- `:use <thread-id-prefix>` switches the active thread
- `:history` prints the current thread history
- `:approvals` lists shell commands waiting for approval in the active thread
- `:approve <approval-id-prefix>` runs a pending approved command and resumes the paused turn
- `:reject <approval-id-prefix> [reason]` rejects a pending command and resumes the paused turn
- `exit` or `quit` exits the CLI

The runtime now stores structured thread, turn, and command-approval data on disk, and the CLI is a thin client over that turn execution flow.
The local toolset now includes workspace search and targeted patching, and shell commands can be auto-allowed, flagged for approval, blocked by policy, and explicitly approved or rejected from the CLI. Approval-required commands now pause the current turn and continue the same task flow after approval or rejection.

## Build and Test

```bash
mvn test
```

## Documentation

Start with [`doc/README.md`](/Users/chenzhu/Git/play-with-ai/doc/README.md).
