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

## Namespace Rules

- `org.dean.codex.*` is reserved for product/runtime code
- `org.dean.experimental.*` is reserved for demos and playground code
- product modules must not depend on `experimental`
