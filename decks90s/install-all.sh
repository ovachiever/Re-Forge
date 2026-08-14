#!/bin/bash
# Install the entire 90s pool into Forge's constructed decks as "90s <Archetype>".
# Filename == metadata Name, so Forge's deck storage won't rename/move them.
POOL="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/Library/Application Support/Forge/decks/constructed"
count=0
while IFS=$'\t' read -r file colors era name; do
  [ -z "$file" ] && continue
  short="${name%% —*}"
  target="90s $short"
  sed "s/^Name=.*/Name=$target/" "$POOL/$file" > "$DEST/$target.dck"
  count=$((count+1))
done < "$POOL/manifest.tsv"
echo "installed $count decks as '90s *' in $DEST"
