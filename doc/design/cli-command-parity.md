# CLI Command Parity Tracker

## Goal

Move the Java CLI from a constructor-driven REPL with ad hoc `:` commands to a Codex-style CLI with:

- real top-level subcommands
- interactive slash commands
- no startup side effects before argument parsing
- a single command model for help, parsing, and execution

The reference point is the current upstream Codex CLI and TUI command model in `/Users/chenzhu/Git/codex`.

## Command Policy

Use the following command prefixes:

- `/` for interactive built-in commands
- `@` reserved for mentions and navigation targets
- `$` reserved for skills and similar references
- `!` reserved for a future shell shortcut if needed

Migration rule:

- keep `:` as a compatibility alias for one transition phase only
- show `/...` in help, docs, and tests immediately

## Current Gap

Today the Java CLI:

- ignores `run(String... args)` and does not parse real CLI arguments
- mutates runtime state in the `CodexConsoleRunner` constructor
- mixes startup, thread selection, command parsing, and rendering in one class
- uses ad hoc `:` commands instead of Codex-style slash commands
- has no top-level subcommands like `resume`, `fork`, `review`, `completion`, or `sandbox`

Target behavior is:

- `codex [OPTIONS] [PROMPT]`
- `codex <SUBCOMMAND> [ARGS]`
- interactive `/...` commands driven by a registry or enum
- shared config overrides across interactive and non-interactive modes
- command help generated from one command model

## Delivery Cutlines

- Foundation MVP: Issues 1 through 4
- Interactive command parity foundation: Issues 1 through 6
- Top-level CLI parity foundation: Issues 1 through 8

## Dependency Order

1. Issue 1 -> Issue 2 -> Issue 3
2. Issue 4 depends on Issues 1 through 3
3. Issue 5 depends on Issues 2 through 4
4. Issue 6 depends on Issues 4 and 5
5. Issue 7 depends on Issues 2 through 5
6. Issue 8 depends on Issues 2 through 7

## Issue 1: Define the command grammar and migration rules

Status: `completed`

Depends on: none

Worker ownership:

- docs only
- no production code changes

Scope:

- define `/`, `@`, `$`, and reserved `!` behavior
- define the temporary `:` compatibility policy
- define the initial top-level subcommand list and interactive slash-command list

Initial interactive slash-command set:

- `/help`
- `/new`
- `/threads`
- `/resume`
- `/fork`
- `/agent`
- `/subagents`
- `/approvals`
- `/compact`
- `/review`
- `/status`
- `/model`

Acceptance:

- [x] add a short command grammar section to docs
- [x] list the initial built-in slash commands
- [x] define the deprecation path for `:` commands

Likely touch points:

- `doc`

## Issue 2: Introduce a real CLI parser and root command model

Status: `completed`

Depends on: Issue 1

Worker ownership:

- `codex-cli/pom.xml`
- new `codex-cli/src/main/java/org/dean/codex/cli/command/**`
- parser-focused tests only

Scope:

- add a Java CLI parsing library
- create a root `codex` command model with subcommands
- parse args before entering interactive mode

Acceptance:

- [x] `codex --help` prints a real command tree
- [x] `codex resume --help` and `codex fork --help` work
- [x] `codex` without a subcommand still supports interactive launch

Progress:

- [x] `picocli` is added to `codex-cli`
- [x] a root parser model and help tests exist
- [x] the real application path now invokes the root parser before choosing launch mode

Likely touch points:

- `codex-cli`

## Issue 3: Remove constructor side effects and add a launch request model

Status: `completed`

Depends on: Issue 2

Worker ownership:

- `codex-cli/src/main/java/org/dean/codex/cli/CodexCliApplication.java`
- new `codex-cli/src/main/java/org/dean/codex/cli/launch/**`
- `CodexConsoleRunner` startup wiring only

Scope:

- stop creating or selecting threads during bean construction
- introduce a `CliLaunchRequest` or equivalent internal launch model
- route startup based on parsed arguments instead of constructor state

Acceptance:

- [x] constructing the CLI runner does not create threads
- [x] interactive startup explicitly chooses create/resume/fork behavior after parsing
- [x] non-interactive commands do not initialize interactive thread state unless needed

Progress:

- [x] `CliLaunchRequest` and launch/bootstrap abstractions exist
- [x] `CodexCliApplication.main(...)` now delegates into the launch boundary
- [x] `CodexConsoleRunner` now initializes session and thread state lazily instead of in the constructor
- [x] runtime launch mode is now selected after top-level argument parsing

Likely touch points:

- `codex-cli`

## Issue 4: Add a slash-command registry and `:` compatibility aliases

Status: `completed`

Depends on: Issue 3

Worker ownership:

- new `codex-cli/src/main/java/org/dean/codex/cli/interactive/**`
- new registry/parser tests
- no top-level subcommand work

Scope:

- replace the ad hoc command `if` chain with a slash-command registry
- support descriptions, aliases, inline-arg support, and availability metadata
- accept `:` aliases temporarily, but emit `/` in help and usage

Acceptance:

- [x] `/help` is driven from registry metadata
- [x] slash commands are resolved centrally instead of by hardcoded prefix checks
- [x] `:` aliases still work during migration
- [x] tests cover `/` and `:` compatibility behavior

Progress:

- [x] slash-command spec, registry, parser, and parse-result types exist under `interactive/**`
- [x] `CodexConsoleRunner` now parses interactive commands through the shared slash-command parser

Likely touch points:

- `codex-cli`

## Issue 5: Migrate interactive thread and agent commands onto slash commands

Status: `completed`

Depends on: Issue 4

Worker ownership:

- `CodexConsoleRunner` interactive command handling and rendering
- `CodexConsoleRunnerTest`

Scope:

- move the current thread, agent, approval, compact, and help commands onto `/...`
- keep the current behavior intact while changing the syntax and dispatch path
- preserve temporary compatibility aliases

Acceptance:

- [x] `/threads`, `/resume`, `/fork`, `/agent`, `/subagents`, `/approvals`, `/compact`, `/help` work
- [x] help output advertises slash commands only
- [x] existing interactive features still pass tests after the migration

Progress:

- [x] interactive command entry now prefers `/...`
- [x] legacy `:...` commands still work as a transition alias
- [x] command execution now routes from parsed shared slash-command metadata

Likely touch points:

- `codex-cli`

## Issue 6: Add shared CLI config overrides for launch modes

Status: `completed`

Depends on: Issues 4 and 5

Worker ownership:

- `codex-cli/src/main/java/org/dean/codex/cli/command/**`
- launch/config mapping code
- config-focused tests

Scope:

- add shared flags such as `--model`, `--cd`, `--sandbox`, and `--approval-mode`
- map them into a shared launch/config object
- make both interactive and non-interactive modes consume the same parsed overrides

Acceptance:

- [x] shared options parse at the root command level
- [x] parsed overrides are available to interactive and top-level commands
- [x] tests cover option parsing and launch-request mapping

Progress:

- [x] shared override enums and mapping types exist
- [x] root command and launch flow now parse and carry these options through `CliLaunchRequest`
- [ ] runtime consumers still need to act on the parsed overrides

Likely touch points:

- `codex-cli`
- possibly `codex-runtime-spring-ai`

## Issue 7: Add first-class top-level `resume`, `fork`, and `completion` commands

Status: `in_progress`

Depends on: Issues 2 through 5

Worker ownership:

- new top-level command handlers under `codex-cli/src/main/java/org/dean/codex/cli/command/**`
- command-focused tests
- avoid editing slash-command registry files

Scope:

- add non-interactive top-level commands for `resume`, `fork`, and `completion`
- route them through the parsed command model
- avoid booting the full interactive loop for simple command flows

Acceptance:

- [x] `codex resume ...` works
- [x] `codex fork ...` works
- [x] `codex completion ...` works
- [x] tests cover command routing and basic outputs

Progress:

- [x] typed session command models and registry exist
- [x] the runtime path now dispatches top-level `resume`, `fork`, and `completion`
- [ ] advanced multi-session selection behaviors such as `--all` still need follow-up work

Likely touch points:

- `codex-cli`

## Issue 8: Add first-class top-level `exec`, `review`, `sandbox`, and auth commands

Status: `completed`

Depends on: Issues 2 through 7

Worker ownership:

- top-level command handlers only
- avoid editing the interactive slash-command path except where shared parsing requires it

Scope:

- add the next layer of top-level parity commands
- keep routing aligned with the root command model
- prepare the CLI surface for later app-server/runtime support in these areas

Acceptance:

- [x] root command tree includes `exec`, `review`, `sandbox`, and auth-related commands
- [x] command help is generated from the root parser model
- [ ] unsupported runtime paths fail clearly instead of silently falling back to the REPL

Progress:

- [x] non-interactive command models exist with explicit placeholder failures
- [x] unsupported non-interactive commands now fail clearly instead of dropping into the REPL
- [ ] the root parser still has some internal duplication with the shared command models

Likely touch points:

- `codex-cli`
- later `codex-runtime-spring-ai`

## Notes

- Do not keep adding more behavior to `CodexConsoleRunner` without first splitting parsing from execution.
- The Java CLI should converge on `/` for interactive commands to match upstream Codex behavior.
- Keep `:` only as a temporary migration shim, not as a permanent command style.
- The first implementation wave should prefer new packages for parser and launch models over deeper edits in the current runner.
