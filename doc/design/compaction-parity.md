# Compaction Parity Tracker

## Goal

Move the Java runtime from sidecar `ThreadMemory` summaries to Codex-style canonical history replacement, with manual compaction first and auto-compaction after the replacement-history path is stable.

## Current Gap

Today compaction is:

- manual only
- based on completed turns, not model-visible history
- persisted as a separate `ThreadMemory` snapshot
- injected into prompts as an extra text block

Target behavior is:

- compaction operates on canonical model-visible history
- compacted output persists as a first-class history item
- replacement history becomes the new authoritative prompt prefix
- auto-compaction can trigger from token thresholds

## Delivery Cutlines

- MVP: Issues 1 through 5
- Parity-ready foundation: Issues 1 through 8
- Follow-on work after this tracker: rollback, fork, remote compact parity

## Dependency Order

1. Issue 1 -> Issue 2 -> Issue 3 -> Issue 4 -> Issue 5
2. Issue 6 depends on Issue 5
3. Issue 7 depends on Issues 4 and 5
4. Issue 8 depends on Issues 4, 5, and 7

## Issue 1: Add canonical thread-history protocol types

Status: `completed`

Depends on: none

Scope:

- add history item types for model-visible records
- add `CompactedHistoryItem` with `summaryText`, `replacementHistory`, `createdAt`, and `strategy`
- keep existing turn-facing APIs source-compatible during migration

Acceptance:

- [x] add serializable canonical history records in `codex-protocol`
- [x] cover mixed history plus compaction with JSON round-trip tests
- [x] avoid forcing callers to switch off `ConversationTurn` immediately

Likely touch points:

- `codex-protocol`
- `codex-core`

## Issue 2: Add `ThreadHistoryStore` and file-backed persistence

Status: `completed`

Depends on: Issue 1

Scope:

- define append, read, and replace semantics for canonical history
- implement file-backed persistence under the existing storage root
- support atomic replacement-history writes

Acceptance:

- [x] add a new core store contract for canonical history
- [x] persist history entries in deterministic order
- [x] reload history correctly after restart
- [x] support atomic replace of visible history with `replacementHistory`

Likely touch points:

- `codex-core`
- `codex-runtime-spring-ai`

## Issue 3: Record canonical history during turn execution

Status: `completed`

Depends on: Issue 2

Scope:

- persist user messages, assistant messages, tool calls, tool results, approvals, and runtime errors in prompt order
- preserve ordering across approval pause and resume
- keep `ConversationTurn` as a projection for now

Acceptance:

- [x] write model-visible history as the turn executes
- [x] preserve ordering when a turn pauses for approval and later resumes
- [x] prove a completed turn can be reconstructed from canonical history without relying on `ConversationTurn.userInput` and `finalAnswer`

Likely touch points:

- `codex-runtime-spring-ai`

## Issue 4: Reconstruct prompts from canonical history replay

Status: `completed`

Depends on: Issue 3

Scope:

- replace ad hoc prompt reconstruction with exact canonical-history replay
- treat `replacementHistory` as authoritative when replay crosses a compaction boundary
- stop relying on `ThreadMemory` for the hot prompt path

Acceptance:

- [x] add a replay-based reconstruction service
- [x] use replacement history verbatim when present
- [x] remove prompt dependence on the `Compacted thread memory` block
- [x] cover follow-up turns after compaction with reconstruction tests

Likely touch points:

- `codex-core`
- `codex-runtime-spring-ai`

## Issue 5: Implement real manual compaction with a model-generated handoff summary

Status: `completed`

Depends on: Issue 4

Scope:

- replace deterministic turn-string summaries with a dedicated compaction task
- compact canonical history, not just old completed turns
- persist a `CompactedHistoryItem` whose `replacementHistory` changes future prompt layout

Acceptance:

- [x] add a compaction service and dedicated compact prompt
- [x] make `thread/compact/start` produce a compaction history item
- [x] ensure the next turn sees replacement history instead of the full pre-compaction history
- [x] keep retained user boundaries plus the summary handoff item

Likely touch points:

- `codex-core`
- `codex-runtime-spring-ai`
- `codex-cli`

## Issue 6: Add streamed `contextCompaction` lifecycle items

Status: `completed`

Depends on: Issue 5

Scope:

- expose compaction as a first-class streamed turn item
- surface started and completed compaction lifecycle through the app-server
- render the new item in the CLI

Acceptance:

- [x] add a protocol item for context compaction
- [x] stream compaction lifecycle notifications
- [x] render compaction progress and result metadata in the CLI
- [x] stop treating `ThreadMemory` as the primary compaction UI surface

Likely touch points:

- `codex-protocol`
- `codex-runtime-spring-ai`
- `codex-cli`

## Issue 7: Add token accounting and automatic compaction triggers

Status: `completed`

Depends on: Issues 4 and 5

Scope:

- add configurable token thresholds
- trigger compaction before sampling and after follow-up steps when history is too large
- avoid infinite compaction loops

Acceptance:

- [x] add config for auto-compaction token thresholds
- [x] compact before sampling when history already exceeds the threshold
- [x] compact after follow-up steps when growth pushes the thread over the threshold
- [x] cover no-op, pre-turn, and post-step auto-compaction with tests

Likely touch points:

- `codex-runtime-spring-ai`

## Issue 8: Harden replay semantics for repeated compaction, restart, and resume

Status: `completed`

Depends on: Issues 4, 5, and 7

Scope:

- define prompt-shape rules for repeated compaction
- ensure restart and approval resume reconstruct the same visible history
- decide whether `ThreadMemory` remains as compatibility output only or is removed later

Acceptance:

- [x] preserve stable prompt layout across repeated compactions
- [x] reconstruct the same visible history after process restart
- [x] resume paused turns safely after compaction
- [x] update docs to describe compaction as history replacement rather than side memory

Likely touch points:

- `codex-runtime-spring-ai`
- `codex-cli`
- `doc`

## Notes

- Keep `ConversationTurn` and existing CLI history features working while canonical history is introduced.
- Do not attempt remote compact parity until the local replacement-history path is stable.
- Rollback and fork work should wait until compaction replay semantics are proven correct.
