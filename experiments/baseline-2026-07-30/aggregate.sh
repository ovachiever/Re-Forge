#!/bin/bash
# Tally shard results. Final "Match Result:" line per shard is cumulative:
#   Match Result: Ai(1)-Basri, Devoted Paladin: N Ai(2)-Chandra, Flame's Catalyst: M
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$ROOT/experiments/baseline-2026-07-30"
w1=0; w2=0; draws=0; games=0
for f in "$OUT_DIR"/shard-*.log; do
  line=$(grep 'Match Result' "$f" | tail -1)
  read -r s1 s2 <<< "$(echo "$line" | awk -F': ' '{split($3,x," "); split($4,y," "); print x[1], y[1]}')"
  w1=$((w1 + ${s1:-0})); w2=$((w2 + ${s2:-0}))
  draws=$((draws + $(grep -c 'ended in a Draw' "$f")))
  games=$((games + $(grep -c 'Game Result' "$f")))
done
awk -v w1="$w1" -v w2="$w2" -v d="$draws" -v g="$games" 'BEGIN {
  n = w1 + w2
  if (n == 0) { print "no completed games yet"; exit }
  p = w1 / n
  hw = 1.96 * sqrt(p * (1 - p) / n)
  printf "games counted: %d   basri: %d   chandra: %d   draws: %d\n", g, w1, w2, d
  printf "basri win rate (draws excluded): %.1f%%  ±%.1f pts (95%% CI, n=%d)\n", p*100, hw*100, n
}'
