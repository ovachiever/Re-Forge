#!/bin/bash
# Play Magic against Claude. macOS and Linux. Windows users run play.bat instead.
#
#   ./play.sh
#       Planner on Opus 5 at medium effort, interrupts on Sonnet 5 at low effort.
#   CLAUDE_PLAN_MODEL=claude-opus-5 CLAUDE_PLAN_EFFORT=high ./play.sh
#       Maximum cunning, slower turns.
#
# In Forge: gear icon (Preferences), then "AI Personality", then Claude. Start any
# match against the AI. Claude's table talk appears in the game log panel, and the
# decision ledger JSONL lands in the claude-logs directory. The full path is printed
# to this terminal when the ledger opens.
#
# Every environment knob, every log path, and the res-directory setup: docs/launch.md

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
run_dir=$root/run
jar=$root/forge-gui-desktop/target/forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar

# LLM_OPTS carries extra -Dllm.* flags (other providers: OpenAI, Ollama, OpenRouter).
# When it is set, a missing Anthropic key is fine: the provider config replaces it.
if [ -z "$ANTHROPIC_API_KEY" ] && [ -z "${LLM_OPTS:-}" ]; then
  echo "ANTHROPIC_API_KEY is not set. Run:  export ANTHROPIC_API_KEY=sk-ant-..." >&2
  echo "Using another provider? Set LLM_OPTS with your -Dllm.* flags instead (see docs/launch.md)." >&2
  exit 1
fi

# Forge resolves its assets against the working directory, so the game has to start
# inside run/ with a res directory sitting next to it. Testing a load-bearing
# subdirectory means a hand-made empty res fails here too.
if [ ! -d "$run_dir/res/cardsfolder" ]; then
  echo "missing or incomplete $run_dir/res" >&2
  echo "create it:  ln -s ../forge/forge-gui/res \"$run_dir/res\"" >&2
  exit 1
fi

if [ ! -f "$jar" ]; then
  echo "missing $jar" >&2
  echo "build it:  cd \"$root/forge\" && mvn -DskipTests package" >&2
  exit 1
fi

# Apple-only window hints. Other platforms ignore them, so they stay behind a guard.
# OSTYPE is set by bash and reads like "darwin25"; uname covers shells that leave it unset.
java_opts=(-Xmx4g -Dfile.encoding=UTF-8)
case "${OSTYPE:-$(uname -s)}" in
  [Dd]arwin*) java_opts+=(-Dapple.awt.application.name=reForge) ;;
esac

cd "$run_dir" || exit 1

# The claude.* property names are the long-lived ones. A parallel change adds llm.*
# aliases that fall back to these, so nothing below has to move when that lands.
exec java "${java_opts[@]}" \
  -Dclaude.force="${CLAUDE_FORCE:-true}" \
  -Dclaude.model.plan="${CLAUDE_PLAN_MODEL:-claude-opus-5}" \
  -Dclaude.effort.plan="${CLAUDE_PLAN_EFFORT:-medium}" \
  -Dclaude.model.fast="${CLAUDE_FAST_MODEL:-claude-sonnet-5}" \
  -Dclaude.effort.fast="${CLAUDE_FAST_EFFORT:-low}" \
  -Dclaude.persona="${CLAUDE_PERSONA:-sydney}" \
  -Dclaude.name="${CLAUDE_NAME:-Sydney}" \
  -Dclaude.thinking.budget="${CLAUDE_THINKING:-3000}" \
  -Dclaude.timeout.seconds="${CLAUDE_TIMEOUT:-45}" \
  ${LLM_OPTS:-} \
  -jar "$jar"
