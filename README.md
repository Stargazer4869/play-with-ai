# Copilot Console Agent

A Spring AI powered console coding agent lives under `src/main/java/ai/copilot`.

## What it can do

- read project files relative to the workspace root
- create or overwrite project files relative to the workspace root
- run `zsh` commands from the workspace root for inspection, builds, and tests
- analyze compiled Java classes from the current project or from an external jar/classes path
- keep a short in-memory conversation history across console turns
- switch between a direct tool-calling agent and a ReAct-style reasoning loop

## Main packages

- `ai.copilot` — console app, runner, agent implementations, and shared config
- `ai.copilot.tools` — root-scoped file, shell, and Java-analysis tools exposed to Spring AI

## Agent modes

- `direct` — the original Spring AI tool-calling flow
- `react` — an explicit Reasoning → Action → Observation loop driven by JSON ReAct steps

You can choose the startup mode with:

- JVM property: `-Dcopilot.agent-mode=react`
- Spring property: `copilot.agent-mode=react`

Inside the console:

- `:agents` — list available modes
- `:agent react` — switch to the ReAct implementation
- `:agent direct` — switch back to the original implementation

## Safety rules implemented

- all file paths are resolved relative to the workspace root unless a tool explicitly requires an absolute path
- path traversal outside the workspace root is rejected
- file reads are truncated for large content
- shell commands run from the workspace root and time out after 60 seconds
- Java project analysis defaults to the current project's `target/classes` directory and only accepts absolute paths for external jars or classes directories
- the console app runs as a non-web Spring Boot application and exits cleanly on `exit` or `quit`

## Configuration

The workspace root defaults to `${user.dir}`.

You can override it with:

- JVM property: `-Dcopilot.workspace-root=/absolute/path`
- Spring property: `copilot.workspace-root=/absolute/path`

The LLM provider is configured through `src/main/resources/application.yml`.

Optional ReAct tuning:

- `copilot.react.max-steps=6`

## Run the console agent

```bash
mvn spring-boot:run
```

Run directly in ReAct mode:

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dcopilot.agent-mode=react"
```

Example prompts:

- `Read pom.xml and summarize the main dependencies.`
- `Create a file named scratch/hello.txt with the text hello world.`
- `Run mvn test and summarize the results.`
- `Analyze the current project's compiled Java classes and summarize the packages, classes, and public members.`
- `Analyze the jar at /absolute/path/to/library.jar and summarize the packages, classes, and public members.`
- `Read src/main/java/ai/copilot/CopilotAgent.java and explain how tool calling works.`
- `Switch to the react mode and explain how your next steps differ from direct mode.`

Exit commands:

- `exit`
- `quit`

## Run tests

```bash
mvn test
```

Current automated coverage focuses on:

- file read success, truncation, and path traversal rejection
- file write create/update behavior and path traversal rejection
- shell command success, failure, and blank-command handling
- Java project analysis for the default `target/classes` directory, external jars, relative-path rejection, and inner-class filtering
- agent mode selection and fallback behavior

## Notes

- Tool parameter names are compiled with `-parameters` so Spring AI can expose stable tool schemas.
- The direct agent relies on built-in Spring AI tool calling.
- The ReAct agent uses an explicit bounded step loop and returns only the final answer to the console.
- The Java project analyzer inspects bytecode directly, so it can summarize classes without loading them into the JVM.
- The current agents use short in-memory history buffers; they do not persist conversations across process restarts.
- For production use, move API credentials out of source-controlled config and into environment-specific secrets.
