# SCOUT

This repository is based on **ChatUniTest Core** from ZJU-ACES-ISE. The original project provides a Generate-Validate-Fix framework for LLM-based Java unit test generation. This branch keeps that foundation and adds experiment-oriented execution, coverage reporting, SCOUT-specific export behavior, status windows, resume support, and stability controls for large runs. (See [Reference and Attribution](#reference-and-attribution).)

<img width="372" alt="running" src="./running.png">

*Live terminal status window. **Left:** generation mode (SCOUT/COVERUP/etc.) showing phase, model, thread usage, method progress, and per-run counters. **Right:** coverage-only mode, focused on valid/error counts and coverage progress. The window repaints in place instead of scrolling logs — see [Status Window](#status-window) for what each field means.*

**The public repository will be updated with the complete source code and installation instructions once the approval process is completed, and the README will provide the latest release status and access information.**

## What This Tool Does

ChatUniTest Core generates Java unit tests for Maven projects with an LLM, validates the generated tests, exports passing tests, and optionally reports final coverage.

The current codebase focuses on these workflows:

- `SCOUT`: scenario/coverage-guided unit test generation.
- `COVERUP`: coverage-guided repair/generation based on uncovered code.
- `CHATTESTER`, `HITS`, `TELPA`, `SYMPROMPT`, `TESTPILOT`, `MUTAP`: inherited ChatUniTest phases.
- `coverage-only`: skip generation and measure coverage for an existing generated test directory.

## Requirements

- Java 8 compatible bytecode target. Building with Java 11 is also acceptable because the Maven compiler is configured with `release 8`.
- Maven.
- A target Maven project with compiled main classes available through the normal Maven build flow.
- An OpenAI-compatible chat completion endpoint for generation modes.

Build:

```bash
mvn clean package
```

The build produces the runnable jar as `target/scout.jar`. Only the build output name is `scout`; the project's Maven coordinates remain `io.github.zju-aces-ise:chatunitest-core:2.1.1`. The build also emits `target/original-scout.jar` (the pre-shade jar) and `target/scout-javadoc.jar`.

```bash
java -jar target/scout.jar --help
```

## Basic Usage

Generate tests for a full project:

```bash
java -jar target/scout.jar \
  --project /path/to/project \
  --phase SCOUT \
  --llm code-llama \
  --url http://localhost:8000/v1/chat/completions \
  --api-key NO_API \
  --output /tmp/chatunitest-out
```

Generate tests for one target class:

```bash
java -jar target/scout.jar \
  --project /path/to/project \
  --class com.example.Target \
  --phase SCOUT \
  --llm code-llama \
  --url http://localhost:8000/v1/chat/completions \
  --output /tmp/chatunitest-out
```

Measure coverage for already generated tests without running generation:

```bash
java -jar target/scout.jar \
  --project /path/to/project \
  --coverage-tests /path/to/generated-tests \
  --class com.example.Target
```

## Important Options

| Option | Meaning |
| --- | --- |
| `--project <dir>` | Target Maven project directory. |
| `--pom <file>` | Target POM file. Use with `--lib` when not using `--project`. |
| `--lib <dir>` | Dependency library directory. |
| `--phase <phase>` | Generation phase. Common values: `SCOUT`, `COVERUP`, `CHATTESTER`, `HITS`. |
| `--class <fqcn>` | Optional fully qualified class name. If omitted, all parsed classes are targeted. |
| `--output <dir>` | Base output directory. |
| `--resume <run-dir>` | Resume a previous run directory: skip methods already attempted (any phase) and accumulate new tests into the same folder. See [Resuming an Interrupted Run](#resuming-an-interrupted-run). |
| `--llm <model>` | Model name shown in prompts/status/output path. |
| `--url <url>` | LLM chat completion endpoint. |
| `--api-key` / `--api_key` | API key. Use `NO_API` for local servers that do not require a key. |
| `--multithread true\|false` | Enable multithreaded method execution. |
| `--maxthreads <n>` | Upper bound used by the method/class scheduler. Default is CPU count minus 2. |
| `--rounds <n>` | Maximum repair/generation rounds. Default is 5. |
| `--tokens <n>` | Maximum prompt tokens. Default is 2600. |
| `--timeout <seconds>` | Overall generation time budget used by legacy runners. |
| `--report-coverage true\|false` | Whether to report final coverage after generation. Default is true. |
| `--coverage-tests <dir>` | Existing generated test source directory. Enables coverage-only mode. |
| `--merge true\|false` | Export merged suite classes under `test_merged/`. Default is false. |
| `--resource-profile true\|false` | Enable resource limiters and timing profiler. Default is false. |
| `--llm_threads <n>` | Maximum concurrent LLM requests when `resource_profile` is true. |
| `--compile_threads <n>` | Maximum concurrent compilation validations when `resource_profile` is true. |
| `--run_threads <n>` | Maximum concurrent unit test executions when `resource_profile` is true. |
| `--coverage_threads <n>` | Maximum concurrent coverage analyses when `resource_profile` is true. |

Both dash and underscore forms are supported for several options, such as `--coverage-tests` / `--coverage_tests`, `--report-coverage` / `--report_coverage`, and `--resource-profile` / `--resource_profile`.

## Output Layout

Generation mode isolates each run by model and timestamp. A typical SCOUT output path looks like:

```text
<output>/<model>/<yyyyMMdd-HHmmss-SSS>/
```

SCOUT also keeps internal run state under a timestamped temporary run directory:

```text
/tmp/SCOUT/chatunitest-info/<project>/scout-runs/<model>/<yyyyMMdd-HHmmss-SSS>/
```

Generated tests are exported under the run output directory. Each run also records per-method progress markers for resume support (see [Resuming an Interrupted Run](#resuming-an-interrupted-run)):

```text
<run-output>/method-progress/
```

If `--merge true` is used, merged suites are written separately:

```text
<run-output>/test_merged/<package>/Target_Suite.java
```

The `test_merged/` directory is intentionally excluded from coverage discovery so merged suites do not duplicate or contaminate coverage accounting.

## Resuming an Interrupted Run

Long runs can die mid-way (a crash, an OOM, a manual stop). To avoid regenerating tests that were already produced, every run records what it attempts and a deliberate re-run can pick up where it left off.

**How it works**

- During *every* run (resume or not), each method is logged the moment generation starts, as a small marker file under `<run-output>/method-progress/`. One file per method holds a JSON record: the method key (`fullClassName#methodSignature`), the phase, start/finish timestamps, and a `started` → `ok`/`error` status. This logging is always on so that a first crash is recoverable; its overhead is negligible local file I/O.
- The marker filename is a filesystem-safe encoding of the method key plus a short hash, so the same method always maps to the same marker across runs.

**Resuming**

```bash
java -jar target/scout.jar \
  --project /path/to/project \
  --phase SCOUT \
  --llm code-llama \
  --url http://localhost:8000/v1/chat/completions \
  --resume /tmp/chatunitest-out/<model>/<yyyyMMdd-HHmmss-SSS>
```

- Point `--resume` at the previous run directory (the `<output>/<model>/<timestamp>` folder).
- The resumed run reuses that exact directory: it reads the existing `method-progress/` markers and writes new tests and new markers into the same folder, so all generated tests stay together. No new timestamped folder is created.
- **Skip semantics:** any method that was *started* in the previous run is skipped — whether it finished successfully, failed, or was interrupted by the crash. Only methods with no marker are generated. Skipped methods are logged as `Resume skip (already attempted): <key>`.
- Without `--resume`, behavior is unchanged: nothing is skipped and a fresh timestamped output directory is created.

When resuming, the status window `Mode` line is annotated, for example `Mode : multithreading (resume)`.

## Coverage Reports

Final coverage is enabled by default. Coverage-only mode also writes the same report files.

Expected files:

```text
coverage-summary.json
coverage-summary.txt
experiment-summary.txt
fully_covered_methods.json
coverage-invalid-tests.json
coverage-invalid-tests.txt
```

The CLI also prints a concise coverage summary with:

- target class count
- generated test class count
- found/succeeded/failed test counts
- instruction coverage
- branch coverage
- line coverage
- fully covered method count
- invalid test source count

`fully_covered_methods.json` records methods whose line coverage reached 100%.

## SCOUT Export Policy

SCOUT does not export every passing test candidate. After the first successful export, later successful tests are exported only when they cover a previously known uncovered branch or region. This keeps the output directory from filling with redundant tests.

Status counters use this convention:

- `valid methods`: methods with at least one exported valid test.
- `exported tests`: number of individual test files exported.
- `fully covered methods`: methods with 100% line coverage after coverage analysis.

## Status Window

Normal execution suppresses noisy logs and renders a compact terminal status window (shown in the screenshots at the top of this README). It repaints in place on a fixed cadence rather than scrolling log lines.

**Generation mode** shows:

- phase and current step
- target project
- model
- output directory
- execution mode (`single-thread` / `multithreading`, with `(resume)` appended when resuming)
- active worker threads
- report coverage mode
- method progress
- valid method count
- exported test count
- fully covered method count
- compile/runtime/timeout counts
- LLM attempts and successful responses

**Coverage-only mode** uses a separate status view focused on:

- valid test count
- compile error count
- runtime error count
- coverage progress

## Timeouts and Stability

Current timeout behavior:

- LLM HTTP calls use a 3 minute call timeout.
- Compile and test execution validation use a 2 minute timeout.
- Coverage execution uses per-batch/per-test timeout handling.

For long experiments, use resource profiling to cap expensive work:

```bash
java -jar target/scout.jar \
  --project /path/to/project \
  --phase SCOUT \
  --multithread true \
  --maxthreads 8 \
  --resource-profile true \
  --llm_threads 2 \
  --compile_threads 1 \
  --run_threads 1 \
  --coverage_threads 1 \
  --llm code-llama \
  --url http://localhost:8000/v1/chat/completions \
  --output /tmp/chatunitest-out
```

When `resource_profile` is false, the additional resource limiters and timing profiler are disabled. In that mode `maxthreads` controls the scheduler, but it does not strictly cap every LLM, compile, run, and coverage resource.

## Notes for Experiments

- `--report-coverage` defaults to true.
- `--merge` defaults to false.
- Coverage-only mode never calls the LLM and does not generate tests.
- Each generation run writes to a fresh timestamped output directory, reducing contamination across repeated experiments. Use `--resume` to continue into a previous run directory instead.
- Build artifacts may appear under temporary build directories during validation and coverage measurement. The runner cleans and recompiles where coverage correctness requires it.
- The implementation is based on ChatUniTest Core and preserves the original phase architecture, prompt template system, Maven project parsing, and validation pipeline.

## Development Checks

Useful local checks:

```bash
mvn -q test-compile
mvn -q -Dtest=MethodProgressTrackerTest,StatusModeLabelTest test
```

`MethodProgressTrackerTest` covers the resume marker logic (encoding, skip semantics, crash/concurrency cases) and `StatusModeLabelTest` covers the status-window `Mode` label, including the `(resume)` suffix.

## Reference and Attribution

This project is **derived from ChatUniTest Core**, the LLM-based Java unit test generation framework by ZJU-ACES-ISE.

- Upstream project: **ChatUniTest** (ZJU-ACES-ISE) — https://github.com/ZJU-ACES-ISE
- Upstream Maven coordinates: `io.github.zju-aces-ise:chatunitest-core`

The upstream Generate-Validate-Fix architecture, prompt template system, Maven project parsing, and validation pipeline are preserved here. This repository adds the experiment-oriented execution flow, coverage reporting, SCOUT/COVERUP export behavior, the terminal status window, resume support, and stability controls for large runs. All credit for the original framework belongs to the ChatUniTest authors.
