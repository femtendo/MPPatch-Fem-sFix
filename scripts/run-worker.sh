#!/usr/bin/env bash
# run-worker.sh — spawn a deepseek-flash Hermes subagent, capture full output.
#
# The single sanctioned way to delegate work in this project. Wraps the real
# `hermes -z` oneshot path (verified 2026-08-23) so every worker:
#   1. runs on deepseek/deepseek-v4-flash (never the orchestrator's model),
#   2. streams its full stdout/stderr to logs/ so the orchestrator can monitor,
#   3. is instructed to file an AAR to subagents/<name>.md,
#   4. is instructed to append a line to the master devlog.md,
#   5. writes a --usage-file so cost is tracked.
#
# Usage:
#   scripts/run-worker.sh <worker-name> "<goal>" [workdir] [tools]
#
#   worker-name  short slug (e.g. renderer-spike) — names the AAR + log
#   goal         the SINGULAR GOAL prompt (self-contained, WIN CONDITION included)
#   workdir      dir the worker operates in (default: project root)
#   tools        comma-separated hermes toolsets (default: file,terminal,code_execution)
#
# Run in background with the terminal tool for long jobs; poll logs/<name>_*.log.
set -euo pipefail

WORKER_NAME="${1:?worker-name required}"
GOAL="${2:?goal prompt required}"
WORKDIR="${3:-$(pwd)}"
TOOLS="${4:-file,terminal,code_execution}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TS="$(date +%Y%m%d-%H%M%S)"
LOG="$ROOT/logs/${WORKER_NAME}_${TS}.log"
USAGE="$ROOT/logs/${WORKER_NAME}_${TS}.usage.json"

mkdir -p "$ROOT/subagents" "$ROOT/logs"

FULL_PROMPT="Your worker name for this task is: $WORKER_NAME

Do exactly what the task asks — nothing broader. If ambiguous, make the
narrowest reasonable interpretation. State plainly whether you completed,
partially completed, or could not complete — your output is verified against
a deterministic check, not taken on your word, so there is no upside to
overclaiming and no downside to an honest failure.

When you finish (success, partial, or failure), do BOTH:
1. APPEND an After-Action Report to $ROOT/subagents/$WORKER_NAME.md in this exact military format:

## <ISO timestamp> — $WORKER_NAME
**SITUATION:** (the goal you were given, restated)
**EXECUTION:** (what you actually did — commands run, files touched, decisions made)
**RESULT:** (completed / partial / failed — be honest)
**LESSONS:** (what worked, what failed, what to avoid next time)
**NEXT STEPS:** (what remains undone)

2. APPEND one line to $ROOT/devlog.md under the current session heading.

$GOAL"

hermes --profile claudeworker -z "$FULL_PROMPT" \
  --provider openrouter -m deepseek/deepseek-v4-flash-0731 \
  -t "$TOOLS" --in "$WORKDIR" \
  --usage-file "$USAGE" 2>&1 | tee "$LOG"

echo "--- run-worker done: $WORKER_NAME | log: $LOG | usage: $USAGE ---"
