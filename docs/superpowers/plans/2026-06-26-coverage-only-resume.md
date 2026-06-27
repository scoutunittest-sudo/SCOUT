# Coverage-Only Resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow coverage-only runs to resume from completed test classes after interruption.

**Architecture:** Store per-test-class checkpoints under the coverage test output's `chatunitest-info` tree. A checkpoint contains source identity, execution status, test counts, and JaCoCo execution data; later coverage-only runs skip valid completed checkpoints and merge their execution data with newly executed tests.

**Tech Stack:** Java 8-compatible code, JUnit 5 tests, JaCoCo core `ExecutionDataWriter`/`ExecutionDataReader`, Gson.

---

### Task 1: Add Resume Regression Test

**Files:**
- Modify: `src/test/java/zju/cst/aces/coverage/CoverageSummaryReporterTest.java`

- [ ] Add a test that runs coverage once with one passing generated test, deletes the generated test source, runs coverage again, and verifies the second report still includes coverage from the checkpoint without executing the missing test.
- [ ] Run `mvn -q -Dtest=CoverageSummaryReporterTest#coverageOnlyResumesFromCompletedTestClassCheckpoint test` and confirm it fails before implementation.

### Task 2: Persist Per-Class Checkpoints

**Files:**
- Modify: `src/main/java/zju/cst/aces/coverage/CoverageSummaryReporter.java`

- [ ] Add small internal checkpoint metadata classes.
- [ ] After each completed coverage test class, collect JaCoCo execution data, write `<test-class>.exec`, and update `manifest.json`.
- [ ] Store checkpoints under `config.getTmpOutput()/coverage-resume`.

### Task 3: Restore Completed Checkpoints

**Files:**
- Modify: `src/main/java/zju/cst/aces/coverage/CoverageSummaryReporter.java`

- [ ] Before executing a test class, check whether a matching successful checkpoint exists for the same test class and source identity.
- [ ] If present, merge its `.exec` data and test counts, mark progress, and skip execution.
- [ ] Treat timeout checkpoints as skipped failures unless the source changes.

### Task 4: Verify

**Files:**
- Modify: `src/test/java/zju/cst/aces/coverage/CoverageSummaryReporterTest.java`
- Modify: `src/main/java/zju/cst/aces/coverage/CoverageSummaryReporter.java`

- [ ] Run the new regression test.
- [ ] Run `mvn -q -Dtest=CoverageSummaryReporterTest test`.
- [ ] Run `mvn -q test-compile`.
