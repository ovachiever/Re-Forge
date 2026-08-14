#!/bin/bash
# Variance-floor baseline: stock AI vs stock AI, Basri (W) vs Chandra (R), 8 shards x 250 games = 2000.
# Decks must exist in the Forge profile constructed-deck dir (SimulateMatch prepends it to -d args).
# No -Djava.awt.headless: FModel.initialize touches AWT and the crash handler dies headless.
# -Dapple.awt.UIElement=true keeps the 8 JVMs out of the macOS dock.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RUN_DIR="$ROOT/run"
OUT_DIR="$ROOT/experiments/baseline-2026-07-30"
JAR="$ROOT/forge-gui-desktop/target/forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar"

cd "$RUN_DIR" || exit 1
: > "$OUT_DIR/pids.txt"
for i in 1 2 3 4 5 6 7 8; do
  seed=$((4200 + i))
  nohup java -Xmx2g -Dapple.awt.UIElement=true -jar "$JAR" \
    sim -d basri.dck chandra.dck -n 250 -q -s "$seed" \
    > "$OUT_DIR/shard-$i.log" 2>&1 &
  echo "$!" >> "$OUT_DIR/pids.txt"
done
echo "launched 8 shards (seeds 4201-4208), logs in $OUT_DIR"
